/** Orders and the order process (saga) that coordinates Inventory, Pricing, Payments and Fulfillment. */
@ApplicationModule(
        displayName = "Ordering",
        allowedDependencies = {"pricing", "customer", "inventory", "payments", "fulfillment", "platform", "shared"})
package com.ecommerce.ordering;

import org.springframework.modulith.ApplicationModule;
