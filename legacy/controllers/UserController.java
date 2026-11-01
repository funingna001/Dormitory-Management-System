package com.example.dormitory.controller;

import com.example.dormitory.entity.Student;
import com.example.dormitory.entity.User;
import com.example.dormitory.service.DormitoryService;
import com.example.dormitory.service.StudentService;
import com.example.dormitory.service.UserService;
import java.util.Map;
import com.example.dormitory.util.CaptchaUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;

@Controller
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private StudentService studentService;

    @Autowired
    private DormitoryService dormitoryService;

    @GetMapping("/")
    public String index(HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        model.addAttribute("user", user);

        if ("student".equals(user.getRole())) {
            if (!user.isInitialized()) {
                return "redirect:/student/profile";
            }
            return "student-dashboard";
        }

        int totalStudents = studentService.count(null, null, "all", "all", "all", null);
        int totalDormitories = dormitoryService.getTotalCount();

        model.addAttribute("totalStudents", totalStudents);
        model.addAttribute("totalDormitories", totalDormitories);

        java.util.Map<String, Integer> gradeStats = studentService.getGradeStatistics();
        java.util.Map<String, Integer> genderStats = studentService.getGenderStatistics();

        model.addAttribute("gradeStats", gradeStats);
        model.addAttribute("genderStats", genderStats);
        
        model.addAttribute("grade1Count", gradeStats.getOrDefault("大一", 0));
        model.addAttribute("grade2Count", gradeStats.getOrDefault("大二", 0));
        model.addAttribute("grade3Count", gradeStats.getOrDefault("大三", 0));
        model.addAttribute("grade4Count", gradeStats.getOrDefault("大四", 0));

        return "index";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/captcha")
    public void captcha(HttpServletResponse response, HttpSession session) throws IOException {
        String code = CaptchaUtil.generateCode();
        session.setAttribute("captcha", code);
        response.setContentType(MediaType.IMAGE_JPEG_VALUE);
        response.getOutputStream().write(CaptchaUtil.generateImage(code));
        response.getOutputStream().flush();
    }

    @PostMapping("/login")
    public String login(@RequestParam String username, @RequestParam String password,
                        @RequestParam(required = false) String captcha, HttpSession session, Model model) {
        if (username == null || username.trim().isEmpty() || password == null || password.trim().isEmpty()) {
            model.addAttribute("error", "用户名和密码不能为空");
            return "login";
        }

        if (!"admin".equalsIgnoreCase(username.trim())) {
            User user = userService.findByUsername(username.trim());
            if (user != null && "student".equals(user.getRole())) {
                // 学生账号跳过验证码
            } else {
                String sessionCaptcha = (String) session.getAttribute("captcha");
                if (sessionCaptcha == null || !sessionCaptcha.equalsIgnoreCase(captcha)) {
                    model.addAttribute("error", "验证码错误");
                    return "login";
                }
            }
        }

        User user = userService.login(username, password);
        if (user != null) {
            session.setAttribute("user", user);
            if ("admin".equals(user.getRole())) {
                return "redirect:/dormitories";
            }
            if ("student".equals(user.getRole())) {
                if (!user.isInitialized()) {
                    return "redirect:/student/profile";
                }
                return "redirect:/student/dashboard";
            }
            if ("worker".equals(user.getRole())) {
                return "redirect:/worker/dashboard";
            }
            return "redirect:/";
        } else {
            model.addAttribute("error", "用户名或密码错误");
            return "login";
        }
    }

    @GetMapping("/register")
    public String register() {
        return "register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String username, @RequestParam String password,
                           @RequestParam String confirmPassword, @RequestParam(required = false) String realName,
                           @RequestParam(required = false) String phone, Model model) {
        if (username == null || username.trim().isEmpty()) {
            model.addAttribute("error", "用户名不能为空");
            return "register";
        }
        if (password == null || password.trim().isEmpty()) {
            model.addAttribute("error", "密码不能为空");
            return "register";
        }
        if (!password.equals(confirmPassword)) {
            model.addAttribute("error", "两次输入的密码不一致");
            return "register";
        }

        User existingUser = userService.findByUsername(username);
        if (existingUser != null) {
            model.addAttribute("error", "用户名已存在");
            return "register";
        }

        int result = userService.register(username, password, realName, phone);
        if (result > 0) {
            return "redirect:/login";
        } else {
            model.addAttribute("error", "注册失败，请重试");
            return "register";
        }
    }

    @GetMapping("/student/profile")
    public String studentProfile(HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null || !"student".equals(user.getRole())) {
            return "redirect:/login";
        }
        model.addAttribute("user", user);
        return "student-profile";
    }

    @PostMapping("/student/profile")
    public String saveStudentProfile(HttpSession session, Model model,
                                    @RequestParam String name,
                                    @RequestParam String studentId,
                                    @RequestParam String grade,
                                    @RequestParam String className,
                                    @RequestParam String gender,
                                    @RequestParam String phone) {
        User user = (User) session.getAttribute("user");
        if (user == null || !"student".equals(user.getRole())) {
            return "redirect:/login";
        }

        userService.initializeStudent(user.getUsername(), name, phone);

        Student existingStudent = studentService.findByStudentId(studentId);
        if (existingStudent != null) {
            existingStudent.setName(name);
            existingStudent.setGrade(grade);
            existingStudent.setClassName(className);
            existingStudent.setGender(gender);
            existingStudent.setPhone(phone);
            studentService.update(existingStudent);
        } else {
            Student student = new Student();
            student.setName(name);
            student.setStudentId(studentId);
            student.setGrade(grade);
            student.setClassName(className);
            student.setGender(gender);
            student.setPhone(phone);
            studentService.add(student);
        }

        user.setRealName(name);
        user.setPhone(phone);
        user.setInitialized(true);
        session.setAttribute("user", user);

        return "redirect:/student/dashboard";
    }

    @GetMapping("/student/dashboard")
    public String studentDashboard(HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null || !"student".equals(user.getRole())) {
            return "redirect:/login";
        }
        if (!user.isInitialized()) {
            return "redirect:/student/profile";
        }

        model.addAttribute("user", user);

        Student student = studentService.findByStudentId(user.getUsername());
        if (student != null) {
            model.addAttribute("student", student);

            if (student.getDormitoryNumber() != null) {
                Map<String, Object> dormitory = dormitoryService.getDormitoryInfo(student.getDormitoryNumber());
                model.addAttribute("dormitory", dormitory);
            }
        }

        return "student-dashboard";
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }
}