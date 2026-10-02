package com.ecommerce.platform.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where tokens come from (LLD §3.2). Mapped onto Boot's resource-server properties in {@code application.yml}; bound
 * here so a blank value fails startup.
 */
@Validated
@ConfigurationProperties(prefix = "ecom.security")
public record SecurityProperties(@NotBlank String issuer, @NotBlank String jwkSetUri, @NotBlank String audience) {
}
