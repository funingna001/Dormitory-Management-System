package com.example.dormitory.service;

import com.example.dormitory.controller.Requests;
import com.example.dormitory.entity.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

@Service
@Transactional
public class HousingService {
    private final JdbcTemplate jdbc;
    private final com.example.dormitory.business.DomainLocks locks;
    private final StudentService students;
    private final DormitoryService dormitories;
    private final BuildingService buildings;
    private final UserService users;
    private final com.example.dormitory.business.HousingLedger ledger;
    public HousingService(JdbcTemplate jdbc, StudentService students, DormitoryService dormitories,
                          BuildingService buildings, UserService users, com.example.dormitory.business.HousingLedger ledger, com.example.dormitory.business.DomainLocks locks) {
        this.jdbc=jdbc; this.students=students; this.dormitories=dormitories; this.buildings=buildings; this.users=users;
        this.ledger=ledger; this.locks=locks;
    }
    public static void require(boolean condition, String message) {
        if (!condition) throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
    public static <T> T found(T value) {
        if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在");
        return value;
    }
    private void lockStudent(Integer id, String roomNumber) {
        locks.structure(false);
        var rooms = roomNumber == null || roomNumber.isBlank() ? List.<Long>of() : jdbc.queryForList("SELECT id FROM dormitory WHERE dormitory_number=?", Long.class, roomNumber);
        locks.housing(id == null ? List.of() : List.of(id.longValue()), rooms, List.of());
    }
    public Student saveStudent(Integer id, Requests.StudentInput input) {
        lockStudent(id, input.dormitoryNumber());
        Student student = id == null ? new Student() : found(students.findById(id));
        if(id!=null)ledger.enrolled(id);
        String oldRoom = student.getDormitoryNumber();
        require(id == null || student.getStudentId().equals(input.studentId()), "学号作为账号标识不可修改");
        require(id != null || users.findByUsername(input.studentId()) == null, "存在未绑定账号，请先由管理员核验身份后处理");
        if (id == null) {
            require(input.initialPassword() != null, "为新学生创建账号需要填写初始密码（至少 8 位）");
            users.createStudentAccount(input.studentId(), input.initialPassword());
        }
        student.setName(input.name()); student.setStudentId(input.studentId()); student.setGrade(input.grade());
        student.setClassName(input.className()); student.setGender(input.gender()); student.setPhone(input.phone());
        String room = input.dormitoryNumber() == null || input.dormitoryNumber().isBlank() ? null : input.dormitoryNumber();
        if(oldRoom!=null&&!oldRoom.isBlank()&&!Objects.equals(oldRoom,room))ledger.checkoutChecks(student.getId());
        validateRoom(student, room);
        if(!Objects.equals(oldRoom,room))student.setDormitoryLeader(false);
        student.setDormitoryNumber(room);
        if (id == null) students.add(student); else students.update(student);
        sync(oldRoom); sync(room);
        return students.findByStudentId(student.getStudentId());
    }
    public Student saveProfile(String username, Requests.Profile input) {
        locks.structure(false);
        var existing = students.findByStudentId(username);
        lockStudent(existing == null ? null : existing.getId(), null);
        Student student = students.findByStudentId(username);
        if (student == null) { student = new Student(); student.setStudentId(username); }
        else ledger.enrolled(student.getId());
        if (student.getDormitoryNumber() != null && !student.getDormitoryNumber().isBlank()) {
            String roomGender = jdbc.queryForObject("SELECT gender FROM dormitory WHERE dormitory_number=?", String.class, student.getDormitoryNumber());
            require(input.gender().equals(roomGender), "个人性别与已分配宿舍不匹配，请联系管理员");
        }
        student.setName(input.name()); student.setGrade(input.grade()); student.setClassName(input.className());
        student.setGender(input.gender()); student.setPhone(input.phone());
        if (student.getId() == 0) students.add(student); else students.update(student);
        users.initializeStudent(username, input.name(), input.phone());
        sync(student.getDormitoryNumber());
        return students.findByStudentId(username);
    }
    private void validateRoom(Student student, String number) {
        if (number == null) return;
        ledger.roomUsable(number);
        if(student.getId()>0)ledger.enrolled(student.getId());
        var rooms = jdbc.queryForList("SELECT capacity, gender FROM dormitory WHERE dormitory_number=?", number);
        require(!rooms.isEmpty(), "宿舍不存在");
        var room = rooms.get(0);
        require(Objects.equals(room.get("gender"), student.getGender()), "学生性别与宿舍限制不匹配");
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM student WHERE dormitory_number=? AND id<>?", Integer.class, number, student.getId());
        require(count < ((Number)room.get("capacity")).intValue(), "宿舍已满");
    }
    public void assign(int dormitoryId, int studentId) {
        locks.housing(List.of((long)studentId), List.of((long)dormitoryId), List.of());
        Dormitory room = found(dormitories.findById(dormitoryId));
        Student student = found(students.findById(studentId));
        require(student.getDormitoryNumber() == null || student.getDormitoryNumber().isBlank(), "学生已入住，请先移出原宿舍");
        validateRoom(student, room.getDormitoryNumber());
        student.setDormitoryNumber(room.getDormitoryNumber()); students.update(student); sync(room.getDormitoryNumber());
    }
    public void remove(int dormitoryId, int studentId) {
        locks.housing(List.of((long)studentId), List.of((long)dormitoryId), List.of());
        Dormitory room = found(dormitories.findById(dormitoryId));
        Student student = found(students.findById(studentId));
        require(Objects.equals(room.getDormitoryNumber(), student.getDormitoryNumber()), "学生不属于该宿舍");
        ledger.checkoutChecks(studentId);
        student.setDormitoryNumber(null); student.setDormitoryLeader(false); students.update(student); sync(room.getDormitoryNumber());
    }
    public void deleteStudents(List<Integer> ids) {
        locks.housing(ids.stream().map(Integer::longValue).toList(), List.of(), List.of());
        for (int id : ids) {
            Student student = found(students.findById(id));
            require(student.getDormitoryNumber()==null||student.getDormitoryNumber().isBlank(),"归档前请先办理退宿，历史档案不会删除");
            ledger.checkoutChecks(id);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM student_profile WHERE student_id=?",Integer.class,id)==0)
                jdbc.update("INSERT INTO student_profile(student_id) VALUES(?)",id);
            jdbc.update("UPDATE student_profile SET lifecycle='GRADUATED',archived_at=?,archive_reason='管理员归档' WHERE student_id=?",java.time.LocalDateTime.now().toString(),id);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM account_policy WHERE username=?",Integer.class,student.getStudentId())==0)
                jdbc.update("INSERT INTO account_policy(username) VALUES(?)",student.getStudentId());
            jdbc.update("UPDATE account_policy SET enabled=0,credential_version=credential_version+1 WHERE username=?",student.getStudentId());
            sync(student.getDormitoryNumber());
        }
    }
    private void sync(String number) {
        if (number == null || number.isBlank()) return;
        dormitories.updateCurrentCount(number);
        ledger.reconcile(number);
    }
    public Dormitory saveDormitory(Integer id, Requests.DormitoryInput input) {
        locks.structure(true);
        var building = jdbc.queryForList("SELECT gender FROM building WHERE name=?", input.building());
        require(!building.isEmpty(), "楼栋不存在");
        require(input.gender().equals(building.get(0).get("gender")), "宿舍性别必须与楼栋一致");
        require(input.dormitoryNumber().matches(java.util.regex.Pattern.quote(input.building()) + "-(0[1-9]|[12][0-9]|30)[0-9]{2}"), "宿舍号格式应为 楼栋-楼层房号，例如 1号楼-0101");
        Dormitory room = id == null ? new Dormitory() : found(dormitories.findById(id));
        String oldNumber = room.getDormitoryNumber();
        int capacity = Map.of("二人间",2,"四人间",4,"六人间",6).get(input.roomType());
        int count = oldNumber == null ? 0 : jdbc.queryForObject("SELECT COUNT(*) FROM student WHERE dormitory_number=?", Integer.class, oldNumber);
        require(count <= capacity, "新容量不能小于现有入住人数");
        if (count > 0) require(room.getGender().equals(input.gender()), "已入住宿舍不可变更性别");
        room.setDormitoryNumber(input.dormitoryNumber()); room.setBuilding(input.building()); room.setRoomType(input.roomType());
        room.setCapacity(capacity); room.setCurrentCount(count); room.setGender(input.gender()); room.setRemark(input.remark());
        if (id == null) dormitories.add(room); else {
            dormitories.update(room);
            jdbc.update("UPDATE student SET dormitory_number=? WHERE dormitory_number=?", room.getDormitoryNumber(), oldNumber);
            jdbc.update("UPDATE repair_request SET dormitory_number=? WHERE dormitory_number=?", room.getDormitoryNumber(), oldNumber);
            jdbc.update("UPDATE housing_history SET room_number=? WHERE room_id=? AND checkout_at IS NULL",room.getDormitoryNumber(),id);
        }
        sync(room.getDormitoryNumber());
        return dormitories.findAll().stream().filter(d -> d.getDormitoryNumber().equals(input.dormitoryNumber())).findFirst().orElseThrow();
    }
    public void deleteDormitories(List<Integer> ids) {
        locks.structure(true);
        for (int id : ids) {
            var room = found(dormitories.findById(id));
            require(jdbc.queryForObject("SELECT COUNT(*) FROM student WHERE dormitory_number=?", Integer.class, room.getDormitoryNumber()) == 0,
                    "宿舍仍有学生入住，请先移出学生");
            require(jdbc.queryForObject("SELECT COUNT(*) FROM bed WHERE room_id=? AND state='RESERVED'",Integer.class,id)==0,"宿舍存在预留床位，请先取消预留");
            require(jdbc.queryForObject("SELECT COUNT(*) FROM asset WHERE room_id=? AND state<>'RETIRED'",Integer.class,id)==0,"宿舍仍有资产，请先办理资产处置");
            jdbc.update("DELETE FROM bed WHERE room_id=?",id);jdbc.update("DELETE FROM room_policy WHERE room_id=?",id);
            dormitories.deleteById(id);
        }
    }
    public Building saveBuilding(Integer id, Requests.BuildingInput input) {
        locks.structure(true);
        Building building = id == null ? new Building() : found(buildings.findById(id));
        String oldName = building.getName();
        if (id != null) {
            int count = jdbc.queryForObject("SELECT COUNT(*) FROM student s JOIN dormitory d ON s.dormitory_number=d.dormitory_number WHERE d.building=?", Integer.class, oldName);
            require(count == 0 || building.getGender().equals(input.gender()), "楼栋仍有学生入住，不可修改性别");
        }
        var oldRooms = id == null ? List.<Dormitory>of() : dormitories.findAll().stream().filter(d -> oldName.equals(d.getBuilding())).toList();
        Map<String, Dormitory> bySuffix = new HashMap<>();
        for (var room : oldRooms) bySuffix.put(room.getDormitoryNumber().substring(oldName.length()), room);
        Set<Integer> kept = new HashSet<>();
        building.setName(input.name()); building.setGender(input.gender());
        if (id == null) buildings.addBuilding(building); else buildings.updateBuilding(building);
        for (int f=0; f<input.floors().size(); f++) {
            Requests.Floor config = input.floors().get(f);
            int total = config.doubleRooms()+config.fourRooms()+config.sixRooms();
            require(total >= 1 && total <= 99, "每层房间数必须为 1–99");
            List<Integer> capacities = new ArrayList<>();
            for (int i=0; i<config.doubleRooms(); i++) capacities.add(2);
            int[] order = input.sortMode().equals("desc") ? new int[]{6,4} : new int[]{4,6};
            for (int cap : order) for (int i=0; i<(cap==4 ? config.fourRooms() : config.sixRooms()); i++) capacities.add(cap);
            for (int r=0; r<capacities.size(); r++) {
                String suffix = "-" + String.format("%02d%02d", f+1, r+1);
                Dormitory existing = bySuffix.get(suffix);
                int cap = capacities.get(r);
                saveDormitory(existing == null ? null : existing.getId(), new Requests.DormitoryInput(input.name()+suffix,
                        input.name(), Map.of(2,"二人间",4,"四人间",6,"六人间").get(cap), input.gender(), existing == null ? "" : existing.getRemark()));
                if (existing != null) kept.add(existing.getId());
            }
        }
        for (var room : oldRooms) if (!kept.contains(room.getId())) deleteDormitories(List.of(room.getId()));
        return buildings.findAll().stream().filter(b -> b.getName().equals(input.name())).findFirst().orElseThrow();
    }
    public void deleteBuilding(int id) {
        locks.structure(true);
        var building = found(buildings.findById(id));
        var ids = dormitories.findAll().stream().filter(d -> building.getName().equals(d.getBuilding())).map(Dormitory::getId).toList();
        deleteDormitories(ids);
        jdbc.update("DELETE FROM building WHERE id=?", id);
    }
}
