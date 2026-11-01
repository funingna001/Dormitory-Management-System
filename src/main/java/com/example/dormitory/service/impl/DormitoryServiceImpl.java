package com.example.dormitory.service.impl;

import com.example.dormitory.entity.Dormitory;
import com.example.dormitory.entity.Student;
import com.example.dormitory.service.DormitoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Service
public class DormitoryServiceImpl implements DormitoryService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public List<Dormitory> findAll() {
        String sql = "SELECT * FROM dormitory ORDER BY dormitory_number ASC";
        return jdbcTemplate.query(sql, new DormitoryRowMapper());
    }

    @Override
    public List<Dormitory> search(String dormitoryNumber, String building, String roomType, String gender, String occupancy, String floor) {
        StringBuilder sql = new StringBuilder("SELECT * FROM dormitory WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (dormitoryNumber != null && !dormitoryNumber.trim().isEmpty()) {
            sql.append(" AND dormitory_number LIKE ?");
            params.add("%" + dormitoryNumber + "%");
        }
        if (building != null && !building.trim().isEmpty() && !"all".equals(building)) {
            sql.append(" AND building = ?");
            params.add(building);
        }
        if (roomType != null && !roomType.trim().isEmpty() && !"all".equals(roomType)) {
            sql.append(" AND room_type = ?");
            params.add(roomType);
        }
        if (gender != null && !gender.trim().isEmpty() && !"all".equals(gender)) {
            sql.append(" AND gender = ?");
            params.add(gender);
        }
        if (occupancy != null && !occupancy.trim().isEmpty() && !"all".equals(occupancy)) {
            if ("full".equals(occupancy)) {
                sql.append(" AND current_count = capacity");
            } else if ("not_full".equals(occupancy)) {
                sql.append(" AND current_count >= 1 AND current_count < capacity");
            } else if ("empty".equals(occupancy)) {
                sql.append(" AND current_count = 0");
            }
        }
        if (floor != null && !floor.trim().isEmpty() && !"all".equals(floor)) {
            sql.append(" AND CAST(SUBSTR(SUBSTR(dormitory_number, INSTR(dormitory_number, '-') + 1), 1, 2) AS SIGNED) = ?");
            params.add(Integer.parseInt(floor));
        }
        sql.append(" ORDER BY dormitory_number ASC");

        return jdbcTemplate.query(sql.toString(), new DormitoryRowMapper(), params.toArray());
    }

    @Override
    public List<Dormitory> searchWithPage(String dormitoryNumber, String building, String roomType, String gender, String occupancy, String floor, String sortBy, String sortOrder, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM dormitory WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (dormitoryNumber != null && !dormitoryNumber.trim().isEmpty()) {
            sql.append(" AND dormitory_number LIKE ?");
            params.add("%" + dormitoryNumber + "%");
        }
        if (building != null && !building.trim().isEmpty() && !"all".equals(building)) {
            sql.append(" AND building = ?");
            params.add(building);
        }
        if (roomType != null && !roomType.trim().isEmpty() && !"all".equals(roomType)) {
            sql.append(" AND room_type = ?");
            params.add(roomType);
        }
        if (gender != null && !gender.trim().isEmpty() && !"all".equals(gender)) {
            sql.append(" AND gender = ?");
            params.add(gender);
        }
        if (occupancy != null && !occupancy.trim().isEmpty() && !"all".equals(occupancy)) {
            if ("full".equals(occupancy)) {
                sql.append(" AND current_count = capacity");
            } else if ("not_full".equals(occupancy)) {
                sql.append(" AND current_count >= 1 AND current_count < capacity");
            } else if ("empty".equals(occupancy)) {
                sql.append(" AND current_count = 0");
            }
        }
        if (floor != null && !floor.trim().isEmpty() && !"all".equals(floor)) {
            sql.append(" AND CAST(SUBSTR(SUBSTR(dormitory_number, INSTR(dormitory_number, '-') + 1), 1, 2) AS SIGNED) = ?");
            params.add(Integer.parseInt(floor));
        }

        sql.append(" ORDER BY ");
        if ("dormitoryNumber".equals(sortBy)) {
            sql.append("dormitory_number");
        } else if ("building".equals(sortBy)) {
            sql.append("building");
        } else if ("roomType".equals(sortBy)) {
            sql.append("room_type");
        } else if ("capacity".equals(sortBy)) {
            sql.append("capacity");
        } else if ("currentCount".equals(sortBy)) {
            sql.append("current_count");
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

        return jdbcTemplate.query(sql.toString(), new DormitoryRowMapper(), params.toArray());
    }

    @Override
    public int count(String dormitoryNumber, String building, String roomType, String gender, String occupancy, String floor) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM dormitory WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (dormitoryNumber != null && !dormitoryNumber.trim().isEmpty()) {
            sql.append(" AND dormitory_number LIKE ?");
            params.add("%" + dormitoryNumber + "%");
        }
        if (building != null && !building.trim().isEmpty() && !"all".equals(building)) {
            sql.append(" AND building = ?");
            params.add(building);
        }
        if (roomType != null && !roomType.trim().isEmpty() && !"all".equals(roomType)) {
            sql.append(" AND room_type = ?");
            params.add(roomType);
        }
        if (gender != null && !gender.trim().isEmpty() && !"all".equals(gender)) {
            sql.append(" AND gender = ?");
            params.add(gender);
        }
        if (occupancy != null && !occupancy.trim().isEmpty() && !"all".equals(occupancy)) {
            if ("full".equals(occupancy)) {
                sql.append(" AND current_count = capacity");
            } else if ("not_full".equals(occupancy)) {
                sql.append(" AND current_count >= 1 AND current_count < capacity");
            } else if ("empty".equals(occupancy)) {
                sql.append(" AND current_count = 0");
            }
        }
        if (floor != null && !floor.trim().isEmpty() && !"all".equals(floor)) {
            sql.append(" AND CAST(SUBSTR(SUBSTR(dormitory_number, INSTR(dormitory_number, '-') + 1), 1, 2) AS SIGNED) = ?");
            params.add(Integer.parseInt(floor));
        }

        Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class, params.toArray());
        return count != null ? count : 0;
    }

    @Override
    public Dormitory findById(int id) {
        String sql = "SELECT * FROM dormitory WHERE id = ?";
        try {
            return jdbcTemplate.queryForObject(sql, new DormitoryRowMapper(), id);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public int add(Dormitory dormitory) {
        String sql = "INSERT INTO dormitory (dormitory_number, building, room_type, capacity, current_count, gender, remark) VALUES (?, ?, ?, ?, ?, ?, ?)";
        return jdbcTemplate.update(sql, dormitory.getDormitoryNumber(), dormitory.getBuilding(),
                dormitory.getRoomType(), dormitory.getCapacity(), dormitory.getCurrentCount(),
                dormitory.getGender(), dormitory.getRemark());
    }

    @Override
    public int update(Dormitory dormitory) {
        String sql = "UPDATE dormitory SET dormitory_number = ?, building = ?, room_type = ?, capacity = ?, current_count = ?, gender = ?, remark = ? WHERE id = ?";
        return jdbcTemplate.update(sql, dormitory.getDormitoryNumber(), dormitory.getBuilding(),
                dormitory.getRoomType(), dormitory.getCapacity(), dormitory.getCurrentCount(),
                dormitory.getGender(), dormitory.getRemark(), dormitory.getId());
    }

    @Override
    public int deleteById(int id) {
        String sql = "DELETE FROM dormitory WHERE id = ?";
        return jdbcTemplate.update(sql, id);
    }

    @Override
    public boolean existsByDormitoryNumber(String dormitoryNumber) {
        String sql = "SELECT COUNT(*) FROM dormitory WHERE dormitory_number = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, dormitoryNumber);
        return count != null && count > 0;
    }

    @Override
    public boolean existsByDormitoryNumberExcludeId(String dormitoryNumber, int excludeId) {
        String sql = "SELECT COUNT(*) FROM dormitory WHERE dormitory_number = ? AND id != ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, dormitoryNumber, excludeId);
        return count != null && count > 0;
    }

    @Override
    public int batchDelete(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String sql = "DELETE FROM dormitory WHERE id IN (" + 
                String.join(",", Collections.nCopies(ids.size(), "?")) + ")";
        return jdbcTemplate.update(sql, ids.toArray());
    }

    @Override
    public List<String> getAllBuildings() {
        String sql = "SELECT DISTINCT building FROM dormitory WHERE building IS NOT NULL AND building != '' ORDER BY building";
        return jdbcTemplate.queryForList(sql, String.class);
    }

    @Override
    public Map<String, Integer> getBuildingStatistics() {
        String sql = "SELECT building, COUNT(*) as count FROM dormitory GROUP BY building ORDER BY building";
        Map<String, Integer> stats = new LinkedHashMap<>();

        jdbcTemplate.query(sql, (rs, rowNum) -> {
            stats.put(rs.getString("building"), rs.getInt("count"));
            return null;
        });

        return stats;
    }

    @Override
    public Map<String, Integer> getRoomTypeStatistics() {
        String sql = "SELECT room_type, COUNT(*) as count FROM dormitory GROUP BY room_type ORDER BY room_type";
        Map<String, Integer> stats = new LinkedHashMap<>();

        jdbcTemplate.query(sql, (rs, rowNum) -> {
            stats.put(rs.getString("room_type"), rs.getInt("count"));
            return null;
        });

        return stats;
    }

    @Override
    public Map<String, Integer> getGenderStatistics() {
        String sql = "SELECT gender, COUNT(*) as count FROM dormitory GROUP BY gender ORDER BY gender";
        Map<String, Integer> stats = new LinkedHashMap<>();

        jdbcTemplate.query(sql, (rs, rowNum) -> {
            stats.put(rs.getString("gender"), rs.getInt("count"));
            return null;
        });

        return stats;
    }

    @Override
    public int getTotalCount() {
        String sql = "SELECT COUNT(*) FROM dormitory";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count != null ? count : 0;
    }

    @Override
    public List<Student> getStudentsByDormitoryNumber(String dormitoryNumber) {
        String sql = "SELECT * FROM student WHERE dormitory_number = ? ORDER BY student_id ASC";
        return jdbcTemplate.query(sql, new StudentRowMapper(), dormitoryNumber);
    }

    @Override
    public void updateCurrentCount(String dormitoryNumber) {
        String sql = "UPDATE dormitory SET current_count = (SELECT COUNT(*) FROM student WHERE student.dormitory_number = ?) WHERE dormitory_number = ?";
        jdbcTemplate.update(sql, dormitoryNumber, dormitoryNumber);
    }

    @Override
    public int getMaxFloor() {
        String sql = "SELECT DISTINCT CAST(SUBSTR(SUBSTR(dormitory_number, INSTR(dormitory_number, '-') + 1), 1, 2) AS SIGNED) as floor FROM dormitory ORDER BY floor DESC LIMIT 1";
        try {
            Integer maxFloor = jdbcTemplate.queryForObject(sql, Integer.class);
            return maxFloor != null ? maxFloor : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    @Override
    public List<Integer> getFloorsByBuilding(String building) {
        String sql = "SELECT DISTINCT CAST(SUBSTR(SUBSTR(dormitory_number, INSTR(dormitory_number, '-') + 1), 1, 2) AS SIGNED) as floor FROM dormitory WHERE building = ? ORDER BY floor ASC";
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getInt("floor"), building);
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }

    @Override
    public List<Integer> getAllFloors() {
        String sql = "SELECT DISTINCT CAST(SUBSTR(SUBSTR(dormitory_number, INSTR(dormitory_number, '-') + 1), 1, 2) AS SIGNED) as floor FROM dormitory ORDER BY floor ASC";
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getInt("floor"));
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }

    @Override
    public Map<String, Object> getDormitoryInfo(String dormitoryNumber) {
        String sql = "SELECT * FROM dormitory WHERE dormitory_number = ?";
        try {
            return jdbcTemplate.queryForMap(sql, dormitoryNumber);
        } catch (Exception e) {
            return java.util.Collections.emptyMap();
        }
    }

    private static class DormitoryRowMapper implements RowMapper<Dormitory> {
        @Override
        public Dormitory mapRow(ResultSet rs, int rowNum) throws SQLException {
            Dormitory dormitory = new Dormitory();
            dormitory.setId(rs.getInt("id"));
            dormitory.setDormitoryNumber(rs.getString("dormitory_number"));
            dormitory.setBuilding(rs.getString("building"));
            dormitory.setRoomType(rs.getString("room_type"));
            dormitory.setCapacity(rs.getInt("capacity"));
            dormitory.setCurrentCount(rs.getInt("current_count"));
            dormitory.setGender(rs.getString("gender"));
            dormitory.setRemark(rs.getString("remark"));
            return dormitory;
        }
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
            return student;
        }
    }
}
