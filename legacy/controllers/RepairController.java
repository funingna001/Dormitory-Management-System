package com.example.dormitory.controller;

import com.example.dormitory.entity.Repair;
import com.example.dormitory.entity.Student;
import com.example.dormitory.entity.User;
import com.example.dormitory.service.RepairService;
import com.example.dormitory.service.StudentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpSession;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/repair")
public class RepairController {

    @Autowired
    private RepairService repairService;

    @Autowired
    private StudentService studentService;

    private static final List<String> REPAIR_TYPES = Arrays.asList("灯具", "下水拥堵", "桌椅报修", "门窗报修", "其他");

    @GetMapping("/apply")
    public String apply(HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null || !"student".equals(user.getRole())) {
            return "redirect:/login";
        }

        Student student = studentService.findByStudentId(user.getUsername());
        if (student == null || !student.isDormitoryLeader()) {
            model.addAttribute("error", "只有宿舍长可以提交报修申请");
            return "student-dashboard";
        }

        model.addAttribute("user", user);
        model.addAttribute("student", student);
        model.addAttribute("repairTypes", REPAIR_TYPES);
        model.addAttribute("dormitoryNumber", student.getDormitoryNumber());

        return "repair-apply";
    }

    @PostMapping("/submit")
    public String submit(@RequestParam String repairType,
                         @RequestParam String description,
                         @RequestParam(required = false) MultipartFile image,
                         HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null || !"student".equals(user.getRole())) {
            return "redirect:/login";
        }

        Student student = studentService.findByStudentId(user.getUsername());
        if (student == null || !student.isDormitoryLeader()) {
            model.addAttribute("error", "只有宿舍长可以提交报修申请");
            return "student-dashboard";
        }

        if (repairType == null || repairType.trim().isEmpty()) {
            model.addAttribute("user", user);
            model.addAttribute("student", student);
            model.addAttribute("repairTypes", REPAIR_TYPES);
            model.addAttribute("description", description);
            model.addAttribute("error", "请选择报修类型");
            return "repair-apply";
        }

        if (description == null || description.trim().isEmpty()) {
            model.addAttribute("user", user);
            model.addAttribute("student", student);
            model.addAttribute("repairTypes", REPAIR_TYPES);
            model.addAttribute("repairType", repairType);
            model.addAttribute("error", "请填写问题描述");
            return "repair-apply";
        }

        String imagePath = null;
        if (image != null && !image.isEmpty()) {
            String uploadDir = "uploads/repair/";
            File dir = new File(uploadDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String originalFilename = image.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String newFilename = UUID.randomUUID().toString() + extension;
            Path path = Paths.get(uploadDir + newFilename);

            try {
                Files.write(path, image.getBytes());
                imagePath = "/uploads/repair/" + newFilename;
            } catch (IOException e) {
                model.addAttribute("user", user);
                model.addAttribute("student", student);
                model.addAttribute("repairTypes", REPAIR_TYPES);
                model.addAttribute("repairType", repairType);
                model.addAttribute("description", description);
                model.addAttribute("error", "图片上传失败，请重试");
                return "repair-apply";
            }
        }

        Repair repair = new Repair();
        repair.setStudentId(user.getUsername());
        repair.setStudentName(student.getName());
        repair.setDormitoryNumber(student.getDormitoryNumber());
        repair.setRepairType(repairType);
        repair.setDescription(description);
        repair.setImagePath(imagePath);
        repair.setStatus("待处理");
        repair.setCreateTime(LocalDateTime.now());
        repair.setUpdateTime(LocalDateTime.now());

        repairService.add(repair);

        model.addAttribute("success", "报修申请提交成功！");
        model.addAttribute("user", user);
        model.addAttribute("student", student);
        model.addAttribute("repairTypes", REPAIR_TYPES);

        return "repair-apply";
    }

    @GetMapping("/my-repairs")
    public String myRepairs(HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null || !"student".equals(user.getRole())) {
            return "redirect:/login";
        }

        Student student = studentService.findByStudentId(user.getUsername());
        if (student == null) {
            return "student-dashboard";
        }

        List<Repair> repairs = repairService.findByStudentId(user.getUsername());

        model.addAttribute("user", user);
        model.addAttribute("student", student);
        model.addAttribute("repairs", repairs);

        return "repair-list";
    }

    @GetMapping("/admin/list")
    public String adminList(HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null || !"admin".equals(user.getRole())) {
            return "redirect:/login";
        }

        List<Repair> repairs = repairService.findAll();
        model.addAttribute("user", user);
        model.addAttribute("repairs", repairs);

        return "repair-admin";
    }

    @PostMapping("/admin/status")
    public String updateStatus(@RequestParam int id, @RequestParam String status, HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null || !"admin".equals(user.getRole())) {
            return "redirect:/login";
        }

        repairService.updateStatus(id, status);

        return "redirect:/repair/admin/list";
    }
}