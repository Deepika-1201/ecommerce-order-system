package com.ecommerce.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.shared.IndianState;
import com.ecommerce.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ADR-017 below the service: every address statement is scoped to its owner, so even an id taken from another
 * customer changes nothing. The service checks ownership first, which hides these statements from the API tests.
 */
class CustomerRepositoryTests extends IntegrationTest {

    private static final AddressDetails HOME = new AddressDetails("Asha Rao", "+919876543210",
            "12, 4th Cross, Indiranagar", null, null, "Bengaluru", IndianState.KARNATAKA, "560038");
    private static final AddressDetails ELSEWHERE = new AddressDetails("Ravi Kumar", "+919123456789",
            "Flat 3B, Lake View", null, null, "Mumbai", IndianState.MAHARASHTRA, "400050");

    @Autowired
    private CustomerRepository customers;

    private UUID asha;
    private UUID ravi;
    private UUID ashasDefault;
    private UUID ashasOther;

    @BeforeEach
    void ashaHasTwoAddressesAndRaviNone() {
        asha = customer();
        ravi = customer();
        ashasDefault = UUID.randomUUID();
        ashasOther = UUID.randomUUID();
        customers.insertAddress(asha, ashasDefault, HOME, true);
        customers.insertAddress(asha, ashasOther, HOME, false);
    }

    @Test
    void anotherCustomersIdReadsNothing() {
        assertThat(customers.address(ravi, ashasDefault)).isEmpty();
        assertThat(customers.addresses(ravi)).isEmpty();
        assertThat(customers.countAddresses(ravi)).isZero();
        assertThat(customers.newestAddress(ravi)).isEmpty();
    }

    @Test
    void anotherCustomersIdChangesNothing() {
        List<Address> before = customers.addresses(asha);

        customers.updateAddress(ravi, ashasDefault, ELSEWHERE);
        customers.makeDefault(ravi, ashasOther);
        customers.deleteAddress(ravi, ashasDefault);

        assertThat(customers.addresses(asha)).isEqualTo(before);
        assertThat(customers.address(asha, ashasDefault)).hasValueSatisfying(address -> {
            assertThat(address.city()).isEqualTo("Bengaluru");
            assertThat(address.isDefault()).isTrue();
        });
    }

    private UUID customer() {
        String subject = "subject-" + UUID.randomUUID();
        customers.insertIfAbsent(UUID.randomUUID(), subject, subject + "@example.test", null);
        return customers.findBySubject(subject, false).orElseThrow().id();
    }
}
