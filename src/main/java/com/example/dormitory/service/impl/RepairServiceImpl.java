package com.example.dormitory.service.impl;

import com.example.dormitory.entity.Repair;
import com.example.dormitory.service.RepairService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class RepairServiceImpl implements RepairService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public void fixInvalidRepairTypes() {
        String sql = "UPDATE repair_request SET repair_type = '其他' WHERE repair_type LIKE '%\\[\\[%' OR repair_type = ''";
        jdbcTemplate.update(sql);
    }

    @Override
    public int add(Repair repair) {
        String sql = "INSERT INTO repair_request (student_id, student_name, dormitory_number, repair_type, description, image_path, status, create_time, update_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return jdbcTemplate.update(sql, 
                repair.getStudentId(),
                repair.getStudentName(),
                repair.getDormitoryNumber(),
                repair.getRepairType(),
                repair.getDescription(),
                repair.getImagePath(),
                repair.getStatus(),
                java.sql.Timestamp.valueOf(repair.getCreateTime()),
                java.sql.Timestamp.valueOf(repair.getUpdateTime())
        );
    }

    @Override
    public int updateStatus(int id, String status) {
        String sql = "UPDATE repair_request SET status = ?, update_time = ? WHERE id = ?";
        return jdbcTemplate.update(sql, status, java.sql.Timestamp.valueOf(LocalDateTime.now()), id);
    }

    @Override
    public Repair findById(int id) {
        String sql = "SELECT * FROM repair_request WHERE id = ?";
        try {
            return jdbcTemplate.queryForObject(sql, new RepairRowMapper(), id);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    public List<Repair> findByStudentId(String studentId) {
        String sql = "SELECT * FROM repair_request WHERE student_id = ? ORDER BY create_time DESC";
        return jdbcTemplate.query(sql, new RepairRowMapper(), studentId);
    }

    @Override
    public List<Repair> findAll() {
        String sql = "SELECT * FROM repair_request ORDER BY create_time DESC";
        return jdbcTemplate.query(sql, new RepairRowMapper());
    }

    @Override
    public List<Repair> findByStatus(String status) {
        String sql = "SELECT * FROM repair_request WHERE status = ? ORDER BY create_time DESC";
        return jdbcTemplate.query(sql, new RepairRowMapper(), status);
    }

    @Override
    public int deleteById(int id) {
        String sql = "DELETE FROM repair_request WHERE id = ?";
        return jdbcTemplate.update(sql, id);
    }

    private static class RepairRowMapper implements RowMapper<Repair> {
        @Override
        public Repair mapRow(ResultSet rs, int rowNum) throws SQLException {
            Repair repair = new Repair();
            repair.setId(rs.getInt("id"));
            repair.setStudentId(rs.getString("student_id"));
            repair.setStudentName(rs.getString("student_name"));
            repair.setDormitoryNumber(rs.getString("dormitory_number"));
            repair.setRepairType(rs.getString("repair_type"));
            repair.setDescription(rs.getString("description"));
            repair.setImagePath(rs.getString("image_path"));
            repair.setStatus(rs.getString("status"));
            repair.setCreateTime(readTime(rs, "create_time"));
            repair.setUpdateTime(readTime(rs, "update_time"));
            return repair;
        }

        private LocalDateTime readTime(ResultSet rs, String column) throws SQLException {
            Object value = rs.getObject(column);
            if (value == null) return null;
            if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
            if (value instanceof Number millis)
                return new java.sql.Timestamp(millis.longValue()).toLocalDateTime();
            String text = value.toString().trim().replace(' ', 'T');
            if (text.matches("[0-9]{11,17}")) return new java.sql.Timestamp(Long.parseLong(text)).toLocalDateTime();
            return LocalDateTime.parse(text);
        }
    }
}
