package com.ecommerce.ordering.domain;

import com.ecommerce.customer.AddressSnapshot;
import com.ecommerce.customer.Customers;
import com.ecommerce.inventory.StockReservations;
import com.ecommerce.ordering.domain.OrderProcess.CancelOutcome;
import com.ecommerce.platform.ApiException;
import com.ecommerce.platform.AuditEntry;
import com.ecommerce.platform.AuditLog;
import com.ecommerce.platform.Caller;
import com.ecommerce.platform.Correlation;
import com.ecommerce.pricing.Quote;
import com.ecommerce.pricing.Quotes;
import com.ecommerce.shared.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Placing, cancelling and reading orders (LLD §6.3, §6.6, §6.9). A customer's orders are found by id and owner:
 * another customer's order is not found, exactly like one that never existed (ADR-017).
 */
@Service
public class OrderService {

    static final String CANCEL_REQUESTED = "ordering.order.cancel-requested";

    private final OrderRepository orders;
    private final OrderProcesses processes;
    private final Quotes quotes;
    private final Customers customers;
    private final AuditLog audit;
    private final OrderingProperties settings;
    private final Clock clock;

    OrderService(OrderRepository orders, OrderProcesses processes, Quotes quotes, Customers customers,
            AuditLog audit, OrderingProperties settings, Clock clock) {
        this.orders = orders;
        this.processes = processes;
        this.quotes = quotes;
        this.customers = customers;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Places an order from one of the caller's valid quotes, at the quote's prices, with snapshots of the addresses.
     * The billing address defaults to the delivery address. The process starts with {@code ReserveStock} in the outbox;
     * placement does not check stock (LLD §6.3).
     */
    @Transactional
    public OrderView place(Caller caller, UUID quoteId, UUID deliveryAddressId, UUID billingAddressId) {
        UUID customerId = customers.idOf(caller);
        Instant now = clock.instant();
        Quote quote = quotes.findForCustomer(quoteId, customerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such quote."));
        if (!quote.validUntil().isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "quote_expired",
                    "This quote has expired: quote the cart again.");
        }
        AddressSnapshot delivery = snapshot(customerId, deliveryAddressId);
        if (delivery.state() != quote.deliveryState()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "address_state_mismatch",
                    "The delivery address is in " + delivery.state().displayName() + ", but the quote's GST is for "
                            + quote.deliveryState().displayName() + ": quote the cart for the address's state.");
        }
        AddressSnapshot billing = billingAddressId == null ? delivery : snapshot(customerId, billingAddressId);
        UUID orderId = Ids.newId();
        Order order = new Order(orderId, orders.nextNumber(), customerId, quoteId, OrderStatus.PLACED, null, null,
                quote.taxRegime(), quote.supplyState(), quote.deliveryState(), quote.couponId(), quote.couponCode(),
                quote.lines().stream().map(Order.Line::copyOf).toList(), quote.shipping(), quote.totals(),
                delivery.id(), billing.id(), null, null, null, null, now, now);
        List<StockReservations.Line> stock = quote.lines().stream()
                .map(line -> new StockReservations.Line(line.sku(), line.quantity()))
                .toList();
        OrderProcess process = OrderProcess.start(new OrderFacts(orderId, customerId, quote.couponId(),
                quote.totals().grandTotalPaise(), stock, delivery.id()), settings, now);
        try {
            orders.insert(order, process, now);
        } catch (DuplicateKeyException quoteOrdered) {
            throw new ApiException(HttpStatus.CONFLICT, "quote_already_ordered",
                    "An order was already placed from this quote.");
        }
        processes.publish(process, Correlation.start(orderId.toString()));
        return new OrderView(order, delivery, billing);
    }

    /** The customer cancels one of their orders (LLD §6.6). */
    @Transactional
    public OrderView cancelForCustomer(Caller caller, UUID orderId) {
        UUID customerId = customers.idOf(caller);
        OrderProcess process = orders.lockProcess(orderId, settings)
                .filter(found -> found.customerId().equals(customerId))
                .orElseThrow(OrderService::notFound);
        cancel(process, Cancellation.byCustomer(clock.instant()));
        return view(orders.find(orderId).orElseThrow());
    }

    /** Support cancels any order, with a reason code; the request is audit-logged (LLD §6.6, FR-ORD5). */
    @Transactional
    public OrderView cancelForSupport(Caller staff, UUID orderId, CancelCode code, String note) {
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        if (code == CancelCode.OTHER && cleanNote == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request",
                    "A cancellation for another reason needs a note.");
        }
        OrderProcess process = orders.lockProcess(orderId, settings).orElseThrow(OrderService::notFound);
        if (cancel(process, Cancellation.bySupport(code, cleanNote, clock.instant())) == CancelOutcome.REQUESTED) {
            audit.record(new AuditEntry("staff", staff.subject(), CANCEL_REQUESTED, "order", orderId.toString(),
                    code.name(), cleanNote == null ? Map.of() : Map.of("note", cleanNote)));
        }
        return view(orders.find(orderId).orElseThrow());
    }

    @Transactional
    public OrderView customerOrder(Caller caller, UUID orderId) {
        UUID customerId = customers.idOf(caller);
        return orders.find(orderId)
                .filter(order -> order.customerId().equals(customerId))
                .map(this::view)
                .orElseThrow(OrderService::notFound);
    }

    /** The caller's orders, newest first, after the order {@code before} if there is one. */
    @Transactional
    public List<OrderSummary> customerOrders(Caller caller, UUID before, int limit) {
        return orders.summaries(customers.idOf(caller), before, limit);
    }

    @Transactional(readOnly = true)
    public OrderView supportOrder(UUID orderId) {
        return orders.find(orderId).map(this::view).orElseThrow(OrderService::notFound);
    }

    private CancelOutcome cancel(OrderProcess process, Cancellation request) {
        OrderStatus status = process.status();
        CancelOutcome outcome = process.requestCancellation(request, request.requestedAt());
        switch (outcome) {
            case REQUESTED -> processes.save(process, Correlation.start(process.orderId().toString()));
            case ALREADY_CANCELLING -> {
                // Returned as it is.
            }
            case REFUSED -> throw new ApiException(HttpStatus.CONFLICT, "order_invalid_state",
                    "This order is " + status + " and can no longer be cancelled.");
        }
        return outcome;
    }

    private AddressSnapshot snapshot(UUID customerId, UUID addressId) {
        return customers.snapshotAddress(customerId, addressId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "address_not_found",
                        "No such address among yours."));
    }

    private OrderView view(Order order) {
        AddressSnapshot delivery = customers.addressSnapshot(order.deliveryAddressId()).orElseThrow();
        AddressSnapshot billing = order.billingAddressId().equals(order.deliveryAddressId())
                ? delivery
                : customers.addressSnapshot(order.billingAddressId()).orElseThrow();
        return new OrderView(order, delivery, billing);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such order.");
    }
}
