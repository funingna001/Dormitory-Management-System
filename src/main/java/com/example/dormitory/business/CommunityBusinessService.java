package com.example.dormitory.business;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

import static com.example.dormitory.business.BusinessStore.*;

@Service
@Transactional
public class CommunityBusinessService {
    private final BusinessStore db;
    private final DomainLocks locks;
    private final BusinessLists lists;
    private final BusinessAccess access;

    public CommunityBusinessService(BusinessStore db, BusinessAccess access, BusinessLists lists, DomainLocks locks) {
        this.db = db;
        this.locks = locks;
        this.lists = lists;
        this.access = access;
    }

    public List<Map<String, Object>> announcements() {
        return lists.all("announcements");
    }

    private boolean buildingAllowed(long id) {
        if (access.role("ADMIN")) return true;
        return access.rooms().stream().anyMatch(r -> db.count("SELECT COUNT(*) FROM building WHERE id=? AND name=?", id, r.get("building")) > 0);
    }

    public long announce(BusinessRequests.Announcement input) {
        access.roles("ADMIN", "MANAGER");
        if (input.audience().equals("BUILDING")) {
            check(input.buildingId() != null && buildingAllowed(input.buildingId()), "无权向此楼栋发布通知");
            db.one("SELECT id FROM building WHERE id=?", input.buildingId());
        } else access.roles("ADMIN");
        long id = db.insert("INSERT INTO announcement(title,content,audience,building_id,author,created_at) VALUES(?,?,?,?,?,?)", input.title(), input.content(), input.audience(), input.audience().equals("BUILDING") ? input.buildingId() : null, access.actor(), now());
        db.audit(access.actor(), "ANNOUNCEMENT_PUBLISH", "announcement", id, Map.of("title", input.title(), "audience", input.audience()));
        return id;
    }

    public void readAnnouncement(long id) {
        check(lists.announcementAllowed(id), "通知不存在或无权访问");
        if (db.count("SELECT COUNT(*) FROM announcement_receipt WHERE announcement_id=? AND username=?", id, access.actor()) == 0)
            db.jdbc.update("INSERT INTO announcement_receipt(announcement_id,username,read_at) VALUES(?,?,?)", id, access.actor(), now());
    }

    public List<Map<String, Object>> inbox() {
        return lists.all("notifications");
    }

    public void readNotification(long id) {
        var row = db.one("SELECT * FROM notification WHERE id=?", id);
        check(text(row.get("recipient")).equals(access.actor()), "无权读取他人消息");
        db.jdbc.update("UPDATE notification SET is_read=1 WHERE id=?", id);
    }

    public List<Map<String, Object>> stays() {
        return lists.all("stays");
    }

    public long stay(BusinessRequests.Stay input) {
        access.roles("STUDENT");
        locks.structure(false);
        locks.students(List.of(number(access.self().get("id"))));
        var student = access.self();
        check(!text(student.get("dormitory_number")).isBlank(), "须先办理入住");
        var start = LocalDateTime.parse(input.startAt());
        var end = LocalDateTime.parse(input.endAt());
        invalid(end.isAfter(start) && start.isAfter(LocalDateTime.now().minusDays(30)) && end.isBefore(start.plusDays(181)), "时间范围不合法，最长 180 天");
        check(db.count("SELECT COUNT(*) FROM stay_request WHERE student_id=? AND kind=? AND state IN ('PENDING','APPROVED') AND start_at<? AND end_at>?", student.get("id"), input.kind(), end.toString(), start.toString()) == 0, "同类申请时间重叠");
        long id = db.insert("INSERT INTO stay_request(student_id,kind,start_at,end_at,reason,contact_phone,created_at) VALUES(?,?,?,?,?,?,?)", student.get("id"), input.kind(), start.toString(), end.toString(), input.reason(), input.contactPhone(), now());
        db.audit(access.actor(), "STAY_APPLY", "stay_request", id, Map.of("kind", input.kind()));
        return id;
    }

    public void reviewStay(long id, BusinessRequests.Decision input) {
        locks.record("stay_request", id);
        var row = db.one("SELECT * FROM stay_request WHERE id=?", id);
        access.reviewStudent(number(row.get("student_id")));
        check(text(row.get("state")).equals("PENDING"), "申请已处理");
        db.jdbc.update("UPDATE stay_request SET state=?,reviewer=?,review_note=? WHERE id=?", input.approve() ? "APPROVED" : "REJECTED", access.actor(), input.note(), id);
        db.notify(text(db.one("SELECT student_id FROM student WHERE id=?", row.get("student_id")).get("student_id")), "留宿/外宿申请审核结果", input.note());
        db.audit(access.actor(), "STAY_REVIEW", "stay_request", id, input);
    }

    public void returned(long id) {
        locks.record("stay_request", id);
        var row = db.one("SELECT * FROM stay_request WHERE id=?", id);
        access.student(number(row.get("student_id")));
        check(text(row.get("state")).equals("APPROVED"), "仅已批准申请可登记返校");
        db.jdbc.update("UPDATE stay_request SET state='RETURNED',actual_return_at=? WHERE id=?", now(), id);
        db.audit(access.actor(), "STAY_RETURN", "stay_request", id, Map.of());
    }

    public List<Map<String, Object>> visitors() {
        return lists.all("visitors");
    }

    public long visit(BusinessRequests.Visitor input) {
        access.staffRoom(input.roomId());
        var room = db.one("SELECT * FROM dormitory WHERE id=?", input.roomId());
        var student = db.one("SELECT * FROM student WHERE id=?", input.studentId());
        check(Objects.equals(room.get("dormitory_number"), student.get("dormitory_number")), "被访学生不属于该宿舍");
        var leave = LocalDateTime.parse(input.expectedLeaveAt());
        invalid(leave.isAfter(LocalDateTime.now()) && leave.isBefore(LocalDateTime.now().plusDays(2)), "预计离开时间须在未来 48 小时内");
        long id = db.insert("INSERT INTO visitor(room_id,student_id,visitor_name,phone,expected_leave_at,arrived_at,operator,note) VALUES(?,?,?,?,?,?,?,?)", input.roomId(), input.studentId(), input.visitorName(), input.phone(), leave.toString(), now(), access.actor(), input.note());
        db.audit(access.actor(), "VISITOR_ARRIVE", "visitor", id, Map.of("roomId", input.roomId()));
        return id;
    }

    public void leave(long id) {
        locks.record("visitor", id);
        var row = db.one("SELECT * FROM visitor WHERE id=?", id);
        access.staffRoom(number(row.get("room_id")));
        check(row.get("left_at") == null, "访客已离开");
        db.jdbc.update("UPDATE visitor SET left_at=? WHERE id=?", now(), id);
        db.audit(access.actor(), "VISITOR_LEAVE", "visitor", id, Map.of());
    }

    public List<Map<String, Object>> inspections() {
        return lists.all("inspections");
    }

    public long inspect(BusinessRequests.Inspection input) {
        access.staffRoom(input.roomId());
        var due = LocalDateTime.parse(input.dueAt());
        invalid(due.isAfter(LocalDateTime.now()) && due.isBefore(LocalDateTime.now().plusDays(181)), "整改期限应在未来 180 天内");
        long id = db.insert("INSERT INTO inspection(room_id,kind,score,detail,due_at,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)", input.roomId(), input.kind(), input.score(), input.detail(), due.toString(), access.actor(), now(), now());
        for (var s : db.jdbc.queryForList("SELECT s.student_id FROM student s JOIN dormitory d ON s.dormitory_number=d.dormitory_number WHERE d.id=?", input.roomId()))
            db.notify(text(s.get("student_id")), "宿舍检查待整改", input.detail());
        db.audit(access.actor(), "INSPECTION_CREATE", "inspection", id, Map.of("roomId", input.roomId(), "kind", input.kind()));
        return id;
    }

    public void rectify(long id, BusinessRequests.Note input) {
        locks.record("inspection", id);
        var row = db.one("SELECT * FROM inspection WHERE id=?", id);
        access.room(number(row.get("room_id")));
        access.roles("STUDENT", "ADMIN", "MANAGER");
        check(text(row.get("state")).equals("OPEN"), "当前记录不允许提交整改");
        db.jdbc.update("UPDATE inspection SET state='SUBMITTED',rectify_note=?,updated_at=? WHERE id=?", input.note(), now(), id);
        db.audit(access.actor(), "INSPECTION_RECTIFY", "inspection", id, Map.of());
    }

    public void reviewInspection(long id, BusinessRequests.Review input) {
        locks.record("inspection", id);
        var row = db.one("SELECT * FROM inspection WHERE id=?", id);
        access.staffRoom(number(row.get("room_id")));
        check(text(row.get("state")).equals("SUBMITTED"), "应先提交整改结果");
        db.jdbc.update("UPDATE inspection SET state=?,review_note=?,reviewed_by=?,updated_at=? WHERE id=?", input.approve() ? "CLOSED" : "OPEN", input.note(), access.actor(), now(), id);
        db.audit(access.actor(), "INSPECTION_REVIEW", "inspection", id, input);
    }

    public Map<String, Object> inspection(long id) {
        var row = db.one("SELECT * FROM inspection WHERE id=?", id);
        access.room(number(row.get("room_id")));
        return row;
    }

    public void inspectionImage(long id, String path, boolean rectify) {
        locks.record("inspection", id);
        var row = inspection(id);
        check(!text(row.get("state")).equals("CLOSED"), "已关闭检查不可修改图片");
        if (!rectify) access.staffRoom(number(row.get("room_id")));
        else
            check(text(row.get("state")).equals("OPEN") || text(row.get("state")).equals("SUBMITTED"), "当前检查不可提交图片");
        String field = rectify ? "rectify_image_path" : "image_path";
        db.jdbc.update("UPDATE inspection SET " + field + "=?,updated_at=? WHERE id=?", path, now(), id);
        db.audit(access.actor(), "INSPECTION_IMAGE", "inspection", id, Map.of("rectification", rectify));
    }
}
