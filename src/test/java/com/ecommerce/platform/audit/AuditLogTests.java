package com.ecommerce.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.platform.AuditEntry;
import com.ecommerce.platform.AuditLog;
import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.PlatformTables;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class AuditLogTests extends IntegrationTest {

    private static final AuditEntry FORCED_REFUND = new AuditEntry("operator", "op-7", "order.refund-forced",
            "order", "order-9", "Customer complaint", Map.of("amount_paise", 129900));

    @Autowired
    private AuditLog audit;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void emptyLog() {
        PlatformTables.clear(jdbc);
    }

    @AfterEach
    void clearLogContext() {
        MDC.clear();
    }

    @Test
    void recordsTheEntryWithTheRequestAndCorrelationIds() {
        MDC.put("request_id", "req_1");
        MDC.put("correlation_id", "order-9");

        transactions.executeWithoutResult(status -> audit.record(FORCED_REFUND));

        Map<String, Object> row = jdbc.sql("""
                        SELECT actor_type, actor_id, action, target_type, target_id, reason,
                               details ->> 'amount_paise' AS amount, request_id, correlation_id,
                               occurred_at > now() - interval '1 minute' AS recent
                        FROM platform.audit_log
                        """)
                .query()
                .singleRow();
        assertThat(row).containsEntry("actor_type", "operator")
                .containsEntry("actor_id", "op-7")
                .containsEntry("action", "order.refund-forced")
                .containsEntry("target_type", "order")
                .containsEntry("target_id", "order-9")
                .containsEntry("reason", "Customer complaint")
                .containsEntry("amount", "129900")
                .containsEntry("request_id", "req_1")
                .containsEntry("correlation_id", "order-9")
                .containsEntry("recent", true);
    }

    @Test
    void recordingNeedsTheCallersTransaction() {
        assertThatThrownBy(() -> audit.record(FORCED_REFUND)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aRolledBackActionLeavesNoEntry() {
        transactions.executeWithoutResult(status -> {
            audit.record(FORCED_REFUND);
            status.setRollbackOnly();
        });

        assertThat(entries()).isZero();
    }

    @Test
    void entriesCannotBeChangedOrDeleted() {
        transactions.executeWithoutResult(status -> audit.record(FORCED_REFUND));

        assertThatThrownBy(() -> jdbc.sql("UPDATE platform.audit_log SET reason = 'nothing to see'").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM platform.audit_log").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThat(entries()).isEqualTo(1);
    }

    private int entries() {
        return jdbc.sql("SELECT count(*) FROM platform.audit_log").query(Integer.class).single();
    }
}
