package com.example.dormitory.business;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;

import tools.jackson.databind.json.JsonMapper;

@Component
public class BusinessStore {
    public final JdbcTemplate jdbc;

    public BusinessStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String now() {
        return LocalDateTime.now().toString();
    }

    public static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    public static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    public static void check(boolean condition, String message) {
        // 条件不满足时停止操作，例如床位已满或记录已经处理过。
        if (!condition) throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public static void invalid(boolean condition, String message) {
        if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在");
        return rows.get(0);
    }

    public long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    public long insert(String sql, Object... args) {
        // 插入后取回数据库生成的编号，后续记录关联时会用到。
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }

    public static String json(Object value) {
        return JsonMapper.builder().build().writeValueAsString(value);
    }

    public void audit(String actor, String action, String type, Object id, Object details) {
        jdbc.update("INSERT INTO audit_log(actor,action,resource_type,resource_id,detail_json,created_at) VALUES(?,?,?,?,?,?)", actor, action, type, String.valueOf(id), json(details), now());
    }

    public void notify(String recipient, String title, String content) {
        if (recipient != null && !recipient.isBlank() && count("SELECT COUNT(*) FROM user WHERE username=?", recipient) > 0)
            jdbc.update("INSERT INTO notification(recipient,title,content,created_at) VALUES(?,?,?,?)", recipient, title, content, now());
    }

    public boolean setting(String key) {
        return !jdbc.queryForList("SELECT setting_value FROM business_setting WHERE setting_key=?", String.class, key).contains("false");
    }
}
