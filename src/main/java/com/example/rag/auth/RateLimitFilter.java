package com.example.rag.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/** 限流：按访问码（无码按 IP）滑动窗口计数，防脚本刷 token。@Order(10) 排在无 @Order 的 AccessCodeFilter 之前。 */
@Component
@Order(10)
public class RateLimitFilter extends OncePerRequestFilter {
    private static final int MAX_PER_MINUTE = 20;
    private static final long WINDOW_MS = 60_000L;
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public RateLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/v1/")) {
            chain.doFilter(request, response);
            return;
        }
        String code = request.getHeader(AccessCodeFilter.HEADER);
        String key = (code == null || code.isBlank()) ? "ip:" + request.getRemoteAddr() : "code:" + code;
        long now = System.currentTimeMillis();
        Deque<Long> window = hits.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MS) window.pollFirst();
            if (window.size() >= MAX_PER_MINUTE) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                        HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
                problem.setTitle("限流");
                problem.setInstance(URI.create(request.getRequestURI()));
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(problem));
                return;
            }
            window.addLast(now);
        }
        chain.doFilter(request, response);
    }
}
