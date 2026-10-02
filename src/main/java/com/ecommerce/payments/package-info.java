/** Payment and refund records per order; anti-corruption layer over the Payment Gateway (ADR-002). */
@ApplicationModule(displayName = "Payments", allowedDependencies = {"platform", "shared"})
package com.ecommerce.payments;

import org.springframework.modulith.ApplicationModule;
