package com.example.dormitory.entity;

public class User {
    private int id;
    private String username;
    private String password;
    private String realName;
    private String phone;
    private String role;
    private boolean initialized;

    public User() {
    }

    public User(String username, String password, String realName, String phone, String role) {
        this.username = username;
        this.password = password;
        this.realName = realName;
        this.phone = phone;
        this.role = role;
        this.initialized = false;
    }

    public User(String username, String password, String realName, String phone, String role, boolean initialized) {
        this.username = username;
        this.password = password;
        this.realName = realName;
        this.phone = phone;
        this.role = role;
        this.initialized = initialized;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }
}