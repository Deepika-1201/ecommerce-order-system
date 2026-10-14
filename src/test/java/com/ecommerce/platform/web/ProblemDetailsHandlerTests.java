package com.ecommerce.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.ServletWebRequest;

class ProblemDetailsHandlerTests {

    @Test
    void unexpectedErrorsExposeNeitherSensitiveDetailsNorThrowableChains() {
        Logger logger = (Logger) LoggerFactory.getLogger(ProblemDetailsHandler.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            var request = new ServletWebRequest(new MockHttpServletRequest("POST", "/v1/me/orders"),
                    new MockHttpServletResponse());
            var failure = new SQLException("Failed row: asha@example.test token=private-token", "23505");

            var response = new ProblemDetailsHandler().handleUnexpected(failure, request);

            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody()).isInstanceOfSatisfying(ProblemDetail.class, problem -> {
                assertThat(problem.getDetail()).isEqualTo("An unexpected error occurred.");
                assertThat(problem.getProperties()).containsEntry("code", "internal_error");
            });
            assertThat(captured.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).contains("SQLSTATE=23505")
                        .doesNotContain("asha@example.test", "private-token", "Failed row");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }
}