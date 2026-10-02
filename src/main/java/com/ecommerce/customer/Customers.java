package com.ecommerce.customer;

import com.ecommerce.platform.Caller;
import java.util.UUID;

/** The customer behind a token, for modules that own data per customer (LLD §4.2). */
public interface Customers {

    /** The caller's customer id; the first call creates the profile (LLD §3.4). */
    UUID idOf(Caller caller);
}
