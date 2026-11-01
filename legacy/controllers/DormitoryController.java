package com.example.dormitory.controller;

import com.example.dormitory.entity.Building;
import com.example.dormitory.entity.Dormitory;
import com.example.dormitory.entity.Student;
import com.example.dormitory.entity.User;
import com.example.dormitory.service.BuildingService;
import com.example.dormitory.service.DormitoryService;
import com.example.dormitory.service.StudentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/dormitories")
public class DormitoryController {

    @Autowired
    private DormitoryService dormitoryService;

    @Autowired
    private BuildingService buildingService;

    private final List<String> ROOM_TYPES = Arrays.asList("四人间", "六人间");
    private final List<String> GENDERS = Arrays.asList("男", "女");
    private static final int PAGE_SIZE = 30;

    @GetMapping
    public String list(@RequestParam(required = false) String dormitoryNumber,
                       @RequestParam(required = false, defaultValue = "all") String building,
                       @RequestParam(required = false, defaultValue = "all") String roomType,
                       @RequestParam(required = false, defaultValue = "all") String gender,
                       @RequestParam(required = false, defaultValue = "all") String occupancy,
                       @RequestParam(required = false, defaultValue = "all") String floor,
                       @RequestParam(defaultValue = "dormitoryNumber") String sortBy,
                       @RequestParam(defaultValue = "asc") String sortOrder,
                       @RequestParam(defaultValue = "1") int page,
                       HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        model.addAttribute("user", user);

        int total = dormitoryService.count(dormitoryNumber, building, roomType, gender, occupancy, floor);
        int totalPages = (int) Math.ceil((double) total / PAGE_SIZE);
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;
        int offset = (page - 1) * PAGE_SIZE;

        List<Dormitory> dormitories = dormitoryService.searchWithPage(dormitoryNumber, building, roomType, gender, occupancy, floor, sortBy, sortOrder, offset, PAGE_SIZE);
        List<Building> buildingList = buildingService.findAll();
        List<String> buildings = buildingList.stream().map(Building::getName).collect(Collectors.toList());
        Map<String, Integer> buildingStats = dormitoryService.getBuildingStatistics();
        Map<String, Integer> roomTypeStats = dormitoryService.getRoomTypeStatistics();
        Map<String, Integer> genderStats = dormitoryService.getGenderStatistics();

        model.addAttribute("dormitories", dormitories);
        model.addAttribute("searchDormitoryNumber", dormitoryNumber != null ? dormitoryNumber : "");
        model.addAttribute("searchBuilding", building);
        model.addAttribute("searchRoomType", roomType);
        model.addAttribute("searchGender", gender);
        model.addAttribute("searchOccupancy", occupancy != null ? occupancy : "all");
        model.addAttribute("searchFloor", floor != null ? floor : "all");
        model.addAttribute("sortBy", sortBy);
        model.addAttribute("sortOrder", sortOrder);
        model.addAttribute("buildings", buildings);
        model.addAttribute("roomTypes", ROOM_TYPES);
        model.addAttribute("genders", GENDERS);
        int maxFloor = dormitoryService.getMaxFloor();
        List<String> floors = new java.util.ArrayList<>();
        for (int i = 1; i <= maxFloor; i++) {
            floors.add(String.valueOf(i));
        }
        model.addAttribute("floors", floors);
        model.addAttribute("buildingStats", buildingStats != null ? buildingStats : new java.util.LinkedHashMap<>());
        model.addAttribute("roomTypeStats", roomTypeStats != null ? roomTypeStats : new java.util.LinkedHashMap<>());
        model.addAttribute("genderStats", genderStats != null ? genderStats : new java.util.LinkedHashMap<>());
        model.addAttribute("page", page);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("total", total);

        java.util.List<Integer> pageList = new java.util.ArrayList<>();
        int displayCount = 10;
        int startPage = Math.max(1, page - displayCount / 2);
        int endPage = Math.min(totalPages, startPage + displayCount - 1);
        if (endPage - startPage < displayCount - 1) {
            startPage = Math.max(1, endPage - displayCount + 1);
        }
        for (int i = startPage; i <= endPage; i++) {
            pageList.add(i);
        }
        model.addAttribute("pageList", pageList);
        model.addAttribute("showFirst", startPage > 1);
        model.addAttribute("showLast", endPage < totalPages);

        return "dormitory-list";
    }

    @GetMapping("/detail/{id}")
    public String detail(@PathVariable int id, HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }

        Dormitory dormitory = dormitoryService.findById(id);
        if (dormitory == null) {
            model.addAttribute("error", "宿舍不存在");
            return "dormitory-list";
        }

        List<Student> students = dormitoryService.getStudentsByDormitoryNumber(dormitory.getDormitoryNumber());

        model.addAttribute("user", user);
        model.addAttribute("dormitory", dormitory);
        model.addAttribute("students", students);
        return "dormitory-detail";
    }

    @GetMapping("/add")
    public String addForm(HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        if (!"admin".equals(user.getRole())) {
            model.addAttribute("error", "没有权限添加宿舍信息");
            return "dormitory-list";
        }

        model.addAttribute("user", user);
        model.addAttribute("dormitory", new Dormitory());
        model.addAttribute("roomTypes", ROOM_TYPES);
        model.addAttribute("genders", GENDERS);
        model.addAttribute("buildings", buildingService.findAll());
        model.addAttribute("action", "/dormitories/add");
        return "dormitory-form";
    }

    @PostMapping("/add")
    public String add(@RequestParam String dormitoryNumber,
                      @RequestParam String building,
                      @RequestParam String roomType,
                      @RequestParam Integer capacity,
                      @RequestParam String gender,
                      @RequestParam(required = false) String remark,
                      HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        if (!"admin".equals(user.getRole())) {
            model.addAttribute("error", "没有权限添加宿舍信息");
            return "dormitory-list";
        }

        String validateResult = validateDormitory(dormitoryNumber, building, roomType, capacity, gender);
        if (validateResult != null) {
            model.addAttribute("user", user);
            Dormitory dormitory = new Dormitory(dormitoryNumber, building, roomType, capacity, 0, gender, remark);
            model.addAttribute("dormitory", dormitory);
            model.addAttribute("roomTypes", ROOM_TYPES);
            model.addAttribute("genders", GENDERS);
            model.addAttribute("buildings", buildingService.findAll());
            model.addAttribute("action", "/dormitories/add");
            model.addAttribute("error", validateResult);
            return "dormitory-form";
        }

        String fullDormitoryNumber = building + "-" + dormitoryNumber;
        if (dormitoryService.existsByDormitoryNumber(fullDormitoryNumber)) {
            model.addAttribute("user", user);
            Dormitory dormitory = new Dormitory(fullDormitoryNumber, building, roomType, capacity, 0, gender, remark);
            model.addAttribute("dormitory", dormitory);
            model.addAttribute("roomTypes", ROOM_TYPES);
            model.addAttribute("genders", GENDERS);
            model.addAttribute("action", "/dormitories/add");
            model.addAttribute("error", "宿舍号已存在");
            return "dormitory-form";
        }

        Dormitory dormitory = new Dormitory(fullDormitoryNumber, building, roomType, capacity, 0, gender, remark);
        int result = dormitoryService.add(dormitory);
        if (result > 0) {
            return "redirect:/dormitories?success=add";
        } else {
            model.addAttribute("user", user);
            model.addAttribute("dormitory", dormitory);
            model.addAttribute("roomTypes", ROOM_TYPES);
            model.addAttribute("genders", GENDERS);
            model.addAttribute("action", "/dormitories/add");
            model.addAttribute("error", "添加失败，请重试");
            return "dormitory-form";
        }
    }

    @GetMapping("/edit/{id}")
    public String editForm(@PathVariable int id, HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        if (!"admin".equals(user.getRole())) {
            model.addAttribute("error", "没有权限编辑宿舍信息");
            return "dormitory-list";
        }

        Dormitory dormitory = dormitoryService.findById(id);
        if (dormitory == null) {
            model.addAttribute("error", "宿舍不存在");
            return "dormitory-list";
        }

        String shortDormitoryNumber = dormitory.getDormitoryNumber();
        if (shortDormitoryNumber != null && shortDormitoryNumber.contains("-")) {
            shortDormitoryNumber = shortDormitoryNumber.substring(shortDormitoryNumber.indexOf("-") + 1);
        }
        dormitory.setDormitoryNumber(shortDormitoryNumber);

        model.addAttribute("user", user);
        model.addAttribute("dormitory", dormitory);
        model.addAttribute("roomTypes", ROOM_TYPES);
        model.addAttribute("genders", GENDERS);
        model.addAttribute("buildings", buildingService.findAll());
        model.addAttribute("action", "/dormitories/edit/" + id);
        return "dormitory-form";
    }

    @GetMapping("/{id}/students")
    public String studentsForm(@PathVariable int id, HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        if (!"admin".equals(user.getRole())) {
            model.addAttribute("error", "没有权限管理入住学生");
            return "dormitory-list";
        }

        Dormitory dormitory = dormitoryService.findById(id);
        if (dormitory == null) {
            model.addAttribute("error", "宿舍不存在");
            return "dormitory-list";
        }

        List<Student> students = dormitoryService.getStudentsByDormitoryNumber(dormitory.getDormitoryNumber());

        model.addAttribute("user", user);
        model.addAttribute("dormitory", dormitory);
        model.addAttribute("students", students);
        return "dormitory-students";
    }

    @PostMapping("/edit/{id}")
    public String edit(@PathVariable int id,
                       @RequestParam String dormitoryNumber,
                       @RequestParam String building,
                       @RequestParam String roomType,
                       @RequestParam Integer capacity,
                       @RequestParam Integer currentCount,
                       @RequestParam String gender,
                       @RequestParam(required = false) String remark,
                       HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        if (!"admin".equals(user.getRole())) {
            model.addAttribute("error", "没有权限编辑宿舍信息");
            return "dormitory-list";
        }

        String validateResult = validateDormitory(dormitoryNumber, building, roomType, capacity, gender);
        if (validateResult != null) {
            model.addAttribute("user", user);
            Dormitory dormitory = new Dormitory(dormitoryNumber, building, roomType, capacity, currentCount, gender, remark);
            dormitory.setId(id);
            model.addAttribute("dormitory", dormitory);
            model.addAttribute("roomTypes", ROOM_TYPES);
            model.addAttribute("genders", GENDERS);
            model.addAttribute("action", "/dormitories/edit/" + id);
            model.addAttribute("error", validateResult);
            return "dormitory-form";
        }

        String fullDormitoryNumber = building + "-" + dormitoryNumber;
        if (dormitoryService.existsByDormitoryNumberExcludeId(fullDormitoryNumber, id)) {
            model.addAttribute("user", user);
            Dormitory dormitory = new Dormitory(fullDormitoryNumber, building, roomType, capacity, currentCount, gender, remark);
            dormitory.setId(id);
            model.addAttribute("dormitory", dormitory);
            model.addAttribute("roomTypes", ROOM_TYPES);
            model.addAttribute("genders", GENDERS);
            model.addAttribute("action", "/dormitories/edit/" + id);
            model.addAttribute("error", "宿舍号已存在");
            return "dormitory-form";
        }

        Dormitory dormitory = new Dormitory(fullDormitoryNumber, building, roomType, capacity, currentCount, gender, remark);
        dormitory.setId(id);
        int result = dormitoryService.update(dormitory);
        if (result > 0) {
            return "redirect:/dormitories?success=edit";
        } else {
            model.addAttribute("user", user);
            model.addAttribute("dormitory", dormitory);
            model.addAttribute("roomTypes", ROOM_TYPES);
            model.addAttribute("genders", GENDERS);
            model.addAttribute("action", "/dormitories/edit/" + id);
            model.addAttribute("error", "更新失败，请重试");
            return "dormitory-form";
        }
    }

    @GetMapping("/delete/{id}")
    public String delete(@PathVariable int id, HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        if (!"admin".equals(user.getRole())) {
            model.addAttribute("error", "没有权限删除宿舍信息");
            return "dormitory-list";
        }

        Dormitory dormitory = dormitoryService.findById(id);
        if (dormitory == null) {
            model.addAttribute("error", "宿舍不存在");
            return "dormitory-list";
        }

        int result = dormitoryService.deleteById(id);
        if (result > 0) {
            return "redirect:/dormitories?success=delete";
        } else {
            model.addAttribute("error", "删除失败，请重试");
            return "dormitory-list";
        }
    }

    @PostMapping("/batch-delete")
    public String batchDelete(@RequestParam String ids, HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        if (!"admin".equals(user.getRole())) {
            model.addAttribute("error", "没有权限批量删除宿舍信息");
            return "dormitory-list";
        }

        if (ids == null || ids.trim().isEmpty()) {
            model.addAttribute("error", "请选择要删除的宿舍");
            return "dormitory-list";
        }

        List<Integer> idList = Arrays.stream(ids.split(","))
                .map(Integer::parseInt)
                .collect(Collectors.toList());

        int result = dormitoryService.batchDelete(idList);
        if (result > 0) {
            return "redirect:/dormitories?success=batchDelete";
        } else {
            model.addAttribute("error", "批量删除失败，请重试");
            return "dormitory-list";
        }
    }

    @Autowired
    private StudentService studentService;

    @PostMapping("/{dormitoryId}/students/add")
    @ResponseBody
    public java.util.Map<String, Object> addStudentToDormitory(@PathVariable int dormitoryId,
                                                                @RequestParam String name,
                                                                @RequestParam String studentId,
                                                                @RequestParam String grade,
                                                                @RequestParam(required = false) String className,
                                                                @RequestParam(required = false) String gender,
                                                                @RequestParam String phone,
                                                                HttpSession session) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        User user = (User) session.getAttribute("user");
        if (user == null) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }

        Dormitory dormitory = dormitoryService.findById(dormitoryId);
        if (dormitory == null) {
            result.put("success", false);
            result.put("message", "宿舍不存在");
            return result;
        }

        List<Student> currentStudents = dormitoryService.getStudentsByDormitoryNumber(dormitory.getDormitoryNumber());
        if (currentStudents.size() >= dormitory.getCapacity()) {
            result.put("success", false);
            result.put("message", "宿舍已满，无法添加更多学生");
            return result;
        }

        if (dormitory.getGender() != null && !dormitory.getGender().isEmpty()) {
            if (gender == null || gender.isEmpty()) {
                result.put("success", false);
                result.put("message", "该宿舍仅限" + dormitory.getGender() + "生入住，请选择性别");
                return result;
            }
            if (!dormitory.getGender().equals(gender)) {
                result.put("success", false);
                result.put("message", "该宿舍仅限" + dormitory.getGender() + "生入住，性别不匹配");
                return result;
            }
        }

        if (studentService.existsByStudentId(studentId)) {
            Student existingStudent = studentService.findByStudentId(studentId);
            if (existingStudent.getDormitoryNumber() != null && !existingStudent.getDormitoryNumber().isEmpty()) {
                result.put("success", false);
                result.put("message", "该学号已入住其他宿舍");
                return result;
            }
            existingStudent.setDormitoryNumber(dormitory.getDormitoryNumber());
            studentService.update(existingStudent);
        } else {
            Student student = new Student(name, studentId, grade, className, gender, phone, dormitory.getDormitoryNumber());
            studentService.add(student);
        }

        dormitoryService.updateCurrentCount(dormitory.getDormitoryNumber());

        updateDormitoryLeader(dormitory.getDormitoryNumber());

        result.put("success", true);
        result.put("message", "学生添加成功");
        return result;
    }

    private void updateDormitoryLeader(String dormitoryNumber) {
        List<Student> students = dormitoryService.getStudentsByDormitoryNumber(dormitoryNumber);
        
        for (Student student : students) {
            student.setDormitoryLeader(false);
            studentService.update(student);
        }
        
        if (students.isEmpty()) {
            return;
        }
        
        Student leader = students.get(0);
        for (Student student : students) {
            if (student.getStudentId().compareTo(leader.getStudentId()) > 0) {
                leader = student;
            }
        }
        
        leader.setDormitoryLeader(true);
        studentService.update(leader);
    }

    @PostMapping("/{dormitoryId}/students/edit")
    @ResponseBody
    public java.util.Map<String, Object> editStudentInDormitory(@PathVariable int dormitoryId,
                                                                @RequestParam int studentId,
                                                                @RequestParam String name,
                                                                @RequestParam String studentNo,
                                                                @RequestParam String grade,
                                                                @RequestParam(required = false) String className,
                                                                @RequestParam(required = false) String gender,
                                                                @RequestParam String phone,
                                                                HttpSession session) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        User user = (User) session.getAttribute("user");
        if (user == null) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }

        Student student = studentService.findById(studentId);
        if (student == null) {
            result.put("success", false);
            result.put("message", "学生不存在");
            return result;
        }

        student.setName(name);
        student.setStudentId(studentNo);
        student.setGrade(grade);
        student.setClassName(className);
        student.setGender(gender);
        student.setPhone(phone);

        studentService.update(student);

        result.put("success", true);
        result.put("message", "学生信息更新成功");
        return result;
    }

    @PostMapping("/{dormitoryId}/students/remove/{studentId}")
    @ResponseBody
    public java.util.Map<String, Object> removeStudentFromDormitory(@PathVariable int dormitoryId,
                                                                    @PathVariable int studentId,
                                                                    HttpSession session) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        User user = (User) session.getAttribute("user");
        if (user == null) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }

        Dormitory dormitory = dormitoryService.findById(dormitoryId);
        if (dormitory == null) {
            result.put("success", false);
            result.put("message", "宿舍不存在");
            return result;
        }

        Student student = studentService.findById(studentId);
        if (student == null) {
            result.put("success", false);
            result.put("message", "学生不存在");
            return result;
        }

        student.setDormitoryNumber(null);
        student.setDormitoryLeader(false);
        studentService.update(student);

        dormitoryService.updateCurrentCount(dormitory.getDormitoryNumber());

        updateDormitoryLeader(dormitory.getDormitoryNumber());

        result.put("success", true);
        result.put("message", "学生已移出宿舍");
        return result;
    }

    @GetMapping("/floors")
    @ResponseBody
    public java.util.List<Integer> getFloorsByBuilding(@RequestParam String building) {
        if ("all".equals(building)) {
            return dormitoryService.getAllFloors();
        }
        return dormitoryService.getFloorsByBuilding(building);
    }

    private String validateDormitory(String dormitoryNumber, String building, String roomType, Integer capacity, String gender) {
        if (dormitoryNumber == null || dormitoryNumber.trim().isEmpty()) {
            return "宿舍号不能为空";
        }

        if (building == null || building.trim().isEmpty()) {
            return "楼栋不能为空";
        }

        if (roomType == null || roomType.trim().isEmpty()) {
            return "房间类型不能为空";
        }
        if (!ROOM_TYPES.contains(roomType)) {
            return "房间类型选择不正确";
        }

        if (capacity == null || capacity <= 0) {
            return "容量必须大于0";
        }

        if (gender == null || gender.trim().isEmpty()) {
            return "性别限制不能为空";
        }
        if (!GENDERS.contains(gender)) {
            return "性别选择不正确";
        }

        return null;
    }
}