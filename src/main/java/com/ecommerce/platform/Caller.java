package com.ecommerce.platform;

import java.util.Set;

/** The authenticated user behind a request, taken from the access token (LLD §3.2). */
public record Caller(String subject, Set<CallerRole> roles, String email, String name) {

    public Caller {
        roles = Set.copyOf(roles);
    }

    public boolean has(CallerRole role) {
        return roles.contains(role);
    }
}
