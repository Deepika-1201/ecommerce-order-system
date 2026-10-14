package com.ecommerce.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecommerce.platform.web.ProblemResponses;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

class SecurityProblemHandlerTests {

    @Test
    void debugLogsDoNotExposeAuthenticationExceptionDetails() throws Exception {
        ProblemResponses problems = mock(ProblemResponses.class);
        SecurityProblemHandler handler = new SecurityProblemHandler(problems);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Logger logger = (Logger) LoggerFactory.getLogger(SecurityProblemHandler.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        logger.setLevel(Level.DEBUG);
        try {
            handler.commence(request, response,
                    new BadCredentialsException("Bearer private-token-value for asha@example.test"));

            assertThat(captured.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).contains("BadCredentialsException")
                        .doesNotContain("private-token-value", "asha@example.test");
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
            verify(problems).write(request, response, HttpStatus.UNAUTHORIZED, "unauthorized",
                    "A valid access token is required.");
        } finally {
            logger.detachAppender(captured);
            logger.setLevel(previous);
            captured.stop();
        }
    }
}