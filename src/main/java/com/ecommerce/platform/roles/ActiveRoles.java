package com.ecommerce.platform.roles;

import com.ecommerce.platform.Role;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/** Reads {@code ecom.roles} directly from the environment, for conditions that run before any bean exists. */
final class ActiveRoles {

    static final String PROPERTY = "ecom.roles";

    private ActiveRoles() {
    }

    static Set<Role> of(Environment environment) {
        return Binder.get(environment)
                .bind(PROPERTY, Bindable.setOf(Role.class))
                .map(Set::copyOf)
                .orElseGet(() -> Set.of(Role.values()));
    }
}
