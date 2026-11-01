package com.example.dormitory.controller;

import com.example.dormitory.service.AiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    @Autowired
    private AiService aiService;

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        String message = request.get("message");
        try {
            String reply = aiService.chat(message);
            response.put("success", true);
            response.put("reply", reply);
        } catch (Exception e) {
            response.put("success", false);
            response.put("reply", "AI服务出错：" + e.getMessage());
        }
        return response;
    }

    @GetMapping("/statistics")
    public Map<String, Object> getStatistics() {
        Map<String, Object> response = new HashMap<>();
        try {
            response.put("success", true);
            response.put("studentStatistics", aiService.getStudentStatistics());
            response.put("dormitoryStatistics", aiService.getDormitoryStatistics());
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return response;
    }

    @GetMapping("/summary")
    public Map<String, Object> getSummary() {
        Map<String, Object> response = new HashMap<>();
        try {
            response.put("success", true);
            response.put("summary", aiService.getStatisticsSummary());
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return response;
    }
}