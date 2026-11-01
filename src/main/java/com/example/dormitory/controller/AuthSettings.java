package com.example.dormitory.controller;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.*;

@Component
@ConfigurationProperties("app.auth.limits")
@Validated
// 限流参数从配置文件读取；注解会检查数值是否在允许范围内。
public class AuthSettings {
    @Min(1) @Max(100000) private int loginIp=60,captcha=60,password=10,capacity=10000;
    @Min(1) @Max(86400) private long loginWindowSeconds=60,captchaWindowSeconds=60,passwordWindowSeconds=900;
    public int getLoginIp(){return loginIp;} public void setLoginIp(int v){loginIp=v;}
    public int getCaptcha(){return captcha;} public void setCaptcha(int v){captcha=v;}
    public int getPassword(){return password;} public void setPassword(int v){password=v;}
    public int getCapacity(){return capacity;} public void setCapacity(int v){capacity=v;}
    public long getLoginWindowSeconds(){return loginWindowSeconds;} public void setLoginWindowSeconds(long v){loginWindowSeconds=v;}
    public long getCaptchaWindowSeconds(){return captchaWindowSeconds;} public void setCaptchaWindowSeconds(long v){captchaWindowSeconds=v;}
    public long getPasswordWindowSeconds(){return passwordWindowSeconds;} public void setPasswordWindowSeconds(long v){passwordWindowSeconds=v;}
}
