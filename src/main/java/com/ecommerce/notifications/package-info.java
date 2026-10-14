/** One durable notification per order and kind; SMTP delivery is at least once (ADR-028). */
@ApplicationModule(displayName = "Notifications", allowedDependencies = {"ordering", "customer", "platform", "shared"})
package com.ecommerce.notifications;

import org.springframework.modulith.ApplicationModule;
