package com.ecommerce.platform;

import java.sql.SQLException;
import org.springframework.core.NestedExceptionUtils;

/** Diagnostic categories safe to persist or log without retaining exception messages, SQL, or payloads. */
public final class FailureDetails {

    private FailureDetails() {
    }

    public static String summarize(Throwable failure) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(failure);
        String category = cause.getClass().getName();
        if (cause instanceof SQLException sql && sql.getSQLState() != null
                && sql.getSQLState().matches("[0-9A-Z]{5}")) {
            return category + " SQLSTATE=" + sql.getSQLState();
        }
        return category;
    }
}