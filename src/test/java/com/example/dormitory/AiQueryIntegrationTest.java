package com.example.dormitory;

import com.example.dormitory.service.AiService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

class AiQueryIntegrationTest extends IntegrationSupport {
    @Autowired AiService ai;
    HttpServer server;
    final AtomicReference<String> body = new AtomicReference<>();

    @AfterEach void resetProvider() {
        if(server!=null) server.stop(0);
        ReflectionTestUtils.setField(ai,"apiKey", "");
        ReflectionTestUtils.setField(ai,"apiUrl", "https://api.deepseek.com/v1/chat/completions");
    }
    void provider(int code, String response) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(code,bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        ReflectionTestUtils.setField(ai,"apiKey","test-only-key");
        ReflectionTestUtils.setField(ai,"apiUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/chat");
    }
    @Test void localStatisticsIncludeCapacityRepairsAndNoSecrets() throws Exception {
        var answer=ai.query("报修状态分布");
        assertThat(answer.source()).isEqualTo("local");
        assertThat(answer.reply()).contains("报修总数");
        assertThat(answer.statistics()).containsKeys("totalCapacity","residentCount","repairStatus");
        assertThat(mapper.writeValueAsString(answer)).doesNotContain("password","TestAdminPassword123!","13800000001");
    }
    @Test void providerClassifiesIntentWithoutReceivingDatabaseContents() throws Exception {
        provider(200,"{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"metric\\\":\\\"occupancy\\\"}\"}}]}");
        var answer=ai.query("住了几成？");
        assertThat(answer.source()).isEqualTo("deepseek");
        assertThat(answer.reply()).contains("入住率");
        assertThat(body.get()).contains("住了几成？").doesNotContain("buildingDetails","totalCapacity","repairStatus","13800000001");
    }
    @Test void providerErrorAndInvalidIntentFallBack() throws Exception {
        provider(503,"{\"error\":\"unavailable\"}");
        assertThat(ai.query("入住率").source()).isEqualTo("local");
        server.stop(0); server=null;
        provider(200,"{\"choices\":[{\"message\":{\"content\":\"{\\\"metric\\\":\\\"DROP TABLE student\\\"}\"}}]}");
        assertThat(ai.query("入住率").source()).isEqualTo("local");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM student",Integer.class)).isPositive();
    }
    @Test void endpointsRequireAdminCsrfAndValidInput() throws Exception {
        mvc.perform(get("/api/ai/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ai/status").with(user("20260001").roles("STUDENT"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/ai/status").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false));
        mvc.perform(post("/api/ai/chat").with(user("admin").roles("ADMIN")).contentType("application/json").content("{\"message\":\"入住率\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/ai/chat").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content("{\"message\":\"\"}")).andExpect(status().isBadRequest());
    }
    @Test void chatReturnsLocalFactsAndLimitsRepeatedQueries() throws Exception {
        for(int i=0;i<10;i++) mvc.perform(post("/api/ai/chat").with(user("user").roles("ADMIN")).with(csrf())
                .contentType("application/json").content("{\"message\":\"入住率\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("local"))
                .andExpect(jsonPath("$.statistics.totalRepairs").value(2));
        mvc.perform(post("/api/ai/chat").with(user("user").roles("ADMIN")).with(csrf())
                .contentType("application/json").content("{\"message\":\"入住率\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }
}
