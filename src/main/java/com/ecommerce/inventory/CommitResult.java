package com.ecommerce.inventory;

/** The outcome of committing a reservation once its payment succeeded (LLD §5.5). */
public enum CommitResult {
    COMMITTED,
    /** The stock went to other orders while the hold was expired, or the reservation was released. */
    LOST
}
