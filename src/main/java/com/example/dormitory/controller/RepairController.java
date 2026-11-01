package com.example.dormitory.controller;

import com.example.dormitory.business.*;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/repairs")
public class RepairController {
    private final WorkOrderService work;
    private final BusinessImages images;

    public RepairController(WorkOrderService work, BusinessImages images) {
        this.work = work;
        this.images = images;
    }

    @GetMapping
    public Object list(Authentication auth, @RequestParam(required = false) String status) {
        return work.list().stream().filter(row -> status == null || status.isBlank() || status.equals(row.get("status"))).toList();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object submit(Authentication auth, @RequestParam String repairType, @RequestParam String description,
                         @RequestParam(required = false) MultipartFile image) throws java.io.IOException {
        if (auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_STUDENT")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅住宿学生可以报修");
        if (!List.of("灯具", "下水拥堵", "桌椅报修", "门窗报修", "其他").contains(repairType) || description == null || description.isBlank() || description.length() > 2000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择报修类型并填写 1–2000 字描述");
        String stored = image == null || image.isEmpty() ? null : images.save(image);
        long id;
        try {
            id = work.submit(repairType, description, stored);
        } catch (RuntimeException error) {
            images.delete(stored);
            throw error;
        }
        var assessment = work.repair(id);
        String priority = assessment.get("priority").toString();
        return Map.of("success", true, "id", id, "dispatchState", "WAITING_ASSIGNMENT", "priority", priority,
                "prioritySource", assessment.get("priority_source"), "priorityReason", assessment.get("priority_reason"),
                "message", "工单 #" + id + " 已提交，自动判定为" + (priority.equals("URGENT") ? "紧急" : "普通") + "；" + assessment.get("priority_reason") + "。等待管理员或宿管派单。");
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable int id) throws java.io.IOException {
        return images.read(BusinessStore.text(work.repair(id).get("image_path")));
    }

    @PatchMapping("/{id}/status")
    public Object status(@PathVariable int id, @Valid @RequestBody Requests.Status input) {
        work.transition(id, input.status(), null);
        return Map.of("success", true);
    }
}
