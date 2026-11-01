package com.example.dormitory.config;

import com.example.dormitory.business.BusinessStore;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.*;

import java.util.Map;

@Configuration
public class BusinessAuditConfig implements WebMvcConfigurer {
    private final BusinessStore db;

    public BusinessAuditConfig(BusinessStore db) {
        this.db = db;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception error) {
                if (java.util.Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod()) || res.getStatus() < 200 || res.getStatus() >= 300 || req.getRequestURI().startsWith("/api/auth/"))
                    return;
                var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                if (authentication == null || authentication.getName().equals("anonymousUser")) return;
                try {
                    db.audit(authentication.getName(), "HTTP_" + req.getMethod(), "api", req.getRequestURI(), Map.of("status", res.getStatus()));
                } catch (RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(BusinessAuditConfig.class).error("Could not persist request audit", failure);
                }
            }
        }).addPathPatterns("/api/**");
    }
}
