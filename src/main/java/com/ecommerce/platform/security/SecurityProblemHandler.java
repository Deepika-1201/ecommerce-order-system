package com.ecommerce.platform.security;

import com.ecommerce.platform.web.ProblemResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** {@code 401} and {@code 403} as problem+json that never says which token check failed (LLD §3.2). */
@Component
class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityProblemHandler.class);

    private final ProblemResponses problems;

    SecurityProblemHandler(ProblemResponses problems) {
        this.problems = problems;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        log.debug("Refused unauthenticated request: {}", exception.getMessage());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                exception instanceof OAuth2AuthenticationException ? "Bearer error=\"invalid_token\"" : "Bearer");
        problems.write(request, response, HttpStatus.UNAUTHORIZED, "unauthorized",
                "A valid access token is required.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"");
        problems.write(request, response, HttpStatus.FORBIDDEN, "forbidden", "You are not allowed to do this.");
    }
}
