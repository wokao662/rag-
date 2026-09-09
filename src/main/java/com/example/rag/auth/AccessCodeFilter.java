package com.example.rag.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 访问码拦截：只拦 /api/v1/users/**，未发放访问码时（开发模式）直接放行。 */
@Component
public class AccessCodeFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Access-Code";
    private static final Pattern USER_PATH = Pattern.compile("^/api/v1/users/([^/]+)(?:/.*)?$");

    private final AccessCodeService accessCodes;
    private final ObjectMapper objectMapper;

    public AccessCodeFilter(AccessCodeService accessCodes, ObjectMapper objectMapper) {
        this.accessCodes = accessCodes;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Matcher matcher = USER_PATH.matcher(request.getRequestURI());
        if (!matcher.matches()) {
            filterChain.doFilter(request, response);
            return;
        }
        String externalId = matcher.group(1);
        String code = request.getHeader(HEADER);
        if (!accessCodes.isAllowed(code, externalId)) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.UNAUTHORIZED, "需要有效的访问码");
            problem.setTitle("未授权");
            problem.setInstance(URI.create(request.getRequestURI()));
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(problem));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
