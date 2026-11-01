package com.example.dormitory.business;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.regex.Pattern;

/** 本地规则仅用于工单分流，不把故障描述发送给外部服务。 */
public final class RepairPriorities {
    private RepairPriorities() {}
    public record Assessment(String priority, String reason) {}
    private static final List<String> URGENT = List.of("漏电", "触电", "冒烟", "火花", "烧焦", "起火", "着火",
            "燃气泄漏", "煤气泄漏", "天然气泄漏", "水管爆裂", "爆管", "严重漏水", "污水倒灌", "马桶溢水",
            "大面积停电", "全寝停电", "全宿舍停电", "整间宿舍没电", "被困", "无法开门");
    private static final Pattern NEGATION = Pattern.compile("(?:没有|并无|未发现|未见|不存在|不是|无)(?:明显|发生|出现)?$");

    /** 常见紧急特征命中即升级；仅识别紧邻关键词的简单否定，不分析图片或复杂语义。 */
    public static Assessment assess(String type, String description) {
        String text = Objects.toString(type, "") + "；" + Objects.toString(description, "");
        for (String clause : text.split("[，。；,;！!？?\\n]")) {
            for (String keyword : URGENT) {
                int start = 0, at;
                while ((at = clause.indexOf(keyword, start)) >= 0) {
                    if (!NEGATION.matcher(clause.substring(0, at).strip()).find())
                        return new Assessment("URGENT", "规则 v1：检测到“" + keyword + "”，建议优先核实和处理");
                    start = at + keyword.length();
                }
            }
        }
        // 未命中规则不代表没有风险，保留工作人员根据现场情况调整的入口。
        return new Assessment("NORMAL", "规则 v1：未命中紧急特征，按普通工单处理；工作人员可根据实际情况调整");
    }

    /** 兼容已有 MySQL / SQLite：独立保存判断依据，不要求应用账号具有 ALTER 权限。 */
    public static void initialize(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO repair_work(repair_id,updated_at) SELECT r.id,? FROM repair_request r WHERE NOT EXISTS (SELECT 1 FROM repair_work w WHERE w.repair_id=r.id)", java.time.LocalDateTime.now().toString());
        // 只接入没有判断记录的历史工单，重复启动不会覆盖已保存的自动或人工决定。
        for (var row : jdbc.queryForList("SELECT r.id,r.repair_type,r.description,r.status,w.assignee,w.priority FROM repair_request r JOIN repair_work w ON w.repair_id=r.id WHERE NOT EXISTS(SELECT 1 FROM repair_priority p WHERE p.repair_id=r.id)")) {
            var assessment = assess((String)row.get("repair_type"), (String)row.get("description"));
            boolean pending = "待处理".equals(row.get("status")) && Objects.toString(row.get("assignee"), "").isBlank();
            if (pending) jdbc.update("UPDATE repair_work SET priority=? WHERE repair_id=?", assessment.priority(), row.get("id"));
            jdbc.update("INSERT INTO repair_priority(repair_id,priority_source,priority_reason,suggested_priority) VALUES(?,?,?,?)", row.get("id"), pending ? "AUTO" : "MANUAL",
                    pending ? assessment.reason() : "保留升级前已派单或已处理工单的优先级", assessment.priority());
        }
    }
}
