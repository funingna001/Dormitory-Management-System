package com.example.dormitory;

import com.example.dormitory.business.RepairPriorities;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

class RepairPriorityIntegrationTest extends IntegrationSupport {
    @org.springframework.beans.factory.annotation.Autowired com.example.dormitory.service.ExportService exports;
    @Test void exportedOrderContainsPriorityAndExplanation() throws Exception {
        long id=submit();
        var out=new java.io.ByteArrayOutputStream();
        exports.write("all",out);
        try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(out.toByteArray()))) {
            var sheet=workbook.getSheet("报修工单");
            var header=sheet.getRow(0);
            int priority=-1,source=-1,reason=-1;
            for(var cell:header) {
                if(cell.getStringCellValue().equals("优先级"))priority=cell.getColumnIndex();
                if(cell.getStringCellValue().equals("判断来源"))source=cell.getColumnIndex();
                if(cell.getStringCellValue().equals("判断依据"))reason=cell.getColumnIndex();
            }
            assertThat(priority).isGreaterThanOrEqualTo(0); assertThat(source).isGreaterThanOrEqualTo(0); assertThat(reason).isGreaterThanOrEqualTo(0);
            boolean found=false;
            for(int n=1;n<=sheet.getLastRowNum();n++)if(sheet.getRow(n).getCell(0).getStringCellValue().equals(Long.toString(id))) {
                var row=sheet.getRow(n); found=true;
                assertThat(row.getCell(priority).getStringCellValue()).isEqualTo("URGENT");
                assertThat(row.getCell(source).getStringCellValue()).isEqualTo("AUTO");
                assertThat(row.getCell(reason).getStringCellValue()).contains("冒烟");
            }
            assertThat(found).isTrue();
        }
    }
    private long submit() throws Exception {
        var response=mvc.perform(multipart("/api/repairs").param("repairType","灯具").param("description","插座冒烟，有火花")
                .with(user("20260001").roles("STUDENT")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.priority").value("URGENT"))
                .andExpect(jsonPath("$.prioritySource").value("AUTO")).andReturn().getResponse();
        return mapper.readTree(response.getContentAsString()).get("id").asLong();
    }
    private void dispatch(long id,String priority) throws Exception {
        String field=priority==null?"":",\"priority\":\""+priority+"\"";
        mvc.perform(post("/api/business/repairs/"+id+"/dispatch").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content("{\"assignee\":\"worker\",\"note\":\"现场核实并安排维修\""+field+"}"))
                .andExpect(status().isOk());
    }
    @Test void urgentSubmissionSortsFirstAndNotifiesDispatchers() throws Exception {
        long id=submit();
        mvc.perform(get("/api/business/lists/repairs").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.items[0].priority_reason").isNotEmpty());
        assertThat(jdbc.queryForObject("SELECT content FROM notification WHERE recipient='admin' AND title='报修待派单'",String.class)).contains("紧急");
    }
    @Test void automaticDispatchManualOverrideAndReturnToAutomaticAreAudited() throws Exception {
        long id=submit(); dispatch(id,null);
        assertThat(jdbc.queryForObject("SELECT priority FROM repair_work WHERE repair_id=?",String.class,id)).isEqualTo("URGENT");
        dispatch(id,"NORMAL");
        assertThat(jdbc.queryForObject("SELECT priority_source FROM repair_priority WHERE repair_id=?",String.class,id)).isEqualTo("MANUAL");
        assertThat(jdbc.queryForObject("SELECT suggested_priority FROM repair_priority WHERE repair_id=?",String.class,id)).isEqualTo("URGENT");
        assertThat(jdbc.queryForObject("SELECT priority FROM repair_work WHERE repair_id=?",String.class,id)).isEqualTo("NORMAL");
        RepairPriorities.initialize(jdbc);
        assertThat(jdbc.queryForObject("SELECT priority FROM repair_work WHERE repair_id=?",String.class,id)).isEqualTo("NORMAL");
        dispatch(id,"AUTO");
        assertThat(jdbc.queryForObject("SELECT priority FROM repair_work WHERE repair_id=?",String.class,id)).isEqualTo("URGENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repair_event WHERE repair_id=? AND action='REPAIR_DISPATCH' AND note LIKE '%人工%'",Integer.class,id)).isEqualTo(1);
    }
    @Test void upgradeEvaluatesUnassignedOrdersButKeepsAssignedPriorities() {
        jdbc.update("UPDATE repair_request SET description='水管爆裂' WHERE id=2");
        jdbc.update("INSERT INTO repair_work(repair_id,assignee,priority,updated_at) VALUES(1,'worker','URGENT','2026-11-01')");
        RepairPriorities.initialize(jdbc);
        RepairPriorities.initialize(jdbc);
        assertThat(jdbc.queryForObject("SELECT priority FROM repair_work WHERE repair_id=1",String.class)).isEqualTo("URGENT");
        assertThat(jdbc.queryForObject("SELECT priority_source FROM repair_priority WHERE repair_id=1",String.class)).isEqualTo("MANUAL");
        assertThat(jdbc.queryForObject("SELECT priority FROM repair_work WHERE repair_id=2",String.class)).isEqualTo("URGENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repair_priority",Integer.class)).isEqualTo(2);
    }
}
