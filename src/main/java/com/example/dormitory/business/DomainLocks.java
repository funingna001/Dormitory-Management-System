package com.example.dormitory.business;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import static com.example.dormitory.business.BusinessStore.*;

/** 统一按“楼栋结构、学生、房间、业务记录”的顺序加锁，减少互相等待的问题。 */
@Component
public class DomainLocks {
    private final BusinessStore db;
    private final boolean mysql;
    private static class Held {
        boolean shared, exclusive;
        final Set<Long> students = new HashSet<>(), rooms = new HashSet<>();
    }

    public DomainLocks(BusinessStore db) throws java.sql.SQLException {
        this.db = db;
        try (var connection = db.jdbc.getDataSource().getConnection()) {
            mysql = connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL");
        }
    }

    private Held held() {
        // 锁跟着事务一起结束，这里只记下本次事务已经锁过哪些记录。
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Domain locks require an active transaction");
        var state = (Held) TransactionSynchronizationManager.getResource(this);
        if (state != null) return state;
        var created = new Held();
        TransactionSynchronizationManager.bindResource(this, created);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { TransactionSynchronizationManager.unbindResourceIfPossible(DomainLocks.this); }
        });
        return created;
    }

    public void structure(boolean exclusive) {
        // 普通业务可以一起执行；修改楼栋结构时，需要独占保护。
        var state = held();
        if (state.exclusive || state.shared && !exclusive) return;
        if (state.shared) throw new IllegalStateException("Cannot upgrade topology lock after taking domain locks");
        if (mysql && !exclusive) db.jdbc.queryForObject("SELECT id FROM topology_guard WHERE id=1 LOCK IN SHARE MODE", Integer.class);
        else db.jdbc.update("UPDATE topology_guard SET id=id WHERE id=1");
        state.shared = true;
        state.exclusive = exclusive;
    }

    public void students(Collection<Long> ids) {
        // SQLite 先取得写入机会，再读取数据，避免后面从读锁升级成写锁。
        structure(false);
        var state = held();
        for (long id : new TreeSet<>(ids)) {
            if (state.students.contains(id)) continue;
            check(state.rooms.isEmpty(), "住宿数据已变化，请刷新后重试");
            check(state.students.isEmpty() || Collections.max(state.students) < id, "学生范围已变化，请刷新后重试");
            db.jdbc.update("UPDATE student SET id=id WHERE id=?", id);
            state.students.add(id);
        }
    }

    public void housing(Collection<Long> students, Collection<Long> rooms, Collection<Long> beds) {
        // 把原房间和目标房间都收集起来，再按编号从小到大加锁。
        students(students);
        var allRooms = new TreeSet<>(rooms);
        for (long student : students)
            for (var row : db.jdbc.queryForList("SELECT d.id FROM student s JOIN dormitory d ON d.dormitory_number=s.dormitory_number WHERE s.id=?", student))
                allRooms.add(number(row.get("id")));
        for (long bed : beds)
            allRooms.add(number(db.one("SELECT room_id FROM bed WHERE id=?", bed).get("room_id")));
        var held = held();
        for (long room : allRooms) {
            if (held.rooms.contains(room)) continue;
            check(held.rooms.isEmpty() || Collections.max(held.rooms) < room, "住宿范围已变化，请刷新后重试");
            db.jdbc.update("UPDATE dormitory SET id=id WHERE id=?", room);
            held.rooms.add(room);
        }
    }

    public void housing(long student, Long bed) {
        housing(List.of(student), List.of(), bed == null ? List.of() : List.of(bed));
    }

    public void room(long room) { housing(List.of(), List.of(room), List.of()); }

    public Map<String, Object> application(long id, Long extraBed) {
        structure(false);
        long student = number(db.one("SELECT student_id FROM housing_application WHERE id=?", id).get("student_id"));
        students(List.of(student));
        var row = db.one("SELECT * FROM housing_application WHERE id=?", id);
        var beds = new HashSet<Long>();
        if (row.get("target_bed_id") != null) beds.add(number(row.get("target_bed_id")));
        if (row.get("old_bed_id") != null) beds.add(number(row.get("old_bed_id")));
        if (extraBed != null) beds.add(extraBed);
        housing(List.of(student), List.of(), beds);
        return row;
    }

    public void record(String table, long id) {
        if (!Set.of("bill", "asset", "repair_request", "inspection", "visitor", "stay_request").contains(table))
            throw new IllegalArgumentException("Unsupported lock target");
        structure(false);
        db.jdbc.update("UPDATE " + table + " SET id=id WHERE id=?", id);
    }

    public void batch(String id) {
        structure(false);
        db.jdbc.update("UPDATE import_batch SET id=id WHERE id=?", id);
    }
}
