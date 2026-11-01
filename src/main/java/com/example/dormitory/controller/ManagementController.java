package com.example.dormitory.controller;

import com.example.dormitory.service.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ManagementController {
    private final StudentService students;
    private final DormitoryService dormitories;
    private final BuildingService buildings;
    private final HousingService housing;
    public ManagementController(StudentService students, DormitoryService dormitories, BuildingService buildings, HousingService housing) {
        this.students=students; this.dormitories=dormitories; this.buildings=buildings; this.housing=housing;
    }
    private int offset(int page, int size) {
        if (page<1 || page>1000000 || size<1 || size>100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"分页参数不合法");
        return (page-1)*size;
    }
    @GetMapping("/students") public Object students(
            @RequestParam(required=false) String studentId, @RequestParam(required=false) String name,
            @RequestParam(defaultValue="all") String grade, @RequestParam(defaultValue="all") String className,
            @RequestParam(defaultValue="all") String gender, @RequestParam(required=false) String dormitoryNumber,
            @RequestParam(defaultValue="id") String sortBy, @RequestParam(defaultValue="desc") String sortOrder,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="10") int size) {
        int offset = offset(page,size);
        return Map.of("items", students.searchWithPage(studentId,name,grade,className,gender,dormitoryNumber,sortBy,sortOrder,offset,size),
                "total", students.count(studentId,name,grade,className,gender,dormitoryNumber), "page",page,"size",size,
                "classes",students.getAllClasses());
    }
    @GetMapping("/students/{id}") public Object student(@PathVariable int id) { return HousingService.found(students.findById(id)); }
    @PostMapping("/students") public Object addStudent(@Valid @RequestBody Requests.StudentInput input) { return housing.saveStudent(null,input); }
    @PutMapping("/students/{id}") public Object editStudent(@PathVariable int id,@Valid @RequestBody Requests.StudentInput input) { return housing.saveStudent(id,input); }
    @DeleteMapping("/students/{id}") public Object deleteStudent(@PathVariable int id) { housing.deleteStudents(List.of(id)); return Map.of("success",true); }
    @PostMapping("/students/batch-delete") public Object deleteStudents(@Valid @RequestBody Requests.Ids input) { housing.deleteStudents(input.ids()); return Map.of("success",true); }
    @GetMapping("/dormitories") public Object dormitories(
            @RequestParam(required=false) String dormitoryNumber, @RequestParam(defaultValue="all") String building,
            @RequestParam(defaultValue="all") String roomType, @RequestParam(defaultValue="all") String gender,
            @RequestParam(defaultValue="all") String occupancy, @RequestParam(defaultValue="all") String floor,
            @RequestParam(defaultValue="dormitoryNumber") String sortBy, @RequestParam(defaultValue="asc") String sortOrder,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="10") int size) {
        int offset=offset(page,size);
        return Map.of("items",dormitories.searchWithPage(dormitoryNumber,building,roomType,gender,occupancy,floor,sortBy,sortOrder,offset,size),
                "total",dormitories.count(dormitoryNumber,building,roomType,gender,occupancy,floor),"page",page,"size",size,
                "buildings",buildings.findAll());
    }
    @GetMapping("/dormitories/floors") public Object floors(@RequestParam(defaultValue="all") String building) { return building.equals("all") ? dormitories.getAllFloors() : dormitories.getFloorsByBuilding(building); }
    @GetMapping("/dormitories/{id}") public Object dormitory(@PathVariable int id) {
        var room=HousingService.found(dormitories.findById(id));
        return Map.of("dormitory",room,"students",dormitories.getStudentsByDormitoryNumber(room.getDormitoryNumber()));
    }
    @PostMapping("/dormitories") public Object addDormitory(@Valid @RequestBody Requests.DormitoryInput input) { return housing.saveDormitory(null,input); }
    @PutMapping("/dormitories/{id}") public Object editDormitory(@PathVariable int id,@Valid @RequestBody Requests.DormitoryInput input) { return housing.saveDormitory(id,input); }
    @DeleteMapping("/dormitories/{id}") public Object deleteDormitory(@PathVariable int id) { housing.deleteDormitories(List.of(id)); return Map.of("success",true); }
    @PostMapping("/dormitories/batch-delete") public Object deleteDormitories(@Valid @RequestBody Requests.Ids input) { housing.deleteDormitories(input.ids()); return Map.of("success",true); }
    @PostMapping("/dormitories/{id}/students/{studentId}") public Object assign(@PathVariable int id,@PathVariable int studentId) { housing.assign(id,studentId); return Map.of("success",true); }
    @DeleteMapping("/dormitories/{id}/students/{studentId}") public Object remove(@PathVariable int id,@PathVariable int studentId) { housing.remove(id,studentId); return Map.of("success",true); }
    @GetMapping("/buildings") public Object buildings() { return buildings.findAll(); }
    @GetMapping("/buildings/{id}") public Object building(@PathVariable int id) {
        var building=HousingService.found(buildings.findById(id));
        return Map.of("building",building,"dormitories",dormitories.findAll().stream().filter(d->building.getName().equals(d.getBuilding())).toList());
    }
    @PostMapping("/buildings") public Object addBuilding(@Valid @RequestBody Requests.BuildingInput input) { return housing.saveBuilding(null,input); }
    @PutMapping("/buildings/{id}") public Object editBuilding(@PathVariable int id,@Valid @RequestBody Requests.BuildingInput input) { return housing.saveBuilding(id,input); }
    @DeleteMapping("/buildings/{id}") public Object deleteBuilding(@PathVariable int id) { housing.deleteBuilding(id); return Map.of("success",true); }
    @GetMapping({"/data/statistics","/data/refresh"}) public Object statistics() {
        return Map.of("totalStudents",students.count(null,null,"all","all","all",null),"totalDormitories",dormitories.getTotalCount(),
                "gradeStats",students.getGradeStatistics(),"genderStats",students.getGenderStatistics(),
                "buildingStats",dormitories.getBuildingStatistics(),"roomTypeStats",dormitories.getRoomTypeStatistics());
    }
}
