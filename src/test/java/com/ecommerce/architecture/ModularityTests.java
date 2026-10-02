package com.ecommerce.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.EcommerceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    private static final ApplicationModules MODULES = ApplicationModules.of(EcommerceApplication.class);

    @Test
    void modulesRespectTheirAllowedDependencies() {
        MODULES.verify();
    }

    @Test
    void everyBoundedContextIsAModule() {
        assertThat(MODULES.stream().map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrder(
                        "shared", "platform", "catalog", "customer", "cart", "pricing", "ordering", "inventory",
                        "payments", "fulfillment", "notifications");
    }
}
