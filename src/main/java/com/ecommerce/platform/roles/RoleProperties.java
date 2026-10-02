package com.ecommerce.platform.roles;

import com.ecommerce.platform.Role;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Validates {@code ecom.roles} at startup, so an empty or unknown role fails fast. */
@Validated
@ConfigurationProperties(prefix = "ecom")
public record RoleProperties(@NotEmpty Set<Role> roles) {
}
