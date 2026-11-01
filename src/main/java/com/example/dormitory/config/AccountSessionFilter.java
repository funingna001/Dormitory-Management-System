package com.example.dormitory.config;

import com.example.dormitory.service.AuthTelemetry;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 每次请求检查登录状态，及时拦住已停用、已改密或身份有变化的会话。 */
public class AccountSessionFilter extends OncePerRequestFilter {
    private final com.example.dormitory.service.AuthAccounts accounts;

    private final AuthTelemetry telemetry;
    public AccountSessionFilter(com.example.dormitory.service.AuthAccounts accounts) { this(accounts,new AuthTelemetry(500)); }
    public AccountSessionFilter(com.example.dormitory.service.AuthAccounts accounts,AuthTelemetry telemetry) { this.accounts=accounts;this.telemetry=telemetry; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)) {
            var existingSession=req.getSession(false);
            Object version=existingSession==null?null:existingSession.getAttribute("credentialVersion");
            // 缺少合法版本时不能直接放行，需要用户重新登录。
            if(!(version instanceof Long value)||value<0){revoke(req,res,authentication.getName(),"INVALID_VERSION");return;}
            com.example.dormitory.service.AuthAccounts.State state;
            try { state=accounts.find(authentication.getName()); }
            catch(org.springframework.dao.DataAccessException failure) {
                // 数据库暂时出问题，不代表账号被删除，所以这里保留会话。
                org.slf4j.LoggerFactory.getLogger(AccountSessionFilter.class).error("Authentication database unavailable",failure);
                telemetry.record(AuthTelemetry.Event.DATABASE_UNAVAILABLE,"SESSION_CHECK",authentication.getName(),req.getRemoteAddr());
                res.setStatus(503);res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"success\":false,\"message\":\"认证服务暂时不可用，请稍后重试\"}");return;
            }
            var user=state==null?null:state.user();
            String reason=user==null?"USER_MISSING":!state.enabled()?"DISABLED":((Long)version).longValue()!=state.version()?"VERSION_CHANGED":authentication.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_"+user.getRole().toUpperCase(java.util.Locale.ROOT)))?"ROLE_CHANGED":null;
            if(reason!=null){revoke(req,res,authentication.getName(),reason);return;}
            req.setAttribute(com.example.dormitory.service.AuthAccounts.REQUEST_ATTRIBUTE,state);
            // 初始密码还没修改时，只开放改密和必要的登录状态接口。
            if (state.mustChange() && !java.util.Set.of("/api/auth/me", "/api/auth/logout", "/api/auth/csrf", "/api/business/account/password").contains(req.getRequestURI())) {
                res.setStatus(403);
                res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"success\":false,\"message\":\"请先修改初始密码\"}");
                return;
            }
        }
        chain.doFilter(req, res);
    }
    private void revoke(HttpServletRequest req,HttpServletResponse res,String actor,String reason) throws IOException {
        telemetry.record(AuthTelemetry.Event.SESSION_REVOKED,reason,actor,req.getRemoteAddr());
        SecurityContextHolder.clearContext();var session=req.getSession(false);if(session!=null)session.invalidate();
        res.setStatus(401);res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"success\":false,\"message\":\"会话已失效，请重新登录\"}");
    }

}
