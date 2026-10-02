package com.ecommerce.platform;

import java.util.Locale;

/** What an instance runs (LLD §1.3): the public HTTP API, background work, or both. */
public enum Role {
    API,
    WORKER;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
