package com.example.dormitory.service.impl;

import com.example.dormitory.service.ExportService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Semaphore;

/** 数据逐行读取，工作表分批暂存，避免把整份导出文件一直放在内存里。 */
@Service
public class ExportServiceImpl implements ExportService {
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final Semaphore permits;
    private final boolean mysql;

    public ExportServiceImpl(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                             @Value("${app.export.max-concurrent:2}") int concurrency) throws java.sql.SQLException {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.permits = new Semaphore(Math.max(1, concurrency));
        try (var connection = jdbc.getDataSource().getConnection()) {
            mysql = connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL");
        }
    }

    @Override
    public void write(String kind, OutputStream output) throws IOException {
        if (!Set.of("students", "dormitories", "buildings", "all").contains(kind))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未知导出类型");
        if (!permits.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "导出任务较多，请稍后重试");
        // 内存只保留一小批行，其余内容交给临时文件保存。
        try (var workbook = new SXSSFWorkbook(100)) {
            workbook.setCompressTempFiles(true);
            var snapshot = new TransactionTemplate(transactions);
            // 所有工作表使用同一份数据快照，避免导出过程中调宿导致表之间对不上。
            snapshot.setReadOnly(true);
            snapshot.setIsolationLevel(mysql ? TransactionDefinition.ISOLATION_REPEATABLE_READ : TransactionDefinition.ISOLATION_SERIALIZABLE);
            snapshot.executeWithoutResult(tx -> {
                if (kind.equals("all")) {
                    var info = workbook.createSheet("导出说明");
                    info.createRow(0).createCell(0).setCellValue("宿舍业务台账；不包含密码、导入预览内容或附件文件，不用于数据库恢复。");
                    info.createRow(1).createCell(0).setCellValue("生成时间：" + LocalDateTime.now());
                    info.createRow(2).createCell(0).setCellValue("所有数据在同一只读事务快照下读取。房间可用状态与入住状态分别列示。");
                    info.setColumnWidth(0, 100 * 256);
                }
                if (kind.equals("students") || kind.equals("all")) sheet(workbook, "学生信息", "SELECT s.student_id,s.name,s.gender,s.grade,s.class_name,s.phone,s.dormitory_number,COALESCE(p.lifecycle,'ENROLLED'),p.college,p.major,p.counselor,p.archived_at,p.archive_reason FROM student s LEFT JOIN student_profile p ON p.student_id=s.id ORDER BY s.student_id", "学号,姓名,性别,年级,班级,电话,宿舍号,学籍状态,学院,专业,辅导员,归档时间,归档原因");
                if (kind.equals("dormitories") || kind.equals("all")) sheet(workbook, "宿舍信息", "SELECT d.dormitory_number,d.building,d.room_type,d.capacity,d.current_count,d.gender,CASE WHEN d.current_count=0 THEN '空闲' WHEN d.current_count>=d.capacity THEN '已住满' ELSE '未住满' END,COALESCE(p.state,'ACTIVE'),p.reason FROM dormitory d LEFT JOIN room_policy p ON p.room_id=d.id ORDER BY d.dormitory_number", "宿舍号,楼栋,房间类型,容量,当前人数,性别限制,入住状态,房间可用状态,状态说明");
                if (kind.equals("buildings") || kind.equals("all")) sheet(workbook, "楼栋信息", BUILDINGS, "楼栋名称,性别限制,楼层数,总宿舍数,入住人数,空闲宿舍数,已住满宿舍数,未住满宿舍数,入住率%,二人间数量,四人间数量,六人间数量,总容量");
                if (kind.equals("all")) businessSheets(workbook);
            });
            // 数据已经读完，先归还数据库连接，再把文件发送给浏览器。
            workbook.write(output);
        } finally {
            permits.release();
        }
    }

    private static final String BUILDINGS = """
        SELECT b.name,b.gender,
          COUNT(DISTINCT SUBSTR(d.dormitory_number,INSTR(d.dormitory_number,'-')+1,2)),COUNT(d.id),COALESCE(SUM(d.current_count),0),
          SUM(CASE WHEN d.current_count=0 THEN 1 ELSE 0 END),SUM(CASE WHEN d.current_count>=d.capacity THEN 1 ELSE 0 END),
          SUM(CASE WHEN d.current_count>0 AND d.current_count<d.capacity THEN 1 ELSE 0 END),
          CASE WHEN SUM(d.capacity)>0 THEN SUM(d.current_count)*100.0/SUM(d.capacity) ELSE 0 END,
          SUM(CASE WHEN d.room_type='二人间' THEN 1 ELSE 0 END),SUM(CASE WHEN d.room_type='四人间' THEN 1 ELSE 0 END),
          SUM(CASE WHEN d.room_type='六人间' THEN 1 ELSE 0 END),COALESCE(SUM(d.capacity),0)
        FROM building b LEFT JOIN dormitory d ON d.building=b.name GROUP BY b.id,b.name,b.gender ORDER BY b.name
        """;

    private void businessSheets(SXSSFWorkbook w) {
        sheet(w,"床位","SELECT b.id,d.dormitory_number,b.bed_number,b.state,s.student_id,b.reserved_for,b.reservation_until FROM bed b JOIN dormitory d ON d.id=b.room_id LEFT JOIN student s ON s.id=b.student_id ORDER BY b.id","床位编号,宿舍,床号,状态,入住学号,预留学生编号,预留到期");
        sheet(w,"住宿申请","SELECT id,student_id,kind,target_bed_id,old_bed_id,reason,state,reviewer,review_note,created_at,reviewed_at,completed_at FROM housing_application ORDER BY id","编号,学生编号,类型,目标床位,原床位,原因,状态,审核人,审核意见,创建时间,审核时间,办理时间");
        sheet(w,"住宿历史","SELECT id,student_number,room_number,bed_number,checkin_at,checkout_at,reason,handled_by,checkout_reason,checkout_by FROM housing_history ORDER BY id","编号,学号,宿舍,床号,入住时间,退宿时间,入住原因,入住经办人,退宿原因,退宿经办人");
        sheet(w,"宿舍长记录","SELECT id,room_id,student_id,appointed_at,ended_at,actor,reason FROM leader_record ORDER BY id","编号,房间编号,学生编号,任命时间,离任时间,经办人,原因");
        sheet(w,"报修工单","SELECT r.id,r.student_id,r.student_name,r.dormitory_number,r.repair_type,r.description,r.status,r.create_time,r.update_time,w.assignee,w.priority,pm.suggested_priority,pm.priority_source,pm.priority_reason,w.completion_note,w.review_note,w.rating,r.image_path FROM repair_request r LEFT JOIN repair_work w ON w.repair_id=r.id LEFT JOIN repair_priority pm ON pm.repair_id=r.id ORDER BY r.id","编号,学号,报修人,宿舍,类型,描述,状态,创建时间,更新时间,维修人员,优先级,自动建议,判断来源,判断依据,完成说明,验收意见,评分,图片引用");
        sheet(w,"维修处理记录","SELECT id,repair_id,actor,action,note,created_at FROM repair_event ORDER BY id","编号,工单编号,操作人,操作,说明,时间");
        sheet(w,"通知公告","SELECT id,title,content,audience,building_id,author,state,created_at FROM announcement ORDER BY id","编号,标题,内容,范围,楼栋编号,发布人,状态,时间");
        sheet(w,"公告回执","SELECT announcement_id,username,read_at FROM announcement_receipt ORDER BY announcement_id,username","公告编号,账号,阅读时间");
        sheet(w,"站内消息","SELECT id,recipient,title,content,is_read,created_at FROM notification ORDER BY id","编号,收件人,标题,内容,已读,时间");
        sheet(w,"留宿外宿","SELECT id,student_id,kind,start_at,end_at,reason,contact_phone,state,reviewer,review_note,actual_return_at,created_at FROM stay_request ORDER BY id","编号,学生编号,类型,开始时间,预计结束,原因,电话,状态,审核人,审核意见,实际返校,创建时间");
        sheet(w,"访客登记","SELECT id,room_id,student_id,visitor_name,phone,expected_leave_at,arrived_at,left_at,operator,note FROM visitor ORDER BY id","编号,房间编号,被访学生编号,访客,电话,预计离开,到访时间,离开时间,登记人,备注");
        sheet(w,"卫生安全检查","SELECT id,room_id,kind,score,detail,due_at,state,created_by,created_at,updated_at,rectify_note,review_note,reviewed_by,image_path,rectify_image_path FROM inspection ORDER BY id","编号,房间编号,类型,评分,检查内容,整改期限,状态,检查人,创建时间,更新时间,整改说明,复查意见,复查人,检查图片引用,整改图片引用");
        sheet(w,"费用账单","SELECT id,student_id,room_number,kind,period,amount_cents,due_at,description,state,created_by,created_at FROM bill ORDER BY id","编号,学生编号,宿舍,类型,期间,应收金额（分）,期限,说明,状态,创建人,创建时间");
        sheet(w,"收退款流水","SELECT id,bill_id,kind,amount_cents,method,reference_no,reason,actor,created_at FROM payment ORDER BY id","编号,账单编号,类型,金额（分）,方式,凭据编号,原因,经办人,时间");
        sheet(w,"资产钥匙","SELECT id,room_id,code,name,category,state,description FROM asset ORDER BY id","编号,房间编号,资产编号,名称,类别,状态,说明");
        sheet(w,"物品交接","SELECT id,asset_id,student_id,issued_at,issue_note,issued_by,returned_at,return_condition,return_note,returned_by FROM handover ORDER BY id","编号,资产编号,学生编号,领用时间,领用说明,发放人,归还时间,归还情况,归还说明,接收人");
        sheet(w,"账号状态","SELECT u.username,u.real_name,u.phone,u.role,COALESCE(p.enabled,1),COALESCE(p.force_password_change,0) FROM user u LEFT JOIN account_policy p ON p.username=u.username ORDER BY u.id","账号,姓名,电话,角色,启用,首次须改密");
        sheet(w,"楼栋权限","SELECT username,building_id FROM manager_building ORDER BY username,building_id","账号,楼栋编号");
        sheet(w,"业务规则","SELECT setting_key,setting_value FROM business_setting ORDER BY setting_key","规则,值");
        sheet(w,"批次摘要","SELECT id,owner,kind,state,created_at,expires_at FROM import_batch ORDER BY id","编号,操作人,类型,状态,创建时间,过期时间");
        sheet(w,"操作审计","SELECT id,actor,action,resource_type,resource_id,detail_json,created_at FROM audit_log ORDER BY id","编号,操作人,操作,对象类型,对象编号,详情,时间");
    }

    private void sheet(SXSSFWorkbook workbook, String name, String sql, String headerText) {
        String[] headers = headerText.split(",");
        var sheet = workbook.createSheet(name);
        header(sheet, headers);
        final int[] number = {1}, part = {1};
        final Sheet[] current = {sheet};
        jdbc.query(connection -> {
            var statement = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            statement.setFetchSize(mysql ? Integer.MIN_VALUE : 256);
            return statement;
        }, (RowCallbackHandler) result -> {
            if (number[0] >= 1_048_576) {
                current[0] = workbook.createSheet(name + "_" + ++part[0]);
                header(current[0], headers);
                number[0] = 1;
            }
            var row = current[0].createRow(number[0]++);
            for (int i = 0; i < headers.length; i++) {
                Object value = result.getObject(i + 1);
                var cell = row.createCell(i);
                if (value instanceof Number n && !headers[i].contains("编号")) cell.setCellValue(n.doubleValue());
                else cell.setCellValue(value == null ? "" : value.toString());
            }
        });
    }

    private void header(Sheet sheet, String[] headers) {
        var row = sheet.createRow(0);
        var style = sheet.getWorkbook().createCellStyle();
        var font = sheet.getWorkbook().createFont();
        font.setBold(true); style.setFont(font);
        for (int i = 0; i < headers.length; i++) {
            var cell = row.createCell(i);
            cell.setCellValue(headers[i]); cell.setCellStyle(style);
            sheet.setColumnWidth(i, Math.min(60, Math.max(18, headers[i].length() * 2 + 4)) * 256);
        }
        sheet.createFreezePane(0, 1);
    }
}
