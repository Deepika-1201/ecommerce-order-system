package com.ecommerce.fulfillment.domain;

import com.ecommerce.customer.AddressSnapshot;
import com.ecommerce.customer.Customers;
import com.ecommerce.fulfillment.ShipmentMessages.Line;
import com.ecommerce.fulfillment.ShipmentStatus;
import com.ecommerce.platform.ApiException;
import com.ecommerce.platform.AuditEntry;
import com.ecommerce.platform.AuditLog;
import com.ecommerce.platform.Caller;
import com.ecommerce.platform.TaskScheduler;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The warehouse's work on shipments, and support's re-drive of a failed booking (LLD §8.5, §8.7). */
@Service
public class ShipmentOperations {

    static final String PACKED = "fulfillment.shipment.packed";
    static final String HANDED_OVER = "fulfillment.shipment.handed-over";
    static final String BOOKING_REDRIVEN = "fulfillment.shipment.booking-redriven";

    private final ShipmentRepository repository;
    private final ShipmentUpdates updates;
    private final Customers customers;
    private final TaskScheduler tasks;
    private final AuditLog audit;
    private final FulfillmentProperties properties;
    private final Clock clock;

    ShipmentOperations(ShipmentRepository repository, ShipmentUpdates updates, Customers customers,
                       TaskScheduler tasks, AuditLog audit, FulfillmentProperties properties, Clock clock) {
        this.repository = repository;
        this.updates = updates;
        this.customers = customers;
        this.tasks = tasks;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** A shipment as the warehouse works on it: what to pack, and where it goes. */
    public record WarehouseShipment(UUID id, UUID orderId, ShipmentStatus status, String carrier, String awb,
                                    List<Line> lines, AddressSnapshot deliveryAddress) {

        public WarehouseShipment {
            lines = List.copyOf(lines);
        }
    }

    /** Shipments in a status, oldest first, after the shipment {@code after}; one more than the limit, if there is. */
    @Transactional(readOnly = true)
    public List<WarehouseShipment> inStatus(ShipmentStatus status, UUID after, int limit) {
        return repository.inStatus(status, after, limit + 1).stream().map(this::view).toList();
    }

    @Transactional
    public WarehouseShipment pack(Caller staff, UUID shipmentId) {
        return mark(staff, shipmentId, ShipmentStatus.PACKED, ShipmentStatus.BOOKED, PACKED);
    }

    @Transactional
    public WarehouseShipment handOver(Caller staff, UUID shipmentId) {
        return mark(staff, shipmentId, ShipmentStatus.HANDED_OVER, ShipmentStatus.PACKED, HANDED_OVER);
    }

    /** Books a failed booking again, with a new budget (S8). */
    @Transactional
    public void redriveBooking(Caller staff, UUID shipmentId) {
        Shipment shipment = repository.lockById(shipmentId).orElseThrow(ShipmentOperations::notFound);
        if (shipment.status() != ShipmentStatus.BOOKING_FAILED) {
            throw invalidState(shipment, "booked again");
        }
        Instant now = clock.instant();
        repository.rebook(shipmentId, now.plus(properties.bookingBudget()), now);
        tasks.schedule(ShipmentTasks.book(shipment.orderId(), null));
        audit.record(new AuditEntry("staff", staff.subject(), BOOKING_REDRIVEN, "shipment", shipmentId.toString(),
                shipment.bookingFailure(), Map.of("order_id", shipment.orderId().toString())));
    }

    /** A mark the shipment has passed changes nothing; one it cannot take, or while it is being cancelled, is refused. */
    private WarehouseShipment mark(Caller staff, UUID shipmentId, ShipmentStatus mark, ShipmentStatus from,
                                   String action) {
        Shipment shipment = repository.lockById(shipmentId).orElseThrow(ShipmentOperations::notFound);
        if (shipment.status().isAtOrPast(mark)) {
            return view(shipment);
        }
        if (shipment.cancelRequested()) {
            throw new ApiException(HttpStatus.CONFLICT, "shipment_cancelling",
                    "This shipment is being cancelled: keep the parcel.");
        }
        if (shipment.status() != from) {
            throw invalidState(shipment, mark.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        Shipment moved = updates.mark(shipment, mark);
        audit.record(new AuditEntry("staff", staff.subject(), action, "shipment", shipmentId.toString(), null,
                Map.of("order_id", shipment.orderId().toString())));
        return view(moved);
    }

    private WarehouseShipment view(Shipment shipment) {
        AddressSnapshot address = shipment.deliveryAddressId() == null ? null
                : customers.addressSnapshot(shipment.deliveryAddressId()).orElse(null);
        return new WarehouseShipment(shipment.id(), shipment.orderId(), shipment.status(), shipment.carrier(),
                shipment.awb(), repository.lines(shipment.id()), address);
    }

    private static ApiException invalidState(Shipment shipment, String change) {
        return new ApiException(HttpStatus.CONFLICT, "shipment_invalid_state",
                "This shipment is " + shipment.status() + " and cannot be " + change + ".");
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such shipment.");
    }
}
