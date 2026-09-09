package com.example.rag.api;

import com.example.rag.profile.UserProfileService;
import com.example.rag.recommendation.FeedbackService;
import com.example.rag.recommendation.RecommendationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class,
            MethodArgumentNotValidException.class})
    ResponseEntity<ProblemDetail> badRequest(Exception error, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "请求参数不合法", safeMessage(error), request);
    }

    @ExceptionHandler({UserProfileService.ProfileNotFoundException.class,
            UserProfileService.ConversationNotFoundException.class,
            FeedbackService.FeedbackNotFoundException.class})
    ResponseEntity<ProblemDetail> notFound(RuntimeException error, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "资源不存在", error.getMessage(), request);
    }

    @ExceptionHandler(UserProfileService.ProfileConflictException.class)
    ResponseEntity<ProblemDetail> conflict(RuntimeException error, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "画像更新冲突", error.getMessage(), request);
    }

    @ExceptionHandler(UserProfileService.ProfileModelException.class)
    ResponseEntity<ProblemDetail> modelUnavailable(RuntimeException error, HttpServletRequest request) {
        return problem(HttpStatus.BAD_GATEWAY, "画像模型不可用", error.getMessage(), request);
    }

    @ExceptionHandler(RecommendationService.RecommendationUnavailableException.class)
    ResponseEntity<ProblemDetail> recommendationUnavailable(RuntimeException error, HttpServletRequest request) {
        return problem(HttpStatus.BAD_GATEWAY, "推荐服务不可用", error.getMessage(), request);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> databaseUnavailable(DataAccessException error, HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "数据库暂时不可用",
                "请稍后重试", request);
    }

    private ResponseEntity<ProblemDetail> problem(
            HttpStatus status,
            String title,
            String detail,
            HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(status).body(problem);
    }

    private String safeMessage(Exception error) {
        if (error instanceof MethodArgumentNotValidException validationError
                && validationError.getBindingResult().getFieldError() != null) {
            return validationError.getBindingResult().getFieldError().getDefaultMessage();
        }
        return error.getMessage() == null ? "请求内容不合法" : error.getMessage();
    }
}
