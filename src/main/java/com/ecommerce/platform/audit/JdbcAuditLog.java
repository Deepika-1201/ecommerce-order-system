package com.ecommerce.platform.audit;

import com.ecommerce.platform.AuditEntry;
import com.ecommerce.platform.AuditLog;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
class JdbcAuditLog implements AuditLog {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    JdbcAuditLog(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry) {
        jdbc.sql("""
                        INSERT INTO platform.audit_log (occurred_at, actor_type, actor_id, action, target_type,
                                                        target_id, reason, details, request_id, correlation_id)
                        VALUES (:occurredAt, :actorType, :actorId, :action, :targetType,
                                :targetId, :reason, CAST(:details AS jsonb), :requestId, :correlationId)
                        """)
                .param("occurredAt", OffsetDateTime.now(clock))
                .param("actorType", entry.actorType())
                .param("actorId", entry.actorId())
                .param("action", entry.action())
                .param("targetType", entry.targetType())
                .param("targetId", entry.targetId())
                .param("reason", entry.reason())
                .param("details", json.writeValueAsString(entry.details()))
                .param("requestId", MDC.get("request_id"))
                .param("correlationId", MDC.get("correlation_id"))
                .update();
    }
}
