package com.ecommerce.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Writes problem+json from filters, which run before the MVC exception handlers (LLD §1.5, §3.2). */
@Component
public class ProblemResponses {

    private final JsonMapper json;

    ProblemResponses(JsonMapper json) {
        this.json = json;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code,
            String detail) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", status.getReasonPhrase());
        problem.put("status", status.value());
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        problem.put("code", code);
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            problem.put("request_id", requestId);
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        json.writeValue(response.getOutputStream(), problem);
    }
}
