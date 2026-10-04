package com.ecommerce.fulfillment.domain;

import com.ecommerce.fulfillment.Shipments;
import com.ecommerce.fulfillment.carrier.Carrier;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Fulfillment's reads for Ordering (LLD §8.10, §8.11). */
@Component
class ShipmentQueries implements Shipments {

    private final ShipmentRepository repository;
    private final Carrier carrier;

    ShipmentQueries(ShipmentRepository repository, Carrier carrier) {
        this.repository = repository;
        this.carrier = carrier;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShipmentView> ofOrder(UUID orderId) {
        return repository.find(orderId).map(shipment -> new ShipmentView(shipment.id(), shipment.status(),
                shipment.carrier(), shipment.awb(), repository.appliedTracking(shipment.id())));
    }

    @Override
    public boolean serviceable(String pinCode) {
        return carrier.serviceable(pinCode);
    }
}
