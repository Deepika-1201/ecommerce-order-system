/** Customer notifications, sent at most once per order and kind. */
@ApplicationModule(displayName = "Notifications", allowedDependencies = {"ordering", "customer", "platform", "shared"})
package com.ecommerce.notifications;

import org.springframework.modulith.ApplicationModule;
