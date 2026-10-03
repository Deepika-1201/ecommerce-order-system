package com.ecommerce.payments.gateway;

import com.ecommerce.payments.PaymentSimulator;
import com.ecommerce.payments.gateway.GatewayWire.CustomerJson;
import com.ecommerce.payments.gateway.GatewayWire.EventJson;
import com.ecommerce.payments.gateway.GatewayWire.PaymentJson;
import com.ecommerce.payments.gateway.GatewayWire.RefundJson;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * The gateway in memory, for tests and local runs without the real one (LLD §7.10). It keeps the rules Payments
 * depends on: answers kept per idempotency key, cancel refused during an attempt and once final, refunds only of a
 * success and within its amount, versions that grow, and an event for every change the gateway reports by webhook.
 * Its events enter the same inbox as webhooks. A restart forgets everything.
 */
public class FakePaymentGateway implements PaymentGateway, PaymentSimulator {

    static final String CHECKOUT_URL = "https://fake-gateway.invalid/checkout/";
    private static final String PROVIDER = "FAKE_PSP";
    private static final Set<GatewayPayment.Status> UNPAID = Set.of(GatewayPayment.Status.REQUIRES_PAYMENT_METHOD,
            GatewayPayment.Status.REQUIRES_ACTION, GatewayPayment.Status.PROCESSING);

    private enum Script {
        REFUSE,
        TIME_OUT,
        TIME_OUT_CHECKOUT
    }

    private final GatewayEvents events;
    private final JsonMapper json;
    private final Clock clock;
    private final Map<String, FakePayment> payments = new HashMap<>();
    private final Map<UUID, FakePayment> paymentsByOrder = new HashMap<>();
    private final Map<String, Object> answers = new HashMap<>();
    private final Map<UUID, Script> scripts = new HashMap<>();

    FakePaymentGateway(GatewayEvents events, JsonMapper json, Clock clock) {
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    // The merchant API.

    @Override
    public synchronized GatewayPayment createPayment(NewPayment request, String idempotencyKey) {
        if (answers.containsKey(idempotencyKey)) {
            return replay(idempotencyKey, GatewayPayment.class);
        }
        Script script = scripts.get(request.orderId());
        if (script == Script.TIME_OUT) {
            throw new GatewayUnavailableException("The fake gateway timed out, as scripted");
        }
        if (script == Script.REFUSE) {
            throw refuse(idempotencyKey, 400, "validation_error", "Refused, as scripted");
        }
        Instant now = clock.instant();
        FakePayment payment = new FakePayment("pay_" + token(), request.orderId(), request.customerId(),
                request.amountPaise(), now.plus(request.expiresIn()), now);
        payments.put(payment.id, payment);
        paymentsByOrder.put(payment.orderId, payment);
        return answer(idempotencyKey, payment.snapshot());
    }

    @Override
    public synchronized CheckoutSession createCheckoutSession(String paymentId, String returnUrl,
                                                              String idempotencyKey) {
        if (answers.containsKey(idempotencyKey)) {
            return replay(idempotencyKey, CheckoutSession.class);
        }
        FakePayment payment = known(paymentId, idempotencyKey);
        if (scripts.get(payment.orderId) == Script.TIME_OUT_CHECKOUT) {
            throw new GatewayUnavailableException("The fake gateway timed out on the checkout session, as scripted");
        }
        String sessionId = "cs_" + token();
        return answer(idempotencyKey, new CheckoutSession(sessionId, CHECKOUT_URL + sessionId));
    }

    @Override
    public synchronized GatewayPayment cancelPayment(String paymentId, String idempotencyKey) {
        if (answers.containsKey(idempotencyKey)) {
            return replay(idempotencyKey, GatewayPayment.class);
        }
        FakePayment payment = known(paymentId, idempotencyKey);
        if (!payment.status.canBeCancelled()) {
            throw refuse(idempotencyKey, 409, "payment_invalid_state", "The payment is " + wire(payment.status));
        }
        payment.moveTo(GatewayPayment.Status.CANCELLED, clock.instant());
        emit("payment.cancelled", payment.json());
        return answer(idempotencyKey, payment.snapshot());
    }

    @Override
    public synchronized GatewayPayment payment(String paymentId) {
        FakePayment payment = payments.get(paymentId);
        if (payment == null) {
            throw new GatewayRefusedException(404, "resource_not_found", "No payment " + paymentId);
        }
        return payment.snapshot();
    }

    @Override
    public synchronized GatewayRefund createRefund(String paymentId, NewRefund request, String idempotencyKey) {
        if (answers.containsKey(idempotencyKey)) {
            return replay(idempotencyKey, GatewayRefund.class);
        }
        FakePayment payment = known(paymentId, idempotencyKey);
        if (payment.status != GatewayPayment.Status.SUCCEEDED) {
            throw refuse(idempotencyKey, 409, "payment_invalid_state", "The payment is " + wire(payment.status));
        }
        if (payment.refunds.stream().anyMatch(refund -> request.merchantRefundId().equals(refund.merchantRefundId))) {
            throw refuse(idempotencyKey, 409, "refund_already_exists", request.merchantRefundId());
        }
        if (request.amountPaise() > payment.refundable()) {
            throw refuse(idempotencyKey, 422, "amount_exceeds_refundable", "Refundable: " + payment.refundable());
        }
        FakeRefund refund = payment.refund(request.amountPaise(), GatewayRefund.Initiator.MERCHANT, request.reason(),
                request.merchantRefundId(), clock.instant());
        return answer(idempotencyKey, refund.snapshot());
    }

    // The customer and the PSPs.

    @Override
    public synchronized void refuseCreation(UUID orderId) {
        script(orderId, Script.REFUSE);
    }

    @Override
    public synchronized void timeOutCreation(UUID orderId) {
        script(orderId, Script.TIME_OUT);
    }

    @Override
    public synchronized void timeOutCheckout(UUID orderId) {
        script(orderId, Script.TIME_OUT_CHECKOUT);
    }

    @Override
    public synchronized void startAttempt(UUID orderId) {
        FakePayment payment = ofOrder(orderId);
        require(payment, payment.status.canBeCancelled(), "start an attempt");
        payment.attempts++;
        payment.moveTo(GatewayPayment.Status.PROCESSING, clock.instant());
    }

    @Override
    public synchronized void failAttempt(UUID orderId) {
        FakePayment payment = ofOrder(orderId);
        require(payment, payment.status == GatewayPayment.Status.PROCESSING, "fail its attempt");
        payment.moveTo(GatewayPayment.Status.REQUIRES_PAYMENT_METHOD, clock.instant());
        emit("payment.attempt_failed", payment.json());
    }

    @Override
    public synchronized void succeed(UUID orderId) {
        FakePayment payment = ofOrder(orderId);
        Instant now = clock.instant();
        boolean accepted = payment.acceptsLateSuccess && payment.status != GatewayPayment.Status.SUCCEEDED;
        if (UNPAID.contains(payment.status) || accepted) {
            payment.moveTo(GatewayPayment.Status.SUCCEEDED, now);
            emit("payment.succeeded", payment.json());
        } else {
            payment.refund(payment.amountPaise, payment.status == GatewayPayment.Status.SUCCEEDED
                    ? GatewayRefund.Initiator.SYSTEM_DUPLICATE_SUCCESS
                    : GatewayRefund.Initiator.SYSTEM_LATE_SUCCESS, null, null, now);
        }
    }

    @Override
    public synchronized void acceptLateSuccess(UUID orderId) {
        ofOrder(orderId).acceptsLateSuccess = true;
    }

    @Override
    public synchronized void fail(UUID orderId) {
        end(orderId, GatewayPayment.Status.FAILED, "payment.failed");
    }

    @Override
    public synchronized void expire(UUID orderId) {
        end(orderId, GatewayPayment.Status.EXPIRED, "payment.expired");
    }

    @Override
    public synchronized void succeedRefund(UUID orderId) {
        settleRefund(orderId, GatewayRefund.Status.SUCCEEDED, "refund.succeeded");
    }

    @Override
    public synchronized void failRefund(UUID orderId) {
        settleRefund(orderId, GatewayRefund.Status.FAILED, "refund.failed");
    }

    private void script(UUID orderId, Script script) {
        if (paymentsByOrder.containsKey(orderId)) {
            throw new IllegalStateException("The payment of order " + orderId + " was already created");
        }
        scripts.put(orderId, script);
    }

    private void end(UUID orderId, GatewayPayment.Status status, String eventType) {
        FakePayment payment = ofOrder(orderId);
        require(payment, UNPAID.contains(payment.status), "become " + wire(status));
        payment.moveTo(status, clock.instant());
        emit(eventType, payment.json());
    }

    private void settleRefund(UUID orderId, GatewayRefund.Status status, String eventType) {
        FakePayment payment = ofOrder(orderId);
        FakeRefund refund = payment.refunds.stream()
                .filter(candidate -> !candidate.status.isFinal())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Order " + orderId + " has no pending refund"));
        refund.moveTo(status, clock.instant());
        if (status == GatewayRefund.Status.SUCCEEDED) {
            payment.amountRefunded += refund.amountPaise;
        }
        emit(eventType, refund.json());
    }

    private FakePayment ofOrder(UUID orderId) {
        FakePayment payment = paymentsByOrder.get(orderId);
        if (payment == null) {
            throw new IllegalStateException("Order " + orderId + " has no payment at the gateway");
        }
        return payment;
    }

    private static void require(FakePayment payment, boolean allowed, String change) {
        if (!allowed) {
            throw new IllegalStateException("The " + wire(payment.status) + " payment of order " + payment.orderId
                    + " cannot " + change);
        }
    }

    private FakePayment known(String paymentId, String idempotencyKey) {
        FakePayment payment = payments.get(paymentId);
        if (payment == null) {
            throw refuse(idempotencyKey, 404, "resource_not_found", "No payment " + paymentId);
        }
        return payment;
    }

    private GatewayRefusedException refuse(String idempotencyKey, int status, String code, String detail) {
        GatewayRefusedException refusal = new GatewayRefusedException(status, code, detail);
        answers.put(idempotencyKey, refusal);
        return refusal;
    }

    private <T> T answer(String idempotencyKey, T answer) {
        answers.put(idempotencyKey, answer);
        return answer;
    }

    private <T> T replay(String idempotencyKey, Class<T> type) {
        Object answer = answers.get(idempotencyKey);
        if (answer instanceof GatewayRefusedException refusal) {
            throw new GatewayRefusedException(refusal.status(), refusal.code(), "Replayed");
        }
        if (!type.isInstance(answer)) {
            throw new GatewayRefusedException(422, "idempotency_key_reuse", idempotencyKey);
        }
        return type.cast(answer);
    }

    /** Delivers an event through the inbox, as a verified webhook would arrive. */
    private void emit(String type, Object object) {
        String eventId = "evt_" + token();
        events.receive(json.writeValueAsString(new EventJson(eventId, type, clock.instant(),
                Map.of("object", object))));
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String wire(Enum<?> constant) {
        return GatewayWire.wire(constant);
    }

    private static final class FakePayment {

        private final String id;
        private final UUID orderId;
        private final UUID customerId;
        private final long amountPaise;
        private final Instant expiresAt;
        private final Instant createdAt;
        private final List<FakeRefund> refunds = new ArrayList<>();
        private GatewayPayment.Status status = GatewayPayment.Status.REQUIRES_PAYMENT_METHOD;
        private long version = 1;
        private int attempts;
        private long amountRefunded;
        private boolean acceptsLateSuccess;
        private Instant updatedAt;

        private FakePayment(String id, UUID orderId, UUID customerId, long amountPaise, Instant expiresAt,
                            Instant createdAt) {
            this.id = id;
            this.orderId = orderId;
            this.customerId = customerId;
            this.amountPaise = amountPaise;
            this.expiresAt = expiresAt;
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
        }

        private void moveTo(GatewayPayment.Status next, Instant now) {
            status = next;
            version++;
            updatedAt = now;
        }

        private long refundable() {
            return amountPaise - refunds.stream()
                    .filter(refund -> refund.initiator == GatewayRefund.Initiator.MERCHANT)
                    .filter(refund -> refund.status != GatewayRefund.Status.FAILED)
                    .mapToLong(refund -> refund.amountPaise)
                    .sum();
        }

        private FakeRefund refund(long amount, GatewayRefund.Initiator initiator, String reason,
                                  String merchantRefundId, Instant now) {
            FakeRefund refund = new FakeRefund("rfnd_" + token(), id, amount, initiator, reason, merchantRefundId, now);
            refunds.add(refund);
            return refund;
        }

        private GatewayPayment snapshot() {
            return new GatewayPayment(id, orderId.toString(), amountPaise, status, version);
        }

        private PaymentJson json() {
            long captured = status == GatewayPayment.Status.SUCCEEDED ? amountPaise : 0;
            return new PaymentJson(id, "payment", orderId.toString(), amountPaise, "INR", wire(status), "automatic",
                    customerId == null ? null : new CustomerJson(customerId.toString()), captured, amountRefunded,
                    attempts, expiresAt, createdAt, updatedAt, version);
        }
    }

    private static final class FakeRefund {

        private final String id;
        private final String paymentId;
        private final long amountPaise;
        private final GatewayRefund.Initiator initiator;
        private final String reason;
        private final String merchantRefundId;
        private final Instant createdAt;
        private GatewayRefund.Status status = GatewayRefund.Status.PENDING;
        private long version = 1;
        private Instant updatedAt;

        private FakeRefund(String id, String paymentId, long amountPaise, GatewayRefund.Initiator initiator,
                           String reason, String merchantRefundId, Instant createdAt) {
            this.id = id;
            this.paymentId = paymentId;
            this.amountPaise = amountPaise;
            this.initiator = initiator;
            this.reason = reason;
            this.merchantRefundId = merchantRefundId;
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
        }

        private void moveTo(GatewayRefund.Status next, Instant now) {
            status = next;
            version++;
            updatedAt = now;
        }

        private GatewayRefund snapshot() {
            return new GatewayRefund(id, paymentId, amountPaise, status, initiator, merchantRefundId, version);
        }

        private RefundJson json() {
            return new RefundJson(id, "refund", paymentId, "att_" + paymentId.substring(4), amountPaise, "INR",
                    wire(status), reason, merchantRefundId, wire(initiator), PROVIDER, createdAt, updatedAt, version);
        }
    }
}
