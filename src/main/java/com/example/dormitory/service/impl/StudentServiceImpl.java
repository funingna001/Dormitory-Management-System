package com.example.dormitory.service.impl;

import com.example.dormitory.entity.Student;
import com.example.dormitory.service.StudentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Service
public class StudentServiceImpl implements StudentService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public List<Student> findAll() {
        String sql = "SELECT * FROM student ORDER BY id DESC";
        return jdbcTemplate.query(sql, new StudentRowMapper());
    }

    @Override
    public List<Student> search(String studentId, String name, String grade, String className, String gender, String dormitoryNumber) {
        StringBuilder sql = new StringBuilder("SELECT * FROM student WHERE 1=1");
        List<Object> params = new ArrayList<>();
        
        if (studentId != null && !studentId.trim().isEmpty()) {
            sql.append(" AND student_id LIKE ?");
            params.add("%" + studentId + "%");
        }
        if (name != null && !name.trim().isEmpty()) {
            sql.append(" AND name LIKE ?");
            params.add("%" + name + "%");
        }
        if (grade != null && !grade.trim().isEmpty() && !"all".equals(grade)) {
            sql.append(" AND grade = ?");
            params.add(grade);
        }
        if (className != null && !className.trim().isEmpty() && !"all".equals(className)) {
            sql.append(" AND class_name LIKE ?");
            params.add("%" + className + "%");
        }
        if (gender != null && !gender.trim().isEmpty() && !"all".equals(gender)) {
            sql.append(" AND gender = ?");
            params.add(gender);
        }
        if (dormitoryNumber != null && !dormitoryNumber.trim().isEmpty()) {
            sql.append(" AND dormitory_number LIKE ?");
            params.add("%" + dormitoryNumber + "%");
        }
        sql.append(" ORDER BY id DESC");
        
        return jdbcTemplate.query(sql.toString(), new StudentRowMapper(), params.toArray());
    }

    @Override
    public List<Student> searchWithPage(String studentId, String name, String grade, String className, String gender, String dormitoryNumber, String sortBy, String sortOrder, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM student WHERE 1=1");
        List<Object> params = new ArrayList<>();
        
        if (studentId != null && !studentId.trim().isEmpty()) {
            sql.append(" AND student_id LIKE ?");
            params.add("%" + studentId + "%");
        }
        if (name != null && !name.trim().isEmpty()) {
            sql.append(" AND name LIKE ?");
            params.add("%" + name + "%");
        }
        if (grade != null && !grade.trim().isEmpty() && !"all".equals(grade)) {
            sql.append(" AND grade = ?");
            params.add(grade);
        }
        if (className != null && !className.trim().isEmpty() && !"all".equals(className)) {
            sql.append(" AND class_name LIKE ?");
            params.add("%" + className + "%");
        }
        if (gender != null && !gender.trim().isEmpty() && !"all".equals(gender)) {
            sql.append(" AND gender = ?");
            params.add(gender);
        }
        if (dormitoryNumber != null && !dormitoryNumber.trim().isEmpty()) {
            sql.append(" AND dormitory_number LIKE ?");
            params.add("%" + dormitoryNumber + "%");
        }
        
        sql.append(" ORDER BY ");
        if ("studentId".equals(sortBy)) {
            sql.append("CAST(SUBSTR(student_id, -4) AS SIGNED)");
        } else if ("name".equals(sortBy)) {
            sql.append("name");
        } else if ("grade".equals(sortBy)) {
            sql.append("grade");
        } else if ("className".equals(sortBy)) {
            sql.append("class_name");
        } else if ("gender".equals(sortBy)) {
            sql.append("gender");
        } else {
            sql.append("id");
        }
        
        if ("asc".equalsIgnoreCase(sortOrder)) {
            sql.append(" ASC");
        } else {
            sql.append(" DESC");
        }
        
        sql.append(" LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        
        return jdbcTemplate.query(sql.toString(), new StudentRowMapper(), params.toArray());
    }

    @Override
    public int count(String studentId, String name, String grade, String className, String gender, String dormitoryNumber) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM student WHERE 1=1");
        List<Object> params = new ArrayList<>();
        
        if (studentId != null && !studentId.trim().isEmpty()) {
            sql.append(" AND student_id LIKE ?");
            params.add("%" + studentId + "%");
        }
        if (name != null && !name.trim().isEmpty()) {
            sql.append(" AND name LIKE ?");
            params.add("%" + name + "%");
        }
        if (grade != null && !grade.trim().isEmpty() && !"all".equals(grade)) {
            sql.append(" AND grade = ?");
            params.add(grade);
        }
        if (className != null && !className.trim().isEmpty() && !"all".equals(className)) {
            sql.append(" AND class_name LIKE ?");
            params.add("%" + className + "%");
        }
        if (gender != null && !gender.trim().isEmpty() && !"all".equals(gender)) {
            sql.append(" AND gender = ?");
            params.add(gender);
        }
        if (dormitoryNumber != null && !dormitoryNumber.trim().isEmpty()) {
            sql.append(" AND dormitory_number LIKE ?");
            params.add("%" + dormitoryNumber + "%");
        }
        
        Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class, params.toArray());
        return count != null ? count : 0;
    }
    
    public List<String> getAllClasses() {
        String sql = "SELECT DISTINCT class_name FROM student WHERE class_name IS NOT NULL AND class_name != '' ORDER BY class_name";
        return jdbcTemplate.queryForList(sql, String.class);
    }

    @Override
    public Map<String, Integer> getGradeStatistics() {
        String sql = "SELECT grade, COUNT(*) as count FROM student GROUP BY grade";
        Map<String, Integer> stats = new LinkedHashMap<>();
        stats.put("大一", 0);
        stats.put("大二", 0);
        stats.put("大三", 0);
        stats.put("大四", 0);

        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> result : results) {
            String grade = (String) result.get("grade");
            Integer count = ((Number) result.get("count")).intValue();
            if (grade != null && count != null) {
                if (stats.containsKey(grade)) {
                    stats.put(grade, count);
                }
            }
        }

        return stats;
    }

    @Override
    public Map<String, Integer> getGenderStatistics() {
        String sql = "SELECT gender, COUNT(*) as count FROM student GROUP BY gender";
        Map<String, Integer> stats = new LinkedHashMap<>();
        stats.put("男", 0);
        stats.put("女", 0);
        
        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> result : results) {
            String gender = (String) result.get("gender");
            Integer count = ((Number) result.get("count")).intValue();
            if (gender != null && count != null) {
                stats.put(gender, count);
            }
        }
        
        return stats;
    }

    @Override
    public Student findById(int id) {
        String sql = "SELECT * FROM student WHERE id = ?";
        try {
            return jdbcTemplate.queryForObject(sql, new StudentRowMapper(), id);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Student findByStudentId(String studentId) {
        String sql = "SELECT * FROM student WHERE student_id = ?";
        try {
            return jdbcTemplate.queryForObject(sql, new StudentRowMapper(), studentId);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public int add(Student student) {
        String sql = "INSERT INTO student (name, student_id, grade, class_name, gender, phone, dormitory_number, is_dormitory_leader) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        return jdbcTemplate.update(sql, student.getName(), student.getStudentId(), 
                student.getGrade(), student.getClassName(), student.getGender(),
                student.getPhone(), student.getDormitoryNumber(), student.isDormitoryLeader() ? 1 : 0);
    }

    @Override
    public int update(Student student) {
        String sql = "UPDATE student SET name = ?, student_id = ?, grade = ?, class_name = ?, gender = ?, phone = ?, dormitory_number = ?, is_dormitory_leader = ? WHERE id = ?";
        return jdbcTemplate.update(sql, student.getName(), student.getStudentId(), 
                student.getGrade(), student.getClassName(), student.getGender(),
                student.getPhone(), student.getDormitoryNumber(), student.isDormitoryLeader() ? 1 : 0, student.getId());
    }

    @Override
    public int deleteById(int id) {
        String sql = "DELETE FROM student WHERE id = ?";
        return jdbcTemplate.update(sql, id);
    }

    @Override
    public int batchDelete(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String sql = "DELETE FROM student WHERE id IN (" + 
                String.join(",", Collections.nCopies(ids.size(), "?")) + ")";
        return jdbcTemplate.update(sql, ids.toArray());
    }

    @Override
    public boolean existsByStudentId(String studentId) {
        String sql = "SELECT COUNT(*) FROM student WHERE student_id = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, studentId);
        return count != null && count > 0;
    }

    @Override
    public boolean existsByStudentIdExcludeId(String studentId, int excludeId) {
        String sql = "SELECT COUNT(*) FROM student WHERE student_id = ? AND id != ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, studentId, excludeId);
        return count != null && count > 0;
    }

    private static class StudentRowMapper implements RowMapper<Student> {
        @Override
        public Student mapRow(ResultSet rs, int rowNum) throws SQLException {
            Student student = new Student();
            student.setId(rs.getInt("id"));
            student.setName(rs.getString("name"));
            student.setStudentId(rs.getString("student_id"));
            student.setGrade(rs.getString("grade"));
            student.setClassName(rs.getString("class_name"));
            student.setGender(rs.getString("gender"));
            student.setPhone(rs.getString("phone"));
            student.setDormitoryNumber(rs.getString("dormitory_number"));
            student.setDormitoryLeader(rs.getInt("is_dormitory_leader") == 1);
            return student;
        }
    }
}