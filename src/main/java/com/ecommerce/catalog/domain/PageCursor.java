package com.ecommerce.catalog.domain;

import java.util.UUID;

/** Where a list page starts: after a product id when browsing, at an offset for ranked search results. */
public sealed interface PageCursor {

    record AfterId(UUID id) implements PageCursor {
    }

    record Offset(int offset) implements PageCursor {
    }
}
