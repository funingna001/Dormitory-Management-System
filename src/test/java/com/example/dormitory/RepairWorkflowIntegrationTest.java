package com.example.dormitory;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

class RepairWorkflowIntegrationTest extends IntegrationSupport {
    @BeforeEach void staff() {
        jdbc.update("INSERT INTO user(username,password,role,is_initialized) SELECT 'manager',password,'manager',1 FROM user WHERE username='admin'");
        jdbc.update("INSERT INTO user(username,password,role,is_initialized) SELECT 'other_manager',password,'manager',1 FROM user WHERE username='admin'");
        jdbc.update("INSERT INTO user(username,password,role,is_initialized) SELECT 'other_worker',password,'worker',1 FROM user WHERE username='admin'");
        jdbc.update("INSERT INTO manager_building(username,building_id) VALUES('manager',1),('other_manager',2)");
    }
    private long submit() throws Exception {
        var response=mvc.perform(multipart("/api/repairs").param("repairType","灯具").param("description","FLOW-CHECK 灯具损坏")
                .with(user("20260001").roles("STUDENT")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dispatchState").value("WAITING_ASSIGNMENT"))
                .andReturn().getResponse();
        return mapper.readTree(response.getContentAsString()).get("id").asLong();
    }
    private void dispatch(long id) throws Exception {
        mvc.perform(post("/api/business/repairs/"+id+"/dispatch").with(user("manager").roles("MANAGER")).with(csrf())
                .contentType("application/json").content("{\"assignee\":\"worker\",\"priority\":\"NORMAL\",\"note\":\"安排灯具维修\"}"))
                .andExpect(status().isOk());
    }
    @Test void submissionCreatesReceiptAndScopedDispatcherTodos() throws Exception {
        long id=submit();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repair_event WHERE repair_id=? AND action='REPAIR_SUBMIT'",Integer.class,id)).isEqualTo(1);
        for(String recipient:new String[]{"admin","user","manager"})
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE recipient=? AND title='报修待派单'",Integer.class,recipient)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE recipient='other_manager'",Integer.class)).isZero();
        mvc.perform(get("/api/business/lists/repairs").param("state","WAITING_ASSIGNMENT").param("search","FLOW-CHECK")
                .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(id));
        mvc.perform(get("/api/business/lists/repairs").with(user("worker").roles("WORKER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }
    @Test void dispatchMakesOrderVisibleAndRepairClosesOnlyAfterStudentAcceptance() throws Exception {
        long id=submit(); dispatch(id);
        mvc.perform(get("/api/business/lists/repairs").param("search","FLOW-CHECK").with(user("worker").roles("WORKER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].dispatch_state").value("ASSIGNED"));
        mvc.perform(get("/api/business/lists/repairs").param("state","WAITING_ASSIGNMENT").param("search","FLOW-CHECK").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(post("/api/business/repairs/"+id+"/start").with(user("worker").roles("WORKER")).with(csrf())
                .contentType("application/json").content("{\"note\":\"已到场\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/business/repairs/"+id+"/finish").with(user("worker").roles("WORKER")).with(csrf())
                .contentType("application/json").content("{\"note\":\"已更换灯具\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM repair_request WHERE id=?",String.class,id)).isEqualTo("待验收");
        mvc.perform(post("/api/business/repairs/"+id+"/review").with(user("20260001").roles("STUDENT")).with(csrf())
                .contentType("application/json").content("{\"approve\":true,\"note\":\"确认恢复\",\"rating\":5}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM repair_request WHERE id=?",String.class,id)).isEqualTo("已完成");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repair_event WHERE repair_id=?",Integer.class,id)).isEqualTo(5);
    }
    @Test void otherWorkersCannotReadOrProcessTheAssignedOrder() throws Exception {
        long id=submit(); dispatch(id);
        mvc.perform(get("/api/business/lists/repairs").param("search","FLOW-CHECK").with(user("other_worker").roles("WORKER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(post("/api/business/repairs/"+id+"/start").with(user("other_worker").roles("WORKER")).with(csrf())
                .contentType("application/json").content("{\"note\":\"不可越权\"}")).andExpect(status().isForbidden());
    }
}
