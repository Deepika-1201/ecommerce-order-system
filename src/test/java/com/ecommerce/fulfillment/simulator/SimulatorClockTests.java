package com.ecommerce.fulfillment.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.fulfillment.ShipmentMessages;
import com.ecommerce.fulfillment.ShipmentMessages.CreateShipment;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentDelivered;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentHandedOver;
import com.ecommerce.fulfillment.carrier.SimulatedCarrier;
import com.ecommerce.ordering.OrderingTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The simulator's clock (LLD §8.9): one scan per handed-over parcel per tick, and nothing for the others. */
class SimulatorClockTests extends OrderingTest {

    private static final Class<?>[] EVENTS = {ShipmentHandedOver.class, ShipmentDelivered.class};

    @Autowired
    private SimulatedCarrier carrier;

    @Test
    void eachTickMovesOnlyHandedOverParcelsOneScanOn() {
        UUID waiting = booked();
        UUID moving = booked();
        shipments.handOver(moving);
        SimulatorClock clock = new SimulatorClock(carrier);

        clock.advance(null);
        deliverExcept(EVENTS);
        assertThat(shipment(moving)).isEqualTo("IN_TRANSIT");
        assertThat(shipment(waiting)).isEqualTo("BOOKED");

        for (int tick = 0; tick < 3; tick++) {
            clock.advance(null);
        }
        deliverExcept(EVENTS);

        assertThat(shipment(moving)).isEqualTo("DELIVERED");
        assertThat(shipment(waiting)).isEqualTo("BOOKED");
        assertThat(carrier.handedOverParcelsWithScansLeft()).isEmpty();
    }

    private UUID booked() {
        UUID order = UUID.randomUUID();
        publish(new CreateShipment(order, deliverySnapshot("560038"), List.of(new ShipmentMessages.Line("SKU-A", 1))),
                "order", order);
        deliverExcept(EVENTS);
        return order;
    }
}
