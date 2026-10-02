package com.ecommerce.platform.idempotency;

import com.ecommerce.platform.ApiException;
import com.ecommerce.platform.IdempotentRequest;
import com.ecommerce.platform.IdempotentRequests;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The key and the action share one transaction (LLD §2.8): the action's effects and the stored response commit
 * together, a concurrent request with the same key waits on the key's row lock, and a failed action releases the key.
 */
@Component
class JdbcIdempotentRequests implements IdempotentRequests {

    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Pattern VALID_KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final IdempotencyProperties properties;

    JdbcIdempotentRequests(JdbcClient jdbc, TransactionTemplate transactions, JsonMapper json,
            IdempotencyProperties properties) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
        this.properties = properties;
    }

    @Override
    public ResponseEntity<?> execute(IdempotentRequest request, Supplier<? extends ResponseEntity<?>> action) {
        Assert.hasText(request.scope(), "scope must not be empty");
        Assert.hasText(request.operation(), "operation must not be empty");
        validate(request.key());
        String fingerprint = fingerprint(request);
        Outcome outcome = transactions.execute(status -> {
            jdbc.sql("DELETE FROM platform.idempotency_keys WHERE scope = :scope AND key = :key AND expires_at <= now()")
                    .param("scope", request.scope())
                    .param("key", request.key())
                    .update();
            Boolean inserted = insertKey(request, fingerprint);
            if (inserted == null) {
                status.setRollbackOnly();
                return Outcome.IN_PROGRESS;
            }
            if (!inserted) {
                return new Outcome(replay(request, fingerprint));
            }
            ResponseEntity<?> response = action.get();
            store(request, response);
            return new Outcome(response);
        });
        if (outcome == Outcome.IN_PROGRESS) {
            throw new ApiException(HttpStatus.CONFLICT, "idempotency_request_in_progress",
                    "A request with this Idempotency-Key is still in progress.", Duration.ofSeconds(1));
        }
        return outcome.response();
    }

    private static void validate(String key) {
        if (key == null || key.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "idempotency_key_required",
                    "This request needs an " + HEADER + " header.");
        }
        if (!VALID_KEY.matcher(key).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_idempotency_key",
                    "The " + HEADER + " must be 1 to 255 printable ASCII characters without spaces.");
        }
    }

    /** True if this request now holds the key, false if the key exists, null if another request still holds it. */
    private Boolean insertKey(IdempotentRequest request, String fingerprint) {
        jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
                .param("timeout", properties.lockTimeout().toMillis() + "ms")
                .query(String.class)
                .single();
        int inserted;
        try {
            inserted = jdbc.sql("""
                            INSERT INTO platform.idempotency_keys (scope, key, fingerprint, created_at, expires_at)
                            VALUES (:scope, :key, :fingerprint, now(), now() + make_interval(secs => :ttlSeconds))
                            ON CONFLICT DO NOTHING
                            """)
                    .param("scope", request.scope())
                    .param("key", request.key())
                    .param("fingerprint", fingerprint)
                    .param("ttlSeconds", properties.keyTtl().toSeconds())
                    .update();
        } catch (DataAccessException e) {
            // Spring does not translate lock_not_available (55P03) to a specific exception.
            if (e.getMostSpecificCause() instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                return null;
            }
            throw e;
        }
        jdbc.sql("SET LOCAL lock_timeout TO DEFAULT").update();
        return inserted == 1;
    }

    private ResponseEntity<?> replay(IdempotentRequest request, String fingerprint) {
        StoredResponse stored = jdbc.sql("""
                        SELECT fingerprint, response_status, response_body, response_location
                        FROM platform.idempotency_keys WHERE scope = :scope AND key = :key
                        """)
                .param("scope", request.scope())
                .param("key", request.key())
                .query(JdbcIdempotentRequests::storedResponse)
                .single();
        if (!stored.fingerprint().equals(fingerprint)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency_key_reused",
                    "This " + HEADER + " was already used for a different request.");
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(stored.status()).header(REPLAYED_HEADER, "true");
        if (stored.location() != null) {
            response.location(URI.create(stored.location()));
        }
        return stored.body() == null ? response.build() : response.body(json.readTree(stored.body()));
    }

    private void store(IdempotentRequest request, ResponseEntity<?> response) {
        URI location = response.getHeaders().getLocation();
        jdbc.sql("""
                        UPDATE platform.idempotency_keys
                        SET response_status = :status, response_body = :body, response_location = :location
                        WHERE scope = :scope AND key = :key
                        """)
                .param("status", response.getStatusCode().value())
                .param("body", response.getBody() == null ? null : json.writeValueAsString(response.getBody()))
                .param("location", location == null ? null : location.toString())
                .param("scope", request.scope())
                .param("key", request.key())
                .update();
    }

    /** SHA-256 of the operation and the body as JSON with sorted map keys, so formatting does not matter. */
    private String fingerprint(IdempotentRequest request) {
        String body = request.body() == null
                ? ""
                : json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(request.body());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((request.operation() + "\n" + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static StoredResponse storedResponse(ResultSet row, int rowNumber) throws SQLException {
        return new StoredResponse(row.getString("fingerprint"), row.getInt("response_status"),
                row.getString("response_body"), row.getString("response_location"));
    }

    private record StoredResponse(String fingerprint, int status, String body, String location) {
    }

    private record Outcome(ResponseEntity<?> response) {

        static final Outcome IN_PROGRESS = new Outcome(null);
    }
}
