package com.ecommerce.customer.domain;

import com.ecommerce.shared.IndianState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for {@code customer.customers} and {@code customer.addresses}. Address lookups always take the owner
 * (ADR-017): there is no way to read an address by id alone.
 */
@Repository
class CustomerRepository {

    private static final String ADDRESS_COLUMNS = """
            id, recipient_name, phone, line1, line2, landmark, city, state_code, pin_code, is_default
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    CustomerRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Creates the customer on first use; concurrent first calls create one row. */
    void insertIfAbsent(UUID id, String subject, String email, String name) {
        OffsetDateTime now = now();
        jdbc.sql("""
                        INSERT INTO customer.customers (id, subject, email, name, created_at, updated_at)
                        VALUES (:id, :subject, :email, :name, :now, :now)
                        ON CONFLICT (subject) DO NOTHING
                        """)
                .param("id", id)
                .param("subject", subject)
                .param("email", email)
                .param("name", name)
                .param("now", now)
                .update();
    }

    Optional<Customer> findBySubject(String subject, boolean lock) {
        return jdbc.sql("SELECT id, subject, email, name, phone FROM customer.customers WHERE subject = :subject"
                        + (lock ? " FOR UPDATE" : ""))
                .param("subject", subject)
                .query(CustomerRepository::customer)
                .optional();
    }

    void updateProfile(UUID id, String email, String name, String phone) {
        jdbc.sql("""
                        UPDATE customer.customers
                        SET email = :email, name = :name, phone = :phone, updated_at = :now
                        WHERE id = :id
                        """)
                .param("email", email)
                .param("name", name)
                .param("phone", phone)
                .param("now", now())
                .param("id", id)
                .update();
    }

    List<Address> addresses(UUID customerId) {
        return jdbc.sql("SELECT " + ADDRESS_COLUMNS + " FROM customer.addresses WHERE customer_id = :customerId"
                        + " ORDER BY is_default DESC, created_at DESC, id DESC")
                .param("customerId", customerId)
                .query(CustomerRepository::address)
                .list();
    }

    Optional<Address> address(UUID customerId, UUID addressId) {
        return jdbc.sql("SELECT " + ADDRESS_COLUMNS
                        + " FROM customer.addresses WHERE customer_id = :customerId AND id = :id")
                .param("customerId", customerId)
                .param("id", addressId)
                .query(CustomerRepository::address)
                .optional();
    }

    int countAddresses(UUID customerId) {
        return jdbc.sql("SELECT count(*) FROM customer.addresses WHERE customer_id = :customerId")
                .param("customerId", customerId)
                .query(Integer.class)
                .single();
    }

    void insertAddress(UUID customerId, UUID id, AddressDetails details, boolean isDefault) {
        OffsetDateTime now = now();
        jdbc.sql("""
                        INSERT INTO customer.addresses (id, customer_id, recipient_name, phone, line1, line2, landmark,
                                                        city, state_code, pin_code, is_default, created_at, updated_at)
                        VALUES (:id, :customerId, :recipientName, :phone, :line1, :line2, :landmark,
                                :city, :stateCode, :pinCode, :isDefault, :now, :now)
                        """)
                .param("id", id)
                .param("customerId", customerId)
                .param("recipientName", details.recipientName())
                .param("phone", details.phone())
                .param("line1", details.line1())
                .param("line2", details.line2())
                .param("landmark", details.landmark())
                .param("city", details.city())
                .param("stateCode", details.state().code())
                .param("pinCode", details.pinCode())
                .param("isDefault", isDefault)
                .param("now", now)
                .update();
    }

    void updateAddress(UUID customerId, UUID id, AddressDetails details) {
        jdbc.sql("""
                        UPDATE customer.addresses
                        SET recipient_name = :recipientName, phone = :phone, line1 = :line1, line2 = :line2,
                            landmark = :landmark, city = :city, state_code = :stateCode, pin_code = :pinCode,
                            updated_at = :now
                        WHERE customer_id = :customerId AND id = :id
                        """)
                .param("recipientName", details.recipientName())
                .param("phone", details.phone())
                .param("line1", details.line1())
                .param("line2", details.line2())
                .param("landmark", details.landmark())
                .param("city", details.city())
                .param("stateCode", details.state().code())
                .param("pinCode", details.pinCode())
                .param("now", now())
                .param("customerId", customerId)
                .param("id", id)
                .update();
    }

    void deleteAddress(UUID customerId, UUID id) {
        jdbc.sql("DELETE FROM customer.addresses WHERE customer_id = :customerId AND id = :id")
                .param("customerId", customerId)
                .param("id", id)
                .update();
    }

    /** Clears the current default first: the partial unique index allows only one. */
    void makeDefault(UUID customerId, UUID id) {
        OffsetDateTime now = now();
        jdbc.sql("""
                        UPDATE customer.addresses SET is_default = false, updated_at = :now
                        WHERE customer_id = :customerId AND is_default AND id <> :id
                        """)
                .param("now", now)
                .param("customerId", customerId)
                .param("id", id)
                .update();
        jdbc.sql("""
                        UPDATE customer.addresses SET is_default = true, updated_at = :now
                        WHERE customer_id = :customerId AND id = :id
                        """)
                .param("now", now)
                .param("customerId", customerId)
                .param("id", id)
                .update();
    }

    Optional<UUID> newestAddress(UUID customerId) {
        return jdbc.sql("""
                        SELECT id FROM customer.addresses WHERE customer_id = :customerId
                        ORDER BY created_at DESC, id DESC LIMIT 1
                        """)
                .param("customerId", customerId)
                .query(UUID.class)
                .optional();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static Customer customer(ResultSet row, int rowNumber) throws SQLException {
        return new Customer(row.getObject("id", UUID.class), row.getString("subject"), row.getString("email"),
                row.getString("name"), row.getString("phone"));
    }

    private static Address address(ResultSet row, int rowNumber) throws SQLException {
        return new Address(
                row.getObject("id", UUID.class),
                row.getString("recipient_name"),
                row.getString("phone"),
                row.getString("line1"),
                row.getString("line2"),
                row.getString("landmark"),
                row.getString("city"),
                IndianState.fromCode(row.getString("state_code")).orElseThrow(),
                row.getString("pin_code"),
                row.getBoolean("is_default"));
    }
}
