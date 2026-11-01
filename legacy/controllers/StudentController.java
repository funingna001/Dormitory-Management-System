package com.example.dormitory.controller;

import com.example.dormitory.entity.Student;
import com.example.dormitory.entity.User;
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
@RequestMapping("/students")
public class StudentController {

    @Autowired
    private StudentService studentService;

    private final List<String> GRADES = Arrays.asList("大一", "大二", "大三", "大四");
    private final List<String> GENDERS = Arrays.asList("男", "女");
    private static final int PAGE_SIZE = 5;

    @GetMapping
    public String list(@RequestParam(required = false) String studentId,
                       @RequestParam(required = false) String name,
                       @RequestParam(required = false, defaultValue = "all") String grade,
                       @RequestParam(required = false, defaultValue = "all") String className,
                       @RequestParam(required = false, defaultValue = "all") String gender,
                       @RequestParam(required = false) String dormitoryNumber,
                       @RequestParam(defaultValue = "id") String sortBy,
                       @RequestParam(defaultValue = "desc") String sortOrder,
                       @RequestParam(defaultValue = "1") int page,
                       HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        model.addAttribute("user", user);

        int total = studentService.count(studentId, name, grade, className, gender, dormitoryNumber);
        int totalPages = (int) Math.ceil((double) total / PAGE_SIZE);
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;
        int offset = (page - 1) * PAGE_SIZE;

        List<Student> students = studentService.searchWithPage(studentId, name, grade, className, gender, dormitoryNumber, sortBy, sortOrder, offset, PAGE_SIZE);
        Map<String, Integer> gradeStats = studentService.getGradeStatistics();
        List<String> classes = studentService.getAllClasses();

        model.addAttribute("students", students);
        model.addAttribute("searchStudentId", studentId != null ? studentId : "");
        model.addAttribute("searchName", name != null ? name : "");
        model.addAttribute("searchGrade", grade);
        model.addAttribute("searchClassName", className);
        model.addAttribute("searchGender", gender);
        model.addAttribute("searchDormitoryNumber", dormitoryNumber != null ? dormitoryNumber : "");
        model.addAttribute("sortBy", sortBy);
        model.addAttribute("sortOrder", sortOrder);
        model.addAttribute("grades", GRADES);
        model.addAttribute("genders", GENDERS);
        model.addAttribute("classes", classes);
        model.addAttribute("gradeStats", gradeStats != null ? gradeStats : new java.util.LinkedHashMap<>());
        model.addAttribute("page", page);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("total", total);
        
        java.util.List<Integer> pageList = new java.util.ArrayList<>();
        for (int i = 1; i <= totalPages; i++) {
            pageList.add(i);
        }
        model.addAttribute("pageList", pageList);

        return "student-list";
    }

    @GetMapping("/detail/{id}")
    public String detail(@PathVariable int id, HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }

        Student student = studentService.findById(id);
        if (student == null) {
            model.addAttribute("error", "学生不存在");
            return "student-list";
        }

        model.addAttribute("user", user);
        model.addAttribute("student", student);
        return "student-detail";
    }

    @GetMapping("/api/students/{id}")
    @ResponseBody
    public java.util.Map<String, Object> getStudentById(@PathVariable int id) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        Student student = studentService.findById(id);
        if (student == null) {
            result.put("success", false);
            result.put("message", "学生不存在");
        } else {
            result.put("success", true);
            result.put("student", student);
        }
        return result;
    }

    @GetMapping("/add")
    public String addForm(HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        if (!"admin".equals(user.getRole())) {
            model.addAttribute("error", "没有权限添加学生信息");
            return "student-list";
        }

        model.addAttribute("user", user);
        model.addAttribute("student", new Student());
        model.addAttribute("grades", GRADES);
        model.addAttribute("genders", GENDERS);
        model.addAttribute("action", "/students/add");
        return "student-form";
    }

    @PostMapping("/add")
    public String add(@RequestParam String name,
                      @RequestParam String studentId,
                      @RequestParam String grade,
                      @RequestParam(required = false) String className,
                      @RequestParam(required = false) String gender,
                      @RequestParam String phone,
                      @RequestParam(required = false) String dormitoryNumber,
                      HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        if (!"admin".equals(user.getRole())) {
            model.addAttribute("error", "没有权限添加学生信息");
            return "student-list";
        }

        String validateResult = validateStudent(name, studentId, grade, phone);
        if (validateResult != null) {
            model.addAttribute("user", user);
            Student student = new Student(name, studentId, grade, className, gender, phone, dormitoryNumber);
            model.addAttribute("student", student);
            model.addAttribute("grades", GRADES);
            model.addAttribute("genders", GENDERS);
            model.addAttribute("action", "/students/add");
            model.addAttribute("error", validateResult);
            return "student-form";
        }

        if (studentService.existsByStudentId(studentId)) {
            model.addAttribute("user", user);
            Student student = new Student(name, studentId, grade, className, gender, phone, dormitoryNumber);
            model.addAttribute("student", student);
            model.addAttribute("grades", GRADES);
            model.addAttribute("genders", GENDERS);
            model.addAttribute("action", "/students/add");
            model.addAttribute("error", "学号已存在");
            return "student-form";
        }

        Student student = new Student(name, studentId, grade, className, gender, phone, dormitoryNumber);
        int result = studentService.add(student);
        if (result > 0) {
            return "redirect:/students?success=add";
        } else {
            model.addAttribute("user", user);
            model.addAttribute("student", student);
            model.addAttribute("grades", GRADES);
            model.addAttribute("genders", GENDERS);
            model.addAttribute("action", "/students/add");
            model.addAttribute("error", "添加失败，请重试");
            return "student-form";
        }
    }

    @GetMapping("/edit/{id}")
    public String editForm(@PathVariable int id, HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        if (!"admin".equals(user.getRole())) {
            model.addAttribute("error", "没有权限编辑学生信息");
            return "student-list";
        }

        Student student = studentService.findById(id);
        if (student == null) {
            model.addAttribute("error", "学生不存在");
            return "student-list";
        }

        model.addAttribute("user", user);
        model.addAttribute("student", student);
        model.addAttribute("grades", GRADES);
        model.addAttribute("genders", GENDERS);
        model.addAttribute("action", "/students/edit/" + id);
        return "student-form";
    }

    @PostMapping("/edit/{id}")
    public String edit(@PathVariable int id,
                       @RequestParam String name,
                       @RequestParam String studentId,
                       @RequestParam String grade,
                       @RequestParam(required = false) String className,
                       @RequestParam(required = false) String gender,
                       @RequestParam String phone,
                       @RequestParam(required = false) String dormitoryNumber,
                       HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        if (!"admin".equals(user.getRole())) {
            model.addAttribute("error", "没有权限编辑学生信息");
            return "student-list";
        }

        String validateResult = validateStudent(name, studentId, grade, phone);
        if (validateResult != null) {
            model.addAttribute("user", user);
            Student student = new Student(name, studentId, grade, className, gender, phone, dormitoryNumber);
            student.setId(id);
            model.addAttribute("student", student);
            model.addAttribute("grades", GRADES);
            model.addAttribute("genders", GENDERS);
            model.addAttribute("action", "/students/edit/" + id);
            model.addAttribute("error", validateResult);
            return "student-form";
        }

        if (studentService.existsByStudentIdExcludeId(studentId, id)) {
            model.addAttribute("user", user);
            Student student = new Student(name, studentId, grade, className, gender, phone, dormitoryNumber);
            student.setId(id);
            model.addAttribute("student", student);
            model.addAttribute("grades", GRADES);
            model.addAttribute("genders", GENDERS);
            model.addAttribute("action", "/students/edit/" + id);
            model.addAttribute("error", "学号已存在");
            return "student-form";
        }

        Student student = new Student(name, studentId, grade, className, gender, phone, dormitoryNumber);
        student.setId(id);
        int result = studentService.update(student);
        if (result > 0) {
            return "redirect:/students?success=edit";
        } else {
            model.addAttribute("user", user);
            model.addAttribute("student", student);
            model.addAttribute("grades", GRADES);
            model.addAttribute("genders", GENDERS);
            model.addAttribute("action", "/students/edit/" + id);
            model.addAttribute("error", "更新失败，请重试");
            return "student-form";
        }
    }

    @GetMapping("/delete/{id}")
    public String delete(@PathVariable int id, HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        if (!"admin".equals(user.getRole())) {
            model.addAttribute("error", "没有权限删除学生信息");
            return "student-list";
        }

        Student student = studentService.findById(id);
        if (student == null) {
            model.addAttribute("error", "学生不存在");
            return "student-list";
        }

        int result = studentService.deleteById(id);
        if (result > 0) {
            return "redirect:/students?success=delete";
        } else {
            model.addAttribute("error", "删除失败，请重试");
            return "student-list";
        }
    }

    @PostMapping("/batch-delete")
    public String batchDelete(@RequestParam String ids, HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        if (!"admin".equals(user.getRole())) {
            model.addAttribute("error", "没有权限批量删除学生信息");
            return "student-list";
        }

        if (ids == null || ids.trim().isEmpty()) {
            model.addAttribute("error", "请选择要删除的学生");
            return "student-list";
        }

        List<Integer> idList = Arrays.stream(ids.split(","))
                .map(Integer::parseInt)
                .collect(Collectors.toList());

        int result = studentService.batchDelete(idList);
        if (result > 0) {
            return "redirect:/students?success=batchDelete";
        } else {
            model.addAttribute("error", "批量删除失败，请重试");
            return "student-list";
        }
    }

    private String validateStudent(String name, String studentId, String grade, String phone) {
        if (name == null || name.trim().isEmpty()) {
            return "姓名不能为空";
        }
        if (name.trim().length() < 2 || name.trim().length() > 20) {
            return "姓名长度必须在2-20个字符之间";
        }

        if (studentId == null || studentId.trim().isEmpty()) {
            return "学号不能为空";
        }
        if (!studentId.trim().matches("^[A-Za-z0-9]{8,12}$")) {
            return "学号格式不正确，必须是8-12位字母或数字组合";
        }

        if (grade == null || grade.trim().isEmpty()) {
            return "年级不能为空";
        }
        if (!GRADES.contains(grade)) {
            return "年级选择不正确";
        }

        if (phone == null || phone.trim().isEmpty()) {
            return "电话号码不能为空";
        }
        if (!phone.trim().matches("^1[3-9]\\d{9}$")) {
            return "电话号码格式不正确，必须是11位手机号";
        }

        return null;
    }
}