package com.ecommerce.fulfillment.simulator;

import com.ecommerce.fulfillment.ShipmentSimulator;
import com.ecommerce.fulfillment.Shipments;
import com.ecommerce.fulfillment.Shipments.ShipmentView;
import com.ecommerce.fulfillment.carrier.SimulatedCarrier;
import com.ecommerce.fulfillment.carrier.SimulatedCarrier.SimulatedParcel;
import com.ecommerce.fulfillment.domain.ShipmentOperations;
import com.ecommerce.platform.ApiException;
import com.ecommerce.platform.Caller;
import com.ecommerce.platform.CallerRole;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Drives the warehouse and the carrier simulator in tests, as phase 6's shipment simulator was driven (LLD §8.9). The
 * warehouse's marks take effect at once; scans enter the inbox, and take effect when their task runs.
 */
@Component
class CarrierSimulatorDriver implements ShipmentSimulator {

    private static final Caller WAREHOUSE = new Caller("shipment-simulator", Set.of(CallerRole.WAREHOUSE), null, null);
    /** Scans after which the carrier no longer tries to deliver. */
    private static final Set<String> DELIVERY_OVER = Set.of("delivered", "rto_in_transit", "rto_delivered");

    private final Shipments shipments;
    private final ShipmentOperations operations;
    private final SimulatedCarrier carrier;

    CarrierSimulatorDriver(Shipments shipments, ShipmentOperations operations, SimulatedCarrier carrier) {
        this.shipments = shipments;
        this.operations = operations;
        this.carrier = carrier;
    }

    @Override
    public void handOver(UUID orderId) {
        UUID shipmentId = shipment(orderId).id();
        try {
            operations.pack(WAREHOUSE, shipmentId);
            operations.handOver(WAREHOUSE, shipmentId);
        } catch (ApiException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public void deliver(UUID orderId) {
        UUID reference = onItsWay(orderId);
        carrier.scan(reference, "in_transit");
        carrier.scan(reference, "out_for_delivery");
        carrier.scan(reference, "delivered");
    }

    @Override
    public void startReturn(UUID orderId) {
        UUID reference = onItsWay(orderId);
        carrier.scan(reference, "delivery_attempt_failed");
        carrier.scan(reference, "rto_in_transit");
    }

    @Override
    public void completeReturn(UUID orderId) {
        SimulatedParcel parcel = parcel(orderId);
        if (!"rto_in_transit".equals(parcel.scan())) {
            throw new IllegalStateException("The parcel of order " + orderId + " is not on its way back");
        }
        carrier.scan(parcel.reference(), "rto_delivered");
    }

    @Override
    public void scan(UUID orderId, String scan, Instant occurredAt) {
        carrier.scan(parcel(orderId).reference(), scan, occurredAt);
    }

    @Override
    public void followScenario(UUID orderId) {
        UUID reference = onItsWay(orderId);
        int steps = carrier.parcel(reference).orElseThrow().scenario().itinerary().size();
        for (int step = 0; step < steps; step++) {
            carrier.advance(reference);
        }
    }

    /** The parcel of an order handed over, which the carrier is still trying to deliver. */
    private UUID onItsWay(UUID orderId) {
        SimulatedParcel parcel = parcel(orderId);
        if (parcel.scan() == null && !shipment(orderId).status().isHandedOver()) {
            throw new IllegalStateException("The parcel of order " + orderId + " was not handed over");
        }
        if (parcel.scan() != null && DELIVERY_OVER.contains(parcel.scan())) {
            throw new IllegalStateException("The parcel of order " + orderId + " is " + parcel.scan());
        }
        return parcel.reference();
    }

    private SimulatedParcel parcel(UUID orderId) {
        return carrier.parcel(shipment(orderId).id())
                .filter(parcel -> parcel.awb() != null)
                .orElseThrow(() -> new IllegalStateException("The carrier has no booking for order " + orderId));
    }

    private ShipmentView shipment(UUID orderId) {
        return shipments.ofOrder(orderId)
                .orElseThrow(() -> new IllegalStateException("Order " + orderId + " has no shipment"));
    }
}
