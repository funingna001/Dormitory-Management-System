package com.example.dormitory.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/** 保存少量近期认证事件和总次数，避免每次登录都额外写数据库。 */
@Component
public class AuthTelemetry {
    public enum Event { LOGIN_SUCCESS, LOGIN_DENIED, CAPTCHA_DENIED, RATE_LIMITED, SESSION_REVOKED, DATABASE_UNAVAILABLE, LOGOUT, REGISTRATION_DISABLED }
    public record Entry(String time,Event event,String reason,String actorTag,String source) {}
    private final EnumMap<Event,LongAdder> counters=new EnumMap<>(Event.class);
    private final ArrayDeque<Entry> recent=new ArrayDeque<>();
    private final int capacity;
    private Map<String,Long> last=Map.of();
    public AuthTelemetry(@Value("${app.auth.audit.capacity:500}") int capacity){
        if(capacity<1||capacity>10000)throw new IllegalArgumentException("Invalid authentication audit capacity");
        this.capacity=capacity;for(var e:Event.values())counters.put(e,new LongAdder());
    }
    public void record(Event event,String reason,String actor,String source){
        counters.get(event).increment();
        String address=source==null?"":source.replaceAll("[\\r\\n]","");
        var entry=new Entry(Instant.now().toString(),event,reason,tag(actor),address.substring(0,Math.min(address.length(),64)));
        synchronized(recent){if(recent.size()==capacity)recent.removeFirst();recent.addLast(entry);}
    }
    private String tag(String actor){
        // 用账号的摘要关联事件，不把原始账号直接存进近期记录。
        if(actor==null||actor.isBlank())return "";
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actor.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))).substring(0,16);}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private Map<String,Long> counts(){var result=new LinkedHashMap<String,Long>();counters.forEach((e,n)->result.put(e.name(),n.sum()));return result;}
    public Map<String,Object> snapshot(){synchronized(recent){return Map.of("counters",counts(),"recent",List.copyOf(recent),"capacity",capacity);}}
    @Scheduled(fixedDelayString="${app.auth.audit.summary-ms:60000}")
    // 定时输出汇总；计数没有变化时就不重复记录。
    public synchronized void summarize(){var values=counts();if(!values.equals(last)){org.slf4j.LoggerFactory.getLogger(AuthTelemetry.class).info("Authentication counters: {}",values);last=values;}}
}
