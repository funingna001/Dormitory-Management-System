package com.example.dormitory.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Component
public class SchemaInitializer implements CommandLineRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final PlatformTransactionManager transactions;
    private final String initialPassword;
    private final com.example.dormitory.business.HousingLedger ledger;
    private final QueryIndexes indexes;

    public SchemaInitializer(JdbcTemplate jdbc, PasswordEncoder encoder, PlatformTransactionManager transactions,
                             @Value("${app.bootstrap-admin-password:}") String initialPassword,
                             com.example.dormitory.business.HousingLedger ledger, QueryIndexes indexes) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.transactions = transactions;
        this.initialPassword = initialPassword;
        this.ledger = ledger;
        this.indexes = indexes;
    }

    @Override
    public void run(String... args) throws Exception {
        // 先识别数据库类型，再选择对应的建表语句。
        boolean mysql;
        try (var connection = jdbc.getDataSource().getConnection()) {
            mysql = connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL");
        }
        new ResourceDatabasePopulator(new ClassPathResource(mysql ? "db/schema-mysql.sql" : "db/schema-sqlite.sql"))
                .execute(jdbc.getDataSource());
        new ResourceDatabasePopulator(new ClassPathResource(mysql ? "db/business-mysql.sql" : "db/business-sqlite.sql"))
                .execute(jdbc.getDataSource());
        com.example.dormitory.business.RepairPriorities.initialize(jdbc);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM business_migration WHERE version=2", Integer.class) == 0) {
            // 索引补建成功后才记录版本，失败时下次启动还能重试。
            indexes.initialize();
            try {
                jdbc.update("INSERT INTO business_migration(version,applied_at) VALUES(2,?)", java.time.LocalDateTime.now().toString());
            } catch (org.springframework.dao.DuplicateKeyException concurrentStartup) {
                // 另一个实例可能已经完成升级，重复的版本记录可以跳过。
            }
        }
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            // 兼容旧数据：已经是 BCrypt 的密码不再重复加密。
            for (var row : jdbc.queryForList("SELECT id, password FROM user")) {
                String password = (String) row.get("password");
                if (!password.matches("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$"))
                    jdbc.update("UPDATE user SET password = ? WHERE id = ?", encoder.encode(password), row.get("id"));
            }
            Integer admins = jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE role='admin'", Integer.class);
            if (admins == 0) {
                if (initialPassword.length() < 12 || initialPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
                    throw new IllegalStateException("首次启动空数据库时，请设置至少 12 字符、至多 72 字节的 ADMIN_INITIAL_PASSWORD");
                jdbc.update("INSERT INTO user(username,password,role,is_initialized) VALUES(?,?,'admin',1)",
                        "admin", encoder.encode(initialPassword));
            }
        });
        // 住宿接入内部会检查版本，已经完成就不再逐个房间处理。
        ledger.initialize();
    }
}
