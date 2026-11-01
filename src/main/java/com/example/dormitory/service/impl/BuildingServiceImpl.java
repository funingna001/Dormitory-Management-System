package com.example.dormitory.service.impl;

import com.example.dormitory.entity.Building;
import com.example.dormitory.entity.BuildingRowMapper;
import com.example.dormitory.service.BuildingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BuildingServiceImpl implements BuildingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public void addBuilding(Building building) {
        String sql = "INSERT INTO building (name, gender) VALUES (?, ?)";
        jdbcTemplate.update(sql, building.getName(), building.getGender());
    }



    @Override
    public void updateBuilding(Building building) {
        String sql = "UPDATE building SET name = ?, gender = ? WHERE id = ?";
        jdbcTemplate.update(sql, building.getName(), building.getGender(), building.getId());
    }



    @Override
    public Building findById(Integer id) {
        String sql = "SELECT * FROM building WHERE id = ?";
        try {
            return jdbcTemplate.queryForObject(sql, new BuildingRowMapper(), id);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<Building> findAll() {
        String sql = "SELECT * FROM building ORDER BY name ASC";
        return jdbcTemplate.query(sql, new BuildingRowMapper());
    }

    @Override
    public boolean existsByName(String name) {
        String sql = "SELECT COUNT(*) FROM building WHERE name = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, name);
        return count != null && count > 0;
    }

    @Override
    public int getNextBuildingNumber() {
        List<Building> buildings = findAll();
        if (buildings.isEmpty()) {
            return 1;
        }
        int maxNumber = 0;
        Pattern pattern = Pattern.compile("(\\d+)");
        for (Building building : buildings) {
            String name = building.getName();
            if (name != null) {
                Matcher matcher = pattern.matcher(name);
                if (matcher.find()) {
                    int num = Integer.parseInt(matcher.group(1));
                    if (num > maxNumber) {
                        maxNumber = num;
                    }
                }
            }
        }
        return maxNumber + 1;
    }

    @Override
    public int getFloorCount(String buildingName) {
        String sql = "SELECT DISTINCT CAST(SUBSTR(SUBSTR(dormitory_number, INSTR(dormitory_number, '-') + 1), 1, 2) AS SIGNED) as floor FROM dormitory WHERE building = ? ORDER BY floor DESC LIMIT 1";
        try {
            Integer maxFloor = jdbcTemplate.queryForObject(sql, Integer.class, buildingName);
            return maxFloor != null ? maxFloor : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public int getRoomsPerFloor(String buildingName) {
        String sql = "SELECT COUNT(*) as count FROM dormitory WHERE building = ? AND SUBSTR(SUBSTR(dormitory_number, INSTR(dormitory_number, '-') + 1), 1, 2) = '01'";
        try {
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, buildingName);
            return count != null ? count : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public List<String> getRoomTypesByFloor(String buildingName, int floor) {
        String sql = "SELECT room_type FROM dormitory WHERE building = ? AND CAST(SUBSTR(SUBSTR(dormitory_number, INSTR(dormitory_number, '-') + 1), 1, 2) AS SIGNED) = ? ORDER BY dormitory_number ASC";
        try {
            return jdbcTemplate.queryForList(sql, String.class, buildingName, floor);
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }


}