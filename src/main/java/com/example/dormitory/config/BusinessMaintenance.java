package com.example.dormitory.config;

import com.example.dormitory.business.BusinessStore;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
public class BusinessMaintenance {
    private final BusinessStore db;

    public BusinessMaintenance(BusinessStore db) {
        this.db = db;
    }

    @Scheduled(initialDelay = 60000, fixedDelay = 60000)
    public void removeExpiredPreviews() {
        db.jdbc.update("DELETE FROM import_batch WHERE expires_at<?", BusinessStore.now());
    }
}
