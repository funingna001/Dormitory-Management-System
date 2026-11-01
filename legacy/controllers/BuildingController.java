package com.example.dormitory.controller;

import com.example.dormitory.entity.Building;
import com.example.dormitory.entity.User;
import com.example.dormitory.service.BuildingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/buildings")
public class BuildingController {

    @Autowired
    private BuildingService buildingService;

    @GetMapping
    public String list(HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        model.addAttribute("user", user);

        List<Building> buildings = buildingService.findAll();
        model.addAttribute("buildings", buildings);

        return "building-list";
    }

    @GetMapping("/add")
    public String add(HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        model.addAttribute("user", user);
        model.addAttribute("building", new Building());
        
        int nextNumber = buildingService.getNextBuildingNumber();
        model.addAttribute("nextBuildingName", nextNumber + "号楼");

        return "building-add";
    }

    @PostMapping("/add")
    public String doAdd(@ModelAttribute Building building, @RequestParam int floorCount,
                        @RequestParam(required = false, defaultValue = "10") int roomsPerFloor,
                        @RequestParam(required = false, defaultValue = "asc") String sortMode,
                        @RequestParam Map<String, String> params,
                        HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }

        if (building.getGender() == null || building.getGender().trim().isEmpty()) {
            model.addAttribute("error", "请选择性别限制");
            model.addAttribute("building", building);
            int nextNumber = buildingService.getNextBuildingNumber();
            model.addAttribute("nextBuildingName", nextNumber + "号楼");
            return "building-add";
        }

        if (floorCount < 1 || floorCount > 30) {
            model.addAttribute("error", "楼层数量必须在1-30之间");
            model.addAttribute("building", building);
            int nextNumber = buildingService.getNextBuildingNumber();
            model.addAttribute("nextBuildingName", nextNumber + "号楼");
            return "building-add";
        }

        String configMode = params.getOrDefault("configMode", "default");
        String floorConfigsJson = params.getOrDefault("floorConfigsJson", "{}");
        
        int[] doubleRoomsPerFloor = new int[floorCount];
        int[] fourRoomsPerFloor = new int[floorCount];
        int[] sixRoomsPerFloor = new int[floorCount];
        
        if ("default".equals(configMode)) {
            int fourRooms = (int) Math.ceil((double) roomsPerFloor / 2);
            int sixRooms = roomsPerFloor - fourRooms;
            if ("desc".equals(sortMode)) {
                int temp = fourRooms;
                fourRooms = sixRooms;
                sixRooms = temp;
            }
            for (int i = 0; i < floorCount; i++) {
                doubleRoomsPerFloor[i] = 0;
                fourRoomsPerFloor[i] = fourRooms;
                sixRoomsPerFloor[i] = sixRooms;
            }
        } else {
            try {
                com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(floorConfigsJson);
                for (int i = 1; i <= floorCount; i++) {
                    com.fasterxml.jackson.databind.JsonNode floorNode = root.get(String.valueOf(i));
                    int doubleCount = 0, fourCount = 0, sixCount = 0;
                    
                    if (floorNode != null && floorNode.isArray()) {
                        for (int j = 0; j < floorNode.size(); j++) {
                            String type = floorNode.get(j).asText();
                            if ("double".equals(type)) doubleCount++;
                            else if ("four".equals(type)) fourCount++;
                            else if ("six".equals(type)) sixCount++;
                        }
                    } else {
                        fourCount = (int) Math.ceil((double) roomsPerFloor / 2);
                        sixCount = roomsPerFloor - fourCount;
                    }
                    
                    doubleRoomsPerFloor[i - 1] = doubleCount;
                    fourRoomsPerFloor[i - 1] = fourCount;
                    sixRoomsPerFloor[i - 1] = sixCount;
                    
                    int totalRooms = doubleRoomsPerFloor[i - 1] + fourRoomsPerFloor[i - 1] + sixRoomsPerFloor[i - 1];
                    if (totalRooms != roomsPerFloor) {
                        model.addAttribute("error", "第" + i + "层宿舍总数必须等于每层房间数量");
                        model.addAttribute("building", building);
                        int nextNumber = buildingService.getNextBuildingNumber();
                        model.addAttribute("nextBuildingName", nextNumber + "号楼");
                        return "building-add";
                    }
                }
            } catch (Exception e) {
                model.addAttribute("error", "配置数据解析失败");
                model.addAttribute("building", building);
                int nextNumber = buildingService.getNextBuildingNumber();
                model.addAttribute("nextBuildingName", nextNumber + "号楼");
                return "building-add";
            }
        }

        int nextNumber = buildingService.getNextBuildingNumber();
        String nextName = nextNumber + "号楼";
        building.setName(nextName);

        buildingService.addBuildingWithDormitories(building, floorCount, doubleRoomsPerFloor, fourRoomsPerFloor, sixRoomsPerFloor, sortMode);
        return "redirect:/buildings";
    }

    @GetMapping("/edit/{id}")
    public String edit(@PathVariable Integer id, HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }

        Building building = buildingService.findById(id);
        if (building == null) {
            return "redirect:/buildings";
        }

        int floorCount = buildingService.getFloorCount(building.getName());
        int roomsPerFloor = buildingService.getRoomsPerFloor(building.getName());
        
        if (floorCount <= 0) floorCount = 5;
        if (roomsPerFloor <= 0) roomsPerFloor = 10;
        
        StringBuilder existingConfigsJson = new StringBuilder("{");
        for (int i = 1; i <= floorCount; i++) {
            List<String> roomTypes = buildingService.getRoomTypesByFloor(building.getName(), i);
            existingConfigsJson.append("\"").append(i).append("\": [");
            for (int j = 0; j < roomTypes.size(); j++) {
                if (j > 0) existingConfigsJson.append(",");
                String type = roomTypes.get(j);
                if ("二人间".equals(type)) {
                    existingConfigsJson.append("\"double\"");
                } else if ("四人间".equals(type)) {
                    existingConfigsJson.append("\"four\"");
                } else if ("六人间".equals(type)) {
                    existingConfigsJson.append("\"six\"");
                }
            }
            existingConfigsJson.append("]");
            if (i < floorCount) existingConfigsJson.append(",");
        }
        existingConfigsJson.append("}");
        
        model.addAttribute("user", user);
        model.addAttribute("building", building);
        model.addAttribute("floorCount", floorCount);
        model.addAttribute("roomsPerFloor", roomsPerFloor);
        model.addAttribute("existingConfigsJson", existingConfigsJson.toString());

        return "building-edit";
    }

    @PostMapping("/edit/{id}")
    public String doEdit(@PathVariable Integer id, @ModelAttribute Building building, 
                         @RequestParam int floorCount,
                         @RequestParam(required = false, defaultValue = "10") int roomsPerFloor,
                         @RequestParam(required = false, defaultValue = "asc") String sortMode,
                         @RequestParam Map<String, String> params,
                         HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }

        Building existing = buildingService.findById(id);
        if (existing == null) {
            return "redirect:/buildings";
        }

        if (building.getName() == null || building.getName().trim().isEmpty()) {
            model.addAttribute("error", "楼栋名称不能为空");
            model.addAttribute("building", building);
            model.addAttribute("floorCount", floorCount);
            model.addAttribute("roomsPerFloor", roomsPerFloor);
            return "building-edit";
        }

        if (building.getGender() == null || building.getGender().trim().isEmpty()) {
            model.addAttribute("error", "请选择性别限制");
            model.addAttribute("building", building);
            model.addAttribute("floorCount", floorCount);
            model.addAttribute("roomsPerFloor", roomsPerFloor);
            return "building-edit";
        }

        if (floorCount < 1 || floorCount > 30) {
            model.addAttribute("error", "楼层数量必须在1-30之间");
            model.addAttribute("building", building);
            model.addAttribute("floorCount", floorCount);
            model.addAttribute("roomsPerFloor", roomsPerFloor);
            return "building-edit";
        }

        if (!existing.getName().equals(building.getName()) && buildingService.existsByName(building.getName())) {
            model.addAttribute("error", "楼栋名称已存在");
            model.addAttribute("building", building);
            model.addAttribute("floorCount", floorCount);
            model.addAttribute("roomsPerFloor", roomsPerFloor);
            return "building-edit";
        }

        String configMode = params.getOrDefault("configMode", "default");
        String floorConfigsJson = params.getOrDefault("floorConfigsJson", "{}");
        
        int[] doubleRoomsPerFloor = new int[floorCount];
        int[] fourRoomsPerFloor = new int[floorCount];
        int[] sixRoomsPerFloor = new int[floorCount];
        
        if ("default".equals(configMode)) {
            int fourRooms = (int) Math.ceil((double) roomsPerFloor / 2);
            int sixRooms = roomsPerFloor - fourRooms;
            if ("desc".equals(sortMode)) {
                int temp = fourRooms;
                fourRooms = sixRooms;
                sixRooms = temp;
            }
            for (int i = 0; i < floorCount; i++) {
                doubleRoomsPerFloor[i] = 0;
                fourRoomsPerFloor[i] = fourRooms;
                sixRoomsPerFloor[i] = sixRooms;
            }
        } else {
            try {
                com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(floorConfigsJson);
                for (int i = 1; i <= floorCount; i++) {
                    com.fasterxml.jackson.databind.JsonNode floorNode = root.get(String.valueOf(i));
                    int doubleCount = 0, fourCount = 0, sixCount = 0;
                    
                    if (floorNode != null && floorNode.isArray()) {
                        for (int j = 0; j < floorNode.size(); j++) {
                            String type = floorNode.get(j).asText();
                            if ("double".equals(type)) doubleCount++;
                            else if ("four".equals(type)) fourCount++;
                            else if ("six".equals(type)) sixCount++;
                        }
                    } else {
                        fourCount = (int) Math.ceil((double) roomsPerFloor / 2);
                        sixCount = roomsPerFloor - fourCount;
                    }
                    
                    doubleRoomsPerFloor[i - 1] = doubleCount;
                    fourRoomsPerFloor[i - 1] = fourCount;
                    sixRoomsPerFloor[i - 1] = sixCount;
                    
                    int totalRooms = doubleRoomsPerFloor[i - 1] + fourRoomsPerFloor[i - 1] + sixRoomsPerFloor[i - 1];
                    if (totalRooms != roomsPerFloor) {
                        model.addAttribute("error", "第" + i + "层宿舍总数必须等于每层房间数量");
                        model.addAttribute("building", building);
                        model.addAttribute("floorCount", floorCount);
                        model.addAttribute("roomsPerFloor", roomsPerFloor);
                        return "building-edit";
                    }
                }
            } catch (Exception e) {
                model.addAttribute("error", "配置数据解析失败");
                model.addAttribute("building", building);
                model.addAttribute("floorCount", floorCount);
                model.addAttribute("roomsPerFloor", roomsPerFloor);
                return "building-edit";
            }
        }

        building.setId(id);
        buildingService.updateBuildingWithDormitories(building, floorCount, doubleRoomsPerFloor, fourRoomsPerFloor, sixRoomsPerFloor, sortMode);

        return "redirect:/buildings";
    }

    @GetMapping("/delete/{id}")
    public String delete(@PathVariable Integer id, HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }

        buildingService.deleteBuilding(id);
        return "redirect:/buildings";
    }

    @GetMapping("/api/list")
    @ResponseBody
    public List<Building> getBuildingList() {
        return buildingService.findAll();
    }
}