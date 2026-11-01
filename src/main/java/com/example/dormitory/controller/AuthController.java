package com.example.dormitory.controller;

import com.example.dormitory.util.CaptchaUtil;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
// 处理登录、退出和验证码请求。具体的密码检查交给 AuthAccounts。
public class AuthController {
    private final SecurityContextRepository contexts;
    private final AuthRateLimits limits;
    private final com.example.dormitory.service.AuthAccounts authAccounts;
    private final com.example.dormitory.service.AuthTelemetry telemetry;
    public AuthController(SecurityContextRepository contexts,AuthRateLimits limits,com.example.dormitory.service.AuthAccounts authAccounts,com.example.dormitory.service.AuthTelemetry telemetry) {
        this.contexts=contexts;this.limits=limits;this.authAccounts=authAccounts;this.telemetry=telemetry;
    }
    @GetMapping("/csrf") public Map<String, String> csrf(CsrfToken token) {
        // 前端提交修改请求时要带上这个令牌，防止其他网站借用用户的登录状态。
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
    @GetMapping(value="/captcha", produces=MediaType.IMAGE_JPEG_VALUE)
    public byte[] captcha(HttpServletRequest request, HttpServletResponse response) throws java.io.IOException {
        // 先限制刷新次数，再把验证码和生成时间存进当前会话。
        limits.take("captcha",request.getRemoteAddr());
        var session = request.getSession();
        String code = CaptchaUtil.generateCode();
        synchronized(session) { session.setAttribute("captcha", code); session.setAttribute("captchaTime", System.currentTimeMillis()); }
        response.setHeader("Cache-Control", "no-store");
        return CaptchaUtil.generateImage(code);
    }
    private void checkCaptcha(HttpSession session, String supplied) {
        // 同一会话一次只校验一个请求，避免两个请求同时用掉同一张验证码。
        synchronized(session) {
            String expected = (String) session.getAttribute("captcha");
            Long time = (Long) session.getAttribute("captchaTime");
            // 不管输入对不对，这张验证码都只能使用一次。
            session.removeAttribute("captcha"); session.removeAttribute("captchaTime");
            if (expected == null || time == null || System.currentTimeMillis() - time > 120000
                    || !expected.equalsIgnoreCase(supplied))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码错误或已过期，请刷新验证码");
        }
    }
    @PostMapping("/login")
    public Object login(@Valid @RequestBody Requests.Login input, HttpServletRequest req, HttpServletResponse res) {
        // 先检查来源请求次数和验证码，通过后才检查密码。
        limits.take("login-ip",req.getRemoteAddr());
        try {checkCaptcha(req.getSession(), input.captcha());}
        catch(ResponseStatusException failure){telemetry.record(com.example.dormitory.service.AuthTelemetry.Event.CAPTCHA_DENIED,"INVALID_CHALLENGE",input.username(),req.getRemoteAddr());throw failure;}
        if (input.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码过长");
        String attemptKey=req.getRemoteAddr()+":"+input.username().strip().toLowerCase(java.util.Locale.ROOT);
        // 同一来源、同一账号共用计数，大小写不同也不会分开计算。
        limits.take("password",attemptKey);
        com.example.dormitory.service.AuthAccounts.State state;
        try {state=authAccounts.authenticate(input.username(),input.password());}
        catch(org.springframework.dao.DataAccessException failure){telemetry.record(com.example.dormitory.service.AuthTelemetry.Event.DATABASE_UNAVAILABLE,"LOGIN",input.username(),req.getRemoteAddr());throw failure;}
        if(state==null)telemetry.record(com.example.dormitory.service.AuthTelemetry.Event.LOGIN_DENIED,"INVALID_CREDENTIALS",input.username(),req.getRemoteAddr());
        if (state == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误，或账号已停用");
        limits.clear("password",attemptKey);
        var user=state.user();
        // 登录后换一个会话编号，并记下本次密码对应的版本。
        // 以后改密或停用账号时，旧版本的会话就会失效。
        req.changeSessionId();
        req.getSession().setAttribute("credentialVersion",state.version());
        // 登录前的 CSRF 令牌不再使用，前端需要重新获取。
        new HttpSessionCsrfTokenRepository().saveToken(null, req, res);
        var auth = new UsernamePasswordAuthenticationToken(user.getUsername(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().toUpperCase(Locale.ROOT))));
        var context = SecurityContextHolder.createEmptyContext();
        // 把已登录身份保存起来，后面的接口才能知道当前用户是谁。
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, req, res);
        telemetry.record(com.example.dormitory.service.AuthTelemetry.Event.LOGIN_SUCCESS,"AUTHENTICATED",user.getUsername(),req.getRemoteAddr());
        return Map.of("success", true, "user", user,"mustChangePassword",state.mustChange());
    }
    @PostMapping("/register")
    public Object register(HttpServletRequest request) {
        // 学生账号由管理员核验后开通，这里统一拒绝自助注册。
        telemetry.record(com.example.dormitory.service.AuthTelemetry.Event.REGISTRATION_DISABLED,"DISABLED",null,request.getRemoteAddr());
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,"自助注册已关闭，请联系管理员核验开通账号");
    }
    // 只允许管理员查看，权限规则在 SecurityConfig 中设置。
    @GetMapping("/metrics") public Object metrics(){return telemetry.snapshot();}
    @GetMapping("/me") public Object me(java.security.Principal principal, HttpServletRequest request) {
        // 过滤器已经查过账号状态，直接复用，避免再查一遍数据库。
        var state=(com.example.dormitory.service.AuthAccounts.State)request.getAttribute(com.example.dormitory.service.AuthAccounts.REQUEST_ATTRIBUTE);
        return Map.of("user", state.user(),"mustChangePassword",state.mustChange());
    }
    @PostMapping("/logout") public Object logout(HttpServletRequest req) {
        // 同时清理会话和当前请求中的身份信息。
        telemetry.record(com.example.dormitory.service.AuthTelemetry.Event.LOGOUT,"EXPLICIT",req.getUserPrincipal()==null?null:req.getUserPrincipal().getName(),req.getRemoteAddr());
        var session = req.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return Map.of("success", true);
    }
}
