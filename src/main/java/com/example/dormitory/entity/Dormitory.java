package com.example.dormitory.entity;

public class Dormitory {
    private Integer id;
    private String dormitoryNumber;
    private String building;
    private String roomType;
    private Integer capacity;
    private Integer currentCount;
    private String gender;
    private String remark;

    public Dormitory() {}

    public Dormitory(String dormitoryNumber, String building, String roomType, Integer capacity, Integer currentCount, String gender, String remark) {
        this.dormitoryNumber = dormitoryNumber;
        this.building = building;
        this.roomType = roomType;
        this.capacity = capacity;
        this.currentCount = currentCount;
        this.gender = gender;
        this.remark = remark;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getDormitoryNumber() {
        return dormitoryNumber;
    }

    public void setDormitoryNumber(String dormitoryNumber) {
        this.dormitoryNumber = dormitoryNumber;
    }

    public String getBuilding() {
        return building;
    }

    public void setBuilding(String building) {
        this.building = building;
    }

    public String getRoomType() {
        return roomType;
    }

    public void setRoomType(String roomType) {
        this.roomType = roomType;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public Integer getCurrentCount() {
        return currentCount;
    }

    public void setCurrentCount(Integer currentCount) {
        this.currentCount = currentCount;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}