package com.example.dormitory.business;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

import static com.example.dormitory.business.BusinessStore.*;

@Service
@Transactional
public class HousingBusinessService {
    private final BusinessStore db;
    private final DomainLocks locks;
    private final BusinessLists lists;
    private final BusinessAccess access;
    private final HousingLedger ledger;

    public HousingBusinessService(BusinessStore db, BusinessAccess access, HousingLedger ledger, BusinessLists lists, DomainLocks locks) {
        this.db = db;
        this.locks = locks;
        this.lists = lists;
        this.access = access;
        this.ledger = ledger;
    }

    public List<Map<String, Object>> beds() {
        access.roles("ADMIN", "MANAGER", "COUNSELOR", "STUDENT");
        ledger.prepareInventory();
        return lists.all("beds");
    }

    public void roomState(long roomId, BusinessRequests.RoomState input) {
        locks.room(roomId);
        access.staffRoom(roomId);
        ledger.ensureRoom(roomId);
        check(input.state().equals("ACTIVE") || db.count("SELECT COUNT(*) FROM bed WHERE room_id=? AND state='RESERVED'", roomId) == 0, "请先处理预留床位");
        db.jdbc.update("UPDATE room_policy SET state=?,reason=?,version=version+1 WHERE room_id=?", input.state(), input.reason(), roomId);
        db.audit(access.actor(), "ROOM_STATE", "room", roomId, input);
        for (var student : db.jdbc.queryForList("SELECT s.student_id FROM student s JOIN dormitory d ON s.dormitory_number=d.dormitory_number WHERE d.id=?", roomId))
            db.notify(text(student.get("student_id")), "宿舍状态变更", input.reason());
    }

    public void bedState(long bedId, BusinessRequests.BedState input) {
        locks.housing(input.studentId() == null ? List.of() : List.of(input.studentId()), List.of(), List.of(bedId));
        var bed = db.one("SELECT * FROM bed WHERE id=?", bedId);
        access.staffRoom(number(bed.get("room_id")));
        check(bed.get("student_id") == null, "已有学生入住的床位不可停用或预留");
        if (input.state().equals("RESERVED")) {
            check(input.studentId() != null && input.until() != null, "预留需要指定学生与到期时间");
            ledger.enrolled(input.studentId());
            var until = LocalDateTime.parse(input.until());
            invalid(until.isAfter(LocalDateTime.now()) && until.isBefore(LocalDateTime.now().plusDays(31)), "预留期限应在未来 30 天内");
            var student = db.one("SELECT * FROM student WHERE id=?", input.studentId());
            var room = db.one("SELECT * FROM dormitory WHERE id=?", bed.get("room_id"));
            check(Objects.equals(student.get("gender"), room.get("gender")), "学生性别不匹配");
            check(db.count("SELECT COUNT(*) FROM bed WHERE reserved_for=? AND id<>?", input.studentId(), bedId) == 0, "该学生已有预留床位");
            ledger.roomUsable(text(room.get("dormitory_number")));
        }
        db.jdbc.update("UPDATE bed SET state=?,reserved_for=?,reservation_until=?,version=version+1 WHERE id=?", input.state(), input.state().equals("RESERVED") ? input.studentId() : null, input.state().equals("RESERVED") ? input.until() : null, bedId);
        db.audit(access.actor(), "BED_STATE", "bed", bedId, input);
    }

    public void move(BusinessRequests.Move input) {
        locks.housing(input.studentId(), input.bedId());
        access.roles("ADMIN", "MANAGER");
        access.student(input.studentId());
        if (input.bedId() != null)
            access.staffRoom(number(db.one("SELECT room_id FROM bed WHERE id=?", input.bedId()).get("room_id")));
        ledger.move(input.studentId(), input.bedId(), input.reason());
    }

    public List<Map<String, Object>> applications() {
        return lists.all("applications");
    }

    public long apply(BusinessRequests.Application input) {
        access.roles("STUDENT");
        locks.structure(false);
        locks.housing(number(access.self().get("id")), input.bedId());
        var student = access.self();
        long id = number(student.get("id"));
        ledger.enrolled(id);
        check(db.count("SELECT COUNT(*) FROM housing_application WHERE student_id=? AND state IN ('PENDING','APPROVED')", id) == 0, "已有待处理住宿申请");
        String room = text(student.get("dormitory_number"));
        boolean housed = !room.isBlank();
        check(input.kind().equals("CHECKIN") ? !housed : housed, "申请类型与当前住宿状态不符");
        if (input.kind().equals("CHECKOUT")) check(input.bedId() == null, "退宿不需要目标床位");
        else if (input.bedId() != null) ledger.validateBed(input.bedId(), id);
        if (housed) ledger.reconcile(room);
        var old = db.jdbc.queryForList("SELECT id FROM bed WHERE student_id=?", id);
        long result = db.insert("INSERT INTO housing_application(student_id,kind,target_bed_id,old_bed_id,reason,created_at) VALUES(?,?,?,?,?,?)", id, input.kind(), input.bedId(), old.isEmpty() ? null : old.get(0).get("id"), input.reason(), now());
        db.audit(access.actor(), "HOUSING_APPLY", "housing_application", result, input);
        return result;
    }

    public void decide(long id, BusinessRequests.Decision input) {
        var row = locks.application(id, input.bedId());
        long student = number(row.get("student_id"));
        access.reviewStudent(student);
        check(text(row.get("state")).equals("PENDING"), "该申请已处理");
        Long bed = input.bedId() != null ? input.bedId() : row.get("target_bed_id") == null ? null : number(row.get("target_bed_id"));
        if (input.approve() && !text(row.get("kind")).equals("CHECKOUT")) {
            check(bed != null, "批准入住或调宿时必须选择床位");
            ledger.validateBed(bed, student);
            long room = number(db.one("SELECT room_id FROM bed WHERE id=?", bed).get("room_id"));
            if (!access.role("ADMIN")) {
                if (access.role("MANAGER")) access.staffRoom(room);
                else
                    check(db.count("SELECT COUNT(*) FROM manager_building m JOIN building b ON b.id=m.building_id JOIN dormitory d ON d.building=b.name WHERE d.id=?", room) > 0, "目标楼栋尚未配置宿管，请由管理员审批");
            }
            check(db.count("SELECT COUNT(*) FROM bed WHERE reserved_for=? AND id<>?", student, bed) == 0, "该学生已有其他预留床位");
            db.jdbc.update("UPDATE bed SET state='RESERVED',reserved_for=?,reservation_until=?,version=version+1 WHERE id=?", student, LocalDateTime.now().plusDays(3).toString(), bed);
        }
        db.jdbc.update("UPDATE housing_application SET state=?,target_bed_id=?,reviewer=?,review_note=?,reviewed_at=? WHERE id=?", input.approve() ? "APPROVED" : "REJECTED", bed, access.actor(), input.note(), now(), id);
        db.notify(text(db.one("SELECT student_id FROM student WHERE id=?", student).get("student_id")), "住宿申请审核结果", (input.approve() ? "已批准：" : "未通过：") + input.note());
        db.audit(access.actor(), "HOUSING_REVIEW", "housing_application", id, input);
    }

    public void complete(long id) {
        var row = locks.application(id, null);
        access.roles("ADMIN", "MANAGER");
        long student = number(row.get("student_id"));
        access.student(student);
        check(text(row.get("state")).equals("APPROVED"), "申请尚未批准或已办理");
        Long target = text(row.get("kind")).equals("CHECKOUT") ? null : number(row.get("target_bed_id"));
        if (target != null)
            access.staffRoom(number(db.one("SELECT room_id FROM bed WHERE id=?", target).get("room_id")));
        var actual = db.jdbc.queryForList("SELECT id FROM bed WHERE student_id=?", student);
        Object old = row.get("old_bed_id");
        check(old == null ? actual.isEmpty() : !actual.isEmpty() && number(actual.get(0).get("id")) == number(old), "申请后住宿状态已变化，请重新申请");
        ledger.move(student, target, "申请 #" + id + "：" + row.get("reason"));
        db.jdbc.update("UPDATE housing_application SET state='COMPLETED',completed_at=? WHERE id=?", now(), id);
    }

    public void cancel(long id) {
        var row = locks.application(id, null);
        access.student(number(row.get("student_id")));
        check(List.of("PENDING", "APPROVED").contains(text(row.get("state"))), "此申请不可撤销");
        if (row.get("target_bed_id") != null)
            db.jdbc.update("UPDATE bed SET state='AVAILABLE',reserved_for=NULL,reservation_until=NULL,version=version+1 WHERE id=? AND state='RESERVED' AND reserved_for=?", row.get("target_bed_id"), row.get("student_id"));
        db.jdbc.update("UPDATE housing_application SET state='CANCELLED' WHERE id=?", id);
        db.audit(access.actor(), "HOUSING_CANCEL", "housing_application", id, Map.of());
    }

    public List<Map<String, Object>> history() {
        return lists.all("history");
    }

    public void appoint(long roomId, BusinessRequests.Leader input) {
        locks.structure(false);
        var leaders = db.jdbc.queryForList("SELECT id FROM student WHERE dormitory_number=(SELECT dormitory_number FROM dormitory WHERE id=?) AND is_dormitory_leader=1", Long.class, roomId);
        var studentIds = new HashSet<>(leaders); studentIds.add(input.studentId());
        locks.housing(studentIds, List.of(roomId), List.of());
        check(new HashSet<>(db.jdbc.queryForList("SELECT id FROM student WHERE dormitory_number=(SELECT dormitory_number FROM dormitory WHERE id=?) AND is_dormitory_leader=1", Long.class, roomId)).equals(new HashSet<>(leaders)), "宿舍长已变化，请刷新后重试");
        access.staffRoom(roomId);
        var room = db.one("SELECT * FROM dormitory WHERE id=?", roomId);
        var student = db.one("SELECT * FROM student WHERE id=?", input.studentId());
        check(Objects.equals(student.get("dormitory_number"), room.get("dormitory_number")), "宿舍长必须为当前宿舍已入住学生");
        db.jdbc.update("UPDATE leader_record SET ended_at=? WHERE room_id=? AND ended_at IS NULL", now(), roomId);
        for (long leader : leaders) db.jdbc.update("UPDATE student SET is_dormitory_leader=0 WHERE id=?", leader);
        db.jdbc.update("UPDATE student SET is_dormitory_leader=1 WHERE id=?", input.studentId());
        db.jdbc.update("INSERT INTO leader_record(room_id,student_id,appointed_at,actor,reason) VALUES(?,?,?,?,?)", roomId, input.studentId(), now(), access.actor(), input.reason());
        db.audit(access.actor(), "LEADER_APPOINT", "room", roomId, input);
        db.notify(text(student.get("student_id")), "宿舍长任命", input.reason());
    }

    public List<Map<String, Object>> leaders() {
        return lists.all("leaders");
    }

    public List<Map<String, Object>> students() {
        return lists.all("students");
    }

    public void profile(long id, BusinessRequests.Profile input) {
        locks.housing(id, null);
        access.roles("ADMIN");
        var student = db.one("SELECT * FROM student WHERE id=?", id);
        if (input.counselor() != null && !input.counselor().isBlank())
            check(db.count("SELECT COUNT(*) FROM user WHERE username=? AND role='counselor'", input.counselor()) == 1, "辅导员账号不存在");
        if (!input.lifecycle().equals("ENROLLED")) {
            check(text(student.get("dormitory_number")).isBlank(), "归档前请先办理退宿");
            ledger.checkoutChecks(id);
        }
        if (db.count("SELECT COUNT(*) FROM student_profile WHERE student_id=?", id) == 0)
            db.jdbc.update("INSERT INTO student_profile(student_id) VALUES(?)", id);
        db.jdbc.update("UPDATE student_profile SET lifecycle=?,college=?,major=?,counselor=?,archived_at=?,archive_reason=? WHERE student_id=?", input.lifecycle(), input.college(), input.major(), input.counselor(), input.lifecycle().equals("ENROLLED") ? null : now(), input.reason(), id);
        if (db.count("SELECT COUNT(*) FROM account_policy WHERE username=?", student.get("student_id")) == 0)
            db.jdbc.update("INSERT INTO account_policy(username) VALUES(?)", student.get("student_id"));
        db.jdbc.update("UPDATE account_policy SET enabled=?,credential_version=credential_version+1 WHERE username=?", input.lifecycle().equals("ENROLLED") ? 1 : 0, student.get("student_id"));
        db.audit(access.actor(), "STUDENT_LIFECYCLE", "student", id, input);
    }
}
