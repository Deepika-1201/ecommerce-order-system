package com.ecommerce.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.platform.ApiController;
import com.ecommerce.platform.HttpAccessRules;
import com.ecommerce.platform.IdempotentRequest;
import com.ecommerce.platform.IdempotentRequests;
import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.PlatformTables;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/** API idempotency keys end to end over HTTP (ADR-010, LLD §2.8). */
@Import({IdempotentRequestsTests.OrderFixtureController.class, IdempotentRequestsTests.FixtureAccess.class})
class IdempotentRequestsTests extends IntegrationTest {

    private static final String PATH = "/test/idempotent-orders";
    private static final String PEN = "{\"item\": \"pen\", \"quantity\": 2}";
    private static final String REPLAYED = JdbcIdempotentRequests.REPLAYED_HEADER;

    @Autowired
    private OrderFixtureController controller;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        PlatformTables.clear(jdbc);
        jdbc.sql("CREATE TABLE IF NOT EXISTS public.test_orders (id uuid PRIMARY KEY, item text, quantity int)")
                .update();
        jdbc.sql("TRUNCATE public.test_orders").update();
        controller.failuresAfterWrite.set(0);
    }

    @Test
    void aRetryWithTheSameKeyReplaysTheFirstResponseWithoutRunningAgain() {
        HttpResponse<String> first = place("key-1", PEN);
        HttpResponse<String> retry = place("key-1", "{ \"quantity\": 2,\n  \"item\": \"pen\" }");

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(first.headers().firstValue(REPLAYED)).isEmpty();
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.headers().firstValue(REPLAYED)).hasValue("true");
        assertThat(retry.headers().firstValue("Location")).isEqualTo(first.headers().firstValue("Location"));
        assertThat(json(retry)).isEqualTo(json(first));
        assertThat(json(first).get("order_id").asString()).isNotBlank();
        assertThat(orders()).isEqualTo(1);
    }

    @Test
    void aKeyReusedForADifferentRequestIsRejected() {
        place("key-2", PEN);

        HttpResponse<String> response = place("key-2", "{\"item\": \"pen\", \"quantity\": 3}");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(json(response).get("code").asString()).isEqualTo("idempotency_key_reused");
        assertThat(orders()).isEqualTo(1);
    }

    @Test
    void keysAreSeparatePerCaller() {
        assertThat(place("shared-key", PEN, "customer-1").statusCode()).isEqualTo(201);
        assertThat(place("shared-key", PEN, "customer-2").statusCode()).isEqualTo(201);

        assertThat(orders()).isEqualTo(2);
    }

    @Test
    void aRequestWithoutAKeyIsRejected() {
        HttpResponse<String> response = post(port, PATH, PEN, "X-Test-Customer", "customer-1");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("code").asString()).isEqualTo("idempotency_key_required");
    }

    @Test
    void aMalformedKeyIsRejected() {
        assertThat(json(place("has space", PEN)).get("code").asString()).isEqualTo("invalid_idempotency_key");
        assertThat(json(place("k".repeat(256), PEN)).get("code").asString()).isEqualTo("invalid_idempotency_key");
        assertThat(orders()).isZero();
    }

    @Test
    void aFailedRequestReleasesItsKeySoTheClientCanRetry() {
        controller.failuresAfterWrite.set(1);

        HttpResponse<String> failed = place("key-3", PEN);
        assertThat(failed.statusCode()).isEqualTo(500);
        assertThat(orders()).as("the write rolled back with the key").isZero();

        HttpResponse<String> retried = place("key-3", PEN);
        assertThat(retried.statusCode()).isEqualTo(201);
        assertThat(retried.headers().firstValue(REPLAYED)).isEmpty();
        assertThat(orders()).isEqualTo(1);
    }

    @Test
    void aRequestWhileAnotherWithTheSameKeyIsRunningGets409WithRetryAfter() throws Exception {
        CountDownLatch keyHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> running = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            jdbc.sql("INSERT INTO platform.idempotency_keys (scope, key, fingerprint, created_at, expires_at) "
                    + "VALUES ('customer-1', 'key-4', 'f', now(), now() + interval '1 hour')").update();
            keyHeld.countDown();
            await(release);
            status.setRollbackOnly();
        }));
        try {
            assertThat(keyHeld.await(10, TimeUnit.SECONDS)).isTrue();

            HttpResponse<String> response = place("key-4", PEN);

            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(json(response).get("code").asString()).isEqualTo("idempotency_request_in_progress");
            assertThat(response.headers().firstValue("Retry-After")).hasValue("1");
        } finally {
            release.countDown();
            running.get(10, TimeUnit.SECONDS);
        }
        assertThat(place("key-4", PEN).statusCode()).as("free once the other request ends").isEqualTo(201);
    }

    @Test
    void anExpiredKeyCanBeUsedForANewRequest() {
        place("key-5", PEN);
        jdbc.sql("UPDATE platform.idempotency_keys SET expires_at = now() - interval '1 second'").update();

        HttpResponse<String> response = place("key-5", "{\"item\": \"ink\", \"quantity\": 1}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(orders()).isEqualTo(2);
    }

    private HttpResponse<String> place(String key, String body) {
        return place(key, body, "customer-1");
    }

    private HttpResponse<String> place(String key, String body, String customer) {
        return post(port, PATH, body, IdempotentRequests.HEADER, key, "X-Test-Customer", customer);
    }

    private int orders() {
        return jdbc.sql("SELECT count(*) FROM public.test_orders").query(Integer.class).single();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Places a fake order inside the idempotent action; can fail after its write. */
    @TestComponent
    @ApiController
    static class OrderFixtureController {

        final AtomicInteger failuresAfterWrite = new AtomicInteger();

        private final IdempotentRequests idempotency;
        private final JdbcClient jdbc;

        OrderFixtureController(IdempotentRequests idempotency, JdbcClient jdbc) {
            this.idempotency = idempotency;
            this.jdbc = jdbc;
        }

        @PostMapping(PATH)
        ResponseEntity<?> place(
                @RequestHeader(name = IdempotentRequests.HEADER, required = false) String key,
                @RequestHeader("X-Test-Customer") String customer,
                @RequestBody OrderRequest order) {
            return idempotency.execute(IdempotentRequest.of(customer, key, "POST " + PATH, order), () -> {
                UUID id = UUID.randomUUID();
                jdbc.sql("INSERT INTO public.test_orders (id, item, quantity) VALUES (?, ?, ?)")
                        .params(id, order.item(), order.quantity())
                        .update();
                if (failuresAfterWrite.getAndDecrement() > 0) {
                    throw new IllegalStateException("Injected failure after the write");
                }
                return ResponseEntity.created(URI.create(PATH + "/" + id)).body(new PlacedOrder(id, order.item()));
            });
        }
    }

    record OrderRequest(String item, int quantity) {
    }

    /** The fixture stands in for a customer endpoint; the caller arrives in a header instead of a token. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureAccess {

        @Bean
        HttpAccessRules idempotentOrderFixtureAccess() {
            return rules -> rules.requestMatchers(PATH).permitAll();
        }
    }

    record PlacedOrder(UUID orderId, String item) {
    }
}
