package com.example.dormitory.business;

import com.example.dormitory.controller.Requests;
import jakarta.validation.Validator;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.*;

import static com.example.dormitory.business.BusinessStore.*;

@Service
@Transactional
public class BatchBusinessService {
    private final BusinessStore db;
    private final DomainLocks locks;
    private final BusinessAccess access;
    private final HousingLedger ledger;
    private final Validator validator;
    private final PasswordEncoder encoder;
    private static final List<String> COLUMNS = List.of("studentId", "name", "grade", "className", "gender", "phone", "college", "major", "counselor", "initialPassword");

    public BatchBusinessService(BusinessStore db, BusinessAccess access, HousingLedger ledger, Validator validator, PasswordEncoder encoder, DomainLocks locks) {
        this.db = db;
        this.locks = locks;
        this.access = access;
        this.ledger = ledger;
        this.validator = validator;
        this.encoder = encoder;
    }

    public byte[] template() throws java.io.IOException {
        access.roles("ADMIN");
        try (var workbook = new XSSFWorkbook(); var out = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("students");
            var row = sheet.createRow(0);
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("@"));
            for (int i = 0; i < COLUMNS.size(); i++) {
                row.createCell(i).setCellValue(COLUMNS.get(i));
                sheet.setColumnWidth(i, 22 * 256);
                sheet.setDefaultColumnStyle(i, style);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    public Map<String, Object> importPreview(MultipartFile file) throws java.io.IOException {
        access.roles("ADMIN");
        invalid(file.getSize() > 0 && file.getSize() <= 2 * 1024 * 1024 && file.getOriginalFilename() != null && file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx"), "请上传不超过 2MB 的 XLSX 文件");
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> numbers = new HashSet<>();
        byte[] archive = file.getBytes();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
            long total = 0;
            int entries = 0;
            byte[] buffer = new byte[8192];
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                invalid(++entries <= 100, "模板压缩包条目过多");
                long size = 0;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    size += read;
                    total += read;
                    invalid(size <= 16 * 1024 * 1024 && total <= 32 * 1024 * 1024, "模板解压后内容过大");
                }
            }
            invalid(entries > 0, "不是有效的 XLSX 文件");
        } catch (java.util.zip.ZipException error) {
            throw new IllegalArgumentException("模板压缩包损坏");
        }
        try (var raw = new java.io.ByteArrayInputStream(archive); var workbook = new XSSFWorkbook(raw)) {
            invalid(workbook.getNumberOfSheets() == 1, "模板只允许一个工作表");
            var sheet = workbook.getSheetAt(0);
            invalid(sheet.getLastRowNum() <= 500, "单批最多 500 名学生");
            var header = sheet.getRow(0);
            invalid(header != null, "缺少表头");
            for (int c = 0; c < COLUMNS.size(); c++)
                invalid(COLUMNS.get(c).equals(value(header.getCell(c))), "请使用系统提供的模板，列顺序不正确");
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                var row = sheet.getRow(r);
                if (row == null) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                for (int c = 0; c < COLUMNS.size(); c++) item.put(COLUMNS.get(c), value(row.getCell(c)));
                if (item.values().stream().allMatch(v -> text(v).isBlank())) continue;
                String no = text(item.get("studentId"));
                String password = text(item.remove("initialPassword"));
                var dto = new Requests.StudentInput(text(item.get("name")), no, text(item.get("grade")), text(item.get("className")), text(item.get("gender")), text(item.get("phone")), null, password.isBlank() ? null : password);
                var errors = validator.validate(dto);
                invalid(errors.isEmpty(), "第 " + (r + 1) + " 行资料格式错误：" + errors.stream().map(e -> e.getPropertyPath().toString()).sorted().toList());
                invalid(text(item.get("college")).length() <= 100 && text(item.get("major")).length() <= 100 && text(item.get("counselor")).length() <= 50, "院系或辅导员信息过长");
                check(numbers.add(no), "模板中学号重复：" + no);
                check(db.count("SELECT COUNT(*) FROM student WHERE student_id=?", no) == 0, "学号已建档：" + no);
                var existing = db.jdbc.queryForList("SELECT role FROM user WHERE username=?", no);
                check(existing.isEmpty() || text(existing.get(0).get("role")).equals("student"), "学号与工作人员账号冲突：" + no);
                check(existing.isEmpty(), "存在未绑定账号，请先由管理员核验身份后处理");
                if (existing.isEmpty()) {
                    AccountBusinessService.passwordPolicy(password);
                    item.put("passwordHash", encoder.encode(password));
                }
                String counselor = text(item.get("counselor"));
                if (!counselor.isBlank())
                    check(db.count("SELECT COUNT(*) FROM user WHERE username=? AND role='counselor'", counselor) == 1, "辅导员账号不存在：" + counselor);
                rows.add(item);
            }
        }
        invalid(!rows.isEmpty(), "模板没有有效学生资料");
        String id = save("IMPORT", rows);
        return response(id, rows.stream().map(row -> {
            var safe = new LinkedHashMap<>(row);
            safe.remove("passwordHash");
            return safe;
        }).toList());
    }

    private String value(Cell cell) {
        if (cell == null) return "";
        invalid(cell.getCellType() != CellType.FORMULA, "模板禁止公式单元格");
        String value;
        if (cell.getCellType() == CellType.NUMERIC) {
            double n = cell.getNumericCellValue();
            value = java.math.BigDecimal.valueOf(n).stripTrailingZeros().toPlainString();
        } else value = new DataFormatter(Locale.ROOT).formatCellValue(cell).trim();
        invalid(value.length() <= 1000, "单元格内容过长");
        return value;
    }

    private String save(String kind, Object payload) {
        String id = UUID.randomUUID().toString();
        String now = now();
        db.jdbc.update("DELETE FROM import_batch WHERE expires_at<?", now);
        db.jdbc.update("INSERT INTO import_batch(id,owner,kind,payload,created_at,expires_at) VALUES(?,?,?,?,?,?)", id, access.actor(), kind, json(payload), now, LocalDateTime.now().plusMinutes(15).toString());
        return id;
    }

    private Map<String, Object> response(String id, Object items) {
        return Map.of("previewId", id, "items", items, "expiresInMinutes", 15);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> payload(long unused, String id, String kind) {
        var row = db.one("SELECT * FROM import_batch WHERE id=?", id);
        check(text(row.get("owner")).equals(access.actor()) && text(row.get("kind")).equals(kind), "预览不属于当前用户或类型不匹配");
        check(text(row.get("state")).equals("PREVIEW") && LocalDateTime.parse(text(row.get("expires_at"))).isAfter(LocalDateTime.now()), "预览已过期或已经执行，请重新预览");
        return (List<Map<String, Object>>) (List<?>) JsonMapper.builder().build().readValue(text(row.get("payload")), List.class);
    }

    public void importConfirm(String id) {
        access.roles("ADMIN");
        locks.batch(id);
        var rows = payload(0, id, "IMPORT");
        for (var item : rows) {
            String no = text(item.get("studentId"));
            check(db.count("SELECT COUNT(*) FROM student WHERE student_id=?", no) == 0, "预览后学号已被占用，请重新预览");
            if (item.containsKey("passwordHash")) {
                check(db.count("SELECT COUNT(*) FROM user WHERE username=?", no) == 0, "预览后账号已被占用");
                db.jdbc.update("INSERT INTO user(username,password,role,is_initialized) VALUES(?,?,'student',1)", no, item.get("passwordHash"));
                db.jdbc.update("INSERT INTO account_policy(username,force_password_change) VALUES(?,1)", no);
            } else
                check(db.count("SELECT COUNT(*) FROM user WHERE username=? AND role='student'", no) == 1, "账号状态已变化，请重新预览");
            String counselor = text(item.get("counselor"));
            if (!counselor.isBlank())
                check(db.count("SELECT COUNT(*) FROM user WHERE username=? AND role='counselor'", counselor) == 1, "辅导员账号已变化");
            long student = db.insert("INSERT INTO student(name,student_id,grade,class_name,gender,phone,is_dormitory_leader) VALUES(?,?,?,?,?,?,0)", item.get("name"), no, item.get("grade"), item.get("className"), item.get("gender"), item.get("phone"));
            db.jdbc.update("INSERT INTO student_profile(student_id,college,major,counselor) VALUES(?,?,?,?)", student, item.get("college"), item.get("major"), item.get("counselor"));
            db.jdbc.update("UPDATE user SET real_name=?,phone=?,is_initialized=1 WHERE username=?", item.get("name"), item.get("phone"), no);
        }
        db.jdbc.update("UPDATE import_batch SET state='APPLIED',payload='[]' WHERE id=?", id);
        db.audit(access.actor(), "STUDENT_IMPORT", "batch", id, Map.of("count", rows.size()));
    }

    public Map<String, Object> allocationPreview(BusinessRequests.Allocation input) {
        access.roles("ADMIN");
        locks.structure(false);
        Set<Integer> buildings = new HashSet<>(input.buildingIds());
        for (int id : buildings) db.one("SELECT id FROM building WHERE id=?", id);
        var candidates = db.jdbc.queryForList("SELECT b.*,d.dormitory_number,d.gender,x.id AS building_id FROM bed b JOIN dormitory d ON d.id=b.room_id JOIN building x ON x.name=d.building JOIN room_policy p ON p.room_id=d.id WHERE b.state='AVAILABLE' AND b.student_id IS NULL AND p.state='ACTIVE' ORDER BY d.dormitory_number,b.bed_number").stream().filter(b -> buildings.contains((int) number(b.get("building_id")))).toList();
        List<Map<String, Object>> plan = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        Map<Long, String> assignedClasses = new HashMap<>();
        for (int studentId : new LinkedHashSet<>(input.studentIds())) {
            ledger.enrolled(studentId);
            var student = db.one("SELECT s.*,COALESCE(p.college,'') AS college FROM student s LEFT JOIN student_profile p ON p.student_id=s.id WHERE s.id=?", studentId);
            check(text(student.get("dormitory_number")).isBlank(), "只允许为未入住学生批量分配");
            String group = text(student.get("college")) + "/" + text(student.get("grade")) + "/" + text(student.get("class_name"));
            var possible = candidates.stream().filter(b -> !used.contains(number(b.get("id"))) && Objects.equals(b.get("gender"), student.get("gender"))).toList();
            check(!possible.isEmpty(), "可用床位不足，无法完整分配，未写入任何入住记录");
            var bed = possible.stream().max(Comparator.comparingInt(b -> {
                long room = number(b.get("room_id"));
                return (group.equals(assignedClasses.get(room)) ? 100 : 0) + (int) db.count("SELECT COUNT(*) FROM student s LEFT JOIN student_profile p ON p.student_id=s.id WHERE s.dormitory_number=? AND s.grade=? AND COALESCE(s.class_name,'')=? AND COALESCE(p.college,'')=?", b.get("dormitory_number"), student.get("grade"), text(student.get("class_name")), text(student.get("college")));
            })).orElseThrow();
            used.add(number(bed.get("id")));
            assignedClasses.put(number(bed.get("room_id")), group);
            plan.add(Map.of("studentId", studentId, "studentNumber", student.get("student_id"), "name", student.get("name"), "bedId", bed.get("id"), "bedVersion", bed.get("version"), "room", bed.get("dormitory_number"), "bedNumber", bed.get("bed_number")));
        }
        String id = save("ALLOCATION", plan);
        return response(id, plan);
    }

    public void allocationConfirm(String id) {
        access.roles("ADMIN");
        locks.structure(false);
        var rows = payload(0, id, "ALLOCATION");
        locks.housing(rows.stream().map(r -> number(r.get("studentId"))).toList(), List.of(), rows.stream().map(r -> number(r.get("bedId"))).toList());
        locks.batch(id);
        rows = payload(0, id, "ALLOCATION");
        for (var row : rows) {
            long student = number(row.get("studentId"));
            long bed = number(row.get("bedId"));
            var seat = db.one("SELECT * FROM bed WHERE id=?", bed);
            check(number(seat.get("version")) == number(row.get("bedVersion")), "预览后床位已发生变化，请重新预览");
            check(text(db.one("SELECT dormitory_number FROM student WHERE id=?", student).get("dormitory_number")).isBlank(), "预览后学生已入住，请重新预览");
            ledger.move(student, bed, "新生批量分配 #" + id);
        }
        db.jdbc.update("UPDATE import_batch SET state='APPLIED',payload='[]' WHERE id=?", id);
        db.audit(access.actor(), "ALLOCATION_CONFIRM", "batch", id, Map.of("count", rows.size()));
    }
}
