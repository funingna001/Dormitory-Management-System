package com.example.dormitory.service;

import java.util.Map;

public interface AiService {

    record Answer(String reply, String source, String notice, String generatedAt, Map<String,Object> statistics) {}
    Answer query(String message);
    Map<String,Object> status();
    String chat(String message);

    String getStatisticsSummary();

    Map<String, Object> getStudentStatistics();

    Map<String, Object> getDormitoryStatistics();
}