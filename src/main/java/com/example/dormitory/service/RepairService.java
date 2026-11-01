package com.example.dormitory.service;

import com.example.dormitory.entity.Repair;

import java.util.List;

public interface RepairService {
    int add(Repair repair);
    int updateStatus(int id, String status);
    Repair findById(int id);
    List<Repair> findByStudentId(String studentId);
    List<Repair> findAll();
    List<Repair> findByStatus(String status);
    int deleteById(int id);
    void fixInvalidRepairTypes();
}