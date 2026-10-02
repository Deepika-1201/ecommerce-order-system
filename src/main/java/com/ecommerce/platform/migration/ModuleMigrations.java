package com.ecommerce.platform.migration;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Migrates each module's schema with its own Flyway history ({@code <module>.flyway_schema_history}), so the history
 * moves with the module when it is extracted (ADR-005, LLD §1.6).
 */
@Configuration(proxyBeanMethods = false)
class ModuleMigrations {

    static final List<String> MODULES = List.of(
            "platform", "catalog", "customer", "cart", "pricing", "inventory", "payments", "fulfillment", "ordering",
            "notifications");

    @Bean
    FlywayMigrationStrategy migrateEachModule() {
        return flyway -> MODULES.forEach(module -> Flyway.configure()
                .configuration(flyway.getConfiguration())
                .schemas(module)
                .defaultSchema(module)
                .locations("classpath:db/migration/" + module)
                .load()
                .migrate());
    }
}
