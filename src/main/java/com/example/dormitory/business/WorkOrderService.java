package com.example.dormitory.business;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;

import java.util.*;

import static com.example.dormitory.business.BusinessStore.*;

/**
 * 报修闭环：学生提交 → 管理员/宿管派单 → 维修处理 → 学生验收或退回重修。
 * 优先级判断只决定处理顺序，不会自动选择维修人员。
 * 工单状态、处理记录、审计和站内通知在同一数据库事务中提交。
 */
@Service
@Transactional
public class WorkOrderService {
    private final BusinessStore db;
    private final DomainLocks locks;
    private final BusinessLists lists;
    private final BusinessAccess access;

    public WorkOrderService(BusinessStore db, BusinessAccess access, BusinessLists lists, DomainLocks locks) {
        this.db = db;
        this.locks = locks;
        this.lists = lists;
        this.access = access;
    }

    /** 数据范围：管理员全部、宿管负责楼栋、维修人员本人已派工单、学生本人或本宿舍。 */
    public boolean allowed(Map<String, Object> repair) {
        if (access.role("ADMIN")) return true;
        if (access.role("WORKER"))
            return db.count("SELECT COUNT(*) FROM repair_work WHERE repair_id=? AND assignee=?", repair.get("id"), access.actor()) > 0;
        if (access.role("MANAGER")) {
            var rooms = db.jdbc.queryForList("SELECT id FROM dormitory WHERE dormitory_number=?", repair.get("dormitory_number"));
            return !rooms.isEmpty() && access.roomAllowed(number(rooms.get(0).get("id")));
        }
        if (access.role("STUDENT"))
            return text(repair.get("student_id")).equals(access.actor()) || text(repair.get("dormitory_number")).equals(text(access.self().get("dormitory_number")));
        return false;
    }

    public Map<String, Object> repair(long id) {
        var row = db.one("SELECT r.*,w.assignee,w.priority,pm.priority_source,pm.priority_reason,pm.suggested_priority,w.completion_note,w.review_note,w.rating FROM repair_request r LEFT JOIN repair_work w ON w.repair_id=r.id LEFT JOIN repair_priority pm ON pm.repair_id=r.id WHERE r.id=?", id);
        if (!allowed(row)) throw new AccessDeniedException("无权访问此报修工单");
        return row;
    }

    public List<Map<String, Object>> list() {
        return lists.all("repairs");
    }

    /** 创建待派单工单并返回编号，保存自动优先级及依据，通知报修人与有权派单的工作人员。 */
    public long submit(String type, String description, String image) {
        access.roles("STUDENT");
        locks.structure(false);
        long id = number(access.self().get("id"));
        locks.housing(id, null);
        var student = access.self();
        check(db.count("SELECT COUNT(*) FROM student_profile WHERE student_id=? AND lifecycle<>'ENROLLED'", id) == 0, "学生已归档或不具备住宿资格");
        if (text(student.get("dormitory_number")).isBlank() || !db.setting("all_residents_can_report") && number(student.get("is_dormitory_leader")) != 1)
            throw new AccessDeniedException("需为已入住学生，并符合报修权限设置");
        var time = java.sql.Timestamp.valueOf(java.time.LocalDateTime.now());
        long repair = db.insert("INSERT INTO repair_request(student_id,student_name,dormitory_number,repair_type,description,image_path,status,create_time,update_time) VALUES(?,?,?,?,?,?,'待处理',?,?)", access.actor(), student.get("name"), student.get("dormitory_number"), type, description, image, time, time);
        ensure(repair);
        // 实际优先级与规则建议分别保存，便于人工覆盖后仍能追溯自动建议。
        var assessment = RepairPriorities.assess(type, description);
        db.jdbc.update("UPDATE repair_work SET priority=? WHERE repair_id=?", assessment.priority(), repair);
        db.jdbc.update("INSERT INTO repair_priority(repair_id,priority_source,priority_reason,suggested_priority) VALUES(?,'AUTO',?,?)", repair, assessment.reason(), assessment.priority());
        String urgency = assessment.priority().equals("URGENT") ? "紧急" : "普通";
        event(repair, "REPAIR_SUBMIT", "报修已提交，等待派单；自动优先级：" + urgency + "；" + assessment.reason());
        var dispatchers = db.jdbc.queryForList("SELECT u.username FROM user u LEFT JOIN account_policy p ON p.username=u.username WHERE COALESCE(p.enabled,1)=1 AND (u.role='admin' OR (u.role='manager' AND EXISTS (SELECT 1 FROM manager_building m JOIN building b ON b.id=m.building_id JOIN dormitory d ON d.building=b.name WHERE m.username=u.username AND d.dormitory_number=?)))", student.get("dormitory_number"));
        for (var dispatcher : dispatchers)
            db.notify(text(dispatcher.get("username")), "报修待派单", "【" + urgency + "】工单 #" + repair + "：" + assessment.reason() + "。请进入报修工单分配维修人员。");
        db.notify(access.actor(), "报修提交成功", "工单 #" + repair + " 等待管理员或宿管派单，派单后将通知你。");
        return repair;
    }

    private void ensure(long id) {
        if (db.count("SELECT COUNT(*) FROM repair_work WHERE repair_id=?", id) == 0)
            db.jdbc.update("INSERT INTO repair_work(repair_id,updated_at) VALUES(?,?)", id, now());
    }

    private void event(long id, String action, String note) {
        db.jdbc.update("INSERT INTO repair_event(repair_id,actor,action,note,created_at) VALUES(?,?,?,?,?)", id, access.actor(), action, note, now());
        db.audit(access.actor(), action, "repair", id, Map.of("note", note));
    }

    /** 派单/改派必须由管理员或负责该楼栋的宿管办理；对工单加锁以串行化并发操作。 */
    public void dispatch(long id, BusinessRequests.Dispatch input) {
        access.roles("ADMIN", "MANAGER");
        locks.record("repair_request", id);
        var row = repair(id);
        check(!List.of("已完成", "待验收").contains(text(row.get("status"))), "已完成或待验收工单不可派单");
        check(db.count("SELECT COUNT(*) FROM user u LEFT JOIN account_policy p ON p.username=u.username WHERE u.username=? AND u.role='worker' AND COALESCE(p.enabled,1)=1", input.assignee()) == 1, "维修人员不存在或已停用");
        ensure(id);
        var assessment = RepairPriorities.assess(text(row.get("repair_type")), text(row.get("description")));
        // 省略或 AUTO 都会重新计算；显式 NORMAL/URGENT 为人工选择，派单说明作为调整理由。
        // 改派时保留人工选择由前端回填原级别实现；再次选择 AUTO 表示恢复自动判断。
        boolean automatic = input.priority() == null || input.priority().equals("AUTO");
        String priority = automatic ? assessment.priority() : input.priority();
        String reason = automatic ? assessment.reason() : "人工调整：" + input.note();
        String source = automatic ? "AUTO" : "MANUAL";
        db.jdbc.update("UPDATE repair_work SET assignee=?,priority=?,updated_at=? WHERE repair_id=?", input.assignee(), priority, now(), id);
        if (db.count("SELECT COUNT(*) FROM repair_priority WHERE repair_id=?", id)==0)
            db.jdbc.update("INSERT INTO repair_priority(repair_id) VALUES(?)", id);
        db.jdbc.update("UPDATE repair_priority SET priority_source=?,priority_reason=?,suggested_priority=? WHERE repair_id=?", source, reason, assessment.priority(), id);
        String urgency = priority.equals("URGENT") ? "紧急" : "普通";
        event(id, "REPAIR_DISPATCH", input.note() + "；优先级：" + urgency + "（" + (automatic ? "自动" : "人工") + "）；" + reason);
        db.notify(input.assignee(), "新维修工单", "【" + urgency + "】工单 #" + id + "：" + input.note() + "；" + reason);
        db.notify(text(row.get("student_id")), "报修已派单", "工单 #" + id + " 已安排维修人员，等待处理。");
        String previous = text(row.get("assignee"));
        if (!previous.isBlank() && !previous.equals(input.assignee()))
            db.notify(previous, "维修工单已改派", "工单 #" + id + " 已改派，请以当前工单列表为准。");
    }

    public List<Map<String, Object>> events(long id) {
        repair(id);
        return db.jdbc.queryForList("SELECT * FROM repair_event WHERE repair_id=? ORDER BY id", id);
    }

    /** 维修人员只能处理本人已派工单，完成维修仅进入待验收，不能直接结单。 */
    public void transition(long id, String state, String note) {
        access.roles("ADMIN", "MANAGER", "WORKER");
        locks.record("repair_request", id);
        var row = repair(id);
        String old = text(row.get("status"));
        if (access.role("WORKER")) check(access.actor().equals(text(row.get("assignee"))), "只能处理派给自己的工单");
        boolean start = old.equals("待处理") && state.equals("处理中");
        boolean finish = old.equals("处理中") && state.equals("待验收");
        check(start || finish, "工单应按 待处理 → 处理中 → 待验收 → 学生验收 完成");
        check(row.get("assignee") != null, "请先派单");
        if (finish) invalid(note != null && !note.isBlank(), "提交验收前须填写完成说明");
        ensure(id);
        db.jdbc.update("UPDATE repair_request SET status=?,update_time=? WHERE id=?", state, java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()), id);
        if (finish)
            db.jdbc.update("UPDATE repair_work SET completion_note=?,updated_at=? WHERE repair_id=?", note, now(), id);
        event(id, start ? "REPAIR_START" : "REPAIR_FINISH", note == null ? "" : note);
        db.notify(text(row.get("student_id")), "报修进度更新", state + (note == null ? "" : "：" + note));
    }

    /** 验收人须为报修人或当前本宿舍宿舍长；不通过时退回处理中，保留原负责人。 */
    public void accept(long id, BusinessRequests.Review input) {
        access.roles("STUDENT");
        locks.structure(false);
        locks.housing(number(access.self().get("id")), null);
        locks.record("repair_request", id);
        var row = repair(id);
        check(text(row.get("status")).equals("待验收"), "工单尚未提交验收");
        check(text(row.get("student_id")).equals(access.actor()) || number(access.self().get("is_dormitory_leader")) == 1, "仅报修人或当前宿舍长可以验收");
        db.jdbc.update("UPDATE repair_request SET status=?,update_time=? WHERE id=?", input.approve() ? "已完成" : "处理中", java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()), id);
        ensure(id);
        db.jdbc.update("UPDATE repair_work SET review_note=?,rating=?,updated_at=? WHERE repair_id=?", input.note(), input.rating(), now(), id);
        event(id, input.approve() ? "REPAIR_ACCEPT" : "REPAIR_REOPEN", input.note());
        db.notify(text(row.get("assignee")), "维修验收结果", input.note());
    }
}
