package com.example.dormitory.entity;

public class Student {
    private int id;
    private String name;
    private String studentId;
    private String grade;
    private String className;
    private String gender;
    private String phone;
    private String dormitoryNumber;
    private boolean isDormitoryLeader;

    public Student() {
    }

    public Student(String name, String studentId, String grade, String className, String gender, String phone, String dormitoryNumber) {
        this.name = name;
        this.studentId = studentId;
        this.grade = grade;
        this.className = className;
        this.gender = gender;
        this.phone = phone;
        this.dormitoryNumber = dormitoryNumber;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStudentId() {
        return studentId;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getDormitoryNumber() {
        return dormitoryNumber;
    }

    public void setDormitoryNumber(String dormitoryNumber) {
        this.dormitoryNumber = dormitoryNumber;
    }

    public boolean isDormitoryLeader() {
        return isDormitoryLeader;
    }

    public void setDormitoryLeader(boolean dormitoryLeader) {
        isDormitoryLeader = dormitoryLeader;
    }
}