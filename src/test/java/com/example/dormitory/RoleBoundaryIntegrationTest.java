package com.example.dormitory;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

class RoleBoundaryIntegrationTest extends IntegrationSupport {
    @BeforeEach void personalRecords() {
        jdbc.update("INSERT INTO student(id,name,student_id,grade,gender,phone,dormitory_number) VALUES(2,'其他学生','20260002','大一','女','13800000002','2号楼-0101')");
        jdbc.update("INSERT INTO bill(student_id,room_number,kind,period,amount_cents,due_at,description,created_by,created_at) VALUES(1,'1号楼-0101','WATER','2026-11',100,'2026-12-01','本人账单','admin','2026-11-01'),(2,'2号楼-0101','WATER','2026-11',200,'2026-12-01','他人账单','admin','2026-11-01')");
    }
    @Test void studentCannotReadAdministrativeApis() throws Exception {
        for(String path : new String[]{"/api/students", "/api/dormitories", "/api/buildings", "/api/data/statistics", "/api/export/all", "/api/ai/status", "/api/business/accounts", "/api/business/settings", "/api/business/audit", "/api/business/lists/accounts", "/api/business/lists/audit", "/api/business/lists/consistency", "/api/business/options/workers", "/api/business/imports/template"}) {
            mvc.perform(get(path).with(user("20260001").roles("STUDENT"))).andExpect(status().isForbidden());
        }
    }
    @Test void personalBillsCannotBeBroadenedWithSearchOrPagination() throws Exception {
        mvc.perform(get("/api/business/lists/students").with(user("20260001").roles("STUDENT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].student_id").value("20260001"));
        mvc.perform(get("/api/business/lists/students").param("search","20260002").with(user("20260001").roles("STUDENT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/business/lists/bills").with(user("20260001").roles("STUDENT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].student_id").value(1));
        mvc.perform(get("/api/business/lists/bills").param("search","他人账单").param("size","100").with(user("20260001").roles("STUDENT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/business/lists/bills").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
    }
    @Test void studentCannotCreateStaffOrReadAnotherStudentsBillDetails() throws Exception {
        long otherBill=jdbc.queryForObject("SELECT id FROM bill WHERE student_id=2",Long.class);
        mvc.perform(get("/api/business/bills/"+otherBill+"/payments").with(user("20260001").roles("STUDENT")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/business/accounts").with(user("20260001").roles("STUDENT")).with(csrf())
                .contentType("application/json").content("{\"username\":\"illegal_staff\",\"realName\":\"禁止创建\",\"role\":\"WORKER\",\"initialPassword\":\"InitialStaff123!\",\"buildingIds\":[]}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE username='illegal_staff'",Integer.class)).isZero();
    }
}
