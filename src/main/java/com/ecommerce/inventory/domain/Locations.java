package com.ecommerce.inventory.domain;

/** Where stock is kept. Every key includes the location, though V1 has only one (LLD §5.1). */
final class Locations {

    /** The Bengaluru warehouse, created by the migration. */
    static final String BENGALURU = "BLR1";

    private Locations() {
    }
}
