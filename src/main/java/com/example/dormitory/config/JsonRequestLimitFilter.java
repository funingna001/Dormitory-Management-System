package com.example.dormitory.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.*;

/**
 * Bound JSON parsing memory for both fixed-length and chunked requests.
 */
@Component
@Order(-200)
public class JsonRequestLimitFilter extends OncePerRequestFilter {
    private static final long LIMIT = 2 * 1024 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        if (req.getContentType() == null || !req.getContentType().toLowerCase(java.util.Locale.ROOT).contains("application/json")) {
            chain.doFilter(req, res);
            return;
        }
        if (req.getContentLengthLong() > LIMIT) {
            res.setStatus(413);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"success\":false,\"message\":\"请求内容不能超过 2MB\"}");
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(req) {
            private ServletInputStream stream;

            @Override
            public ServletInputStream getInputStream() throws IOException {
                if (stream == null) {
                    var delegate = super.getInputStream();
                    stream = new ServletInputStream() {
                        private long read;

                        private void track(int count) throws IOException {
                            if (count > 0 && ((read += count) > LIMIT))
                                throw new IOException("JSON request limit exceeded");
                        }

                        @Override
                        public int read() throws IOException {
                            int value = delegate.read();
                            track(value < 0 ? 0 : 1);
                            return value;
                        }

                        @Override
                        public int read(byte[] data, int off, int len) throws IOException {
                            int count = delegate.read(data, off, len);
                            track(count);
                            return count;
                        }

                        @Override
                        public boolean isFinished() {
                            return delegate.isFinished();
                        }

                        @Override
                        public boolean isReady() {
                            return delegate.isReady();
                        }

                        @Override
                        public void setReadListener(ReadListener listener) {
                            delegate.setReadListener(listener);
                        }
                    };
                }
                return stream;
            }

            @Override
            public BufferedReader getReader() throws IOException {
                return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }, res);
    }
}
