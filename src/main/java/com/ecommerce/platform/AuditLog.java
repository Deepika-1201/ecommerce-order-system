package com.ecommerce.platform;

/** The append-only record of who did what to which entity, and why (LLD §2.9). */
public interface AuditLog {

    /** Records the entry in the caller's transaction, which must exist. */
    void record(AuditEntry entry);
}
