package com.example.dormitory.controller;

import com.example.dormitory.config.DataInitializer;
import com.example.dormitory.service.DormitoryService;
import com.example.dormitory.service.StudentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/data")
public class DataController {

    @Autowired
    private StudentService studentService;

    @Autowired
    private DormitoryService dormitoryService;

    @Autowired
    private DataInitializer dataInitializer;

    @GetMapping("/refresh")
    public Map<String, Object> refreshData() {
        Map<String, Object> response = new HashMap<>();
        try {
            int totalStudents = studentService.count(null, null, "all", "all", "all", null);
            int totalDormitories = dormitoryService.getTotalCount();

            Map<String, Integer> gradeStats = studentService.getGradeStatistics();
            Map<String, Integer> genderStats = studentService.getGenderStatistics();

            response.put("success", true);
            response.put("totalStudents", totalStudents);
            response.put("totalDormitories", totalDormitories);
            response.put("gradeStats", gradeStats);
            response.put("genderStats", genderStats);
            response.put("grade1Count", gradeStats.getOrDefault("大一", 0));
            response.put("grade2Count", gradeStats.getOrDefault("大二", 0));
            response.put("grade3Count", gradeStats.getOrDefault("大三", 0));
            response.put("grade4Count", gradeStats.getOrDefault("大四", 0));

        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return response;
    }

    @GetMapping("/reinitialize")
    public Map<String, Object> reinitializeData() {
        Map<String, Object> response = new HashMap<>();
        try {
            dataInitializer.run();
            response.put("success", true);
            response.put("message", "数据重新初始化成功");
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return response;
    }

    @GetMapping("/statistics")
    public Map<String, Object> getStatistics() {
        Map<String, Object> response = new HashMap<>();
        try {
            int totalStudents = studentService.count(null, null, "all", "all", "all", null);
            int totalDormitories = dormitoryService.getTotalCount();
            int maleCount = studentService.count(null, "男", "all", "all", "all", null);
            int femaleCount = studentService.count(null, "女", "all", "all", "all", null);

            Map<String, Integer> gradeStats = studentService.getGradeStatistics();

            response.put("success", true);
            response.put("totalStudents", totalStudents);
            response.put("totalDormitories", totalDormitories);
            response.put("maleCount", maleCount);
            response.put("femaleCount", femaleCount);
            response.put("gradeStats", gradeStats);

        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return response;
    }
}
