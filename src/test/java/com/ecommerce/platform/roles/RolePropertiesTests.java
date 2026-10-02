package com.ecommerce.platform.roles;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.platform.Role;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

class RolePropertiesTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @Test
    void bindsCommaSeparatedRolesInAnyCase() {
        runner.withPropertyValues("ecom.roles=API, worker").run(context ->
                assertThat(context.getBean(RoleProperties.class).roles()).containsExactlyInAnyOrder(Role.API, Role.WORKER));
    }

    @Test
    void refusesToStartWithoutARole() {
        runner.withPropertyValues("ecom.roles=").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refusesToStartWithAnUnknownRole() {
        runner.withPropertyValues("ecom.roles=api,cron").run(context -> assertThat(context).hasFailed());
    }

    @EnableConfigurationProperties(RoleProperties.class)
    static class Config {
    }
}
