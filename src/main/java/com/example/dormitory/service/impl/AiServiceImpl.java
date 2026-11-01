package com.example.dormitory.service.impl;

import com.example.dormitory.service.AiService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

@Service
public class AiServiceImpl implements AiService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${deepseek.api.key:}")
    private String apiKey;

    @Value("${deepseek.api.url:https://api.deepseek.com/v1/chat/completions}")
    private String apiUrl;

    @Value("${deepseek.api.model:deepseek-flash}")
    private String model;

    private final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .callTimeout(35, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    @Override
    public Map<String,Object> status() {
        return Map.of("configured", configured(), "provider", "DeepSeek", "model", model);
    }

    private boolean configured() { return apiKey != null && !apiKey.isBlank() && !apiKey.equals("sk-xxx"); }

    @Override public String chat(String message) { return query(message).reply(); }

    @Override
    public Answer query(String message) {
        Map<String,Object> statistics = getAllStatistics();
        String generatedAt = java.time.Instant.now().toString();
        if (!configured()) return localAnswer(message,statistics,generatedAt,"未配置 DeepSeek，当前使用本地实时统计。");
        try {
            String prompt = "你只负责识别宿舍统计问题的意图，不查询数据库，不回答数据，不生成SQL。"
                    + "仅输出JSON，格式为 {\"metric\":\"summary\"}。metric只允许："
                    + "summary(概况)、students(学生总数)、male(男生)、female(女生)、grades(年级分布)、"
                    + "rooms(房间总数)、occupancy(入住率)、capacity(剩余容量)、buildings(楼栋对比)、"
                    + "repairs(报修状态和类型)、unsupported(个人明细、日期区间、修改操作或不支持的筛选)。"
                    + "问题需要姓名、学号、具体楼栋或日期筛选时返回unsupported。";
            String json = new ObjectMapper().writeValueAsString(Map.of("model",model,
                    "messages",List.of(Map.of("role","system","content",prompt),Map.of("role","user","content",message)),
                    "max_tokens",150,"response_format",Map.of("type","json_object"),"thinking",Map.of("type","disabled")));
            Request request = new Request.Builder().url(apiUrl).post(RequestBody.create(json,MediaType.parse("application/json")))
                    .header("Authorization","Bearer " + apiKey).build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body()!=null) {
                    JsonNode root = new ObjectMapper().readTree(response.body().string());
                    String reply = root.path("choices").path(0).path("message").path("content").asText("");
                    String finish = root.path("choices").path(0).path("finish_reason").asText("");
                    if (!reply.isBlank() && !finish.equals("length")) {
                        String metric = new ObjectMapper().readTree(reply).path("metric").asText("");
                        String keyword = switch(metric) {
                            case "summary" -> "概况";
                            case "students" -> "学生总数";
                            case "male" -> "男生";
                            case "female" -> "女生";
                            case "grades" -> "年级";
                            case "rooms" -> "宿舍总数";
                            case "occupancy" -> "入住率";
                            case "capacity" -> "容量";
                            case "buildings" -> "楼栋";
                            case "repairs" -> "报修";
                            case "unsupported" -> "不支持";
                            default -> null;
                        };
                        if(keyword != null) return new Answer(generateLocalResponse(keyword,statistics),"deepseek",
                                "DeepSeek 识别查询意图；统计结果由本地数据库生成。",generatedAt,statistics);
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            // 不记录请求正文、凭据和供应商错误正文。
        }
        return localAnswer(message,statistics,generatedAt,"DeepSeek 暂不可用，已返回本地实时统计，请检查配置或稍后重试。");
    }

    private Answer localAnswer(String message,Map<String,Object> statistics,String time,String notice) {
        return new Answer(generateLocalResponse(message,statistics),"local",notice,time,statistics);
    }

    private String generateLocalResponse(String message, Map<String, Object> statistics) {
        String lowerMessage = message.toLowerCase(Locale.ROOT);
        if (lowerMessage.contains("不支持")) return "当前支持全校概况、学生与年级分布、楼栋对比、容量和报修统计。暂不支持个人明细、日期区间及指定楼栋筛选，也不会修改数据。请使用快捷问题查询。";
        if (lowerMessage.contains("报修") || lowerMessage.contains("工单"))
            return "报修总数：" + statistics.get("totalRepairs") + "；状态分布：" + statistics.get("repairStatus")
                    + "；类型分布：" + statistics.get("repairTypes");
        if (lowerMessage.contains("床位") || lowerMessage.contains("容量"))
            return "房间总容量：" + statistics.get("totalCapacity") + "；当前入住：" + statistics.get("residentCount")
                    + "；剩余房间容量：" + statistics.get("remainingCapacity") + "（未扣除维修、停用和预留限制）。";
        
        if (lowerMessage.contains("统计") || lowerMessage.contains("概况") || lowerMessage.contains("摘要")) {
            return summary(statistics);
        }
        
        if (lowerMessage.contains("学生") && lowerMessage.contains("总数")) {
            return "总学生数：" + statistics.get("totalStudents") + "人";
        }
        
        if (lowerMessage.contains("宿舍") && lowerMessage.contains("总数")) {
            return "总宿舍数：" + statistics.get("totalDormitories") + "间";
        }
        
        if (lowerMessage.contains("男生")) {
            return "男生人数：" + statistics.get("maleCount") + "人";
        }
        
        if (lowerMessage.contains("女生")) {
            return "女生人数：" + statistics.get("femaleCount") + "人";
        }
        
        if (lowerMessage.contains("年级") || lowerMessage.contains("分布")) {
            return "年级分布：" + statistics.get("gradeDistribution");
        }
        
        if (lowerMessage.contains("入住率")) {
            return "入住率：" + statistics.get("occupancyRate") + "%";
        }
        
        if (lowerMessage.contains("空闲") || lowerMessage.contains("空")) {
            return "空闲宿舍：" + statistics.get("emptyDormitories") + "间";
        }
        
        if (lowerMessage.contains("楼栋") || lowerMessage.contains("楼")) {
            String buildingDetails = (String) statistics.get("buildingDetails");
            if (buildingDetails != null && !buildingDetails.isEmpty()) {
                return buildingDetails;
            }
            return "楼栋分布：" + statistics.get("buildingDistribution");
        }
        
        if (lowerMessage.contains("房间类型") || lowerMessage.contains("类型")) {
            return "房间类型分布：" + statistics.get("roomTypeDistribution");
        }
        
        StringBuilder response = new StringBuilder();
        response.append("根据系统数据，以下是宿舍管理系统的统计信息：\n\n");
        response.append("【关键概念定义】\n");
        response.append("- 空闲宿舍：当前住宿人数为0，没有人入住\n");
        response.append("- 已住满宿舍：当前住宿人数等于宿舍容量，达到最大住宿人数\n");
        response.append("- 未住满宿舍：当前住宿人数大于0但小于宿舍容量，有人入住但没达到最大住宿人数\n");
        response.append("- 已入住宿舍：包含已住满和未住满的宿舍\n\n");
        response.append(summary(statistics));
        String buildingDetails = (String) statistics.get("buildingDetails");
        if (buildingDetails != null && !buildingDetails.isEmpty()) {
            response.append("\n").append(buildingDetails);
        }
        response.append("\n\n如果您需要更详细的信息，请告诉我您想了解哪方面的数据。");
        return response.toString();
    }

    @Override
    public String getStatisticsSummary() {
        return summary(getAllStatistics());
    }

    private String summary(Map<String,Object> stats) {
        return String.format(
                "宿舍管理系统统计摘要：\n" +
                "- 总学生数：%d人\n" +
                "- 总宿舍数：%d间\n" +
                "- 男生人数：%d人\n" +
                "- 女生人数：%d人\n" +
                "- 空闲宿舍：%d间\n" +
                "- 已入住宿舍：%d间\n" +
                "- 入住率：%.1f%%",
                stats.get("totalStudents"),
                stats.get("totalDormitories"),
                stats.get("maleCount"),
                stats.get("femaleCount"),
                stats.get("emptyDormitories"),
                stats.get("occupiedDormitories"),
                Double.parseDouble((String) stats.get("occupancyRate"))
        );
    }

    @Override
    public Map<String, Object> getStudentStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();

        String totalSql = "SELECT COUNT(*) FROM student";
        stats.put("totalStudents", jdbcTemplate.queryForObject(totalSql, Integer.class));

        String maleSql = "SELECT COUNT(*) FROM student WHERE gender = '男'";
        stats.put("maleCount", jdbcTemplate.queryForObject(maleSql, Integer.class));

        String femaleSql = "SELECT COUNT(*) FROM student WHERE gender = '女'";
        stats.put("femaleCount", jdbcTemplate.queryForObject(femaleSql, Integer.class));

        String gradeSql = "SELECT grade, COUNT(*) as count FROM student GROUP BY grade";
        List<Map<String, Object>> gradeResults = jdbcTemplate.queryForList(gradeSql);
        Map<String, Integer> gradeMap = new LinkedHashMap<>();
        gradeMap.put("大一", 0);
        gradeMap.put("大二", 0);
        gradeMap.put("大三", 0);
        gradeMap.put("大四", 0);
        for (Map<String, Object> result : gradeResults) {
            String grade = (String) result.get("grade");
            Integer count = ((Number) result.get("count")).intValue();
            if (grade != null && count != null) {
                gradeMap.put(grade, count);
            }
        }
        stats.put("gradeDistribution", gradeMap);

        return stats;
    }

    @Override
    public Map<String, Object> getDormitoryStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();

        String totalSql = "SELECT COUNT(*) FROM dormitory";
        stats.put("totalDormitories", jdbcTemplate.queryForObject(totalSql, Integer.class));

        String occupiedSql = "SELECT COUNT(*) FROM dormitory WHERE current_count > 0";
        stats.put("occupiedDormitories", jdbcTemplate.queryForObject(occupiedSql, Integer.class));

        String emptySql = "SELECT COUNT(*) FROM dormitory WHERE current_count = 0";
        stats.put("emptyDormitories", jdbcTemplate.queryForObject(emptySql, Integer.class));

        String buildingSql = "SELECT building, COUNT(*) as count FROM dormitory GROUP BY building";
        List<Map<String, Object>> buildingResults = jdbcTemplate.queryForList(buildingSql);
        Map<String, Integer> buildingMap = new LinkedHashMap<>();
        for (Map<String, Object> result : buildingResults) {
            String building = (String) result.get("building");
            Integer count = ((Number) result.get("count")).intValue();
            if (building != null && count != null) {
                buildingMap.put(building, count);
            }
        }
        stats.put("buildingDistribution", buildingMap);

        String roomTypeSql = "SELECT room_type, COUNT(*) as count FROM dormitory GROUP BY room_type";
        List<Map<String, Object>> roomTypeResults = jdbcTemplate.queryForList(roomTypeSql);
        Map<String, Integer> roomTypeMap = new LinkedHashMap<>();
        for (Map<String, Object> result : roomTypeResults) {
            String roomType = (String) result.get("room_type");
            Integer count = ((Number) result.get("count")).intValue();
            if (roomType != null && count != null) {
                roomTypeMap.put(roomType, count);
            }
        }
        stats.put("roomTypeDistribution", roomTypeMap);

        String genderSql = "SELECT gender, COUNT(*) as count FROM dormitory GROUP BY gender";
        List<Map<String, Object>> genderResults = jdbcTemplate.queryForList(genderSql);
        Map<String, Integer> genderMap = new LinkedHashMap<>();
        genderMap.put("男", 0);
        genderMap.put("女", 0);
        for (Map<String, Object> result : genderResults) {
            String gender = (String) result.get("gender");
            Integer count = ((Number) result.get("count")).intValue();
            if (gender != null && count != null) {
                genderMap.put(gender, count);
            }
        }
        stats.put("genderDistribution", genderMap);

        return stats;
    }

    private Map<String, Object> getAllStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.putAll(getStudentStatistics());
        stats.putAll(getDormitoryStatistics());
        
        String fullDormitorySql = "SELECT COUNT(*) FROM dormitory WHERE current_count = capacity";
        Integer fullCount = jdbcTemplate.queryForObject(fullDormitorySql, Integer.class);
        stats.put("fullDormitories", fullCount != null ? fullCount : 0);
        
        String partlyOccupiedSql = "SELECT COUNT(*) FROM dormitory WHERE current_count > 0 AND current_count < capacity";
        Integer partlyCount = jdbcTemplate.queryForObject(partlyOccupiedSql, Integer.class);
        stats.put("partlyOccupiedDormitories", partlyCount != null ? partlyCount : 0);
        
        String notFullSql = "SELECT COUNT(*) FROM dormitory WHERE current_count < capacity";
        Integer notFullCount = jdbcTemplate.queryForObject(notFullSql, Integer.class);
        stats.put("notFullDormitories", notFullCount != null ? notFullCount : 0);
        
        String occupancyRateSql = "SELECT SUM(current_count) * 100.0 / NULLIF(SUM(capacity), 0) FROM dormitory";
        Double occupancyRate = jdbcTemplate.queryForObject(occupancyRateSql, Double.class);
        stats.put("occupancyRate", occupancyRate != null ? String.format(Locale.ROOT,"%.1f", occupancyRate) : "0.0");
        
        stats.put("buildingDetails", getBuildingDetails());
        stats.put("totalCapacity", jdbcTemplate.queryForObject("SELECT COALESCE(SUM(capacity),0) FROM dormitory",Long.class));
        stats.put("residentCount", jdbcTemplate.queryForObject("SELECT COALESCE(SUM(current_count),0) FROM dormitory",Long.class));
        stats.put("remainingCapacity", ((Number)stats.get("totalCapacity")).longValue()-((Number)stats.get("residentCount")).longValue());
        stats.put("totalRepairs", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM repair_request",Long.class));
        stats.put("repairStatus", jdbcTemplate.queryForList("SELECT status,COUNT(*) AS count FROM repair_request GROUP BY status ORDER BY status"));
        stats.put("repairTypes", jdbcTemplate.queryForList("SELECT repair_type,COUNT(*) AS count FROM repair_request GROUP BY repair_type ORDER BY repair_type"));
        
        return stats;
    }

    private String getBuildingDetails() {
        var rows=jdbcTemplate.queryForList("SELECT b.name,b.gender,COUNT(d.id) AS rooms,COALESCE(SUM(d.capacity),0) AS capacity,"
                + "COALESCE(SUM(d.current_count),0) AS residents FROM building b LEFT JOIN dormitory d ON d.building=b.name GROUP BY b.id,b.name,b.gender ORDER BY b.name");
        StringBuilder text=new StringBuilder();
        for(var row:rows) text.append(row.get("name")).append("：房间 ").append(row.get("rooms"))
                .append("，容量 ").append(row.get("capacity")).append("，入住 ").append(row.get("residents")).append("\n");
        return text.toString();
    }
}
