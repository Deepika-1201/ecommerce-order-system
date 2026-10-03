package com.ecommerce.inventory.web;

import com.ecommerce.platform.CallerRole;
import com.ecommerce.platform.HttpAccessRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class InventoryWebConfiguration {

    /** Stock counts are the warehouse's to change: admins and support staff get 403 (LLD §5.9). */
    @Bean
    HttpAccessRules inventoryAccessRules() {
        return rules -> rules.requestMatchers("/v1/warehouse/**").hasRole(CallerRole.WAREHOUSE.name());
    }
}
