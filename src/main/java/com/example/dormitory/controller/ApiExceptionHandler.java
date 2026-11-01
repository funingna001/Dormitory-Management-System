package com.example.dormitory.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).headers(ex.getHeaders()).body(Map.of("success", false, "message",
                ex.getReason() == null ? "请求失败" : ex.getReason()));
    }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<?> forbidden() { return error(403, "无访问权限"); }
    @ExceptionHandler({org.springframework.web.servlet.NoHandlerFoundException.class,
            org.springframework.web.servlet.resource.NoResourceFoundException.class})
    ResponseEntity<?> notFound() { return error(404, "接口不存在"); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict() { return error(409, "数据重复或仍被其他记录引用"); }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<?> databaseUnavailable(org.springframework.dao.DataAccessException ex) {
        log.error("Database request failed",ex);
        return error(503,"服务暂时不可用，请稍后重试");
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class,java.time.DateTimeException.class,org.apache.poi.ooxml.POIXMLException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    ResponseEntity<?> invalid(Exception ex) {
        if (ex instanceof MethodArgumentNotValidException validation) {
            return error(400, validation.getBindingResult().getFieldErrors().stream()
                    .map(e -> e.getField() + ": " + e.getDefaultMessage()).findFirst().orElse("参数不合法"));
        }
        return error(400, "请求参数不合法");
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge() { return error(413, "图片大小不能超过 5MB"); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception ex) {
        log.error("API request failed", ex);
        return error(500, "服务器处理失败，请稍后重试");
    }
    private ResponseEntity<?> error(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("success", false, "message", message));
    }
}
