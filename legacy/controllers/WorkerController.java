package com.example.dormitory.controller;

import com.example.dormitory.entity.Repair;
import com.example.dormitory.entity.User;
import com.example.dormitory.service.RepairService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.List;

@Controller
@RequestMapping("/worker")
public class WorkerController {

    @Autowired
    private RepairService repairService;

    @GetMapping("/dashboard")
    public String dashboard(HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null || !"worker".equals(user.getRole())) {
            return "redirect:/login";
        }

        List<Repair> pendingRepairs = repairService.findByStatus("待处理");
        List<Repair> processingRepairs = repairService.findByStatus("处理中");
        List<Repair> completedRepairs = repairService.findByStatus("已完成");

        model.addAttribute("user", user);
        model.addAttribute("pendingCount", pendingRepairs.size());
        model.addAttribute("processingCount", processingRepairs.size());
        model.addAttribute("completedCount", completedRepairs.size());

        return "worker-dashboard";
    }

    @GetMapping("/repair-list")
    public String repairList(HttpSession session, Model model, @RequestParam(required = false) String status) {
        User user = (User) session.getAttribute("user");
        if (user == null || !"worker".equals(user.getRole())) {
            return "redirect:/login";
        }

        List<Repair> repairs;
        if (status != null && !status.isEmpty()) {
            repairs = repairService.findByStatus(status);
        } else {
            repairs = repairService.findAll();
        }

        model.addAttribute("user", user);
        model.addAttribute("repairs", repairs);
        model.addAttribute("selectedStatus", status);

        return "worker-repair-list";
    }

    @PostMapping("/complete-repair")
    public String completeRepair(@RequestParam int id, HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null || !"worker".equals(user.getRole())) {
            return "redirect:/login";
        }

        repairService.updateStatus(id, "已完成");

        return "redirect:/worker/repair-list";
    }

    @PostMapping("/start-repair")
    public String startRepair(@RequestParam int id, HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null || !"worker".equals(user.getRole())) {
            return "redirect:/login";
        }

        repairService.updateStatus(id, "处理中");

        return "redirect:/worker/repair-list";
    }
}