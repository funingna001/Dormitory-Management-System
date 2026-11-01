package com.example.dormitory.controller;

import com.example.dormitory.service.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/api/me")
public class MeController {
    private final StudentService students;
    private final DormitoryService dormitories;
    private final HousingService housing;
    public MeController(StudentService students,DormitoryService dormitories,HousingService housing) {
        this.students=students; this.dormitories=dormitories; this.housing=housing;
    }
    @GetMapping public Object me(Principal principal) {
        var student=students.findByStudentId(principal.getName());
        Map<String,Object> result=new HashMap<>();
        result.put("student",student);
        result.put("dormitory",student==null || student.getDormitoryNumber()==null ? null : dormitories.getDormitoryInfo(student.getDormitoryNumber()));
        return result;
    }
    @PutMapping("/profile") public Object profile(Principal principal,@Valid @RequestBody Requests.Profile input) {
        return housing.saveProfile(principal.getName(),input);
    }
}
