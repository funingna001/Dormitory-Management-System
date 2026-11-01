package com.example.dormitory.service;

import com.example.dormitory.entity.Student;

import java.util.List;
import java.util.Map;

public interface StudentService {
    List<Student> findAll();
    List<Student> search(String studentId, String name, String grade, String className, String gender, String dormitoryNumber);
    List<Student> searchWithPage(String studentId, String name, String grade, String className, String gender, String dormitoryNumber, String sortBy, String sortOrder, int offset, int limit);
    int count(String studentId, String name, String grade, String className, String gender, String dormitoryNumber);
    List<String> getAllClasses();
    Student findById(int id);
    Student findByStudentId(String studentId);
    int add(Student student);
    int update(Student student);
    int deleteById(int id);
    int batchDelete(List<Integer> ids);
    boolean existsByStudentId(String studentId);
    boolean existsByStudentIdExcludeId(String studentId, int excludeId);
    Map<String, Integer> getGradeStatistics();
    Map<String, Integer> getGenderStatistics();
}