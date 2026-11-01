package com.example.dormitory;

import com.example.dormitory.business.*;
import com.example.dormitory.service.ExportService;
import com.example.dormitory.service.impl.ExportServiceImpl;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

class OptimizationIntegrationTest extends IntegrationSupport {
    @Autowired HousingLedger ledger;
    @Autowired DomainLocks locks;
    @Autowired ExportService exports;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach void extraData() {
        jdbc.update("INSERT INTO dormitory(id,dormitory_number,building,room_type,capacity,current_count,gender) VALUES(3,'1号楼-0201','1号楼','二人间',2,0,'男')");
        jdbc.update("INSERT INTO student(id,name,student_id,grade,gender,phone) VALUES(2,'李同学','20260002','大一','男','13800000002'),(3,'王同学','20260003','大一','男','13800000003')");
        ledger.initialize();
    }

    private boolean mysql() throws Exception {
        try (var connection=jdbc.getDataSource().getConnection()) { return connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL"); }
    }
    private long bed(int room,int slot) { return jdbc.queryForObject("SELECT id FROM bed WHERE room_id=? AND bed_number=?",Long.class,room,slot); }
    private void await(CountDownLatch latch) {
        try { assertThat(latch.await(10,TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
    }
    private int assign(int room,int student) {
        try { housing.assign(room,student); return 200; }
        catch (org.springframework.web.server.ResponseStatusException ex) { return ex.getStatusCode().value(); }
    }

    @Test void exportContainsBusinessLedgersSeparateRoomStateAndTextIdentifiers() throws Exception {
        jdbc.update("UPDATE room_policy SET state='MAINTENANCE',reason='维修测试' WHERE room_id=3");
        jdbc.update("UPDATE student SET name='=1+1' WHERE id=2");
        var response=mvc.perform(get("/api/export/all").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("filename*="))).andReturn().getResponse();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(25);
            for(String name:List.of("导出说明","住宿历史","费用账单","收退款流水","物品交接","报修工单","卫生安全检查","账号状态","批次摘要")) assertThat(book.getSheet(name)).isNotNull();
            var student=book.getSheet("学生信息");
            assertThat(student.getRow(2).getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(student.getRow(2).getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(student.getRow(2).getCell(1).getStringCellValue()).isEqualTo("=1+1");
            var room=book.getSheet("宿舍信息").getRow(2);
            assertThat(room.getCell(6).getStringCellValue()).isEqualTo("空闲");
            assertThat(room.getCell(7).getStringCellValue()).isEqualTo("MAINTENANCE");
            var account=book.getSheet("账号状态").getRow(0);
            for(Cell cell:account) assertThat(cell.getStringCellValue()).doesNotContain("密码", "password");
        }
    }

    @Test void buildingAggregationHandlesEmptyBuildingsWithoutPerBuildingQueries() throws Exception {
        jdbc.update("INSERT INTO building(id,name,gender) VALUES(3,'空楼栋','男')");
        var queries=new AtomicInteger();
        var observed=new JdbcTemplate(jdbc.getDataSource()) {
            @Override public void query(PreparedStatementCreator creator, RowCallbackHandler callback) { queries.incrementAndGet(); super.query(creator,callback); }
        };
        var out=new ByteArrayOutputStream();
        new ExportServiceImpl(observed,transactions,1).write("buildings",out);
        assertThat(queries.get()).isEqualTo(1);
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            var sheet=book.getSheetAt(0);
            for(int i=1;i<=sheet.getLastRowNum();i++) {
                var row=sheet.getRow(i);
                if(row.getCell(0).getStringCellValue().equals("1号楼")) {
                    assertThat(row.getCell(2).getNumericCellValue()).isEqualTo(2);
                    assertThat(row.getCell(3).getNumericCellValue()).isEqualTo(2);
                    assertThat(row.getCell(4).getNumericCellValue()).isEqualTo(1);
                    assertThat(row.getCell(8).getNumericCellValue()).isEqualTo(25);
                } else if(row.getCell(0).getStringCellValue().equals("空楼栋")) {
                    for(int c=2;c<13;c++) assertThat(row.getCell(c).getNumericCellValue()).isZero();
                }
            }
        }
    }

    @Test void exportDisconnectReleasesPermitAndConcurrentDownloadsAreBounded() throws Exception {
        var exporter=new ExportServiceImpl(jdbc,transactions,1);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var executor=Executors.newSingleThreadExecutor();
        try {
            var first=executor.submit(()->{
                try { exporter.write("students",new OutputStream(){@Override public void write(int b) throws IOException { entered.countDown(); await(release); throw new IOException("simulated disconnect"); }}); }
                catch(IOException expected) { return true; } return false;
            });
            await(entered);
            assertThatThrownBy(()->exporter.write("students",OutputStream.nullOutputStream()))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .satisfies(error->assertThat(((org.springframework.web.server.ResponseStatusException)error).getStatusCode().value()).isEqualTo(429));
            release.countDown(); assertThat(first.get(10,TimeUnit.SECONDS)).isTrue();
            exporter.write("students",OutputStream.nullOutputStream());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void exportKeepsOneSnapshotWhileHousingChanges() throws Exception {
        Assumptions.assumeTrue(mysql(),"Concurrent snapshots are verified on the isolated MySQL server");
        var executor=Executors.newSingleThreadExecutor(); var count=new AtomicInteger();
        var observed=new JdbcTemplate(jdbc.getDataSource()) {
            @Override public void query(PreparedStatementCreator creator, RowCallbackHandler callback) {
                super.query(creator,callback);
                if(count.incrementAndGet()==1) {
                    try { assertThat(executor.submit(()->assign(1,2)).get(10,TimeUnit.SECONDS)).isEqualTo(200); }
                    catch(Exception ex) { throw new RuntimeException(ex); }
                }
            }
        };
        try {
            var out=new ByteArrayOutputStream(); new ExportServiceImpl(observed,transactions,1).write("all",out);
            try(var book=new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
                assertThat(book.getSheet("学生信息").getRow(2).getCell(6).getStringCellValue()).isEmpty();
                assertThat(book.getSheet("宿舍信息").getRow(1).getCell(4).getNumericCellValue()).isEqualTo(1);
            }
            assertThat(jdbc.queryForObject("SELECT current_count FROM dormitory WHERE id=1",Integer.class)).isEqualTo(2);
        } finally { executor.shutdownNow(); }
    }

    @Test void unrelatedRoomOperationsDoNotWaitForEachOtherOnMysql() throws Exception {
        Assumptions.assumeTrue(mysql(),"SQLite permits only one database writer");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try {
            var blocked=executor.submit(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{locks.housing(1,null);entered.countDown();await(release);}));
            await(entered);
            assertThat(executor.submit(()->assign(3,2)).get(3,TimeUnit.SECONDS)).isEqualTo(200);
            release.countDown();blocked.get(10,TimeUnit.SECONDS);
        } finally {release.countDown();executor.shutdownNow();}
    }

    @Test void concurrentCheckinsNeverExceedCapacity() throws Exception {
        var executor=Executors.newFixedThreadPool(2);
        try {
            var results=executor.invokeAll(List.<Callable<Integer>>of(()->assign(1,2),()->assign(1,3)));
            assertThat(List.of(results.get(0).get(),results.get(1).get())).containsExactlyInAnyOrder(200,409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM student WHERE dormitory_number='1号楼-0101'",Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT current_count FROM dormitory WHERE id=1",Integer.class)).isEqualTo(2);
        } finally {executor.shutdownNow();}
    }

    @Test void concurrentReservationsCannotReserveTwoRoomsForOneStudent() throws Exception {
        var executor=Executors.newFixedThreadPool(2);
        String body=mapper.writeValueAsString(Map.of("state","RESERVED","studentId",2,"until",LocalDateTime.now().plusDays(1).toString(),"reason","并发预留验证"));
        try {
            var results=executor.invokeAll(List.<Callable<Integer>>of(
                ()->mvc.perform(patch("/api/business/beds/"+bed(1,2)+"/state").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content(body)).andReturn().getResponse().getStatus(),
                ()->mvc.perform(patch("/api/business/beds/"+bed(3,1)+"/state").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content(body)).andReturn().getResponse().getStatus()));
            assertThat(List.of(results.get(0).get(),results.get(1).get())).containsExactlyInAnyOrder(200,409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bed WHERE reserved_for=2",Integer.class)).isEqualTo(1);
        } finally {executor.shutdownNow();}
    }

    @Test void largeExportFlushesManyRowWindowsWithoutLosingRows() throws Exception {
        var rows=new ArrayList<Object[]>();
        for(int i=0;i<4000;i++) rows.add(new Object[]{"导出测试"+i,"EXPORT-"+i,"大一","男","13800000000"});
        jdbc.batchUpdate("INSERT INTO student(name,student_id,grade,gender,phone) VALUES(?,?,?,?,?)",rows);
        var file=java.nio.file.Files.createTempFile("dormitory-stream-export-",".xlsx");
        try {
            try(var output=java.nio.file.Files.newOutputStream(file)){exports.write("students",output);}
            var count=new AtomicInteger();
            var factory=javax.xml.parsers.SAXParserFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            try(var zip=new java.util.zip.ZipFile(file.toFile());var xml=zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml"))){
                factory.newSAXParser().parse(xml,new org.xml.sax.helpers.DefaultHandler(){
                    @Override public void startElement(String uri,String local,String name,org.xml.sax.Attributes attributes){if(name.equals("row"))count.incrementAndGet();}
                });
            }
            assertThat(count.get()).isEqualTo(4004);
        } finally {java.nio.file.Files.deleteIfExists(file);}
    }

    @Test void oppositeRoomTransfersUseStableLockOrder() throws Exception {
        housing.assign(3,2);
        var executor=Executors.newFixedThreadPool(2);
        try {
            var results=executor.invokeAll(List.<Callable<Boolean>>of(()->{ledger.move(1,bed(3,2),"并发调宿");return true;},()->{ledger.move(2,bed(1,2),"并发调宿");return true;}),15,TimeUnit.SECONDS);
            for(var result:results) assertThat(result.get()).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM housing_history WHERE checkout_at IS NULL",Integer.class)).isEqualTo(2);
        } finally {executor.shutdownNow();}
    }

    @Test void newBillCommitsBeforeCheckoutCanPassItsSettlementCheck() throws Exception {
        Assumptions.assumeTrue(mysql(),"Separate database connections are required");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try {
            var writer=executor.submit(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
                locks.students(List.of(1L));
                jdbc.update("INSERT INTO bill(student_id,kind,period,amount_cents,due_at,description,created_by,created_at) VALUES(1,'WATER','2026-10',100,'2026-11-01','待结清','admin','2026-10-31')");
                entered.countDown();await(release);
            }));
            await(entered);
            var checkout=executor.submit(()->{try{housing.remove(1,1);return 200;}catch(org.springframework.web.server.ResponseStatusException error){return error.getStatusCode().value();}});
            assertThatThrownBy(()->checkout.get(250,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();writer.get(10,TimeUnit.SECONDS);
            assertThat(checkout.get(10,TimeUnit.SECONDS)).isEqualTo(409);
        } finally {release.countDown();executor.shutdownNow();}
    }

    @Test void exactFiltersAndRemoteChoicesRemainScopedAndBounded() throws Exception {
        jdbc.update("INSERT INTO user(username,password,role) SELECT 'manager',password,'manager' FROM user WHERE username='admin'");
        jdbc.update("INSERT INTO manager_building(username,building_id) VALUES('manager',1)");
        jdbc.update("INSERT INTO audit_log(actor,action,resource_type,created_at) VALUES('admin','FIRST','test','2026-10-01T23:59:59'),('admin','SECOND','test','2026-10-02T00:00:00')");
        mvc.perform(get("/api/business/lists/audit").with(user("admin").roles("ADMIN")).param("from","2026-10-01").param("to","2026-10-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].action").value("FIRST"));
        mvc.perform(get("/api/business/lists/rooms").with(user("manager").roles("MANAGER")).param("building","2号楼"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/business/lists/rooms").with(user("admin").roles("ADMIN")).param("state","ACTIVE").param("building","1号楼"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/business/options/students").with(user("admin").roles("ADMIN")).param("size","1").param("unhoused","true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/business/options/students").with(user("manager").roles("MANAGER")).param("ids","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/business/options/students").with(user("admin").roles("ADMIN")).param("ids","3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(3));
        mvc.perform(get("/api/business/options/students").with(user("admin").roles("ADMIN")).param("search","李"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(2));
        mvc.perform(get("/api/business/options/workers").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].username").value("worker"));
        mvc.perform(get("/api/business/options/students").with(user("admin").roles("ADMIN")).param("size","101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/business/lists/bills").with(user("admin").roles("ADMIN")).param("from","2026-11-01").param("to","2026-10-01")).andExpect(status().isBadRequest());
    }

    @Test void startupDoesNotRepeatHousingMigrationAndCheckReportsWithoutRepairing() throws Exception {
        jdbc.update("UPDATE dormitory SET current_count=0 WHERE id=1");
        initializer.run();
        assertThat(jdbc.queryForObject("SELECT current_count FROM dormitory WHERE id=1",Integer.class)).isZero();
        mvc.perform(get("/api/business/lists/consistency").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].actual_count").value(1));
        mvc.perform(get("/api/business/lists/consistency").with(user("20260001").roles("STUDENT"))).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_migration WHERE version=2",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT current_count FROM dormitory WHERE id=1",Integer.class)).isZero();
    }
}
