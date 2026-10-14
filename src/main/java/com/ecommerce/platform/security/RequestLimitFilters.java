package com.ecommerce.platform.security;

import com.ecommerce.platform.web.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

final class RequestLimitFilters {

    private RequestLimitFilters() {
    }

    static final class BeforeAuthentication extends OncePerRequestFilter {

        private final RequestLimits limits;
        private final RateLimitProperties settings;
        private final ProblemResponses problems;

        BeforeAuthentication(RequestLimits limits, RateLimitProperties settings, ProblemResponses problems) {
            this.limits = limits;
            this.settings = settings;
            this.problems = problems;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !settings.enabled() || !applicationRequest(request);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            RequestLimits.Decision decision = limits.ip(request.getRemoteAddr(), webhook(request));
            if (!decision.allowed()) {
                reject(problems, request, response, decision);
                return;
            }
            chain.doFilter(request, response);
        }
    }

    static final class AfterAuthentication extends OncePerRequestFilter {

        private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

        private final RequestLimits limits;
        private final RateLimitProperties settings;
        private final ProblemResponses problems;

        AfterAuthentication(RequestLimits limits, RateLimitProperties settings, ProblemResponses problems) {
            this.limits = limits;
            this.settings = settings;
            this.problems = problems;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !settings.enabled() || !applicationRequest(request) || webhook(request)
                    || READ_METHODS.contains(request.getMethod());
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token
                    && token.isAuthenticated()) {
                RequestLimits.Decision decision = limits.principal(token.getToken().getSubject());
                if (!decision.allowed()) {
                    reject(problems, request, response, decision);
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }

    private static boolean applicationRequest(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/v1") || path.startsWith("/v1/");
    }

    private static boolean webhook(HttpServletRequest request) {
        return request.getServletPath().startsWith("/v1/webhooks/");
    }

    private static void reject(ProblemResponses problems, HttpServletRequest request, HttpServletResponse response,
            RequestLimits.Decision decision) throws IOException {
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        problems.write(request, response, HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                "Too many requests. Retry after the indicated delay.");
    }
}