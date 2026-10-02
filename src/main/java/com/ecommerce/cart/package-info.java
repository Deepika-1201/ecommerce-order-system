/** Shopping carts for customers and guests. Carts never hold stock. */
@ApplicationModule(displayName = "Cart", allowedDependencies = {"catalog", "customer", "pricing", "platform", "shared"})
package com.ecommerce.cart;

import org.springframework.modulith.ApplicationModule;
