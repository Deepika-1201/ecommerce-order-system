package com.ecommerce.inventory.domain;

/** Why {@code on_hand} changed (LLD §5.8). */
public enum MovementKind {
    RECEIPT,
    ADJUSTMENT,
    HANDOVER,
    RETURN
}
