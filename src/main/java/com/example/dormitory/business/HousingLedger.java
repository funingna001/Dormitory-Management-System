package com.example.dormitory.business;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

import static com.example.dormitory.business.BusinessStore.*;

@Service
@Transactional
public class HousingLedger {
    private final BusinessStore db;
    private final DomainLocks locks;

    public HousingLedger(BusinessStore db, DomainLocks locks) {
        this.db = db;
        this.locks = locks;
    }

    private String actor() {
        var a = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return a == null || a.getName().equals("anonymousUser") ? "SYSTEM" : a.getName();
    }

    public void ensureRoom(long id) {
        var room = db.one("SELECT * FROM dormitory WHERE id=?", id);
        if (db.count("SELECT COUNT(*) FROM room_policy WHERE room_id=?", id) == 0)
            db.jdbc.update("INSERT INTO room_policy(room_id,state) VALUES(?,'ACTIVE')", id);
        int capacity = (int) number(room.get("capacity"));
        for (int slot = 1; slot <= capacity; slot++)
            if (db.count("SELECT COUNT(*) FROM bed WHERE room_id=? AND bed_number=?", id, slot) == 0)
                db.jdbc.update("INSERT INTO bed(room_id,bed_number,state) VALUES(?,?,'AVAILABLE')", id, slot);
        check(db.count("SELECT COUNT(*) FROM bed WHERE room_id=? AND bed_number>? AND (student_id IS NOT NULL OR state='RESERVED')", id, capacity) == 0, "缩容涉及已占用或预留床位，请先处理");
        db.jdbc.update("DELETE FROM bed WHERE room_id=? AND bed_number>? AND student_id IS NULL AND state<>'RESERVED'", id, capacity);
        expireReservations(id);
    }

    private void expireReservations(long roomId) {
        for (var row : db.jdbc.queryForList("SELECT * FROM bed WHERE room_id=? AND state='RESERVED' AND reservation_until IS NOT NULL", roomId))
            if (LocalDateTime.parse(text(row.get("reservation_until"))).isBefore(LocalDateTime.now())) {
                long id = number(row.get("id"));
                db.jdbc.update("UPDATE bed SET state='AVAILABLE',reserved_for=NULL,reservation_until=NULL,version=version+1 WHERE id=?", id);
                db.jdbc.update("UPDATE housing_application SET state='CANCELLED',review_note='预留已到期' WHERE target_bed_id=? AND state='APPROVED'", id);
            }
    }

    public void roomUsable(String number) {
        var room = db.one("SELECT * FROM dormitory WHERE dormitory_number=?", number);
        ensureRoom(number(room.get("id")));
        check(db.count("SELECT COUNT(*) FROM room_policy WHERE room_id=? AND state='ACTIVE'", room.get("id")) > 0, "房间已停用或正在维修，不可安排入住");
    }

    public void enrolled(long studentId) {
        check(db.count("SELECT COUNT(*) FROM student_profile WHERE student_id=? AND lifecycle<>'ENROLLED'", studentId) == 0, "学生已归档或不具备住宿资格");
    }

    public void prepareInventory() {
        locks.structure(false);
        var rooms = new TreeSet<>(db.jdbc.queryForList("SELECT d.id FROM dormitory d LEFT JOIN room_policy p ON p.room_id=d.id WHERE p.room_id IS NULL UNION SELECT room_id FROM bed WHERE state='RESERVED'", Long.class));
        locks.housing(List.of(), rooms, List.of());
        for (long room : rooms) ensureRoom(room);
    }

    public void checkoutChecks(long studentId) {
        if (db.setting("checkout_requires_returned_assets"))
            check(db.count("SELECT COUNT(*) FROM handover WHERE student_id=? AND returned_at IS NULL", studentId) == 0, "仍有钥匙或物品未归还，请先办理交接");
        if (db.setting("checkout_requires_settled_bills"))
            check(db.count("SELECT COUNT(*) FROM bill b WHERE b.student_id=? AND b.state='ACTIVE' AND b.amount_cents>COALESCE((SELECT SUM(CASE WHEN p.kind='PAYMENT' THEN p.amount_cents ELSE -p.amount_cents END) FROM payment p WHERE p.bill_id=b.id),0)", studentId) == 0, "存在未结清账单，请先结清费用");
    }

    public void reconcile(String roomNumber) {
        reconcile(roomNumber, "管理员办理");
    }

    public void reconcile(String roomNumber, String reason) {
        if (roomNumber == null || roomNumber.isBlank()) return;
        var rooms = db.jdbc.queryForList("SELECT * FROM dormitory WHERE dormitory_number=?", roomNumber);
        if (rooms.isEmpty()) return;
        var room = rooms.get(0);
        long roomId = number(room.get("id"));
        ensureRoom(roomId);
        for (var bed : db.jdbc.queryForList("SELECT * FROM bed WHERE room_id=? AND student_id IS NOT NULL", roomId)) {
            long student = number(bed.get("student_id"));
            if (db.count("SELECT COUNT(*) FROM student WHERE id=? AND dormitory_number=?", student, roomNumber) == 0)
                releaseBed(bed, reason);
        }
        var students = db.jdbc.queryForList("SELECT * FROM student WHERE dormitory_number=? ORDER BY id", roomNumber);
        check(students.size() <= number(room.get("capacity")), "存量入住人数超过房间容量，请先核实");
        for (var student : students) {
            long studentId = number(student.get("id"));
            var occupied = db.jdbc.queryForList("SELECT * FROM bed WHERE student_id=?", studentId);
            if (!occupied.isEmpty() && number(occupied.get(0).get("room_id")) == roomId) continue;
            if (!occupied.isEmpty()) releaseBed(occupied.get(0), reason);
            var available = db.jdbc.queryForList("SELECT * FROM bed WHERE room_id=? AND state='AVAILABLE' AND student_id IS NULL ORDER BY bed_number LIMIT 1", roomId);
            check(!available.isEmpty(), "没有可分配床位，请检查停用或预留状态");
            occupy(available.get(0), student, reason);
        }
        db.jdbc.update("UPDATE dormitory SET current_count=? WHERE id=?", students.size(), roomId);
        db.jdbc.update("UPDATE leader_record SET ended_at=? WHERE room_id=? AND ended_at IS NULL AND student_id NOT IN (SELECT id FROM student WHERE dormitory_number=? AND is_dormitory_leader=1)", now(), roomId, roomNumber);
    }

    private void releaseBed(Map<String, Object> bed, String reason) {
        db.jdbc.update("UPDATE housing_history SET checkout_at=?,checkout_reason=?,checkout_by=? WHERE bed_id=? AND checkout_at IS NULL", now(), reason, actor(), bed.get("id"));
        db.jdbc.update("UPDATE bed SET student_id=NULL,state='AVAILABLE',reserved_for=NULL,reservation_until=NULL,version=version+1 WHERE id=?", bed.get("id"));
    }

    private void occupy(Map<String, Object> bed, Map<String, Object> student, String reason) {
        long roomId = number(bed.get("room_id"));
        var room = db.one("SELECT * FROM dormitory WHERE id=?", roomId);
        db.jdbc.update("UPDATE bed SET student_id=?,state='OCCUPIED',reserved_for=NULL,reservation_until=NULL,version=version+1 WHERE id=?", student.get("id"), bed.get("id"));
        db.jdbc.update("INSERT INTO housing_history(student_id,student_number,room_id,room_number,bed_id,bed_number,checkin_at,reason,handled_by) VALUES(?,?,?,?,?,?,?,?,?)", student.get("id"), student.get("student_id"), roomId, room.get("dormitory_number"), bed.get("id"), bed.get("bed_number"), now(), reason, actor());
    }

    public void validateBed(long bedId, long studentId) {
        var bed = db.one("SELECT * FROM bed WHERE id=?", bedId);
        var student = db.one("SELECT * FROM student WHERE id=?", studentId);
        var room = db.one("SELECT * FROM dormitory WHERE id=?", bed.get("room_id"));
        roomUsable(text(room.get("dormitory_number")));
        enrolled(studentId);
        bed = db.one("SELECT * FROM bed WHERE id=?", bedId);
        check(student.get("gender").equals(room.get("gender")), "学生性别与宿舍不匹配");
        check(bed.get("student_id") == null, "床位已被占用");
        check(text(bed.get("state")).equals("AVAILABLE") || text(bed.get("state")).equals("RESERVED") && number(bed.get("reserved_for")) == studentId, "床位已停用或为其他学生预留");
    }

    public void move(long studentId, Long bedId, String reason) {
        // 调宿涉及原房间和目标房间，先锁定相关记录，再检查和更新住宿信息。
        locks.housing(studentId, bedId);
        enrolled(studentId);
        var student = db.one("SELECT * FROM student WHERE id=?", studentId);
        String oldRoom = text(student.get("dormitory_number"));
        if (!oldRoom.isBlank()) reconcile(oldRoom, "历史住宿接入");
        var oldBeds = db.jdbc.queryForList("SELECT * FROM bed WHERE student_id=?", studentId);
        check(bedId == null || oldBeds.isEmpty() || number(oldBeds.get(0).get("id")) != bedId, "目标床位与当前床位相同");
        Map<String, Object> target = null;
        if (bedId != null) {
            validateBed(bedId, studentId);
            target = db.one("SELECT d.* FROM dormitory d JOIN bed b ON b.room_id=d.id WHERE b.id=?", bedId);
        }
        String newRoom = target == null ? null : text(target.get("dormitory_number"));
        if (!oldRoom.isBlank() && !Objects.equals(oldRoom, newRoom)) checkoutChecks(studentId);
        if (!oldBeds.isEmpty()) releaseBed(oldBeds.get(0), reason);
        db.jdbc.update("UPDATE student SET is_dormitory_leader=CASE WHEN dormitory_number=? THEN is_dormitory_leader ELSE 0 END,dormitory_number=? WHERE id=?", newRoom, newRoom, studentId);
        if (target != null) occupy(db.one("SELECT * FROM bed WHERE id=?", bedId), student, reason);
        reconcile(oldRoom, reason);
        reconcile(newRoom, reason);
        db.audit(actor(), "HOUSING_MOVE", "student", studentId, Map.of("from", oldRoom, "to", newRoom == null ? "" : newRoom, "reason", reason));
        db.notify(text(student.get("student_id")), "住宿安排已更新", newRoom == null ? "已办理退宿：" + reason : "宿舍：" + newRoom + "，床位：" + db.one("SELECT bed_number FROM bed WHERE id=?", bedId).get("bed_number"));
    }

    public void initialize() {
        // 旧数据只接入一次，普通启动不会重复改写入住记录。
        if (db.count("SELECT COUNT(*) FROM business_migration WHERE version=1") > 0) return;
        locks.structure(true);
        if (db.count("SELECT COUNT(*) FROM business_migration WHERE version=1") > 0) return;
        for (var room : db.jdbc.queryForList("SELECT * FROM dormitory ORDER BY id")) {
            reconcile(text(room.get("dormitory_number")), "原系统住宿数据接入");
            long roomId = number(room.get("id"));
            for (var leader : db.jdbc.queryForList("SELECT id FROM student WHERE dormitory_number=? AND is_dormitory_leader=1", room.get("dormitory_number")))
                if (db.count("SELECT COUNT(*) FROM leader_record WHERE room_id=? AND student_id=? AND ended_at IS NULL", roomId, leader.get("id")) == 0)
                    db.jdbc.update("INSERT INTO leader_record(room_id,student_id,appointed_at,actor,reason) VALUES(?,?,?,'SYSTEM','原系统宿舍长接入')", roomId, leader.get("id"), now());
        }
        for (String key : List.of("checkout_requires_settled_bills", "checkout_requires_returned_assets", "all_residents_can_report"))
            if (db.count("SELECT COUNT(*) FROM business_setting WHERE setting_key=?", key) == 0)
                db.jdbc.update("INSERT INTO business_setting(setting_key,setting_value) VALUES(?,'true')", key);
        if (db.count("SELECT COUNT(*) FROM business_migration WHERE version=1") == 0)
            db.jdbc.update("INSERT INTO business_migration(version,applied_at) VALUES(1,?)", now());
    }
}
