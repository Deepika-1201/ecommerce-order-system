package com.ecommerce.customer.domain;

import java.util.UUID;

/** A customer profile; identity stays with the identity provider (domain model §2). */
public record Customer(UUID id, String subject, String email, String name, String phone) {
}
