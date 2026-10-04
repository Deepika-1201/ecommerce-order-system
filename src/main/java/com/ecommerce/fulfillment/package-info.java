/** Shipments, carrier bookings and tracking. Reads delivery addresses from Customer's snapshots (ADR-023). */
@ApplicationModule(displayName = "Fulfillment", allowedDependencies = {"customer", "platform", "shared"})
package com.ecommerce.fulfillment;

import org.springframework.modulith.ApplicationModule;
