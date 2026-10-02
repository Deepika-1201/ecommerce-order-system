package com.ecommerce.platform;

import java.util.Locale;
import java.util.Optional;

/** Realm roles from the identity provider. Staff roles do not include {@code CUSTOMER}. */
public enum CallerRole {
    CUSTOMER,
    SUPPORT,
    WAREHOUSE,
    ADMIN;

    /** The Spring Security authority, as matched by {@code hasRole(name())}. */
    public String authority() {
        return "ROLE_" + name();
    }

    /** Maps a role name from the token; roles this system does not know are ignored. */
    public static Optional<CallerRole> fromTokenRole(String role) {
        try {
            return Optional.of(valueOf(role.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
