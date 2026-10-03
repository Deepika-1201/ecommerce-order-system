package com.ecommerce.inventory.domain;

/** A reservation's life (LLD §5.5). Only {@code HELD} and {@code COMMITTED} reservations count in {@code reserved}. */
enum ReservationStatus {
    HELD,
    REJECTED,
    COMMITTED,
    RELEASED,
    EXPIRED,
    FULFILLED,
    RETURNED
}
