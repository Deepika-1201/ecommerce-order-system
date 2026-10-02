package com.ecommerce.catalog.domain;

import java.util.UUID;

public record Category(UUID id, UUID parentId, String name, String slug) {
}
