package com.example.dormitory;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

class AuthFlowIntegrationTest extends IntegrationSupport {
    @Test void registrationAlwaysReturnsTheSameRefusalWithoutAChallenge() throws Exception {
        for(String body:java.util.List.of("{}","{\"username\":\"admin\"}","{\"username\":\"unassigned\"}"))
            mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("自助注册已关闭，请联系管理员核验开通账号"));
    }
    @Test void authenticatedSessionsWithoutAVersionCannotUseMe() throws Exception {
        var session=new MockHttpSession();var context=org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin",null,java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        session.setAttribute("SPRING_SECURITY_CONTEXT",context);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());assertThat(session.isInvalid()).isTrue();
    }
    @Test void auditMetricsRequireAnAdministrator() throws Exception {
        mvc.perform(get("/api/auth/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/metrics").with(user("20260001").roles("STUDENT"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/metrics").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.capacity").value(500)).andExpect(jsonPath("$.recent").isArray());
    }
    @Test void sharedAddressBurstIsBoundedAndReturnsRetryAfter() throws Exception {
        var executor=java.util.concurrent.Executors.newFixedThreadPool(8);
        try{
            var tasks=new java.util.ArrayList<java.util.concurrent.Callable<Integer>>();
            for(int i=0;i<200;i++)tasks.add(()->{var response=mvc.perform(post("/api/auth/login").with(csrf()).with(req->{req.setRemoteAddr("198.51.100.42");return req;}).contentType("application/json").content("{\"username\":\"admin\",\"password\":\"wrong\",\"captcha\":\"0000\"}")).andReturn().getResponse();if(response.getStatus()==429)assertThat(response.getHeader("Retry-After")).isNotBlank();return response.getStatus();});
            var results=executor.invokeAll(tasks);int admitted=0,limited=0;for(var result:results){int status=result.get();if(status==400)admitted++;else if(status==429)limited++;else throw new AssertionError("Unexpected status: "+status);}
            assertThat(admitted).isEqualTo(60);assertThat(limited).isEqualTo(140);
        }finally{executor.shutdownNow();}
    }
    @org.springframework.beans.factory.annotation.Value("${server.tomcat.remoteip.internal-proxies}") String trustedProxies;
    @Test void proxyTrustDefaultsOnlyIncludeLoopback(){
        assertThat("127.0.0.1".matches(trustedProxies)).isTrue();
        assertThat("203.0.113.10".matches(trustedProxies)).isFalse();
    }
    @Test void wrongCaptchasDoNotLockAnotherClientOut() throws Exception {
        for(int i=0;i<12;i++)mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content("{\"username\":\"admin\",\"password\":\"wrong\",\"captcha\":\"0000\"}")).andExpect(status().isBadRequest());
        var session=new MockHttpSession();session.setAttribute("captcha","1234");session.setAttribute("captchaTime",System.currentTimeMillis());
        mvc.perform(post("/api/auth/login").session(session).with(csrf()).contentType("application/json").content("{\"username\":\"admin\",\"password\":\"TestAdminPassword123!\",\"captcha\":\"1234\"}")).andExpect(status().isOk());
        assertThat(session.getAttribute("credentialVersion")).isEqualTo(0L);
    }
    @Test void publicRegistrationCannotReserveFutureStudentNumbers() throws Exception {
        var session=new MockHttpSession();session.setAttribute("captcha","1234");session.setAttribute("captchaTime",System.currentTimeMillis());
        mvc.perform(post("/api/auth/register").session(session).with(csrf()).contentType("application/json").content("{\"username\":\"20999999\",\"password\":\"Password123!\",\"confirmPassword\":\"Password123!\",\"captcha\":\"1234\"}")).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE username='20999999'",Integer.class)).isZero();
    }
    @Test void existingUnboundAccountsCannotSilentlyAcquireAStudentRecord(){
        jdbc.update("INSERT INTO user(username,password,role) SELECT '20999999',password,'student' FROM user WHERE username='admin'");
        assertThatThrownBy(()->housing.saveStudent(null,new com.example.dormitory.controller.Requests.StudentInput("测试","20999999","大一","一班","男","13800000001",null,"Initial123!"))).hasMessageContaining("核验");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM student WHERE student_id='20999999'",Integer.class)).isZero();
    }
}
