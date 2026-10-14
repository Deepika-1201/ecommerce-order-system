package com.ecommerce.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class FailureDetailsTests {

    @Test
    void nestedMessagesAndSuppressedFailuresCannotLeakIntoTheSummary() {
        var cause = new IllegalArgumentException("private-token for asha@example.test");
        cause.addSuppressed(new IllegalStateException("another-private-secret"));
        var wrapper = new IllegalStateException("request body contains a secret", cause);

        assertThat(FailureDetails.summarize(wrapper)).isEqualTo("java.lang.IllegalArgumentException");
    }

    @Test
    void sqlStateIsPreservedButQueriesRowsAndCredentialsAreNot() {
        var failure = new SQLException("Failed row: asha@example.test password=private-secret", "23505");

        assertThat(FailureDetails.summarize(failure)).isEqualTo("java.sql.SQLException SQLSTATE=23505");
    }

    @Test
    void anInvalidSqlStateCannotCarryArbitraryText() {
        assertThat(FailureDetails.summarize(new SQLException("private-secret", "secret@example.test")))
                .isEqualTo("java.sql.SQLException");
        assertThat(FailureDetails.summarize(new SQLException("private-secret")))
                .isEqualTo("java.sql.SQLException");
    }
}