package com.ecommerce.pricing.domain;

import com.ecommerce.platform.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for coupons and their redemptions. Usage counters change only through conditional updates (LLD §4.10). */
@Repository
class CouponRepository {

    private static final String COLUMNS = """
            id, code, kind, percent_bps, max_discount_paise, amount_paise, min_order_paise, valid_from, valid_until,
            total_limit, per_customer_limit, active, reserved, redeemed, version, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    CouponRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(Coupon coupon) {
        try {
            jdbc.sql("""
                            INSERT INTO pricing.coupons (id, code, kind, percent_bps, max_discount_paise, amount_paise,
                                                         min_order_paise, valid_from, valid_until, total_limit,
                                                         per_customer_limit, active, version, created_at, updated_at)
                            VALUES (:id, :code, :kind, :percentBps, :maxDiscountPaise, :amountPaise, :minOrderPaise,
                                    :validFrom, :validUntil, :totalLimit, :perCustomerLimit, :active, 1, :now, :now)
                            """)
                    .param("id", coupon.id())
                    .param("code", coupon.code())
                    .param("kind", kind(coupon.rule()))
                    .param("percentBps", coupon.rule() instanceof DiscountRule.Percent percent
                            ? percent.basisPoints() : null)
                    .param("maxDiscountPaise", coupon.rule() instanceof DiscountRule.Percent percent
                            ? percent.capPaise() : null)
                    .param("amountPaise", coupon.rule() instanceof DiscountRule.Flat flat ? flat.amountPaise() : null)
                    .param("minOrderPaise", coupon.minOrderPaise())
                    .param("validFrom", utc(coupon.validFrom()))
                    .param("validUntil", utc(coupon.validUntil()))
                    .param("totalLimit", coupon.totalLimit())
                    .param("perCustomerLimit", coupon.perCustomerLimit())
                    .param("active", coupon.active())
                    .param("now", utc(coupon.createdAt()))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "coupon_code_taken", "Another coupon has this code.");
        }
    }

    Optional<Coupon> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM pricing.coupons WHERE id = :id")
                .param("id", id)
                .query(CouponRepository::coupon)
                .optional();
    }

    Optional<Coupon> lock(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM pricing.coupons WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(CouponRepository::coupon)
                .optional();
    }

    Optional<Coupon> findByCode(String code) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM pricing.coupons WHERE code = :code")
                .param("code", code)
                .query(CouponRepository::coupon)
                .optional();
    }

    /** Newest first, after the given id; one more than {@code limit}, to know whether a page follows. */
    List<Coupon> list(UUID after, int limit) {
        JdbcClient.StatementSpec statement = jdbc.sql("SELECT " + COLUMNS + " FROM pricing.coupons"
                        + (after != null ? " WHERE id < :after" : "") + " ORDER BY id DESC LIMIT :limit")
                .param("limit", limit + 1);
        if (after != null) {
            statement = statement.param("after", after);
        }
        return statement.query(CouponRepository::coupon).list();
    }

    void update(UUID id, boolean active, Instant validUntil, Integer totalLimit, Instant now) {
        jdbc.sql("""
                        UPDATE pricing.coupons
                        SET active = :active, valid_until = :validUntil, total_limit = :totalLimit,
                            version = version + 1, updated_at = :now
                        WHERE id = :id
                        """)
                .param("active", active)
                .param("validUntil", utc(validUntil))
                .param("totalLimit", totalLimit)
                .param("now", utc(now))
                .param("id", id)
                .update();
    }

    /** Takes one use if the coupon is active, in its window and under its limit; locks the row until commit. */
    boolean takeUse(UUID couponId, Instant now) {
        return jdbc.sql("""
                        UPDATE pricing.coupons
                        SET reserved = reserved + 1, updated_at = :now
                        WHERE id = :id AND active AND valid_from <= :now AND (valid_until IS NULL OR valid_until > :now)
                          AND (total_limit IS NULL OR reserved + redeemed < total_limit)
                        """)
                .param("now", utc(now))
                .param("id", couponId)
                .update() == 1;
    }

    void giveBackReserved(UUID couponId, Instant now) {
        adjust(couponId, "reserved = reserved - 1", now);
    }

    void moveReservedToRedeemed(UUID couponId, Instant now) {
        adjust(couponId, "reserved = reserved - 1, redeemed = redeemed + 1", now);
    }

    void giveBackRedeemed(UUID couponId, Instant now) {
        adjust(couponId, "redeemed = redeemed - 1", now);
    }

    /** Held and committed uses by one customer. */
    int usesBy(UUID couponId, UUID customerId) {
        return jdbc.sql("""
                        SELECT count(*) FROM pricing.coupon_redemptions
                        WHERE coupon_id = :couponId AND customer_id = :customerId AND status IN ('HELD', 'COMMITTED')
                        """)
                .param("couponId", couponId)
                .param("customerId", customerId)
                .query(Integer.class)
                .single();
    }

    Optional<String> redemptionStatus(UUID orderId) {
        return jdbc.sql("SELECT status FROM pricing.coupon_redemptions WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .optional();
    }

    void insertRedemption(UUID id, UUID couponId, UUID orderId, UUID customerId, Instant now) {
        jdbc.sql("""
                        INSERT INTO pricing.coupon_redemptions (id, coupon_id, order_id, customer_id, status,
                                                                created_at, updated_at)
                        VALUES (:id, :couponId, :orderId, :customerId, 'HELD', :now, :now)
                        """)
                .param("id", id)
                .param("couponId", couponId)
                .param("orderId", orderId)
                .param("customerId", customerId)
                .param("now", utc(now))
                .update();
    }

    /** Moves the order's redemption from one of {@code from} to {@code to}; returns its coupon and old status. */
    Optional<Transition> transition(UUID orderId, List<String> from, String to, Instant now) {
        return jdbc.sql("""
                        WITH old AS (
                            SELECT id, coupon_id, status FROM pricing.coupon_redemptions
                            WHERE order_id = :orderId AND status = ANY(:from)
                            FOR UPDATE)
                        UPDATE pricing.coupon_redemptions r SET status = :to, updated_at = :now
                        FROM old WHERE r.id = old.id
                        RETURNING old.coupon_id, old.status
                        """)
                .param("orderId", orderId)
                .param("from", from.toArray(String[]::new))
                .param("to", to)
                .param("now", utc(now))
                .query((row, rowNumber) -> new Transition(row.getObject("coupon_id", UUID.class),
                        row.getString("status")))
                .optional();
    }

    record Transition(UUID couponId, String previousStatus) {
    }

    private void adjust(UUID couponId, String change, Instant now) {
        jdbc.sql("UPDATE pricing.coupons SET " + change + ", updated_at = :now WHERE id = :id")
                .param("now", utc(now))
                .param("id", couponId)
                .update();
    }

    private static String kind(DiscountRule rule) {
        return switch (rule) {
            case DiscountRule.Percent percent -> CouponCommands.Kind.PERCENT.name();
            case DiscountRule.Flat flat -> CouponCommands.Kind.FLAT.name();
        };
    }

    private static Coupon coupon(ResultSet row, int rowNumber) throws SQLException {
        DiscountRule rule = CouponCommands.Kind.PERCENT.name().equals(row.getString("kind"))
                ? new DiscountRule.Percent(row.getInt("percent_bps"), row.getObject("max_discount_paise", Long.class))
                : new DiscountRule.Flat(row.getLong("amount_paise"));
        return new Coupon(
                row.getObject("id", UUID.class),
                row.getString("code"),
                rule,
                row.getLong("min_order_paise"),
                instant(row, "valid_from"),
                instant(row, "valid_until"),
                row.getObject("total_limit", Integer.class),
                row.getObject("per_customer_limit", Integer.class),
                row.getBoolean("active"),
                row.getInt("reserved"),
                row.getInt("redeemed"),
                row.getLong("version"),
                instant(row, "created_at"),
                instant(row, "updated_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
