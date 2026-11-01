package com.example.dormitory.business;

import org.springframework.stereotype.Component;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;

import java.util.*;

import static com.example.dormitory.business.BusinessStore.*;

@Component
public class BusinessAccess {
    private final BusinessStore db;

    public BusinessAccess(BusinessStore db) {
        this.db = db;
    }

    public String actor() {
        var a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || a.getName().equals("anonymousUser"))
            throw new AccessDeniedException("请先登录");
        return a.getName();
    }

    public boolean role(String role) {
        var a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_" + role));
    }

    public void roles(String... roles) {
        if (Arrays.stream(roles).noneMatch(this::role)) throw new AccessDeniedException("无访问权限");
    }

    public Map<String, Object> self() {
        return db.one("SELECT * FROM student WHERE student_id=?", actor());
    }

    public boolean roomAllowed(long roomId) {
        if (role("ADMIN")) return true;
        if (role("MANAGER"))
            return db.count("SELECT COUNT(*) FROM dormitory d JOIN building b ON d.building=b.name JOIN manager_building m ON m.building_id=b.id WHERE d.id=? AND m.username=?", roomId, actor()) > 0;
        if (role("STUDENT"))
            return db.count("SELECT COUNT(*) FROM student s JOIN dormitory d ON s.dormitory_number=d.dormitory_number WHERE d.id=? AND s.student_id=?", roomId, actor()) > 0;
        return false;
    }

    public void room(long id) {
        if (!roomAllowed(id)) throw new AccessDeniedException("不在你的楼栋或宿舍管理范围内");
    }

    public void staffRoom(long id) {
        roles("ADMIN", "MANAGER");
        room(id);
    }

    public boolean studentAllowed(long id) {
        if (role("ADMIN")) return true;
        var student = db.one("SELECT * FROM student WHERE id=?", id);
        if (role("STUDENT")) return text(student.get("student_id")).equals(actor());
        if (role("COUNSELOR"))
            return db.count("SELECT COUNT(*) FROM student_profile WHERE student_id=? AND counselor=?", id, actor()) > 0;
        if (role("MANAGER")) {
            if (db.count("SELECT COUNT(*) FROM housing_application a JOIN bed b ON a.target_bed_id=b.id JOIN dormitory d ON b.room_id=d.id JOIN building x ON d.building=x.name JOIN manager_building m ON m.building_id=x.id WHERE a.student_id=? AND a.state IN ('PENDING','APPROVED') AND m.username=?", id, actor()) > 0)
                return true;
            var rows = db.jdbc.queryForList("SELECT id FROM dormitory WHERE dormitory_number=?", student.get("dormitory_number"));
            return !rows.isEmpty() && roomAllowed(number(rows.get(0).get("id")));
        }
        return false;
    }

    public void student(long id) {
        if (!studentAllowed(id)) throw new AccessDeniedException("无权操作此学生");
    }

    public void reviewStudent(long id) {
        roles("ADMIN", "MANAGER", "COUNSELOR");
        student(id);
    }

    public List<Map<String, Object>> rooms() {
        var args = new ArrayList<Object>();
        String scope = roomScope("d.id", args);
        return db.jdbc.queryForList("SELECT d.*,COALESCE(p.state,'ACTIVE') AS room_state,COALESCE(p.reason,'') AS state_reason FROM dormitory d LEFT JOIN room_policy p ON p.room_id=d.id WHERE " + scope + " ORDER BY d.building,d.dormitory_number", args.toArray());
    }

    // 字段表达式由后端指定，账号值通过参数传入，不能让前端拼接 SQL。
    public String roomScope(String roomId, List<Object> args) {
        if (role("ADMIN")) return "1=1";
        if (role("MANAGER")) {
            args.add(actor());
            return "EXISTS (SELECT 1 FROM dormitory scope_d JOIN building scope_b ON scope_b.name=scope_d.building JOIN manager_building scope_m ON scope_m.building_id=scope_b.id WHERE scope_d.id=" + roomId + " AND scope_m.username=?)";
        }
        if (role("STUDENT")) {
            args.add(actor());
            return "EXISTS (SELECT 1 FROM student scope_s JOIN dormitory scope_d ON scope_d.dormitory_number=scope_s.dormitory_number WHERE scope_d.id=" + roomId + " AND scope_s.student_id=?)";
        }
        return "1=0";
    }

    public String studentScope(String studentId, List<Object> args) {
        if (role("ADMIN")) return "1=1";
        if (role("STUDENT")) {
            args.add(actor());
            return "EXISTS (SELECT 1 FROM student scope_s WHERE scope_s.id=" + studentId + " AND scope_s.student_id=?)";
        }
        if (role("COUNSELOR")) {
            args.add(actor());
            return "EXISTS (SELECT 1 FROM student_profile scope_p WHERE scope_p.student_id=" + studentId + " AND scope_p.counselor=?)";
        }
        if (role("MANAGER")) {
            args.add(actor());
            args.add(actor());
            return "(EXISTS (SELECT 1 FROM student scope_s JOIN dormitory scope_d ON scope_d.dormitory_number=scope_s.dormitory_number JOIN building scope_b ON scope_b.name=scope_d.building JOIN manager_building scope_m ON scope_m.building_id=scope_b.id WHERE scope_s.id=" + studentId + " AND scope_m.username=?) OR EXISTS (SELECT 1 FROM housing_application scope_a JOIN bed scope_bed ON scope_bed.id=scope_a.target_bed_id JOIN dormitory scope_d ON scope_d.id=scope_bed.room_id JOIN building scope_b ON scope_b.name=scope_d.building JOIN manager_building scope_m ON scope_m.building_id=scope_b.id WHERE scope_a.student_id=" + studentId + " AND scope_a.state IN ('PENDING','APPROVED') AND scope_m.username=?))";
        }
        return "1=0";
    }
}
