package com.example.dormitory.service;

import com.example.dormitory.entity.Building;

import java.util.List;

public interface BuildingService {
    void addBuilding(Building building);
    void updateBuilding(Building building);
    Building findById(Integer id);
    List<Building> findAll();
    boolean existsByName(String name);
    int getNextBuildingNumber();
    int getFloorCount(String buildingName);
    int getRoomsPerFloor(String buildingName);
    List<String> getRoomTypesByFloor(String buildingName, int floor);
}