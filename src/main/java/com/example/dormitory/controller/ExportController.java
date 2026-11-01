package com.example.dormitory.controller;

import com.example.dormitory.service.ExportService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
@RequestMapping("/api/export")
public class ExportController {
    private static final Map<String, String> NAMES = Map.of("students", "学生信息", "dormitories", "宿舍信息", "buildings", "楼栋信息", "all", "宿舍业务台账");
    private final ExportService exports;

    public ExportController(ExportService exports) { this.exports = exports; }

    @GetMapping({"/students", "/dormitories", "/buildings", "/all"})
    public void export(HttpServletResponse response, jakarta.servlet.http.HttpServletRequest request) throws IOException {
        // 根据地址选择导出内容，再设置下载文件名和 Excel 类型。
        String kind = request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/') + 1);
        String filename = NAMES.get(kind) + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx";
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        exports.write(kind, response.getOutputStream());
    }
}
