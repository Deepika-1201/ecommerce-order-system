package com.ecommerce.customer.domain;

import com.ecommerce.customer.Customers;
import com.ecommerce.platform.ApiException;
import com.ecommerce.platform.Caller;
import com.ecommerce.shared.Ids;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Profiles and addresses, always for the calling customer (ADR-017, LLD §3.4). The profile is created on the first
 * call; every address change locks the customer row, which serializes the limit and default rules.
 */
@Service
public class CustomerService implements Customers {

    static final int MAX_ADDRESSES = 10;

    private final CustomerRepository customers;

    CustomerService(CustomerRepository customers) {
        this.customers = customers;
    }

    @Override
    @Transactional
    public UUID idOf(Caller caller) {
        return resolve(caller, false).id();
    }

    @Transactional
    public Customer profile(Caller caller) {
        return resolve(caller, false);
    }

    /** {@code null} leaves a field unchanged. */
    @Transactional
    public Customer updateProfile(Caller caller, String name, String phone) {
        Customer customer = resolve(caller, true);
        String newName = name != null ? name : customer.name();
        String newPhone = phone != null ? phone : customer.phone();
        customers.updateProfile(customer.id(), customer.email(), newName, newPhone);
        return new Customer(customer.id(), customer.subject(), customer.email(), newName, newPhone);
    }

    @Transactional
    public List<Address> addresses(Caller caller) {
        return customers.addresses(resolve(caller, false).id());
    }

    @Transactional
    public Address address(Caller caller, UUID addressId) {
        return owned(resolve(caller, false), addressId);
    }

    @Transactional
    public Address addAddress(Caller caller, AddressDetails details) {
        Customer customer = resolve(caller, true);
        int existing = customers.countAddresses(customer.id());
        if (existing >= MAX_ADDRESSES) {
            throw new ApiException(HttpStatus.CONFLICT, "address_limit_reached",
                    "A customer can keep at most " + MAX_ADDRESSES + " addresses.");
        }
        UUID id = Ids.newId();
        customers.insertAddress(customer.id(), id, details, existing == 0);
        return owned(customer, id);
    }

    @Transactional
    public Address replaceAddress(Caller caller, UUID addressId, AddressDetails details) {
        Customer customer = resolve(caller, true);
        owned(customer, addressId);
        customers.updateAddress(customer.id(), addressId, details);
        return owned(customer, addressId);
    }

    @Transactional
    public void deleteAddress(Caller caller, UUID addressId) {
        Customer customer = resolve(caller, true);
        Address address = owned(customer, addressId);
        customers.deleteAddress(customer.id(), addressId);
        if (address.isDefault()) {
            customers.newestAddress(customer.id()).ifPresent(next -> customers.makeDefault(customer.id(), next));
        }
    }

    @Transactional
    public Address makeDefault(Caller caller, UUID addressId) {
        Customer customer = resolve(caller, true);
        owned(customer, addressId);
        customers.makeDefault(customer.id(), addressId);
        return owned(customer, addressId);
    }

    /** Finds or creates the caller's customer; the identity provider owns the email, so it follows the token. */
    private Customer resolve(Caller caller, boolean lock) {
        Customer customer = customers.findBySubject(caller.subject(), lock).orElseGet(() -> {
            customers.insertIfAbsent(Ids.newId(), caller.subject(), caller.email(), caller.name());
            return customers.findBySubject(caller.subject(), lock).orElseThrow();
        });
        if (caller.email() != null && !Objects.equals(caller.email(), customer.email())) {
            customers.updateProfile(customer.id(), caller.email(), customer.name(), customer.phone());
            customer = new Customer(customer.id(), customer.subject(), caller.email(), customer.name(),
                    customer.phone());
        }
        return customer;
    }

    /** Another customer's address is not found, exactly like one that never existed (ADR-017). */
    private Address owned(Customer customer, UUID addressId) {
        return customers.address(customer.id(), addressId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such address."));
    }
}
