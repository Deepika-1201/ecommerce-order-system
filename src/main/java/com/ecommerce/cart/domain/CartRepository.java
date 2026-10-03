package com.ecommerce.cart.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for carts. Every change locks the cart row first, then increments its version (LLD §4.3). */
@Repository
class CartRepository {

    private static final String CART_COLUMNS = "id, customer_id, coupon_code, version, expires_at";

    private final JdbcClient jdbc;

    CartRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Cart> find(CartOwner owner) {
        return header(owner, false).map(this::withLines);
    }

    Optional<Cart> lock(CartOwner owner) {
        return header(owner, true).map(this::withLines);
    }

    Optional<Cart> findById(UUID id) {
        return jdbc.sql("SELECT " + CART_COLUMNS + " FROM cart.carts WHERE id = :id")
                .param("id", id)
                .query((row, rowNumber) -> header(row))
                .optional()
                .map(this::withLines);
    }

    /** The customer's cart, locked, created at version 0 if it does not exist; concurrent calls create one. */
    Cart lockOrCreate(CartOwner.Customer owner, UUID newId, Instant now, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO cart.carts (id, customer_id, version, created_at, updated_at, expires_at)
                        VALUES (:id, :customerId, 0, :now, :now, :expiresAt)
                        ON CONFLICT (customer_id) DO NOTHING
                        """)
                .param("id", newId)
                .param("customerId", owner.customerId())
                .param("now", utc(now))
                .param("expiresAt", utc(expiresAt))
                .update();
        return lock(owner).orElseThrow();
    }

    void insertGuest(UUID id, byte[] tokenHash, Instant now, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO cart.carts (id, guest_token_hash, version, created_at, updated_at, expires_at)
                        VALUES (:id, :tokenHash, 0, :now, :now, :expiresAt)
                        """)
                .param("id", id)
                .param("tokenHash", tokenHash)
                .param("now", utc(now))
                .param("expiresAt", utc(expiresAt))
                .update();
    }

    /** Adds the line, or sets its quantity; a line keeps the price and time it was first added with. */
    void setLine(UUID cartId, String sku, int quantity, long addedPricePaise, Instant now) {
        jdbc.sql("""
                        INSERT INTO cart.cart_lines (cart_id, sku, quantity, added_price_paise, added_at, updated_at)
                        VALUES (:cartId, :sku, :quantity, :addedPrice, :now, :now)
                        ON CONFLICT (cart_id, sku) DO UPDATE SET quantity = EXCLUDED.quantity, updated_at = :now
                        """)
                .param("cartId", cartId)
                .param("sku", sku)
                .param("quantity", quantity)
                .param("addedPrice", addedPricePaise)
                .param("now", utc(now))
                .update();
    }

    /** Replaces the line entirely, as a merge does with a guest's line. */
    void replaceLine(UUID cartId, Cart.Line line, Instant now) {
        jdbc.sql("""
                        INSERT INTO cart.cart_lines (cart_id, sku, quantity, added_price_paise, added_at, updated_at)
                        VALUES (:cartId, :sku, :quantity, :addedPrice, :addedAt, :now)
                        ON CONFLICT (cart_id, sku) DO UPDATE
                        SET quantity = EXCLUDED.quantity, added_price_paise = EXCLUDED.added_price_paise,
                            added_at = EXCLUDED.added_at, updated_at = :now
                        """)
                .param("cartId", cartId)
                .param("sku", line.sku())
                .param("quantity", line.quantity())
                .param("addedPrice", line.addedPricePaise())
                .param("addedAt", utc(line.addedAt()))
                .param("now", utc(now))
                .update();
    }

    boolean deleteLine(UUID cartId, String sku) {
        return jdbc.sql("DELETE FROM cart.cart_lines WHERE cart_id = :cartId AND sku = :sku")
                .param("cartId", cartId)
                .param("sku", sku)
                .update() == 1;
    }

    void setCoupon(UUID cartId, String couponCode) {
        jdbc.sql("UPDATE cart.carts SET coupon_code = :couponCode WHERE id = :id")
                .param("couponCode", couponCode)
                .param("id", cartId)
                .update();
    }

    /** Records a change: the next version, and a later expiry. */
    void changed(UUID cartId, Instant now, Instant expiresAt) {
        jdbc.sql("""
                        UPDATE cart.carts SET version = version + 1, updated_at = :now, expires_at = :expiresAt
                        WHERE id = :id
                        """)
                .param("now", utc(now))
                .param("expiresAt", utc(expiresAt))
                .param("id", cartId)
                .update();
    }

    /** Activity without a change, such as a quote. */
    void extendExpiry(UUID cartId, Instant expiresAt) {
        jdbc.sql("UPDATE cart.carts SET expires_at = greatest(expires_at, :expiresAt) WHERE id = :id")
                .param("expiresAt", utc(expiresAt))
                .param("id", cartId)
                .update();
    }

    void delete(UUID cartId) {
        jdbc.sql("DELETE FROM cart.carts WHERE id = :id").param("id", cartId).update();
    }

    int deleteExpired(Instant now, int limit) {
        return jdbc.sql("""
                        DELETE FROM cart.carts WHERE id IN (
                            SELECT id FROM cart.carts WHERE expires_at < :now LIMIT :limit)
                        """)
                .param("now", utc(now))
                .param("limit", limit)
                .update();
    }

    private Optional<Cart> header(CartOwner owner, boolean lock) {
        String condition = switch (owner) {
            case CartOwner.Customer customer -> "customer_id = :owner";
            case CartOwner.Guest guest -> "guest_token_hash = :owner";
        };
        Object ownerValue = switch (owner) {
            case CartOwner.Customer customer -> customer.customerId();
            case CartOwner.Guest guest -> guest.tokenHash();
        };
        return jdbc.sql("SELECT " + CART_COLUMNS + " FROM cart.carts WHERE " + condition + (lock ? " FOR UPDATE" : ""))
                .param("owner", ownerValue)
                .query((row, rowNumber) -> header(row))
                .optional();
    }

    private static Cart header(ResultSet row) throws SQLException {
        return new Cart(row.getObject("id", UUID.class), row.getObject("customer_id", UUID.class),
                row.getString("coupon_code"), row.getLong("version"),
                row.getObject("expires_at", OffsetDateTime.class).toInstant(), List.of());
    }

    private Cart withLines(Cart cart) {
        List<Cart.Line> lines = jdbc.sql("""
                        SELECT sku, quantity, added_price_paise, added_at FROM cart.cart_lines
                        WHERE cart_id = :cartId ORDER BY added_at, sku
                        """)
                .param("cartId", cart.id())
                .query((row, rowNumber) -> new Cart.Line(row.getString("sku"), row.getInt("quantity"),
                        row.getLong("added_price_paise"), row.getObject("added_at", OffsetDateTime.class).toInstant()))
                .list();
        return new Cart(cart.id(), cart.customerId(), cart.couponCode(), cart.version(), cart.expiresAt(), lines);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
