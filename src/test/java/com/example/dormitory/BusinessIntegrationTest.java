package com.example.dormitory;

import com.example.dormitory.business.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

class BusinessIntegrationTest extends IntegrationSupport {
    @Autowired
    HousingLedger ledger;
    @Autowired
    BusinessStore store;

    @BeforeEach
    void businessSeed() {
        String hash = jdbc.queryForObject("SELECT password FROM user WHERE username='20260001'", String.class);
        for (String[] u : List.of(new String[]{"manager", "manager"}, new String[]{"counselor", "counselor"}, new String[]{"worker2", "worker"}, new String[]{"20260002", "student"}, new String[]{"20260003", "student"}))
            jdbc.update("INSERT INTO user(username,password,role,is_initialized) VALUES(?,?,?,1)", u[0], hash, u[1]);
        jdbc.update("INSERT INTO manager_building(username,building_id) VALUES('manager',1)");
        jdbc.update("INSERT INTO student(id,name,student_id,grade,class_name,gender,phone) VALUES(2,'李同学','20260002','大一','一班','男','13800000002')");
        jdbc.update("INSERT INTO student(id,name,student_id,grade,gender,phone,dormitory_number) VALUES(3,'王同学','20260003','大一','女','13800000003','2号楼-0101')");
        jdbc.update("INSERT INTO dormitory(id,dormitory_number,building,room_type,capacity,current_count,gender) VALUES(3,'1号楼-0102','1号楼','二人间',2,0,'男')");
        jdbc.update("INSERT INTO student_profile(student_id,counselor) VALUES(1,'counselor'),(2,'counselor'),(3,'other')");
        ledger.initialize();
    }

    private ResultActions call(String role, MockHttpServletRequestBuilder request, Object body) throws Exception {
        String username = switch (role) {
            case "ADMIN" -> "admin";
            case "MANAGER" -> "manager";
            case "COUNSELOR" -> "counselor";
            case "WORKER" -> "worker";
            case "WORKER2" -> "worker2";
            default -> role;
        };
        String authority = role.matches("[0-9]+") ? "STUDENT" : role.equals("WORKER2") ? "WORKER" : role;
        request.with(user(username).roles(authority));
        if (!request.buildRequest(new org.springframework.mock.web.MockServletContext()).getMethod().equals("GET"))
            request.with(csrf());
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(request);
    }

    private long id(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private long seat(int room, int slot) {
        return jdbc.queryForObject("SELECT id FROM bed WHERE room_id=? AND bed_number=?", Long.class, room, slot);
    }

    private long apply(String student, String kind, Long bed) throws Exception {
        var input = new HashMap<String, Object>();
        input.put("kind", kind);
        input.put("reason", "实际住宿需求");
        if (bed != null) input.put("bedId", bed);
        return id(call(student, post("/api/business/housing/applications"), input).andExpect(status().isOk()));
    }

    private long charge(int student, String amount) throws Exception {
        var result = call("ADMIN", post("/api/business/bills"), Map.of("studentId", student, "kind", "ACCOMMODATION", "period", "2026秋季", "amount", amount, "dueAt", LocalDate.now().plusDays(10).toString(), "description", "住宿费用")).andExpect(status().isOk());
        return mapper.readTree(result.andReturn().getResponse().getContentAsString()).get("ids").get(0).asLong();
    }

    @Test
    void existingOccupantsBecomeBedsAndHistoryWithoutDuplicates() throws Exception {
        assertThat(store.count("SELECT COUNT(*) FROM bed")).isEqualTo(6);
        assertThat(store.count("SELECT COUNT(*) FROM bed WHERE student_id IS NOT NULL")).isEqualTo(2);
        ledger.initialize();
        assertThat(store.count("SELECT COUNT(*) FROM housing_history")).isEqualTo(2);
        call("20260001", get("/api/business/housing/history"), null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void managerScopeAndStudentManagementAreEnforced() throws Exception {
        call("MANAGER", get("/api/business/options"), null).andExpect(status().isOk()).andExpect(jsonPath("$.rooms.length()").value(2));
        call("MANAGER", patch("/api/business/rooms/2/state"), Map.of("state", "CLOSED", "reason", "不可越权")).andExpect(status().isForbidden());
        call("20260001", patch("/api/business/rooms/1/state"), Map.of("state", "CLOSED", "reason", "不可越权")).andExpect(status().isForbidden());
        call("COUNSELOR", get("/api/business/students"), null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        call("WORKER", get("/api/business/bills"), null).andExpect(status().isForbidden());
    }

    @Test
    void unavailableRoomsAlsoBlockLegacyAssignment() throws Exception {
        call("MANAGER", patch("/api/business/rooms/3/state"), Map.of("state", "MAINTENANCE", "reason", "漏水维修")).andExpect(status().isOk());
        call("ADMIN", post("/api/dormitories/3/students/2"), null).andExpect(status().isConflict());
        assertThat(store.count("SELECT COUNT(*) FROM student WHERE id=2 AND dormitory_number IS NULL")).isEqualTo(1);
    }

    @Test
    void reservedAndDisabledBedsCannotBeTaken() throws Exception {
        long bed = seat(1, 2);
        call("ADMIN", patch("/api/business/beds/" + bed + "/state"), Map.of("state", "RESERVED", "studentId", 2, "until", LocalDateTime.now().plusDays(1).toString(), "reason", "新生预留")).andExpect(status().isOk());
        call("ADMIN", post("/api/business/housing/move"), Map.of("studentId", 1, "bedId", bed, "reason", "不能占用他人预留")).andExpect(status().isConflict());
        call("ADMIN", patch("/api/business/beds/" + bed + "/state"), Map.of("state", "DISABLED", "reason", "床架维修")).andExpect(status().isOk());
        call("ADMIN", post("/api/business/housing/move"), Map.of("studentId", 2, "bedId", bed, "reason", "不可入住")).andExpect(status().isConflict());
    }

    @Test
    void checkinApprovalReservesAndCompletionCreatesHistory() throws Exception {
        long bed = seat(1, 2), request = apply("20260002", "CHECKIN", bed);
        call("20260002", post("/api/business/housing/applications/" + request + "/complete"), null).andExpect(status().isForbidden());
        call("COUNSELOR", post("/api/business/housing/applications/" + request + "/review"), Map.of("approve", true, "note", "准予入住", "bedId", bed)).andExpect(status().isOk());
        assertThat(store.count("SELECT COUNT(*) FROM bed WHERE id=? AND state='RESERVED'", bed)).isEqualTo(1);
        call("MANAGER", post("/api/business/housing/applications/" + request + "/complete"), null).andExpect(status().isOk());
        assertThat(store.count("SELECT COUNT(*) FROM student WHERE id=2 AND dormitory_number='1号楼-0101'")).isEqualTo(1);
        call("ADMIN", post("/api/business/housing/applications/" + request + "/complete"), null).andExpect(status().isConflict());
    }

    @Test
    void transfersAreAtomicAndPreserveHistory() throws Exception {
        long request = apply("20260001", "TRANSFER", seat(3, 1));
        call("ADMIN", post("/api/business/housing/applications/" + request + "/review"), Map.of("approve", true, "note", "批准调宿")).andExpect(status().isOk());
        call("ADMIN", post("/api/business/housing/applications/" + request + "/complete"), null).andExpect(status().isOk());
        assertThat(store.count("SELECT COUNT(*) FROM housing_history WHERE student_id=1")).isEqualTo(2);
        assertThat(store.count("SELECT COUNT(*) FROM housing_history WHERE student_id=1 AND checkout_at IS NULL")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT current_count FROM dormitory WHERE id=1", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT current_count FROM dormitory WHERE id=3", Integer.class)).isEqualTo(1);
    }

    @Test
    void checkoutChecksBillsAndAssetsAndCanCompleteAfterSettlement() throws Exception {
        long bill = charge(1, "10.00");
        long asset = id(call("MANAGER", post("/api/business/assets"), Map.of("roomId", 1, "code", "KEY-1", "name", "宿舍钥匙", "category", "KEY")).andExpect(status().isOk()));
        call("MANAGER", post("/api/business/assets/" + asset + "/issue"), Map.of("studentId", 1, "note", "入住领用")).andExpect(status().isOk());
        long request = apply("20260001", "CHECKOUT", null);
        call("ADMIN", post("/api/business/housing/applications/" + request + "/review"), Map.of("approve", true, "note", "同意退宿")).andExpect(status().isOk());
        call("ADMIN", post("/api/business/housing/applications/" + request + "/complete"), null).andExpect(status().isConflict());
        long handover = jdbc.queryForObject("SELECT id FROM handover WHERE asset_id=?", Long.class, asset);
        call("MANAGER", post("/api/business/handovers/" + handover + "/return"), Map.of("condition", "GOOD", "note", "钥匙完好归还")).andExpect(status().isOk());
        call("ADMIN", post("/api/business/housing/applications/" + request + "/complete"), null).andExpect(status().isConflict());
        call("ADMIN", post("/api/business/bills/" + bill + "/payments"), Map.of("kind", "PAYMENT", "amount", "10.00", "method", "BANK", "referenceNo", "BANK001", "reason", "核对到账")).andExpect(status().isOk());
        call("ADMIN", post("/api/business/housing/applications/" + request + "/complete"), null).andExpect(status().isOk());
        assertThat(store.count("SELECT COUNT(*) FROM student WHERE id=1 AND dormitory_number IS NULL")).isEqualTo(1);
        assertThat(store.count("SELECT COUNT(*) FROM housing_history WHERE student_id=1 AND checkout_at IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void archiveDoesNotDeleteStudentsAccountsOrHistory() throws Exception {
        call("ADMIN", post("/api/business/housing/move"), Map.of("studentId", 1, "reason", "毕业退宿")).andExpect(status().isOk());
        call("ADMIN", patch("/api/business/students/1/profile"), Map.of("lifecycle", "GRADUATED", "college", "计算机学院", "major", "软件工程", "counselor", "counselor", "reason", "毕业归档")).andExpect(status().isOk());
        assertThat(store.count("SELECT COUNT(*) FROM student WHERE id=1")).isEqualTo(1);
        assertThat(store.count("SELECT COUNT(*) FROM user WHERE username='20260001'")).isEqualTo(1);
        assertThat(store.count("SELECT COUNT(*) FROM housing_history WHERE student_id=1")).isEqualTo(1);
        call("20260001", get("/api/me"), null).andExpect(status().isUnauthorized());
    }

    @Test
    void leadersAreExplicitlyAppointedAndNotReelectedOnOccupancyChanges() throws Exception {
        call("ADMIN", post("/api/business/housing/move"), Map.of("studentId", 2, "bedId", seat(1, 2), "reason", "入住")).andExpect(status().isOk());
        assertThat(store.count("SELECT COUNT(*) FROM student WHERE id=1 AND is_dormitory_leader=1")).isEqualTo(1);
        call("MANAGER", post("/api/business/rooms/1/leader"), Map.of("studentId", 2, "reason", "宿舍成员推选")).andExpect(status().isOk());
        call("ADMIN", post("/api/business/housing/move"), Map.of("studentId", 1, "reason", "退宿")).andExpect(status().isOk());
        assertThat(store.count("SELECT COUNT(*) FROM student WHERE id=2 AND is_dormitory_leader=1")).isEqualTo(1);
        assertThat(store.count("SELECT COUNT(*) FROM leader_record WHERE room_id=1 AND ended_at IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void workOrdersRequireAssignmentAndStudentAcceptance() throws Exception {
        call("WORKER", get("/api/repairs"), null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        call("MANAGER", post("/api/business/repairs/1/dispatch"), Map.of("assignee", "worker", "priority", "URGENT", "note", "灯具故障")).andExpect(status().isOk());
        call("WORKER2", post("/api/business/repairs/1/start"), Map.of("note", "越权")).andExpect(status().isForbidden());
        call("WORKER", post("/api/business/repairs/1/start"), Map.of("note", "到场检查")).andExpect(status().isOk());
        call("WORKER", post("/api/business/repairs/1/finish"), Map.of("note", "已更换灯具")).andExpect(status().isOk());
        call("20260001", post("/api/business/repairs/1/review"), Map.of("approve", false, "note", "仍然闪烁", "rating", 2)).andExpect(status().isOk());
        call("WORKER", post("/api/business/repairs/1/finish"), Map.of("note", "重新处理接线")).andExpect(status().isOk());
        call("20260001", post("/api/business/repairs/1/review"), Map.of("approve", true, "note", "确认恢复", "rating", 5)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM repair_request WHERE id=1", String.class)).isEqualTo("已完成");
        assertThat(store.count("SELECT COUNT(*) FROM repair_event WHERE repair_id=1")).isEqualTo(6);
    }

    @Test
    void announcementsAndReceiptsRespectAudience() throws Exception {
        long id = id(call("ADMIN", post("/api/business/announcements"), Map.of("title", "女寝检修", "content", "明日检修", "audience", "BUILDING", "buildingId", 2)).andExpect(status().isOk()));
        call("20260001", get("/api/business/announcements"), null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        call("20260003", post("/api/business/announcements/" + id + "/read"), null).andExpect(status().isOk());
        call("20260003", get("/api/business/announcements"), null).andExpect(jsonPath("$[0].is_read").value(1));
        call("MANAGER", post("/api/business/announcements"), Map.of("title", "非法全校通知", "content", "不能发布", "audience", "ALL")).andExpect(status().isForbidden());
    }

    @Test
    void holidayApplicationsAndActualReturnAreRecorded() throws Exception {
        long id = id(call("20260001", post("/api/business/stays"), Map.of("kind", "HOLIDAY", "startAt", LocalDateTime.now().plusDays(1).toString(), "endAt", LocalDateTime.now().plusDays(3).toString(), "reason", "参加学校活动", "contactPhone", "13800000001")).andExpect(status().isOk()));
        call("MANAGER", post("/api/business/stays/" + id + "/review"), Map.of("approve", true, "note", "同意留宿")).andExpect(status().isOk());
        call("20260003", post("/api/business/stays/" + id + "/return"), null).andExpect(status().isForbidden());
        call("20260001", post("/api/business/stays/" + id + "/return"), null).andExpect(status().isOk());
        assertThat(store.count("SELECT COUNT(*) FROM stay_request WHERE id=? AND actual_return_at IS NOT NULL", id)).isEqualTo(1);
    }

    @Test
    void visitorsRequireCorrectRoomAndLeaveOnce() throws Exception {
        var body = Map.of("roomId", 1, "studentId", 3, "visitorName", "张家长", "phone", "13800000000", "expectedLeaveAt", LocalDateTime.now().plusHours(1).toString());
        call("MANAGER", post("/api/business/visitors"), body).andExpect(status().isConflict());
        long id = id(call("MANAGER", post("/api/business/visitors"), Map.of("roomId", 1, "studentId", 1, "visitorName", "张家长", "phone", "13800000000", "expectedLeaveAt", LocalDateTime.now().plusHours(1).toString())).andExpect(status().isOk()));
        call("MANAGER", post("/api/business/visitors/" + id + "/leave"), null).andExpect(status().isOk());
        call("MANAGER", post("/api/business/visitors/" + id + "/leave"), null).andExpect(status().isConflict());
    }

    @Test
    void inspectionsHaveRectificationAndReviewLifecycle() throws Exception {
        long id = id(call("MANAGER", post("/api/business/inspections"), Map.of("roomId", 1, "kind", "SAFETY", "score", 70, "detail", "疏散通道需清理", "dueAt", LocalDateTime.now().plusDays(2).toString())).andExpect(status().isOk()));
        call("20260003", post("/api/business/inspections/" + id + "/rectify"), Map.of("note", "其他宿舍不可操作")).andExpect(status().isForbidden());
        call("20260001", post("/api/business/inspections/" + id + "/rectify"), Map.of("note", "已清理通道")).andExpect(status().isOk());
        call("MANAGER", post("/api/business/inspections/" + id + "/review"), Map.of("approve", true, "note", "复查通过")).andExpect(status().isOk());
        call("20260001", post("/api/business/inspections/" + id + "/rectify"), Map.of("note", "关闭后不可修改")).andExpect(status().isConflict());
    }

    @Test
    void roomBillSplitsCentsExactlyAndRefundsCannotExceedPayment() throws Exception {
        call("ADMIN", post("/api/business/housing/move"), Map.of("studentId", 2, "bedId", seat(1, 2), "reason", "入住")).andExpect(status().isOk());
        call("ADMIN", post("/api/business/bills"), Map.of("roomId", 1, "kind", "WATER", "period", "2026-10", "amount", "1.01", "dueAt", LocalDate.now().plusDays(7).toString(), "description", "宿舍水费分摊")).andExpect(status().isOk());
        assertThat(store.count("SELECT SUM(amount_cents) FROM bill")).isEqualTo(101);
        assertThat(jdbc.queryForList("SELECT amount_cents FROM bill ORDER BY student_id", Long.class)).containsExactly(51L, 50L);
        long bill = jdbc.queryForObject("SELECT id FROM bill WHERE student_id=1", Long.class);
        call("20260001", post("/api/business/bills/" + bill + "/payments"), Map.of("kind", "PAYMENT", "amount", "0.51", "method", "BANK", "referenceNo", "HACK", "reason", "不可自认缴费")).andExpect(status().isForbidden());
        call("ADMIN", post("/api/business/bills/" + bill + "/payments"), Map.of("kind", "PAYMENT", "amount", "0.51", "method", "BANK", "referenceNo", "PAY1", "reason", "已核实")).andExpect(status().isOk());
        call("ADMIN", post("/api/business/bills/" + bill + "/payments"), Map.of("kind", "REFUND", "amount", "0.52", "method", "BANK", "referenceNo", "REF1", "reason", "超额退款禁止")).andExpect(status().isConflict());
        call("ADMIN", post("/api/business/bills/" + bill + "/payments"), Map.of("kind", "REFUND", "amount", "0.20", "method", "BANK", "referenceNo", "REF2", "reason", "核实退费")).andExpect(status().isOk());
        call("20260003", get("/api/business/bills/" + bill + "/payments"), null).andExpect(status().isForbidden());
    }

    @Test
    void assetCustodyEnforcesOwnershipAndPreservesLostRecords() throws Exception {
        long asset = id(call("MANAGER", post("/api/business/assets"), Map.of("roomId", 1, "code", "REMOTE-1", "name", "空调遥控器", "category", "REMOTE")).andExpect(status().isOk()));
        call("MANAGER", post("/api/business/assets/" + asset + "/issue"), Map.of("studentId", 3, "note", "不是本宿舍学生")).andExpect(status().isConflict());
        call("MANAGER", post("/api/business/assets/" + asset + "/issue"), Map.of("studentId", 1, "note", "入住领用")).andExpect(status().isOk());
        long h = jdbc.queryForObject("SELECT id FROM handover WHERE asset_id=?", Long.class, asset);
        call("MANAGER", post("/api/business/handovers/" + h + "/return"), Map.of("condition", "LOST", "note", "遗失已登记，后续核定赔偿")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT state FROM asset WHERE id=?", String.class, asset)).isEqualTo("LOST");
        assertThat(store.count("SELECT COUNT(*) FROM handover WHERE id=? AND returned_at IS NOT NULL", h)).isEqualTo(1);
    }

    private byte[] workbook(boolean formula) throws Exception {
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var out = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("students");
            String[] headers = {"studentId", "name", "grade", "className", "gender", "phone", "college", "major", "counselor", "initialPassword"};
            var header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
            String[] values = {"20269999", "新同学", "大一", "一班", "男", "13800009999", "计算机学院", "软件工程", "counselor", "InitialImport123!"};
            var row = sheet.createRow(1);
            for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
            if (formula) row.getCell(1).setCellFormula("1+1");
            workbook.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void importRequiresPreviewHidesPasswordsAndRejectsReplay() throws Exception {
        var file = new MockMultipartFile("file", "students.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook(false));
        var preview = mvc.perform(multipart("/api/business/imports/preview").file(file).with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isOk()).andReturn();
        String json = preview.getResponse().getContentAsString();
        assertThat(json).doesNotContain("InitialImport123!", "passwordHash");
        String id = mapper.readTree(json).get("previewId").asText();
        call("MANAGER", post("/api/business/imports/" + id + "/confirm"), null).andExpect(status().isForbidden());
        call("ADMIN", post("/api/business/imports/" + id + "/confirm"), null).andExpect(status().isOk());
        call("ADMIN", post("/api/business/imports/" + id + "/confirm"), null).andExpect(status().isConflict());
        assertThat(passwords.matches("InitialImport123!", jdbc.queryForObject("SELECT password FROM user WHERE username='20269999'", String.class))).isTrue();
    }

    @Test
    void formulaImportsAreRejected() throws Exception {
        mvc.perform(multipart("/api/business/imports/preview").file(new MockMultipartFile("file", "bad.xlsx", "application/octet-stream", workbook(true))).with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isBadRequest());
    }

    @Test
    void allocationPreviewDetectsConflictsAndCommitsOnlyOnce() throws Exception {
        var preview = call("ADMIN", post("/api/business/allocations/preview"), Map.of("studentIds", List.of(2), "buildingIds", List.of(1))).andExpect(status().isOk()).andReturn();
        var json = mapper.readTree(preview.getResponse().getContentAsString());
        String id = json.get("previewId").asText();
        long bed = json.get("items").get(0).get("bedId").asLong();
        call("MANAGER", post("/api/business/allocations/" + id + "/confirm"), null).andExpect(status().isForbidden());
        call("ADMIN", patch("/api/business/beds/" + bed + "/state"), Map.of("state", "DISABLED", "reason", "预览后维修")).andExpect(status().isOk());
        call("ADMIN", post("/api/business/allocations/" + id + "/confirm"), null).andExpect(status().isConflict());
        assertThat(store.count("SELECT COUNT(*) FROM student WHERE id=2 AND dormitory_number IS NULL")).isEqualTo(1);
        var next = call("ADMIN", post("/api/business/allocations/preview"), Map.of("studentIds", List.of(2), "buildingIds", List.of(1))).andExpect(status().isOk()).andReturn();
        String nextId = mapper.readTree(next.getResponse().getContentAsString()).get("previewId").asText();
        call("ADMIN", post("/api/business/allocations/" + nextId + "/confirm"), null).andExpect(status().isOk());
        call("ADMIN", post("/api/business/allocations/" + nextId + "/confirm"), null).andExpect(status().isConflict());
    }

    @Test
    void passwordChangesRevokeOtherSessionsAndNeverEnterAuditDetails() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute("credentialVersion", 0L);
        call("20260001", put("/api/business/account/password"), Map.of("oldPassword", "TestStudent123!", "newPassword", "NewSecurePassword123!")).andExpect(status().isOk());
        assertThat(passwords.matches("NewSecurePassword123!", jdbc.queryForObject("SELECT password FROM user WHERE username='20260001'", String.class))).isTrue();
        call("20260001", get("/api/auth/me").session(session), null).andExpect(status().isUnauthorized());
        String audit = call("ADMIN", get("/api/business/audit"), null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(audit).doesNotContain("NewSecurePassword123!", "TestStudent123!");
        call("20260003", get("/api/business/audit"), null).andExpect(status().isForbidden());
    }

    @Test
    void staffAccountsHaveForcedPasswordChangeAndScopedAssignments() throws Exception {
        call("ADMIN", post("/api/business/accounts"), Map.of("username", "manager_new", "realName", "新宿管", "role", "MANAGER", "initialPassword", "InitialStaff123!", "buildingIds", List.of(2))).andExpect(status().isOk());
        assertThat(store.count("SELECT COUNT(*) FROM manager_building WHERE username='manager_new' AND building_id=2")).isEqualTo(1);
        assertThat(store.count("SELECT COUNT(*) FROM account_policy WHERE username='manager_new' AND force_password_change=1")).isEqualTo(1);
        mvc.perform(get("/api/business/options").with(user("manager_new").roles("MANAGER"))).andExpect(status().isForbidden());
        call("20260001", post("/api/business/accounts"), Map.of("username", "illegal_staff", "realName", "非法", "role", "WORKER", "initialPassword", "InitialStaff123!", "buildingIds", List.of())).andExpect(status().isForbidden());
    }

    @Test
    void changingSettingsRequiresAdminAndWritesAreCsrfProtected() throws Exception {
        mvc.perform(post("/api/business/housing/applications").with(user("20260002").roles("STUDENT")).contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"CHECKIN\",\"reason\":\"缺少CSRF\"}")).andExpect(status().isForbidden());
        call("20260001", put("/api/business/settings"), Map.of("checkoutRequiresSettledBills", false, "checkoutRequiresReturnedAssets", false, "allResidentsCanReport", false)).andExpect(status().isForbidden());
        call("ADMIN", put("/api/business/settings"), Map.of("checkoutRequiresSettledBills", true, "checkoutRequiresReturnedAssets", true, "allResidentsCanReport", true)).andExpect(status().isOk());
    }

    @Test
    void paymentRaceCannotRecordMoreThanTheBillAmount() throws Exception {
        long bill = charge(1, "10.00");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Callable<Integer>> operations = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                int index = i;
                operations.add(() -> call("ADMIN", post("/api/business/bills/" + bill + "/payments"), Map.of("kind", "PAYMENT", "amount", "10.00", "method", "BANK", "referenceNo", "RACE-" + index, "reason", "并发核验")).andReturn().getResponse().getStatus());
            }
            var responses = executor.invokeAll(operations);
            List<Integer> statuses = new ArrayList<>();
            for (var response : responses) statuses.add(response.get());
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(store.count("SELECT SUM(amount_cents) FROM payment WHERE bill_id=?", bill)).isEqualTo(1000);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void previewOwnershipExpiryAndUnknownFieldsAreEnforced() throws Exception {
        var file = new MockMultipartFile("file", "students.xlsx", "application/octet-stream", workbook(false));
        var preview = mvc.perform(multipart("/api/business/imports/preview").file(file).with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isOk()).andReturn();
        String id = mapper.readTree(preview.getResponse().getContentAsString()).get("previewId").asText();
        jdbc.update("UPDATE import_batch SET expires_at='2000-01-01T00:00:00' WHERE id=?", id);
        call("ADMIN", post("/api/business/imports/" + id + "/confirm"), null).andExpect(status().isConflict());
        assertThat(store.count("SELECT COUNT(*) FROM student WHERE student_id='20269999'")).isZero();
        call("20260002", post("/api/business/housing/applications"), Map.of("kind", "CHECKIN", "reason", "资料", "role", "ADMIN")).andExpect(status().isBadRequest());
    }

    @Test
    void oversizedJsonIsRejectedBeforeBusinessHandling() throws Exception {
        mvc.perform(post("/api/business/announcements").with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("x".repeat(2 * 1024 * 1024 + 1))).andExpect(status().isPayloadTooLarge());
    }

    @Test
    void notificationsCannotBeReadByAnotherStudent() throws Exception {
        charge(1, "2.00");
        long id = jdbc.queryForObject("SELECT id FROM notification WHERE recipient='20260001' ORDER BY id DESC LIMIT 1", Long.class);
        call("20260003", post("/api/business/notifications/" + id + "/read"), null).andExpect(status().isConflict());
        call("20260001", post("/api/business/notifications/" + id + "/read"), null).andExpect(status().isOk());
        call("COUNSELOR", get("/api/business/bills/1/payments"), null).andExpect(status().isForbidden());
    }

    @Test
    void inspectionPhotosAreReencodedAndRequireRoomAuthorization() throws Exception {
        long id = id(call("MANAGER", post("/api/business/inspections"), Map.of("roomId", 1, "kind", "HYGIENE", "score", 80, "detail", "卫生整改", "dueAt", LocalDateTime.now().plusDays(1).toString())).andExpect(status().isOk()));
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(3, 3, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out);
        mvc.perform(multipart("/api/business/inspections/" + id + "/image").file(new MockMultipartFile("image", "../bad.html", "image/png", out.toByteArray())).with(user("manager").roles("MANAGER")).with(csrf())).andExpect(status().isOk());
        call("20260001", get("/api/business/inspections/" + id + "/image"), null).andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG));
        call("20260003", get("/api/business/inspections/" + id + "/image"), null).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/business/inspections/" + id + "/image").file(new MockMultipartFile("image", "evil.jpg", "image/jpeg", "<script>bad</script>".getBytes())).with(user("manager").roles("MANAGER")).with(csrf())).andExpect(status().isBadRequest());
    }

    @Test
    void importArchiveExpansionIsBounded() throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("large.xml"));
            byte[] data = new byte[8192];
            for (int i = 0; i < 2200; i++) zip.write(data);
            zip.closeEntry();
        }
        mvc.perform(multipart("/api/business/imports/preview").file(new MockMultipartFile("file", "oversized.xlsx", "application/octet-stream", out.toByteArray())).with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isBadRequest());
    }

    @Test
    void existingStudentAccountCanOnlyBeProvisionedByAdmin() throws Exception {
        jdbc.update("INSERT INTO student(id,name,student_id,grade,gender,phone) VALUES(4,'旧档案同学','20260004','大一','男','13800000004')");
        var input = Map.of("newPassword", "InitialExisting123!", "reason", "为已建档学生开通账号");
        call("20260001", post("/api/business/students/4/account"), input).andExpect(status().isForbidden());
        call("ADMIN", post("/api/business/students/4/account"), input).andExpect(status().isOk());
        call("ADMIN", post("/api/business/students/4/account"), input).andExpect(status().isConflict());
        assertThat(store.count("SELECT COUNT(*) FROM account_policy WHERE username='20260004' AND force_password_change=1")).isEqualTo(1);
        assertThat(passwords.matches("InitialExisting123!", jdbc.queryForObject("SELECT password FROM user WHERE username='20260004'", String.class))).isTrue();
    }

    @Test
    void pagedListsHaveBoundedStableResultsAndScopedTotals() throws Exception {
        call("ADMIN", get("/api/business/lists/students").param("size", "2"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[0].id").value(3));
        call("ADMIN", get("/api/business/lists/students").param("size", "2").param("page", "2"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(1));
        call("MANAGER", get("/api/business/lists/students"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        call("MANAGER", get("/api/business/lists/students").param("search", "王同学"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        call("COUNSELOR", get("/api/business/lists/students"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        call("20260003", get("/api/business/lists/students"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(3)).andExpect(jsonPath("$.total").value(1));
        for (String resource : List.of("beds", "rooms", "applications", "history", "leaders", "accounts", "audit", "announcements", "notifications", "stays", "visitors", "inspections", "bills", "assets", "handovers", "repairs"))
            call("ADMIN", get("/api/business/lists/" + resource).param("search", "test"), null)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray()).andExpect(jsonPath("$.size").value(20));
        call("WORKER", get("/api/business/lists/students"), null).andExpect(status().isForbidden());
        call("20260001", get("/api/business/lists/accounts"), null).andExpect(status().isForbidden());
    }

    @Test
    void pendingTransfersAndPastRoomsKeepTheirManagerVisibility() throws Exception {
        jdbc.update("INSERT INTO housing_application(student_id,kind,target_bed_id,reason,created_at) VALUES(3,'TRANSFER',?,'待调入','2026-10-31')", seat(1, 2));
        call("MANAGER", get("/api/business/lists/applications"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        call("MANAGER", get("/api/business/lists/students"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        jdbc.update("INSERT INTO housing_history(student_id,student_number,room_id,room_number,bed_id,bed_number,checkin_at,checkout_at,handled_by) VALUES(3,'20260003',1,'1号楼-0101',?,2,'2025-01-01','2025-02-01','admin')", seat(1, 2));
        jdbc.update("UPDATE housing_application SET state='COMPLETED'");
        call("MANAGER", get("/api/business/lists/history").param("search", "20260003"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void bedSearchCannotRevealRedactedNamesAndOptionsLoadSelectively() throws Exception {
        call("20260001", get("/api/business/lists/beds"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4));
        // A second student in the same room remains hidden, even through search/count.
        call("ADMIN", post("/api/business/housing/move"), Map.of("studentId", 2, "bedId", seat(1, 2), "reason", "入住")).andExpect(status().isOk());
        call("20260001", get("/api/business/lists/beds").param("search", "李同学"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        var response = call("20260001", get("/api/business/lists/beds"), null).andExpect(status().isOk()).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain("李同学", "reserved_for");
        call("COUNSELOR", get("/api/business/lists/beds"), null).andExpect(status().isOk());
        call("ADMIN", get("/api/business/options").param("include", "workers"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.workers").isArray())
                .andExpect(jsonPath("$.students").doesNotExist()).andExpect(jsonPath("$.rooms").doesNotExist());
        call("ADMIN", get("/api/business/options").param("include", "beds,buildings"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.beds").isArray()).andExpect(jsonPath("$.buildings").isArray());
        call("ADMIN", get("/api/business/options").param("include", "passwords"), null).andExpect(status().isBadRequest());
    }

    @Test
    void workerPagesOnlyContainAssignedOrdersAndStudentPagesRespectOwnership() throws Exception {
        call("MANAGER", post("/api/business/repairs/1/dispatch"), Map.of("assignee", "worker", "priority", "NORMAL", "note", "派单")).andExpect(status().isOk());
        call("WORKER", get("/api/business/lists/repairs"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(1));
        call("WORKER2", get("/api/business/lists/repairs"), null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        charge(1, "3.00"); charge(3, "4.00");
        call("20260001", get("/api/business/lists/bills"), null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        call("20260003", get("/api/business/lists/bills").param("search", "张同学"), null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void oldMessagesAndAuditRecordsAreReachableBeyondPreviousCaps() throws Exception {
        for (int i = 0; i < 1005; i++) {
            jdbc.update("INSERT INTO notification(recipient,title,content,created_at) VALUES('20260001',?,?,?)", "消息" + i, "内容", "2026-10-31");
            store.audit("admin", "PAGE_TEST", "test", i, Map.of());
        }
        call("20260001", get("/api/business/lists/notifications").param("page", "51"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1005)).andExpect(jsonPath("$.items.length()").value(5));
        call("20260003", get("/api/business/lists/notifications"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        call("ADMIN", get("/api/business/lists/audit").param("page", "51").param("search", "PAGE_TEST"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1005)).andExpect(jsonPath("$.items.length()").value(5));
    }

    @Test
    void paginationRejectsUnboundedRequestsAndSearchTreatsWildcardsLiterally() throws Exception {
        for (String[] parameter : List.of(new String[]{"page", "0"}, new String[]{"page", "1000001"}, new String[]{"size", "0"}, new String[]{"size", "101"}, new String[]{"search", "x".repeat(201)}))
            call("ADMIN", get("/api/business/lists/students").param(parameter[0], parameter[1]), null).andExpect(status().isBadRequest());
        for (String search : List.of("%", "_", "' OR 1=1 --", "!"))
            call("ADMIN", get("/api/business/lists/students").param("search", search), null)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        call("ADMIN", get("/api/business/lists/passwords"), null).andExpect(status().isNotFound());
    }

    @Test
    void indexesAreIdempotentAndUsedForCommonLookups() throws Exception {
        initializer.run();
        boolean mysql;
        try (var connection = jdbc.getDataSource().getConnection()) {
            mysql = connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL");
        }
        for (String[] lookup : List.of(new String[]{"notification", "recipient='20260001'", "idx_notification_recipient"},
                new String[]{"housing_history", "student_id=1", "idx_history_student"},
                new String[]{"payment", "bill_id=1", "idx_payment_bill"},
                new String[]{"repair_work", "assignee='worker'", "idx_repair_assignee"})) {
            String sql = "SELECT * FROM " + lookup[0] + " WHERE " + lookup[1] + " ORDER BY " + (lookup[0].equals("repair_work") ? "repair_id" : "id") + " DESC LIMIT 20";
            var plan = jdbc.queryForList((mysql ? "EXPLAIN " : "EXPLAIN QUERY PLAN ") + sql);
            System.out.println("Query plan for " + lookup[0] + ": " + plan);
            if (mysql) assertThat(plan).anySatisfy(row -> assertThat(row.get("key")).isEqualTo(lookup[2]));
            else assertThat(plan.toString()).contains(lookup[2]);
        }
    }
}
