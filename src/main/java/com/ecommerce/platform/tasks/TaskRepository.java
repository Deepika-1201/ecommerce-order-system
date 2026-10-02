package com.ecommerce.platform.tasks;

import com.ecommerce.platform.TaskRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * SQL for {@code platform.scheduled_tasks} (LLD §2.7). Every update after the claim is fenced by status and attempt,
 * so a worker that lost its lease cannot overwrite the result of the worker that took over.
 */
@Component
class TaskRepository {

    private static final int MAX_ERROR_LENGTH = 2_000;

    private static final String FENCE = " WHERE id = :id AND status = 'RUNNING' AND attempts = :attempt";

    private final JdbcClient jdbc;

    TaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID id, TaskRequest request, String payload) {
        jdbc.sql("""
                        INSERT INTO platform.scheduled_tasks (id, type, dedupe_key, payload, status, run_at, attempts,
                                                              max_attempts, correlation_id, created_at, updated_at)
                        VALUES (:id, :type, :dedupeKey, :payload, 'PENDING', coalesce(CAST(:runAt AS timestamptz), now()),
                                0, :maxAttempts, :correlationId, now(), now())
                        ON CONFLICT (dedupe_key) WHERE status IN ('PENDING', 'RUNNING') DO NOTHING
                        """)
                .param("id", id)
                .param("type", request.type())
                .param("dedupeKey", request.dedupeKey())
                .param("payload", payload)
                .param("runAt", request.runAt() == null ? null : OffsetDateTime.ofInstant(request.runAt(), ZoneOffset.UTC))
                .param("maxAttempts", request.maxAttempts())
                .param("correlationId", request.correlationId())
                .update();
    }

    /** Creates the single row of a recurring task, or updates its interval; due at once when first created. */
    void registerRecurring(UUID id, String type, Duration every) {
        jdbc.sql("""
                        INSERT INTO platform.scheduled_tasks (id, type, dedupe_key, payload, status, run_at, attempts,
                                                              max_attempts, every_seconds, created_at, updated_at)
                        VALUES (:id, :type, :dedupeKey, 'null', 'PENDING', now(), 0, 1, :everySeconds, now(), now())
                        ON CONFLICT (dedupe_key) WHERE status IN ('PENDING', 'RUNNING')
                        DO UPDATE SET every_seconds = EXCLUDED.every_seconds, updated_at = now()
                        WHERE scheduled_tasks.every_seconds IS DISTINCT FROM EXCLUDED.every_seconds
                        """)
                .param("id", id)
                .param("type", type)
                .param("dedupeKey", "recurring:" + type)
                .param("everySeconds", every.toSeconds())
                .update();
    }

    void notifyWorkers() {
        jdbc.sql("NOTIFY ecom_tasks").update();
    }

    /** Leases the next due task of the given types, or a running one whose lease expired because its worker died. */
    Optional<ClaimedTask> claimNext(String[] types, Duration lease) {
        return jdbc.sql("""
                        UPDATE platform.scheduled_tasks t
                        SET status = 'RUNNING', attempts = t.attempts + 1, updated_at = now(),
                            lease_until = now() + make_interval(secs => :leaseSeconds)
                        WHERE t.id = (
                            SELECT id FROM platform.scheduled_tasks
                            WHERE type = ANY(:types)
                              AND ((status = 'PENDING' AND run_at <= now())
                                   OR (status = 'RUNNING' AND lease_until < now()))
                            ORDER BY run_at
                            LIMIT 1
                            FOR UPDATE SKIP LOCKED)
                        RETURNING t.id, t.type, t.payload, t.attempts, t.max_attempts, t.every_seconds,
                                  t.correlation_id
                        """)
                .param("leaseSeconds", lease.toMillis() / 1000.0)
                .param("types", types)
                .query(TaskRepository::claimedTask)
                .optional();
    }

    boolean complete(ClaimedTask task) {
        return jdbc.sql("UPDATE platform.scheduled_tasks"
                        + " SET status = 'SUCCEEDED', lease_until = NULL, last_error = NULL, updated_at = now()" + FENCE)
                .param("id", task.id())
                .param("attempt", task.attempt())
                .update() == 1;
    }

    /**
     * Fixed rate: the next run is the first slot of the schedule ({@code run_at} plus whole intervals) after now, so
     * runs missed while the system was down collapse into the one that just finished.
     */
    boolean reschedule(ClaimedTask task) {
        return jdbc.sql("""
                        UPDATE platform.scheduled_tasks
                        SET status = 'PENDING', lease_until = NULL, last_error = NULL, updated_at = now(),
                            run_at = run_at + make_interval(secs => every_seconds
                                * (floor(extract(epoch FROM now() - run_at) / every_seconds) + 1))
                        """ + FENCE)
                .param("id", task.id())
                .param("attempt", task.attempt())
                .update() == 1;
    }

    boolean fail(ClaimedTask task, String error, Duration retryAfter, boolean dead) {
        return jdbc.sql("""
                        UPDATE platform.scheduled_tasks
                        SET status = CASE WHEN :dead THEN 'DEAD' ELSE 'PENDING' END,
                            run_at = CASE WHEN :dead THEN run_at
                                          ELSE now() + make_interval(secs => :retryAfterSeconds) END,
                            lease_until = NULL, last_error = :error, updated_at = now()
                        """ + FENCE)
                .param("dead", dead)
                .param("retryAfterSeconds", retryAfter.toMillis() / 1000.0)
                .param("error", truncate(error))
                .param("id", task.id())
                .param("attempt", task.attempt())
                .update() == 1;
    }

    /** A failed recurring run waits for the next interval rather than retrying. */
    boolean failRecurring(ClaimedTask task, String error) {
        return jdbc.sql("""
                        UPDATE platform.scheduled_tasks
                        SET status = 'PENDING', lease_until = NULL, last_error = :error, updated_at = now(),
                            run_at = now() + make_interval(secs => every_seconds)
                        """ + FENCE)
                .param("error", truncate(error))
                .param("id", task.id())
                .param("attempt", task.attempt())
                .update() == 1;
    }

    private static String truncate(String error) {
        return error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
    }

    private static ClaimedTask claimedTask(ResultSet row, int rowNumber) throws SQLException {
        return new ClaimedTask(
                row.getObject("id", UUID.class),
                row.getString("type"),
                row.getString("payload"),
                row.getInt("attempts"),
                row.getInt("max_attempts"),
                row.getObject("every_seconds", Long.class),
                row.getString("correlation_id"));
    }
}
