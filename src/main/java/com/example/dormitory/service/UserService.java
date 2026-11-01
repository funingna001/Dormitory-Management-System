package com.example.dormitory.service;

import com.example.dormitory.entity.User;

public interface UserService {
    User login(String username, String password);
    User findByUsername(String username);
    int initializeStudent(String username, String realName, String phone);
    int createStudentAccount(String username, String password);
}