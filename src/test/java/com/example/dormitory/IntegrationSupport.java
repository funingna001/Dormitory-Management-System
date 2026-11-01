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

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(IntegrationSupport.MockSessionVersions.class)
abstract class IntegrationSupport {
    /** Mock principals bypass real login; give these test-only identities its version stamp. */
    @org.springframework.boot.test.context.TestConfiguration
    static class MockSessionVersions {
        @org.springframework.context.annotation.Bean
        static org.springframework.beans.factory.config.BeanPostProcessor mockSessionVersions(){return new org.springframework.beans.factory.config.BeanPostProcessor(){
        @Override public Object postProcessAfterInitialization(Object bean,String name){
            if(bean instanceof org.springframework.security.web.DefaultSecurityFilterChain security){
                var filters=security.getFilters();int index=-1;
                for(int i=0;i<filters.size();i++)if(filters.get(i) instanceof com.example.dormitory.config.AccountSessionFilter)index=i;
                if(index>=0)filters.add(index,(request,response,chain)->{
            var req=(jakarta.servlet.http.HttpServletRequest)request;
            var session=req.getSession(false);
            var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if(auth!=null&&auth.getPrincipal() instanceof org.springframework.security.core.userdetails.UserDetails){
                session=req.getSession();if(session.getAttribute("credentialVersion")==null)session.setAttribute("credentialVersion",0L);
            }
            chain.doFilter(request,response);
                });
            }
            return bean;
        }};}
    }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        try {
            String mysqlUrl = System.getenv("TEST_MYSQL_URL");
            if (mysqlUrl != null) {
                // Fixtures delete rows: only explicitly named disposable local test databases are allowed.
                if (!mysqlUrl.matches("^jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/susheguanli_test_[A-Za-z0-9_]+(\\?.*)?$"))
                    throw new IllegalArgumentException("MySQL tests require an isolated local susheguanli_test_* database");
                registry.add("spring.datasource.url", () -> mysqlUrl);
                registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
                registry.add("spring.datasource.username", () -> System.getenv("TEST_MYSQL_USERNAME"));
                registry.add("spring.datasource.password", () -> System.getenv("TEST_MYSQL_PASSWORD"));
                registry.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
            } else {
                Path database = Files.createTempFile("dormitory-test-", ".db");
                database.toFile().deleteOnExit();
                registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + database + "?busy_timeout=10000");
                registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
                registry.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
                registry.add("spring.datasource.hikari.transaction-isolation", () -> "TRANSACTION_SERIALIZABLE");
            }
            registry.add("app.upload-dir", () -> Path.of(System.getProperty("java.io.tmpdir"), "dormitory-test-uploads").toString());
            registry.add("app.bootstrap-admin-password", () -> "TestAdminPassword123!");
            registry.add("deepseek.api.key", () -> "");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    PasswordEncoder passwords;
    @Autowired
    SchemaInitializer initializer;
    @Autowired
    HousingService housing;

    @BeforeEach
    void seed() {
        for (String table : List.of("business_migration", "business_setting", "import_batch", "handover", "asset", "payment", "bill", "inspection", "visitor", "stay_request", "notification", "announcement_receipt", "announcement", "repair_event", "repair_priority", "repair_work", "audit_log", "leader_record", "housing_application", "housing_history", "bed", "manager_building", "account_policy", "student_profile", "room_policy"))
            jdbc.update("DELETE FROM " + table);
        for (String table : List.of("repair_request", "student", "dormitory", "building", "user"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO user(username,password,role,is_initialized) VALUES('admin',?,'admin',1)", passwords.encode("TestAdminPassword123!"));
        jdbc.update("INSERT INTO user(username,password,role,is_initialized) VALUES('20260001',?,'student',1)", passwords.encode("TestStudent123!"));
        jdbc.update("INSERT INTO user(username,password,role,is_initialized) VALUES('user',?,'admin',1),('worker',?,'worker',1)", passwords.encode("TestAdmin123!"), passwords.encode("TestWorker123!"));
        jdbc.update("INSERT INTO building(id,name,gender) VALUES(1,'1号楼','男'),(2,'2号楼','女')");
        jdbc.update("INSERT INTO dormitory(id,dormitory_number,building,room_type,capacity,current_count,gender) VALUES(1,'1号楼-0101','1号楼','二人间',2,1,'男'),(2,'2号楼-0101','2号楼','二人间',2,0,'女')");
        jdbc.update("INSERT INTO student(id,name,student_id,grade,gender,phone,dormitory_number,is_dormitory_leader) VALUES(1,'张同学','20260001','大一','男','13800000001','1号楼-0101',1)");
        jdbc.update("INSERT INTO repair_request(id,student_id,student_name,dormitory_number,repair_type,description,status) VALUES(1,'20260001','张同学','1号楼-0101','灯具','灯坏了','待处理'),(2,'20260002','李同学','2号楼-0101','灯具','灯坏了','待处理')");
    }
}
