package com.example.dormitory.controller;

import com.example.dormitory.service.AiService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final AiService ai;
    private final AuthRateLimits limits;
    public AiController(AiService ai, AuthRateLimits limits) { this.ai=ai; this.limits=limits; }
    @GetMapping("/status") public Object status() { return ai.status(); }
    @PostMapping("/chat") public Object chat(@Valid @RequestBody Requests.Chat input, java.security.Principal principal) {
        limits.take("ai-query",principal.getName(),10,60000);
        return ai.query(input.message());
    }
    @GetMapping("/statistics") public Object statistics() { return Map.of("studentStatistics",ai.getStudentStatistics(),"dormitoryStatistics",ai.getDormitoryStatistics()); }
    @GetMapping("/summary") public Object summary() { return Map.of("summary",ai.getStatisticsSummary()); }
}
