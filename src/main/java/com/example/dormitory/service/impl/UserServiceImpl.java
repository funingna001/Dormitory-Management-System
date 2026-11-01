package com.example.dormitory.service.impl;

import com.example.dormitory.entity.User;
import com.example.dormitory.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;

@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Override
    public User login(String username, String password) {
        User user = findByUsername(username);
        return user != null && passwordEncoder.matches(password, user.getPassword()) ? user : null;
    }

    @Override
    public User findByUsername(String username) {
        String sql = "SELECT * FROM user WHERE username = ?";
        try {
            return jdbcTemplate.queryForObject(sql, new UserRowMapper(), username);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    public int initializeStudent(String username, String realName, String phone) {
        String sql = "UPDATE user SET real_name = ?, phone = ?, is_initialized = 1 WHERE username = ?";
        return jdbcTemplate.update(sql, realName, phone, username);
    }

    @Override
    public int createStudentAccount(String username, String password) {
        validatePassword(password);
        String sql = "INSERT INTO user (username, password, role, is_initialized) VALUES (?, ?, 'student', 0)";
        int created=jdbcTemplate.update(sql, username, passwordEncoder.encode(password));
        jdbcTemplate.update("INSERT INTO account_policy(username,force_password_change) VALUES(?,1)",username);
        return created;
    }

    private void validatePassword(String password) {
        com.example.dormitory.business.AccountBusinessService.passwordPolicy(password);
        if (password == null || password.length() < 8 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "密码至少 8 个字符，UTF-8 编码后不能超过 72 字节");
    }

    public static class UserRowMapper implements RowMapper<User> {
        @Override
        public User mapRow(ResultSet rs, int rowNum) throws SQLException {
            User user = new User();
            user.setId(rs.getInt("id"));
            user.setUsername(rs.getString("username"));
            user.setPassword(rs.getString("password"));
            user.setRealName(rs.getString("real_name"));
            user.setPhone(rs.getString("phone"));
            user.setRole(rs.getString("role"));
            user.setInitialized(rs.getBoolean("is_initialized"));
            return user;
        }
    }
}
