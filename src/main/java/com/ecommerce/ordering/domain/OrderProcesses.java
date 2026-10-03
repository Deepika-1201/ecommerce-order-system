package com.ecommerce.ordering.domain;

import com.ecommerce.platform.Correlation;
import com.ecommerce.platform.IncomingMessage;
import com.ecommerce.platform.Messages;
import com.ecommerce.platform.Origin;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs order processes (LLD §6.5, §6.7): lock the order, decide, save, publish, all in the caller's transaction,
 * which for a message is its delivery transaction.
 */
@Component
class OrderProcesses {

    static final String AGGREGATE = "order";

    private static final Logger log = LoggerFactory.getLogger(OrderProcesses.class);

    private final OrderRepository orders;
    private final Messages messages;
    private final OrderingProperties settings;
    private final Clock clock;

    OrderProcesses(OrderRepository orders, Messages messages, OrderingProperties settings, Clock clock) {
        this.orders = orders;
        this.messages = messages;
        this.settings = settings;
        this.clock = clock;
    }

    /** A participant's reply or event; one the step does not expect is logged and changes nothing. */
    void handle(UUID orderId, IncomingMessage<?> message) {
        OrderProcess process = lock(orderId);
        Step step = process.step();
        process.decide(message.payload(), clock.instant());
        if (!process.changed()) {
            log.info("Order {} in step {} ignored {} {}", orderId, step, message.type(), message.messageId());
            return;
        }
        if (process.refundFailed()) {
            log.error("order_refund_failed: order {}, {} refund of {} paise", orderId, process.refundReason(),
                    process.refundAmountPaise());
        }
        save(process, Correlation.causedBy(message));
    }

    /** Acts on the process's deadline if it has passed; nothing if another sweep acted first. */
    void handleDeadline(UUID orderId) {
        OrderProcess process = lock(orderId);
        process.onDeadline(clock.instant());
        if (!process.changed()) {
            return;
        }
        if (process.overdue()) {
            ProcessState state = process.state();
            log.error("order_process_overdue: order {} in step {} after {} attempts", orderId, state.step(),
                    state.attempts());
        }
        save(process, Correlation.start(orderId.toString()));
    }

    OrderProcess lock(UUID orderId) {
        return orders.lockProcess(orderId, settings)
                .orElseThrow(() -> new IllegalStateException("No order " + orderId));
    }

    /** Saves the change and publishes its commands, which come from the order at the change's version. */
    void save(OrderProcess process, Correlation correlation) {
        Instant now = clock.instant();
        orders.save(process, now);
        publish(process, correlation);
    }

    void publish(OrderProcess process, Correlation correlation) {
        Origin origin = new Origin(AGGREGATE, process.orderId(), process.sequence());
        process.commands().forEach(command -> messages.publish(command, origin, correlation));
    }
}
