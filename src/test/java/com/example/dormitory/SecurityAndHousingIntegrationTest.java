package com.example.dormitory;

import com.example.dormitory.config.SchemaInitializer;
import com.example.dormitory.controller.Requests;
import com.example.dormitory.service.HousingService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

class SecurityAndHousingIntegrationTest extends IntegrationSupport {
    @Test void anonymousCannotExportOrReadStatistics() throws Exception {
        for(String path:List.of("/api/export/students","/api/data/statistics","/api/data/reinitialize","/api/students","/api/ai/summary","/uploads/repair/test.html"))
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }
    @Test @WithMockUser(username="20260001",roles="STUDENT") void studentCannotAccessManagementOrAi() throws Exception {
        for(String path:List.of("/api/students","/api/dormitories","/api/buildings","/api/export/all","/api/data/statistics","/api/ai/summary"))
            mvc.perform(get(path)).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles="ADMIN") void csrfIsRequiredForWrites() throws Exception {
        mvc.perform(delete("/api/students/1")).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM student",Integer.class)).isEqualTo(1);
    }
    @Test @WithMockUser(roles="ADMIN") void destructiveGetIsUnavailable() throws Exception {
        mvc.perform(get("/api/data/reinitialize")).andExpect(status().isNotFound());
        mvc.perform(get("/students/delete/1")).andExpect(status().isForbidden());
    }
    @Test void loginRequiresCaptchaAndNeverReturnsPassword() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"TestAdminPassword123!\",\"captcha\":\"0000\"}"))
                .andExpect(status().isBadRequest());
        MockHttpSession session=new MockHttpSession();
        session.setAttribute("captcha","1234");session.setAttribute("captchaTime",System.currentTimeMillis());
        String old=session.getId();
        var result=mvc.perform(post("/api/auth/login").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"TestAdminPassword123!\",\"captcha\":\"1234\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.password").doesNotExist()).andReturn();
        assertThat(result.getRequest().getSession().getId()).isNotEqualTo(old);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.user.username").value("admin"));
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isOk());
    }
    @Test void realCsrfTokenWorksForSpaLogin() throws Exception {
        var result=mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var json=mapper.readTree(result.getResponse().getContentAsString());
        MockHttpSession session=(MockHttpSession)result.getRequest().getSession();
        session.setAttribute("captcha","9876");session.setAttribute("captchaTime",System.currentTimeMillis());
        mvc.perform(post("/api/auth/login").session(session).header(json.get("headerName").asText(),json.get("token").asText())
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"20260001\",\"password\":\"TestStudent123!\",\"captcha\":\"9876\"}"))
                .andExpect(status().isOk());
    }
    @Test @WithMockUser(username="20260001",roles="STUDENT") void studentCannotOverwriteSomeoneElsesProfile() throws Exception {
        mvc.perform(put("/api/me/profile").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentId\":\"20260002\",\"name\":\"张同学\",\"grade\":\"大一\",\"gender\":\"男\",\"phone\":\"13800000001\"}"))
                .andExpect(status().isBadRequest());
    }
    @Test @WithMockUser(roles="ADMIN") void pagingAndInjectionAreSafe() throws Exception {
        mvc.perform(get("/api/data/statistics")).andExpect(status().isOk())
                .andExpect(jsonPath("$.buildingStats.length()").value(2))
                .andExpect(jsonPath("$.buildingStats['1号楼']").value(1))
                .andExpect(jsonPath("$.buildingStats['2号楼']").value(1));
        mvc.perform(get("/api/students?name=does-not-exist")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/students?page=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/students").param("sortBy","id; DELETE FROM student")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM student",Integer.class)).isEqualTo(1);
    }
    @Test @WithMockUser(roles="ADMIN") void occupiedDormitoryCannotBeDeleted() throws Exception {
        mvc.perform(delete("/api/dormitories/1").with(csrf())).andExpect(status().isConflict());
        mvc.perform(delete("/api/buildings/1").with(csrf())).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dormitory WHERE id=1",Integer.class)).isEqualTo(1);
    }
    @Test @WithMockUser(roles="ADMIN") void removingFromWrongDormitoryIsRejected() throws Exception {
        mvc.perform(delete("/api/dormitories/2/students/1").with(csrf())).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT dormitory_number FROM student WHERE id=1",String.class)).isEqualTo("1号楼-0101");
    }
    @Test void assigningAndRemovingUpdatesCountsAndLeader() {
        jdbc.update("INSERT INTO student(id,name,student_id,grade,gender,phone) VALUES(2,'李同学','20260002','大一','男','13800000002')");
        housing.assign(1,2);
        assertThat(jdbc.queryForObject("SELECT current_count FROM dormitory WHERE id=1",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT is_dormitory_leader FROM student WHERE id=2",Integer.class)).isEqualTo(0);
        housing.remove(1,2);
        assertThat(jdbc.queryForObject("SELECT current_count FROM dormitory WHERE id=1",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT is_dormitory_leader FROM student WHERE id=1",Integer.class)).isEqualTo(1);
    }
    @Test void genderAndCapacityAreEnforcedAndBatchRollsBack() {
        jdbc.update("INSERT INTO student(id,name,student_id,grade,gender,phone) VALUES(2,'李同学','20260002','大一','女','13800000002')");
        assertThatThrownBy(()->housing.assign(1,2)).hasMessageContaining("性别");
        assertThatThrownBy(()->housing.deleteDormitories(List.of(2,1))).hasMessageContaining("入住");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dormitory WHERE id=2",Integer.class)).isEqualTo(1);
    }
    @Test void simultaneousAssignmentsCannotOverfillDormitory() throws Exception {
        for(int id:List.of(2,3))jdbc.update("INSERT INTO student(id,name,student_id,grade,gender,phone) VALUES(?, '同学',?,'大一','男','13800000002')",id,"2026000"+id);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var results=executor.invokeAll(List.of(()->{try{housing.assign(1,2);return true;}catch(Exception e){return false;}},()->{try{housing.assign(1,3);return true;}catch(Exception e){return false;}}));
            int successes=0;for(var result:results)if(Boolean.TRUE.equals(result.get()))successes++;
            assertThat(successes).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM student WHERE dormitory_number='1号楼-0101'",Integer.class)).isEqualTo(2);
        } finally {executor.shutdownNow();}
    }
    @Test void buildingRenamePreservesStudentsRepairsAndRoomIds() {
        housing.saveBuilding(1,new Requests.BuildingInput("新1号楼","男",List.of(new Requests.Floor(1,0,0)),"asc"));
        assertThat(jdbc.queryForObject("SELECT dormitory_number FROM student WHERE id=1",String.class)).isEqualTo("新1号楼-0101");
        assertThat(jdbc.queryForObject("SELECT dormitory_number FROM repair_request WHERE id=1",String.class)).isEqualTo("新1号楼-0101");
        assertThat(jdbc.queryForObject("SELECT current_count FROM dormitory WHERE id=1",Integer.class)).isEqualTo(1);
    }
    @Test void initializerPreservesDataAndMigratesPasswordsIdempotently() throws Exception {
        jdbc.update("UPDATE user SET password='LegacyPassword!' WHERE username='20260001'");
        initializer.run();
        String hash=jdbc.queryForObject("SELECT password FROM user WHERE username='20260001'",String.class);
        assertThat(passwords.matches("LegacyPassword!",hash)).isTrue();
        initializer.run();
        assertThat(jdbc.queryForObject("SELECT password FROM user WHERE username='20260001'",String.class)).isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM student",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repair_request",Integer.class)).isEqualTo(2);
    }
    @Test @WithMockUser(username="20260001",roles="STUDENT") void studentOnlySeesOwnRepairsAndCannotUpdateStatus() throws Exception {
        mvc.perform(get("/api/repairs")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/repairs/2/image")).andExpect(status().isForbidden());
        mvc.perform(patch("/api/repairs/1/status").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"已完成\"}")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(username="worker",roles="WORKER") void workerMustFollowRepairTransitions() throws Exception {
        jdbc.update("INSERT INTO repair_work(repair_id,assignee,updated_at) VALUES(1,'worker','2026-10-28T00:00:00')");
        mvc.perform(patch("/api/repairs/1/status").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"已完成\"}")).andExpect(status().isConflict());
        mvc.perform(patch("/api/repairs/1/status").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"处理中\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/business/repairs/1/finish").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"灯具已修复\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM repair_request WHERE id=1",String.class)).isEqualTo("待验收");
        mvc.perform(post("/api/business/repairs/1/review").with(user("20260001").roles("STUDENT")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"approve\":true,\"note\":\"验收通过\",\"rating\":5}")).andExpect(status().isOk());
    }
    @Test @WithMockUser(username="20260001",roles="STUDENT") void executableUploadIsRejected() throws Exception {
        var file=new MockMultipartFile("image","evil.html","image/png","<script>alert(1)</script>".getBytes());
        mvc.perform(multipart("/api/repairs").file(file).param("repairType","灯具").param("description","灯坏了").with(csrf())).andExpect(status().isBadRequest());
    }
    @Test @WithMockUser(roles="ADMIN") void exportAndLocalAssistantWork() throws Exception {
        mvc.perform(get("/api/export/all")).andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        mvc.perform(post("/api/ai/chat").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"统计摘要\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.reply").isString());
    }
    @Test @WithMockUser(username="20260001",roles="STUDENT") void deletedAccountCannotReuseSession() throws Exception {
        jdbc.update("DELETE FROM user WHERE username='20260001'");
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }
    @Test @WithMockUser(username="20260001",roles="STUDENT") void validImageAndNewRepairCanBeReadBack() throws Exception {
        var output=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",output);
        var image=new MockMultipartFile("image","../../test.html","image/png",output.toByteArray());
        mvc.perform(multipart("/api/repairs").file(image).param("repairType","灯具").param("description","新报修").with(csrf())).andExpect(status().isOk());
        int id=jdbc.queryForObject("SELECT MAX(id) FROM repair_request",Integer.class);
        mvc.perform(get("/api/repairs")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/repairs/"+id+"/image")).andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG));
    }
    @Test void registrationCannotClaimExistingStudent() throws Exception {
        jdbc.update("INSERT INTO student(name,student_id,grade,gender,phone) VALUES('同学','20260003','大一','男','13800000003')");
        MockHttpSession session=new MockHttpSession();session.setAttribute("captcha","1122");session.setAttribute("captchaTime",System.currentTimeMillis());
        mvc.perform(post("/api/auth/register").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"20260003\",\"password\":\"TestPassword123!\",\"confirmPassword\":\"TestPassword123!\",\"captcha\":\"1122\"}"))
                .andExpect(status().isForbidden());
    }
}
