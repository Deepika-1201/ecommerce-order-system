package com.ecommerce.platform.web;

import com.ecommerce.platform.ApiException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Turns every error into an RFC 9457 problem detail with {@code code} and {@code request_id} (LLD §1.5). */
@RestControllerAdvice
final class ProblemDetailsHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApiException(ApiException exception, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        HttpHeaders headers = new HttpHeaders();
        // Whole seconds, rounded up: a client that comes back early would only be refused again.
        exception.retryAfter().ifPresent(delay -> headers.set(HttpHeaders.RETRY_AFTER,
                Long.toString(Math.max(1, (delay.toMillis() + 999) / 1000))));
        return handleExceptionInternal(exception, problem, headers, exception.status(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception exception, WebRequest request) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "An unexpected error occurred.");
        return handleExceptionInternal(exception, problem, new HttpHeaders(), status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = body instanceof ProblemDetail detail ? detail : ProblemDetail.forStatus(status);
        problem.setProperty("code", codeFor(exception, status));
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            problem.setProperty("request_id", requestId);
        }
        if (exception instanceof MethodArgumentNotValidException invalid) {
            problem.setProperty("errors", fieldViolations(invalid));
        }
        if (status.is5xxServerError()) {
            log.error("Request failed with status {}", status.value(), exception);
        }
        return super.handleExceptionInternal(exception, problem, headers, status, request);
    }

    private static String codeFor(Exception exception, HttpStatusCode status) {
        return switch (exception) {
            case ApiException api -> api.code();
            case MethodArgumentNotValidException _, HandlerMethodValidationException _ -> "validation_failed";
            case HttpMessageNotReadableException _ -> "malformed_request";
            case NoResourceFoundException _, NoHandlerFoundException _ -> "not_found";
            case HttpRequestMethodNotSupportedException _ -> "method_not_allowed";
            case HttpMediaTypeNotAcceptableException _ -> "not_acceptable";
            case HttpMediaTypeNotSupportedException _ -> "unsupported_media_type";
            default -> status.is5xxServerError() ? "internal_error" : "invalid_request";
        };
    }

    private static List<FieldViolation> fieldViolations(MethodArgumentNotValidException exception) {
        return exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(snakeCase(error.getField()), error.getDefaultMessage()))
                .toList();
    }

    // Field paths come from Java names; clients see the snake_case names they sent.
    private static String snakeCase(String path) {
        StringBuilder result = new StringBuilder(path.length() + 8);
        for (char character : path.toCharArray()) {
            if (Character.isUpperCase(character)) {
                result.append('_').append(Character.toLowerCase(character));
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

    record FieldViolation(String field, String message) {
    }
}
