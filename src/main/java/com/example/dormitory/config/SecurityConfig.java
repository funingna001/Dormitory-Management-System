package com.example.dormitory.config;

import com.example.dormitory.service.UserService;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.List;
import java.util.Map;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }

    @Bean UserDetailsService userDetailsService(UserService users) {
        return username -> {
            var user = users.findByUsername(username);
            if (user == null) throw new UsernameNotFoundException("用户不存在");
            return org.springframework.security.core.userdetails.User.withUsername(user.getUsername())
                    .password(user.getPassword()).roles(user.getRole().toUpperCase(java.util.Locale.ROOT)).build();
        };
    }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contexts,
                                                ObjectMapper mapper, UserService users,com.example.dormitory.business.AccountBusinessService accounts, com.example.dormitory.service.AuthAccounts authAccounts, com.example.dormitory.service.AuthTelemetry telemetry) throws Exception {
        // 公开接口放前面，管理接口按角色限制，其余没有声明的请求默认拒绝。
        http.cors(cors -> {}).securityContext(c -> c.securityContextRepository(contexts))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/api/auth/captcha").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register").permitAll()
                .requestMatchers("/api/auth/metrics").hasRole("ADMIN")
                .requestMatchers("/api/students/**", "/api/dormitories/**", "/api/buildings/**",
                        "/api/data/**", "/api/export/**", "/api/ai/**").hasRole("ADMIN")
                .requestMatchers("/api/me/**").hasRole("STUDENT")
                .requestMatchers("/api/business/**","/api/repairs/**", "/api/auth/me", "/api/auth/logout").authenticated()
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401); res.setContentType("application/json;charset=UTF-8");
                    mapper.writeValue(res.getOutputStream(), Map.of("success", false, "message", "请先登录"));
                })
                .accessDeniedHandler((req, res, ex) -> {
                    res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
                    mapper.writeValue(res.getOutputStream(), Map.of("success", false, "message", "无访问权限或安全令牌已失效，请刷新重试"));
                }))
            .addFilterBefore(new AccountSessionFilter(authAccounts,telemetry), org.springframework.security.web.access.intercept.AuthorizationFilter.class)
            .formLogin(c -> c.disable()).httpBasic(c -> c.disable()).logout(c -> c.disable());
        return http.build();
    }

    @Bean CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") String origins) {
        // 只允许配置好的前端地址跨域访问，携带会话时不能使用任意来源。
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(origins.split(",")));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN"));
        cors.setExposedHeaders(List.of("Content-Disposition", "Retry-After"));
        cors.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
