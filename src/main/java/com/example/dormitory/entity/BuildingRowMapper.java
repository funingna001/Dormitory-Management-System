package com.example.dormitory.entity;

import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

public class BuildingRowMapper implements RowMapper<Building> {
    @Override
    public Building mapRow(ResultSet rs, int rowNum) throws SQLException {
        Building building = new Building();
        building.setId(rs.getInt("id"));
        building.setName(rs.getString("name"));
        building.setGender(rs.getString("gender"));
        return building;
    }
}