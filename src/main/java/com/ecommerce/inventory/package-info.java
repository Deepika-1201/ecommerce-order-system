/** Stock per SKU and location, and reservations. The only gate against overselling (ADR-009). */
@ApplicationModule(displayName = "Inventory", allowedDependencies = {"platform", "shared"})
package com.ecommerce.inventory;

import org.springframework.modulith.ApplicationModule;
