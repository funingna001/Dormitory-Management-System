package com.example.dormitory.service;

import com.example.dormitory.entity.Dormitory;
import com.example.dormitory.entity.Student;

import java.util.List;
import java.util.Map;

public interface DormitoryService {
    List<Dormitory> findAll();
    List<Dormitory> search(String dormitoryNumber, String building, String roomType, String gender, String occupancy, String floor);
    List<Dormitory> searchWithPage(String dormitoryNumber, String building, String roomType, String gender, String occupancy, String floor, String sortBy, String sortOrder, int offset, int limit);
    int count(String dormitoryNumber, String building, String roomType, String gender, String occupancy, String floor);
    Dormitory findById(int id);
    int add(Dormitory dormitory);
    int update(Dormitory dormitory);
    int deleteById(int id);
    boolean existsByDormitoryNumber(String dormitoryNumber);
    boolean existsByDormitoryNumberExcludeId(String dormitoryNumber, int excludeId);
    List<String> getAllBuildings();
    int batchDelete(List<Integer> ids);
    Map<String, Integer> getBuildingStatistics();
    Map<String, Integer> getRoomTypeStatistics();
    Map<String, Integer> getGenderStatistics();
    int getTotalCount();
    List<Student> getStudentsByDormitoryNumber(String dormitoryNumber);
    void updateCurrentCount(String dormitoryNumber);
    int getMaxFloor();
    List<Integer> getFloorsByBuilding(String building);
    List<Integer> getAllFloors();
    Map<String, Object> getDormitoryInfo(String dormitoryNumber);
}