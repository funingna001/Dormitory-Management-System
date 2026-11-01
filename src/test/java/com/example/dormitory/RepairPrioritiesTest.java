package com.example.dormitory;

import com.example.dormitory.business.RepairPriorities;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RepairPrioritiesTest {
    @Test void hazardsBecomeUrgentWithAnExplanation() {
        for(String issue:new String[]{"插座漏电", "灯具冒烟并有火花", "水管爆裂", "卫生间污水倒灌", "人员被困无法开门", "全宿舍停电"}) {
            var result=RepairPriorities.assess("其他",issue);
            assertThat(result.priority()).as(issue).isEqualTo("URGENT");
            assertThat(result.reason()).contains("规则 v1", "优先");
        }
    }
    @Test void routineFaultsAndNegatedHazardsAreOrdinary() {
        for(String issue:new String[]{"灯泡不亮", "桌椅螺丝松动", "没有漏电，灯泡坏了", "未发现冒烟", "没有冒烟", "只是灯罩松动"}) {
            assertThat(RepairPriorities.assess("灯具",issue).priority()).as(issue).isEqualTo("NORMAL");
        }
    }
    @Test void anotherHazardStillEscalatesAfterANegatedOne() {
        assertThat(RepairPriorities.assess("灯具","没有漏电，但是有冒烟").priority()).isEqualTo("URGENT");
        assertThat(RepairPriorities.assess(null,null).priority()).isEqualTo("NORMAL");
    }
}
