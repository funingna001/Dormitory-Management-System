package com.example.dormitory.business;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.time.LocalDate;
import java.math.BigDecimal;

import static com.example.dormitory.business.BusinessStore.*;

@Service
@Transactional
public class FinanceAssetService {
    private final BusinessStore db;
    private final DomainLocks locks;
    private final BusinessLists lists;
    private final BusinessAccess access;

    public FinanceAssetService(BusinessStore db, BusinessAccess access, BusinessLists lists, DomainLocks locks) {
        this.db = db;
        this.locks = locks;
        this.lists = lists;
        this.access = access;
    }

    public List<Map<String, Object>> bills() {
        return lists.all("bills");
    }

    private void lockBill(long id) {
        locks.structure(false);
        long student = number(db.one("SELECT student_id FROM bill WHERE id=?", id).get("student_id"));
        locks.students(List.of(student));
        locks.record("bill", id);
    }

    private void lockAsset(long id, Long student) {
        locks.structure(false);
        long room = number(db.one("SELECT room_id FROM asset WHERE id=?", id).get("room_id"));
        locks.housing(student == null ? List.of() : List.of(student), List.of(room), List.of());
        locks.record("asset", id);
    }

    public static long cents(BigDecimal amount) {
        // 金额按“分”保存，避免使用小数计算时出现精度误差。
        try {
            return amount.movePointRight(2).longValueExact();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("金额最多两位小数");
        }
    }

    public List<Long> createBill(BusinessRequests.Bill input) {
        access.roles("ADMIN");
        locks.structure(false);
        LocalDate.parse(input.dueAt());
        long total = cents(input.amount());
        invalid(total > 0 && total <= 9999999999L, "金额超出范围");
        invalid((input.studentId() != null) ^ (input.roomId() != null), "应选择单个学生或宿舍分摊其中一种方式");
        if (input.studentId() != null) locks.students(List.of(input.studentId()));
        else {
            var residents = db.jdbc.queryForList("SELECT s.id FROM student s JOIN dormitory d ON d.dormitory_number=s.dormitory_number WHERE d.id=? ORDER BY s.id", Long.class, input.roomId());
            locks.housing(residents, List.of(input.roomId()), List.of());
            check(residents.equals(db.jdbc.queryForList("SELECT s.id FROM student s JOIN dormitory d ON d.dormitory_number=s.dormitory_number WHERE d.id=? ORDER BY s.id", Long.class, input.roomId())), "入住名单已变化，请刷新后重新生成账单");
        }
        List<Map<String, Object>> students;
        if (input.studentId() != null)
            students = List.of(db.one("SELECT * FROM student WHERE id=?", input.studentId()));
        else {
            var room = db.one("SELECT * FROM dormitory WHERE id=?", input.roomId());
            students = db.jdbc.queryForList("SELECT * FROM student WHERE dormitory_number=? ORDER BY id", room.get("dormitory_number"));
        }
        check(!students.isEmpty(), "没有可生成账单的住宿学生");
        check(total >= students.size(), "分摊后每人金额应至少一分");
        List<Long> ids = new ArrayList<>();
        long base = total / students.size(), remainder = total % students.size();
        for (int i = 0; i < students.size(); i++) {
            var student = students.get(i);
            long amount = base + (i < remainder ? 1 : 0);
            long id = db.insert("INSERT INTO bill(student_id,room_number,kind,period,amount_cents,due_at,description,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?)", student.get("id"), student.get("dormitory_number"), input.kind(), input.period(), amount, input.dueAt(), input.description(), access.actor(), now());
            ids.add(id);
            db.notify(text(student.get("student_id")), "新账单待处理", input.description() + "，金额 " + BigDecimal.valueOf(amount, 2).toPlainString() + " 元");
        }
        db.audit(access.actor(), "BILL_CREATE", "bill", ids, Map.of("totalCents", total, "kind", input.kind(), "split", input.roomId() != null));
        return ids;
    }

    public List<Map<String, Object>> payments(long billId) {
        access.roles("ADMIN", "MANAGER", "STUDENT");
        var bill = db.one("SELECT * FROM bill WHERE id=?", billId);
        access.student(number(bill.get("student_id")));
        return db.jdbc.queryForList("SELECT * FROM payment WHERE bill_id=? ORDER BY id", billId);
    }

    public void pay(long id, BusinessRequests.Payment input) {
        // 同一账单先加锁再核对金额，避免同时缴费时重复记账。
        access.roles("ADMIN");
        lockBill(id);
        var bill = db.one("SELECT * FROM bill WHERE id=?", id);
        check(text(bill.get("state")).equals("ACTIVE"), "账单已作废");
        long amount = cents(input.amount());
        invalid(amount > 0, "金额必须大于零");
        long net = db.count("SELECT COALESCE(SUM(CASE WHEN kind='PAYMENT' THEN amount_cents ELSE -amount_cents END),0) FROM payment WHERE bill_id=?", id);
        check(input.kind().equals("PAYMENT") ? amount <= number(bill.get("amount_cents")) - net : amount <= net, "缴费超过欠款或退款超过已缴金额");
        db.jdbc.update("INSERT INTO payment(bill_id,kind,amount_cents,method,reference_no,reason,actor,created_at) VALUES(?,?,?,?,?,?,?,?)", id, input.kind(), amount, input.method(), input.referenceNo(), input.reason(), access.actor(), now());
        db.audit(access.actor(), "BILL_" + input.kind(), "bill", id, Map.of("amountCents", amount, "referenceNo", input.referenceNo()));
        db.notify(text(db.one("SELECT student_id FROM student WHERE id=?", bill.get("student_id")).get("student_id")), "账单登记更新", input.kind().equals("PAYMENT") ? "缴费已登记" : "退款已登记");
    }

    public void voidBill(long id, BusinessRequests.Note note) {
        access.roles("ADMIN");
        lockBill(id);
        var bill = db.one("SELECT * FROM bill WHERE id=?", id);
        check(db.count("SELECT COUNT(*) FROM payment WHERE bill_id=?", id) == 0, "已有流水的账单不可作废，应登记退款并保留流水");
        check(text(bill.get("state")).equals("ACTIVE"), "账单已作废");
        db.jdbc.update("UPDATE bill SET state='VOID' WHERE id=?", id);
        db.audit(access.actor(), "BILL_VOID", "bill", id, note);
    }

    public List<Map<String, Object>> assets() {
        return lists.all("assets");
    }

    public long asset(BusinessRequests.Asset input) {
        locks.room(input.roomId());
        access.staffRoom(input.roomId());
        db.one("SELECT id FROM dormitory WHERE id=?", input.roomId());
        long id = db.insert("INSERT INTO asset(room_id,code,name,category,description) VALUES(?,?,?,?,?)", input.roomId(), input.code(), input.name(), input.category(), input.description());
        db.audit(access.actor(), "ASSET_CREATE", "asset", id, input);
        return id;
    }

    public void assetState(long id, BusinessRequests.AssetState input) {
        lockAsset(id, null);
        var asset = db.one("SELECT * FROM asset WHERE id=?", id);
        access.staffRoom(number(asset.get("room_id")));
        check(db.count("SELECT COUNT(*) FROM handover WHERE asset_id=? AND returned_at IS NULL", id) == 0, "物品尚未归还，请先登记归还或丢失");
        check(!text(asset.get("state")).equals("RETIRED"), "已报废资产不可重新启用");
        db.jdbc.update("UPDATE asset SET state=? WHERE id=?", input.state(), id);
        db.audit(access.actor(), "ASSET_STATE", "asset", id, input);
    }

    public void issue(long id, BusinessRequests.Issue input) {
        lockAsset(id, input.studentId());
        var asset = db.one("SELECT * FROM asset WHERE id=?", id);
        access.staffRoom(number(asset.get("room_id")));
        check(text(asset.get("state")).equals("AVAILABLE"), "此物品不可领用");
        var room = db.one("SELECT dormitory_number FROM dormitory WHERE id=?", asset.get("room_id"));
        var student = db.one("SELECT * FROM student WHERE id=?", input.studentId());
        check(Objects.equals(student.get("dormitory_number"), room.get("dormitory_number")), "学生未入住物品所在宿舍");
        db.jdbc.update("INSERT INTO handover(asset_id,student_id,active_asset_id,issued_at,issue_note,issued_by) VALUES(?,?,?,?,?,?)", id, input.studentId(), id, now(), input.note(), access.actor());
        db.jdbc.update("UPDATE asset SET state='ISSUED' WHERE id=?", id);
        db.audit(access.actor(), "ASSET_ISSUE", "asset", id, input);
        db.notify(text(student.get("student_id")), "物品领用登记", asset.get("name") + "：" + input.note());
    }

    public List<Map<String, Object>> handovers() {
        return lists.all("handovers");
    }

    public void returnAsset(long id, BusinessRequests.ReturnAsset input) {
        locks.structure(false);
        var probe = db.one("SELECT asset_id,student_id FROM handover WHERE id=?", id);
        lockAsset(number(probe.get("asset_id")), number(probe.get("student_id")));
        var row = db.one("SELECT h.*,a.room_id FROM handover h JOIN asset a ON a.id=h.asset_id WHERE h.id=?", id);
        access.staffRoom(number(row.get("room_id")));
        check(row.get("returned_at") == null, "物品已经归还");
        db.jdbc.update("UPDATE handover SET returned_at=?,active_asset_id=NULL,return_condition=?,return_note=?,returned_by=? WHERE id=?", now(), input.condition(), input.note(), access.actor(), id);
        db.jdbc.update("UPDATE asset SET state=? WHERE id=?", Map.of("GOOD", "AVAILABLE", "DAMAGED", "REPAIR", "LOST", "LOST").get(input.condition()), row.get("asset_id"));
        db.audit(access.actor(), "ASSET_RETURN", "handover", id, input);
    }
}
