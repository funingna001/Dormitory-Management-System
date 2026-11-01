package com.example.dormitory.business;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

import static com.example.dormitory.business.BusinessStore.*;

/** 列表和选择框共用查询规则，让权限筛选和分页都在数据库里完成。 */
@Service
@Transactional(readOnly = true)
public class BusinessLists {
    private final BusinessStore db;
    private final BusinessAccess access;

    public BusinessLists(BusinessStore db, BusinessAccess access) {
        this.db = db;
        this.access = access;
    }

    public record Page(List<Map<String, Object>> items, long total, int page, int size) {}
    private record Query(String sql, String order, String columns, List<Object> args) {}

    public Page page(String resource, int page, int size, String search) {
        return page(resource, page, size, search, "", "", "", "");
    }

    public Page page(String resource, int page, int size, String search, String state, String building, String from, String to) {
        // 先限制参数范围，再组合条件；用户输入始终通过参数传入 SQL。
        if (page < 1 || page > 1000000 || size < 1 || size > 100 || search != null && search.length() > 200)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页或搜索参数不合法");
        var query = query(resource);
        var args = new ArrayList<>(query.args());
        String sql = " FROM (" + query.sql() + ") q";
        var conditions = new ArrayList<String>();
        if (!state.isBlank()) {
            invalid(state.length() <= 30, "状态参数过长");
            String field = switch (resource) {
                case "students" -> "lifecycle";
                case "rooms" -> "room_state";
                case "repairs" -> state.equals("WAITING_ASSIGNMENT") ? "dispatch_state" : "status";
                case "beds", "applications", "stays", "inspections", "bills", "assets", "announcements" -> "state";
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前业务不支持状态筛选");
            };
            conditions.add("q." + field + "=?"); args.add(state);
        }
        if (!building.isBlank()) {
            invalid(building.length() <= 50, "楼栋参数过长");
            String room = switch (resource) {
                case "students", "repairs" -> "dormitory_number";
                case "bills" -> "room_number";
                default -> null;
            };
            if (Set.of("rooms", "beds").contains(resource)) conditions.add("q.building=?");
            else if (room != null) conditions.add("q." + room + " IN (SELECT dormitory_number FROM dormitory WHERE building=?)");
            else if (Set.of("history", "leaders", "visitors", "inspections", "assets", "handovers").contains(resource))
                conditions.add("q.room_id IN (SELECT id FROM dormitory WHERE building=?)");
            else if (resource.equals("applications")) conditions.add("q.target_bed_id IN (SELECT b.id FROM bed b JOIN dormitory d ON d.id=b.room_id WHERE d.building=?)");
            else if (resource.equals("stays")) conditions.add("q.dormitory_number IN (SELECT dormitory_number FROM dormitory WHERE building=?)");
            else throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前业务不支持楼栋筛选");
            args.add(building);
        }
        if (!from.isBlank() || !to.isBlank()) {
            String date = switch (resource) {
                case "history" -> "checkin_at";
                case "leaders" -> "appointed_at";
                case "visitors" -> "arrived_at";
                case "repairs" -> "create_time";
                case "students" -> "archived_at";
                case "applications", "audit", "announcements", "notifications", "stays", "inspections", "bills" -> "created_at";
                case "handovers" -> "issued_at";
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前业务不支持日期筛选");
            };
            var start = from.isBlank() ? null : java.time.LocalDate.parse(from);
            var end = to.isBlank() ? null : java.time.LocalDate.parse(to);
            invalid(start == null || end == null || !start.isAfter(end), "日期范围不合法");
            if (start != null) { conditions.add("q." + date + ">=?"); args.add(start.toString()); }
            if (end != null) { conditions.add("q." + date + "<?"); args.add(end.plusDays(1).toString()); }
        }
        if (search != null && !search.isBlank()) {
            String pattern = "%" + search.strip().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            var clauses = new ArrayList<String>();
            for (String column : query.columns().split(",")) {
                clauses.add("LOWER(CAST(q." + column + " AS CHAR)) LIKE ? ESCAPE '!' ");
                args.add(pattern);
            }
            conditions.add("(" + String.join(" OR ", clauses) + ")");
        }
        if (!conditions.isEmpty()) sql += " WHERE " + String.join(" AND ", conditions);
        // 总数和当前页使用相同的筛选条件。
        long total = db.count("SELECT COUNT(*)" + sql, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        var rows = db.jdbc.queryForList("SELECT q.*" + sql + " ORDER BY " + query.order() + " LIMIT ? OFFSET ?", args.toArray());
        decorate(resource, rows);
        return new Page(rows, total, page, size);
    }

    public List<Map<String, Object>> all(String resource) {
        var query = query(resource);
        var rows = db.jdbc.queryForList("SELECT q.* FROM (" + query.sql() + ") q ORDER BY " + query.order(), query.args().toArray());
        decorate(resource, rows);
        return rows;
    }

    public Page options(String source, int page, int size, String search, List<String> ids, boolean unhoused) {
        invalid(page >= 1 && page <= 1000000 && size >= 1 && size <= 100 && search.length() <= 100 && ids.size() <= 500 && ids.stream().allMatch(id -> id.length() <= 50), "选项参数不合法");
        invalid(Set.of("students", "rooms", "beds", "buildings", "workers").contains(source), "未知选项类型");
        var query = query(source);
        var args = new ArrayList<>(query.args());
        String key = source.equals("workers") ? "username" : "id";
        String[] fields = switch (source) {
            case "students" -> new String[]{"name", "student_id"};
            case "rooms", "beds" -> new String[]{"dormitory_number"};
            case "workers" -> new String[]{"username", "real_name"};
            default -> new String[]{"name"};
        };
        var where = new ArrayList<String>();
        if (!ids.isEmpty()) {
            if (!source.equals("workers")) invalid(ids.stream().allMatch(id -> id.matches("[1-9][0-9]{0,18}")), "选项编号不合法");
            where.add("q." + key + " IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")");
            args.addAll(ids);
        }
        if (!search.isBlank()) {
            var parts = new ArrayList<String>();
            String pattern = search.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            for (String field : fields) { parts.add("q." + field + " LIKE ? ESCAPE '!'"); args.add(pattern); }
            where.add("(" + String.join(" OR ", parts) + ")");
        }
        if (source.equals("beds")) where.add("q.state IN ('AVAILABLE','RESERVED')");
        if (unhoused) {
            invalid(source.equals("students"), "仅学生选项支持未入住条件");
            where.add("(q.dormitory_number IS NULL OR q.dormitory_number='') AND q.lifecycle='ENROLLED'");
        }
        String projection = switch (source) {
            case "students" -> "q.id,q.name,q.student_id,q.class_name,q.dormitory_number,q.lifecycle";
            case "rooms" -> "q.id,q.dormitory_number,q.building";
            case "beds" -> "q.id,q.dormitory_number,q.bed_number,q.state";
            case "workers" -> "q.username,q.real_name";
            default -> "q.id,q.name,q.gender";
        };
        String sql = " FROM (" + query.sql() + ") q" + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where));
        long total = db.count("SELECT COUNT(*)" + sql, args.toArray());
        args.add(size); args.add((page - 1) * size);
        return new Page(db.jdbc.queryForList("SELECT " + projection + sql + " ORDER BY " + query.order() + " LIMIT ? OFFSET ?", args.toArray()), total, page, size);
    }

    public boolean announcementAllowed(long id) {
        var query = query("announcements");
        var args = new ArrayList<>(query.args());
        args.add(id);
        return db.count("SELECT COUNT(*) FROM (" + query.sql() + ") q WHERE q.id=?", args.toArray()) > 0;
    }

    private Query query(String resource) {
        var args = new ArrayList<Object>();
        String sql, scope, columns, order = "id DESC";
        switch (resource) {
            case "buildings" -> {
                access.roles("ADMIN", "MANAGER");
                scope = "1=1";
                if (!access.role("ADMIN")) { scope = "EXISTS(SELECT 1 FROM manager_building m WHERE m.building_id=b.id AND m.username=?)"; args.add(access.actor()); }
                sql = "SELECT b.* FROM building b WHERE " + scope;
                columns = "name,gender"; order = "name,id";
            }
            case "workers" -> {
                access.roles("ADMIN", "MANAGER");
                sql = "SELECT u.id,u.username,u.real_name FROM user u LEFT JOIN account_policy p ON p.username=u.username WHERE u.role='worker' AND COALESCE(p.enabled,1)=1";
                columns = "username,real_name";
            }
            case "consistency" -> {
                access.roles("ADMIN");
                sql = """
                    SELECT c.* FROM (
                      SELECT d.id*2 AS id,d.dormitory_number,d.capacity,d.current_count,
                        (SELECT COUNT(*) FROM student s WHERE s.dormitory_number=d.dormitory_number) AS actual_count,
                        (SELECT COUNT(*) FROM bed b WHERE b.room_id=d.id AND b.state='OCCUPIED') AS occupied_beds,
                        (SELECT COUNT(*) FROM bed b WHERE b.room_id=d.id) AS inventory_capacity,
                        (SELECT COUNT(*) FROM housing_history h WHERE h.room_id=d.id AND h.checkout_at IS NULL) AS open_history,
                        (SELECT COUNT(*) FROM student s WHERE s.dormitory_number=d.dormitory_number AND s.is_dormitory_leader=1) AS leaders,
                        CASE WHEN NOT EXISTS(SELECT 1 FROM room_policy p WHERE p.room_id=d.id) OR EXISTS(SELECT 1 FROM bed b LEFT JOIN student s ON s.id=b.student_id WHERE b.room_id=d.id AND ((b.state='OCCUPIED' AND (s.id IS NULL OR s.dormitory_number IS NULL OR s.dormitory_number<>d.dormitory_number)) OR (b.student_id IS NOT NULL AND b.state<>'OCCUPIED'))) THEN 1 ELSE 0 END AS invalid_beds,
                        'ROOM_COUNTS' AS issue
                      FROM dormitory d
                    ) c WHERE c.current_count<>c.actual_count OR c.actual_count>c.capacity OR c.occupied_beds<>c.actual_count OR c.inventory_capacity<>c.capacity OR c.open_history<>c.actual_count OR c.leaders>1 OR c.invalid_beds>0
                    UNION ALL
                    SELECT s.id*2+1,s.dormitory_number,0,0,1,0,0,0,0,0,'MISSING_ROOM' FROM student s
                      WHERE s.dormitory_number IS NOT NULL AND s.dormitory_number<>'' AND NOT EXISTS(SELECT 1 FROM dormitory d WHERE d.dormitory_number=s.dormitory_number)
                    """;
                columns = "dormitory_number,issue";
            }
            case "students" -> {
                access.roles("ADMIN", "MANAGER", "COUNSELOR", "STUDENT");
                scope = access.studentScope("s.id", args);
                sql = "SELECT s.*,COALESCE(p.lifecycle,'ENROLLED') AS lifecycle,p.college,p.major,p.counselor,p.archived_at,p.archive_reason,CASE WHEN EXISTS(SELECT 1 FROM user u WHERE u.username=s.student_id AND u.role='student') THEN 1 ELSE 0 END AS has_account FROM student s LEFT JOIN student_profile p ON p.student_id=s.id WHERE " + scope;
                columns = "student_id,name,grade,class_name,college,major,counselor,dormitory_number,lifecycle,archive_reason";
            }
            case "rooms" -> {
                access.roles("ADMIN", "MANAGER", "STUDENT");
                scope = access.roomScope("d.id", args);
                sql = "SELECT d.*,COALESCE(p.state,'ACTIVE') AS room_state,COALESCE(p.reason,'') AS state_reason FROM dormitory d LEFT JOIN room_policy p ON p.room_id=d.id WHERE " + scope;
                columns = "dormitory_number,building,room_state,state_reason,capacity,current_count";
                order = "building,dormitory_number,id";
            }
            case "beds" -> {
                access.roles("ADMIN", "MANAGER", "COUNSELOR", "STUDENT");
                scope = access.roomScope("d.id", args);
                if (access.role("STUDENT") || access.role("COUNSELOR")) {
                    String gender = "";
                    if (access.role("STUDENT")) {
                        args.add(access.self().get("gender"));
                        gender = " AND d.gender=?";
                    }
                    scope = "(" + scope + " OR (b.student_id IS NULL AND b.state='AVAILABLE' AND COALESCE(p.state,'ACTIVE')='ACTIVE'" + gender + "))";
                }
                sql = "SELECT b.*,d.dormitory_number,d.building,d.gender,COALESCE(p.state,'ACTIVE') AS room_state,s.name AS student_name,s.student_id AS student_number FROM bed b JOIN dormitory d ON d.id=b.room_id LEFT JOIN student s ON s.id=b.student_id LEFT JOIN room_policy p ON p.room_id=d.id WHERE " + scope;
                columns = "dormitory_number,bed_number,gender,room_state,state,student_name,reservation_until";
                // Redact before searching as well as before returning: search must not reveal a roommate's name.
                if (access.role("STUDENT") || access.role("COUNSELOR")) {
                    Object selfId = access.role("STUDENT") ? access.self().get("id") : -1;
                    sql = sql.replace("SELECT b.*", "SELECT b.id,b.room_id,b.bed_number,b.state,b.reservation_until,b.version,CASE WHEN b.student_id=? THEN b.student_id ELSE NULL END AS student_id")
                            .replace("s.name AS student_name,s.student_id AS student_number", "CASE WHEN b.student_id=? THEN s.name ELSE NULL END AS student_name,CASE WHEN b.student_id=? THEN s.student_id ELSE NULL END AS student_number");
                    args.addAll(0, List.of(selfId, selfId, selfId));
                }
                order = "dormitory_number,bed_number,id";
            }
            case "applications" -> {
                access.roles("ADMIN", "MANAGER", "COUNSELOR", "STUDENT");
                scope = access.studentScope("a.student_id", args);
                sql = "SELECT a.*,s.name,s.student_id AS student_number,d.dormitory_number AS target_room,b.bed_number FROM housing_application a JOIN student s ON s.id=a.student_id LEFT JOIN bed b ON a.target_bed_id=b.id LEFT JOIN dormitory d ON b.room_id=d.id WHERE " + scope;
                columns = "id,name,student_number,kind,target_room,bed_number,reason,state,review_note,created_at";
            }
            case "history" -> {
                access.roles("ADMIN", "MANAGER", "COUNSELOR", "STUDENT");
                scope = access.studentScope("h.student_id", args);
                if (access.role("MANAGER")) scope = "(" + scope + " OR " + access.roomScope("h.room_id", args) + ")";
                sql = "SELECT h.*,s.name FROM housing_history h JOIN student s ON s.id=h.student_id WHERE " + scope;
                columns = "name,student_number,room_number,bed_number,checkin_at,reason,handled_by,checkout_at,checkout_reason,checkout_by";
            }
            case "leaders" -> {
                access.roles("ADMIN", "MANAGER", "STUDENT");
                scope = access.roomScope("l.room_id", args);
                sql = "SELECT l.*,s.name,s.student_id AS student_number,d.dormitory_number FROM leader_record l JOIN student s ON s.id=l.student_id LEFT JOIN dormitory d ON d.id=l.room_id WHERE " + scope;
                columns = "dormitory_number,name,student_number,appointed_at,ended_at,actor,reason";
            }
            case "accounts" -> {
                access.roles("ADMIN");
                sql = "SELECT u.id,u.username,u.real_name,u.role,COALESCE(p.enabled,1) AS enabled,COALESCE(p.force_password_change,0) AS force_password_change FROM user u LEFT JOIN account_policy p ON p.username=u.username";
                columns = "username,real_name,role,enabled,force_password_change";
                order = "id";
            }
            case "audit" -> {
                access.roles("ADMIN");
                sql = "SELECT * FROM audit_log";
                columns = "actor,action,resource_type,resource_id,detail_json,created_at";
            }
            case "announcements" -> {
                args.add(access.actor());
                scope = access.role("ADMIN") ? "1=1" : "EXISTS (SELECT 1 FROM dormitory announcement_d JOIN building announcement_b ON announcement_b.name=announcement_d.building WHERE announcement_b.id=a.building_id AND " + access.roomScope("announcement_d.id", args) + ")";
                sql = "SELECT a.*,CASE WHEN r.username IS NULL THEN 0 ELSE 1 END AS is_read FROM announcement a LEFT JOIN announcement_receipt r ON r.announcement_id=a.id AND r.username=? WHERE a.state='PUBLISHED' AND (a.audience='ALL' OR " + scope + ")";
                columns = "title,content,audience,author,created_at,is_read";
            }
            case "notifications" -> {
                args.add(access.actor());
                sql = "SELECT * FROM notification WHERE recipient=?";
                columns = "title,content,is_read,created_at";
            }
            case "stays" -> {
                access.roles("ADMIN", "MANAGER", "COUNSELOR", "STUDENT");
                scope = access.studentScope("r.student_id", args);
                sql = "SELECT r.*,s.name,s.student_id AS student_number,s.dormitory_number FROM stay_request r JOIN student s ON s.id=r.student_id WHERE " + scope;
                columns = "name,student_number,dormitory_number,kind,start_at,end_at,reason,contact_phone,state,review_note,actual_return_at";
            }
            case "visitors" -> {
                access.roles("ADMIN", "MANAGER", "STUDENT");
                scope = access.role("STUDENT") ? access.studentScope("v.student_id", args) : access.roomScope("v.room_id", args);
                sql = "SELECT v.*,d.dormitory_number,s.name AS host_name FROM visitor v LEFT JOIN dormitory d ON d.id=v.room_id JOIN student s ON s.id=v.student_id WHERE " + scope;
                columns = "visitor_name,phone,host_name,dormitory_number,arrived_at,expected_leave_at,left_at,operator";
            }
            case "inspections" -> {
                access.roles("ADMIN", "MANAGER", "STUDENT");
                scope = access.roomScope("i.room_id", args);
                sql = "SELECT i.*,d.dormitory_number FROM inspection i LEFT JOIN dormitory d ON d.id=i.room_id WHERE " + scope;
                columns = "dormitory_number,kind,score,detail,due_at,state,rectify_note,review_note";
            }
            case "bills" -> {
                access.roles("ADMIN", "MANAGER", "STUDENT");
                scope = access.studentScope("b.student_id", args);
                sql = "SELECT b.*,s.name,s.student_id AS student_number,COALESCE((SELECT SUM(CASE WHEN p.kind='PAYMENT' THEN p.amount_cents ELSE -p.amount_cents END) FROM payment p WHERE p.bill_id=b.id),0) AS net_paid_cents FROM bill b JOIN student s ON s.id=b.student_id WHERE " + scope;
                columns = "id,name,student_number,room_number,kind,period,amount_cents,net_paid_cents,due_at,description,state";
            }
            case "assets" -> {
                access.roles("ADMIN", "MANAGER", "STUDENT");
                scope = access.roomScope("a.room_id", args);
                sql = "SELECT a.*,d.dormitory_number FROM asset a LEFT JOIN dormitory d ON d.id=a.room_id WHERE " + scope;
                columns = "code,name,category,dormitory_number,state,description";
            }
            case "handovers" -> {
                access.roles("ADMIN", "MANAGER", "STUDENT");
                scope = access.role("STUDENT") ? access.studentScope("h.student_id", args) : access.roomScope("a.room_id", args);
                sql = "SELECT h.*,a.name AS asset_name,a.code,a.room_id,s.name,s.student_id AS student_number FROM handover h JOIN asset a ON a.id=h.asset_id JOIN student s ON s.id=h.student_id WHERE " + scope;
                columns = "asset_name,code,name,student_number,issued_at,returned_at,return_condition,return_note";
            }
            case "repairs" -> {
                access.roles("ADMIN", "MANAGER", "WORKER", "STUDENT");
                if (access.role("ADMIN")) scope = "1=1";
                else if (access.role("WORKER")) { scope = "w.assignee=?"; args.add(access.actor()); }
                else if (access.role("MANAGER")) scope = "EXISTS (SELECT 1 FROM dormitory repair_d WHERE repair_d.dormitory_number=r.dormitory_number AND " + access.roomScope("repair_d.id", args) + ")";
                else {
                    scope = "(r.student_id=? OR EXISTS (SELECT 1 FROM student repair_s WHERE repair_s.student_id=? AND repair_s.dormitory_number=r.dormitory_number))";
                    args.add(access.actor()); args.add(access.actor());
                }
                sql = "SELECT r.*,w.assignee,w.priority,pm.priority_source,pm.priority_reason,pm.suggested_priority,w.completion_note,w.review_note,w.rating,CASE WHEN w.assignee IS NULL OR w.assignee='' THEN 'WAITING_ASSIGNMENT' ELSE 'ASSIGNED' END AS dispatch_state FROM repair_request r LEFT JOIN repair_work w ON w.repair_id=r.id LEFT JOIN repair_priority pm ON pm.repair_id=r.id WHERE " + scope;
                columns = "id,dormitory_number,student_name,repair_type,description,status,dispatch_state,assignee,priority,priority_reason,completion_note,review_note";
                order = "CASE WHEN status IN ('待处理','处理中') THEN 0 WHEN status='待验收' THEN 1 ELSE 2 END,CASE WHEN priority='URGENT' THEN 0 ELSE 1 END,create_time,id";
            }
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未知业务列表");
        }
        return new Query(sql, order, columns, args);
    }

    private void decorate(String resource, List<Map<String, Object>> rows) {
        if (!resource.equals("accounts") || rows.isEmpty()) return;
        var byUsername = new HashMap<String, List<Integer>>();
        for (var row : rows) byUsername.put(text(row.get("username")), new ArrayList<>());
        String placeholders = String.join(",", Collections.nCopies(rows.size(), "?"));
        for (var scope : db.jdbc.queryForList("SELECT username,building_id FROM manager_building WHERE username IN (" + placeholders + ") ORDER BY building_id", byUsername.keySet().toArray()))
            byUsername.get(text(scope.get("username"))).add(((Number) scope.get("building_id")).intValue());
        for (var row : rows) row.put("buildingIds", byUsername.get(text(row.get("username"))));
    }
}
