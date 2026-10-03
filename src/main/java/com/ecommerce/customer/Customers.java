package com.ecommerce.customer;

import com.ecommerce.platform.Caller;
import java.util.Optional;
import java.util.UUID;

/** The customer behind a token, for modules that own data per customer (LLD §4.2). */
public interface Customers {

    /** The caller's customer id; the first call creates the profile (LLD §3.4). */
    UUID idOf(Caller caller);

    /**
     * Copies one of the customer's addresses into a new snapshot that never changes (ADR-023), in the caller's
     * transaction; empty if the customer has no such address.
     */
    Optional<AddressSnapshot> snapshotAddress(UUID customerId, UUID addressId);

    Optional<AddressSnapshot> addressSnapshot(UUID snapshotId);
}
