package com.ecommerce.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

class FlowContextTests {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void nestedScopesRestoreTheOuterContextIncludingAfterFailure() {
        String orderId = UUID.randomUUID().toString();
        UUID messageId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        MDC.put("request_id", "req_trace");
        try (var _ = FlowContext.message(orderId, messageId)) {
            assertThat(MDC.getCopyOfContextMap()).containsAllEntriesOf(Map.of(
                    "request_id", "req_trace", "correlation_id", orderId, "message_id", messageId.toString()));
            assertThatThrownBy(() -> {
                try (var _ = FlowContext.task(orderId, taskId)) {
                    assertThat(MDC.get("message_id")).isNull();
                    assertThat(MDC.get("task_id")).isEqualTo(taskId.toString());
                    throw new IllegalStateException("task failed");
                }
            }).isInstanceOf(IllegalStateException.class);
            assertThat(MDC.get("message_id")).isEqualTo(messageId.toString());
            assertThat(MDC.get("task_id")).isNull();
        }
        assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("request_id", "req_trace"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"asha@example.test", "line\nbreak", "1-1-1-1-1", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "01900000-0000-7000-8000-00000000000G"})
    void nonCanonicalCorrelationValuesAreNotLogged(String candidate) {
        MDC.put("correlation_id", UUID.randomUUID().toString());
        try (var _ = FlowContext.task(candidate, UUID.randomUUID())) {
            assertThat(MDC.get("correlation_id")).isNull();
        }
    }
}