package com.example.dormitory.controller;

import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** 按来源和账号限制请求次数；不同用途分开计数，避免互相影响。 */
@Component
public class AuthRateLimits {
    private final AuthSettings settings;
    private final com.example.dormitory.service.AuthTelemetry telemetry;
    public AuthRateLimits(){this(new AuthSettings(),new com.example.dormitory.service.AuthTelemetry(500));}
    @org.springframework.beans.factory.annotation.Autowired
    public AuthRateLimits(AuthSettings settings,com.example.dormitory.service.AuthTelemetry telemetry){this.settings=settings;this.telemetry=telemetry;}
    public void take(String category,String key){
        switch(category){
            case "login-ip" -> take(category,key,settings.getLoginIp(),settings.getLoginWindowSeconds()*1000);
            case "captcha" -> take(category,key,settings.getCaptcha(),settings.getCaptchaWindowSeconds()*1000);
            case "password" -> take(category,key,settings.getPassword(),settings.getPasswordWindowSeconds()*1000);
            default -> throw new IllegalArgumentException("Unknown authentication quota");
        }
    }
    private record Window(int count,long end) {}
    private final Map<String,LinkedHashMap<String,Window>> buckets=new HashMap<>();
    public synchronized void take(String category,String key,int maximum,long duration) {
        long now=System.currentTimeMillis();
        var entries=buckets.computeIfAbsent(category,k->new LinkedHashMap<>());
        // 同类计数使用相同的有效期，按插入顺序清理过期项就可以了。
        while(!entries.isEmpty() && entries.entrySet().iterator().next().getValue().end()<=now)entries.remove(entries.keySet().iterator().next());
        Window old=entries.get(key);
        if(old!=null&&old.count()>=maximum){
            // 超过次数时，把还要等多少秒告诉前端。
            telemetry.record(com.example.dormitory.service.AuthTelemetry.Event.RATE_LIMITED,category,null,null);
            throw new Rejected(Math.max(1,(old.end()-now+999)/1000));
        }
        // 容量满了就移除最旧项，避免计数表一直占用内存。
        if(old==null&&entries.size()>=settings.getCapacity())entries.remove(entries.keySet().iterator().next());
        entries.put(key,new Window(old==null?1:old.count()+1,old==null?now+duration:old.end()));
    }
    public synchronized void clear(String category,String key){var entries=buckets.get(category);if(entries!=null)entries.remove(key);}
    private static final class Rejected extends ResponseStatusException {
        private final org.springframework.http.HttpHeaders headers=new org.springframework.http.HttpHeaders();
        Rejected(long seconds){super(HttpStatus.TOO_MANY_REQUESTS,"尝试过于频繁，请稍后重试");headers.set("Retry-After",Long.toString(seconds));}
        @Override public org.springframework.http.HttpHeaders getHeaders(){return org.springframework.http.HttpHeaders.readOnlyHttpHeaders(headers);}
    }
}
