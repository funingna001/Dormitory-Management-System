package com.example.dormitory.service;

import java.io.IOException;
import java.io.OutputStream;

public interface ExportService {
    void write(String kind, OutputStream output) throws IOException;
}
