package com.example.dormitory.service;

import com.example.dormitory.entity.User;
import com.example.dormitory.service.impl.UserServiceImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
// 一起读取账号和登录策略，保证密码与会话版本能对应上。
public class AuthAccounts {
    public static final String REQUEST_ATTRIBUTE = AuthAccounts.class.getName();
    public record State(User user, long version, boolean enabled, boolean mustChange) {}
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    public AuthAccounts(JdbcTemplate jdbc, PasswordEncoder passwords) { this.jdbc=jdbc;this.passwords=passwords; }
    public State find(String username) {
        // 老账号可能还没有策略记录，用默认值补齐即可，不在查询时修改数据。
        var rows=jdbc.query("SELECT u.*,COALESCE(p.credential_version,0) AS credential_version,COALESCE(p.enabled,1) AS enabled,COALESCE(p.force_password_change,0) AS force_password_change FROM user u LEFT JOIN account_policy p ON p.username=u.username WHERE u.username=?",
                (rs,n)->new State(new UserServiceImpl.UserRowMapper().mapRow(rs,n),rs.getLong("credential_version"),rs.getInt("enabled")==1,rs.getInt("force_password_change")==1),username);
        return rows.isEmpty()?null:rows.get(0);
    }
    public State authenticate(String username,String password) {
        // 先验证这次读到的密码，再确认验证期间账号没有被修改。
        State checked=find(username);
        if(checked==null||!checked.enabled()||!passwords.matches(password,checked.user().getPassword()))return null;
        State latest=find(checked.user().getUsername());
        if(latest==null||!latest.enabled()||latest.version()!=checked.version()||!latest.user().getPassword().equals(checked.user().getPassword())||!latest.user().getRole().equals(checked.user().getRole()))return null;
        // 只返回验证过的版本，不能给旧密码配上改密后的新版本。
        return checked;
    }
}
