package com.example.dormitory.business;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.AccessDeniedException;

import java.util.*;

import static com.example.dormitory.business.BusinessStore.*;

@Service
@Transactional
public class AccountBusinessService {
    private final BusinessStore db;
    private final DomainLocks locks;
    private final BusinessLists lists;
    private final BusinessAccess access;
    private final PasswordEncoder encoder;

    public AccountBusinessService(BusinessStore db, BusinessAccess access, PasswordEncoder encoder, BusinessLists lists, DomainLocks locks) {
        this.db = db;
        this.locks = locks;
        this.lists = lists;
        this.access = access;
        this.encoder = encoder;
    }

    public static void passwordPolicy(String password) {
        invalid(password != null && password.length() >= 8 && password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72 && password.matches("(?s).*[A-Za-z].*") && password.matches("(?s).*[0-9].*"), "新密码至少 8 个字符，包含字母和数字，UTF-8 不超过 72 字节");
    }

    public long version(String username) {
        var rows = db.jdbc.queryForList("SELECT credential_version FROM account_policy WHERE username=?", username);
        return rows.isEmpty() ? 0 : number(rows.get(0).get("credential_version"));
    }

    public boolean enabled(String username) {
        return db.count("SELECT COUNT(*) FROM account_policy WHERE username=? AND enabled=0", username) == 0;
    }

    public boolean mustChange(String username) {
        return db.count("SELECT COUNT(*) FROM account_policy WHERE username=? AND force_password_change=1", username) > 0;
    }

    private void ensure(String username) {
        if (db.count("SELECT COUNT(*) FROM account_policy WHERE username=?", username) == 0)
            db.jdbc.update("INSERT INTO account_policy(username) VALUES(?)", username);
    }

    public long changePassword(BusinessRequests.Password input) {
        // 先锁定账号，避免用户改密和管理员重置密码互相覆盖。
        passwordPolicy(input.newPassword());
        db.jdbc.update("UPDATE user SET id=id WHERE username=?", access.actor());
        var user = db.one("SELECT * FROM user WHERE username=?", access.actor());
        if (!encoder.matches(input.oldPassword(), text(user.get("password"))))
            throw new AccessDeniedException("原密码不正确");
        invalid(!input.oldPassword().equals(input.newPassword()), "新密码不能与原密码相同");
        ensure(access.actor());
        db.jdbc.update("UPDATE user SET password=? WHERE username=?", encoder.encode(input.newPassword()), access.actor());
        db.jdbc.update("UPDATE account_policy SET credential_version=credential_version+1,force_password_change=0 WHERE username=?", access.actor());
        db.audit(access.actor(), "PASSWORD_CHANGE", "account", access.actor(), Map.of("sessionsRevoked", true));
        // 返回本次修改产生的版本，当前会话会使用这个值。
        return version(access.actor());
    }

    public List<Map<String, Object>> accounts() {
        return lists.all("accounts");
    }

    public void create(BusinessRequests.Account input) {
        access.roles("ADMIN");
        passwordPolicy(input.initialPassword());
        check(db.count("SELECT COUNT(*) FROM user WHERE username=?", input.username()) == 0, "账号已存在");
        db.jdbc.update("INSERT INTO user(username,password,real_name,role,is_initialized) VALUES(?,?,?,?,1)", input.username(), encoder.encode(input.initialPassword()), input.realName(), input.role().toLowerCase(Locale.ROOT));
        ensure(input.username());
        db.jdbc.update("UPDATE account_policy SET force_password_change=1 WHERE username=?", input.username());
        scopes(input.username(), input.role(), input.buildingIds());
        db.audit(access.actor(), "ACCOUNT_CREATE", "account", input.username(), Map.of("role", input.role(), "buildingIds", input.buildingIds()));
    }

    private void scopes(String username, String role, List<Integer> ids) {
        check(role.equalsIgnoreCase("MANAGER") || ids.isEmpty(), "仅楼栋宿管需要配置管理楼栋");
        for (int id : ids) db.one("SELECT id FROM building WHERE id=?", id);
        db.jdbc.update("DELETE FROM manager_building WHERE username=?", username);
        for (int id : new HashSet<>(ids))
            db.jdbc.update("INSERT INTO manager_building(username,building_id) VALUES(?,?)", username, id);
    }

    public void state(String username, BusinessRequests.AccountState input) {
        access.roles("ADMIN");
        var user = db.one("SELECT * FROM user WHERE username=?", username);
        check(!username.equals(access.actor()), "不能停用或修改当前管理员账号的权限范围");
        check(!text(user.get("role")).equals("admin"), "系统管理员账号不通过此入口调整");
        if (input.enabled() && text(user.get("role")).equals("student"))
            check(db.count("SELECT COUNT(*) FROM student s JOIN student_profile p ON p.student_id=s.id WHERE s.student_id=? AND p.lifecycle<>'ENROLLED'", username) == 0, "已归档学生需先恢复在校状态");
        ensure(username);
        scopes(username, text(user.get("role")), input.buildingIds());
        db.jdbc.update("UPDATE account_policy SET enabled=?,credential_version=credential_version+1 WHERE username=?", input.enabled() ? 1 : 0, username);
        db.audit(access.actor(), "ACCOUNT_STATE", "account", username, Map.of("enabled", input.enabled(), "buildingIds", input.buildingIds(), "reason", input.reason()));
    }

    public void reset(String username, BusinessRequests.ResetPassword input) {
        db.jdbc.update("UPDATE user SET id=id WHERE username=?", username);
        access.roles("ADMIN");
        check(!username.equals(access.actor()), "当前账号请通过修改密码操作");
        var user = db.one("SELECT * FROM user WHERE username=?", username);
        check(!text(user.get("role")).equals("admin"), "不可在此重置其他系统管理员密码");
        passwordPolicy(input.newPassword());
        ensure(username);
        db.jdbc.update("UPDATE user SET password=? WHERE username=?", encoder.encode(input.newPassword()), username);
        db.jdbc.update("UPDATE account_policy SET credential_version=credential_version+1,force_password_change=1 WHERE username=?", username);
        db.audit(access.actor(), "PASSWORD_RESET", "account", username, Map.of("reason", input.reason()));
    }

    public void provisionStudent(long id, BusinessRequests.ResetPassword input) {
        access.roles("ADMIN");
        locks.housing(id, null);
        passwordPolicy(input.newPassword());
        var student = db.one("SELECT * FROM student WHERE id=?", id);
        String username = text(student.get("student_id"));
        check(db.count("SELECT COUNT(*) FROM student_profile WHERE student_id=? AND lifecycle<>'ENROLLED'", id) == 0, "已归档学生不能开通账号");
        check(db.count("SELECT COUNT(*) FROM user WHERE username=?", username) == 0, "账号已存在，请使用密码重置或启用操作");
        db.jdbc.update("INSERT INTO user(username,password,real_name,phone,role,is_initialized) VALUES(?,?,?,?,'student',1)", username, encoder.encode(input.newPassword()), student.get("name"), student.get("phone"));
        ensure(username);
        db.jdbc.update("UPDATE account_policy SET force_password_change=1 WHERE username=?", username);
        db.audit(access.actor(), "STUDENT_ACCOUNT_CREATE", "student", id, Map.of("reason", input.reason()));
    }

    public List<Map<String, Object>> audit() {
        return lists.all("audit");
    }

    public Map<String, Object> settings() {
        access.roles("ADMIN");
        Map<String, Object> result = new LinkedHashMap<>();
        for (var r : db.jdbc.queryForList("SELECT * FROM business_setting"))
            result.put(text(r.get("setting_key")), text(r.get("setting_value")).equals("true"));
        return result;
    }

    public void settings(BusinessRequests.Settings input) {
        access.roles("ADMIN");
        var values = Map.of("checkout_requires_settled_bills", input.checkoutRequiresSettledBills(), "checkout_requires_returned_assets", input.checkoutRequiresReturnedAssets(), "all_residents_can_report", input.allResidentsCanReport());
        values.forEach((key, value) -> {
            if (db.count("SELECT COUNT(*) FROM business_setting WHERE setting_key=?", key) == 0)
                db.jdbc.update("INSERT INTO business_setting(setting_key,setting_value) VALUES(?,?)", key, value.toString());
            else
                db.jdbc.update("UPDATE business_setting SET setting_value=? WHERE setting_key=?", value.toString(), key);
        });
        db.audit(access.actor(), "SETTINGS_UPDATE", "settings", "business", values);
    }
}
