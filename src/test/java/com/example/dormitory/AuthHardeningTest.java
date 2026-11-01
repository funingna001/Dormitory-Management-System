package com.example.dormitory;

import com.example.dormitory.controller.*;
import com.example.dormitory.config.AccountSessionFilter;
import com.example.dormitory.service.AuthAccounts;
import com.example.dormitory.entity.User;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthHardeningTest {
    @Test void missingAndMalformedVersionsAreRejectedBeforeQuerying() throws Exception {
        for(Object version:Arrays.asList(null,"0",0,0.0,-1L)){
            var accounts=mock(AuthAccounts.class);var request=new MockHttpServletRequest();var session=new MockHttpSession();request.setSession(session);
            if(version!=null)session.setAttribute("credentialVersion",version);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("student",null,List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
            var response=new MockHttpServletResponse();new AccountSessionFilter(accounts).doFilter(request,response,new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(401);assertThat(session.isInvalid()).isTrue();verifyNoInteractions(accounts);
        }
    }
    @Test void configurableLimitsReturnRemainingWindowAndAuditIsBounded(){
        var settings=new AuthSettings();settings.setLoginIp(2);settings.setLoginWindowSeconds(5);settings.setCapacity(3);
        var audit=new com.example.dormitory.service.AuthTelemetry(3);var limits=new AuthRateLimits(settings,audit);
        limits.take("login-ip","shared");limits.take("login-ip","shared");
        assertThatThrownBy(()->limits.take("login-ip","shared")).isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,error->{
            assertThat(error.getStatusCode().value()).isEqualTo(429);assertThat(Long.parseLong(error.getHeaders().getFirst("Retry-After"))).isBetween(1L,5L);
        });
        for(int i=0;i<1000;i++)audit.record(com.example.dormitory.service.AuthTelemetry.Event.LOGIN_DENIED,"INVALID_CREDENTIALS","secret-user","127.0.0.1");
        var snapshot=audit.snapshot();assertThat((List<?>)snapshot.get("recent")).hasSize(3);
        assertThat(snapshot.toString()).doesNotContain("secret-user");
        assertThat(((Map<?,?>)snapshot.get("counters")).get("LOGIN_DENIED")).isEqualTo(1000L);
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void authenticationNeverPairsOldPasswordWithNewVersion(){
        var encoder=mock(PasswordEncoder.class);when(encoder.matches("old","hash0")).thenReturn(true);
        var first=new User("student","hash0",null,null,"student");var latest=new User("student","hash1",null,null,"student");
        var auth=spy(new AuthAccounts(mock(JdbcTemplate.class),encoder));
        doReturn(new AuthAccounts.State(first,0,true,false),new AuthAccounts.State(latest,1,true,false)).when(auth).find("student");
        assertThat(auth.authenticate("student","old")).isNull();
    }
    @Test void databaseFailureDoesNotDestroyAnAuthenticatedSession() throws Exception {
        var auth=mock(AuthAccounts.class);when(auth.find("student")).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("simulated outage"));
        var request=new MockHttpServletRequest();var session=new MockHttpSession();session.setAttribute("credentialVersion",0L);request.setSession(session);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("student",null,List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
        var response=new MockHttpServletResponse();new AccountSessionFilter(auth).doFilter(request,response,new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(503);assertThat(session.isInvalid()).isFalse();assertThat(session.getAttribute("credentialVersion")).isEqualTo(0L);
    }
    @Test void sessionSummaryIsReadOnceAndAttachedToTheRequest() throws Exception {
        var auth=mock(AuthAccounts.class);var state=new AuthAccounts.State(new User("student","hash",null,null,"student"),7,true,false);when(auth.find("student")).thenReturn(state);
        var request=new MockHttpServletRequest();request.getSession().setAttribute("credentialVersion",7L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("student",null,List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
        new AccountSessionFilter(auth).doFilter(request,new MockHttpServletResponse(),new MockFilterChain());
        verify(auth,times(1)).find("student");assertThat(request.getAttribute(AuthAccounts.REQUEST_ATTRIBUTE)).isSameAs(state);
    }
    @Test void captchaCanOnlyBeConsumedByOneConcurrentRequest() throws Exception {
        var controller=new AuthController(null,new AuthRateLimits(),null,new com.example.dormitory.service.AuthTelemetry(10));
        var method=AuthController.class.getDeclaredMethod("checkCaptcha",jakarta.servlet.http.HttpSession.class,String.class);method.setAccessible(true);
        var session=new MockHttpSession();session.setAttribute("captcha","1234");session.setAttribute("captchaTime",System.currentTimeMillis());
        var executor=Executors.newFixedThreadPool(2);
        try{var tasks=executor.invokeAll(List.<Callable<Boolean>>of(()->{try{method.invoke(controller,session,"1234");return true;}catch(java.lang.reflect.InvocationTargetException e){return false;}},()->{try{method.invoke(controller,session,"1234");return true;}catch(java.lang.reflect.InvocationTargetException e){return false;}}));assertThat(List.of(tasks.get(0).get(),tasks.get(1).get())).containsExactlyInAnyOrder(true,false);}
        finally{executor.shutdownNow();}
    }
    @Test void fullLimitBucketDoesNotGloballyRejectNewUsers(){
        var limits=new AuthRateLimits();for(int i=0;i<10020;i++)limits.take("password","fake"+i,10,900000);
        assertThatCode(()->limits.take("password","real",10,900000)).doesNotThrowAnyException();
        for(int i=1;i<10;i++)limits.take("password","real",10,900000);
        assertThatThrownBy(()->limits.take("password","real",10,900000)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatCode(()->limits.take("login-ip","real",60,60000)).doesNotThrowAnyException();
    }
    @Test void forwardedAddressesAreAcceptedOnlyFromTrustedProxyPeers() throws Exception {
        for(String peer:List.of("127.0.0.1","203.0.113.10")) {
            var valve=new org.apache.catalina.valves.RemoteIpValve();valve.setInternalProxies("127\\.0\\.0\\.1|::1");valve.setRemoteIpHeader("X-Forwarded-For");
            valve.setNext(mock(org.apache.catalina.Valve.class));
            var request=mock(org.apache.catalina.connector.Request.class);
            when(request.getRemoteAddr()).thenReturn(peer);when(request.getRemoteHost()).thenReturn(peer);
            when(request.getHeaders(anyString())).thenAnswer(call->Collections.enumeration(call.<String>getArgument(0).equalsIgnoreCase("X-Forwarded-For")?List.of("198.51.100.20"):List.of()));
            when(request.getCoyoteRequest()).thenReturn(new org.apache.coyote.Request());
            when(request.getConnector()).thenReturn(new org.apache.catalina.connector.Connector());
            valve.invoke(request,mock(org.apache.catalina.connector.Response.class));
            verify(request,peer.equals("127.0.0.1")?atLeastOnce():never()).setRemoteAddr("198.51.100.20");
        }
    }
}
