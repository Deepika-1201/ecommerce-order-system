package com.ecommerce.pricing.domain;

import com.ecommerce.pricing.Quote;
import com.ecommerce.pricing.TaxRegime;
import com.ecommerce.shared.IndianState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** SQL for quotes, which are written once and read only by their owner (LLD §4.5). */
@Repository
class QuoteRepository {

    private static final TypeReference<List<Map<String, String>>> OPTION_VALUES = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    QuoteRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void insert(Quote quote) {
        Quote.Totals totals = quote.totals();
        Quote.Shipping shipping = quote.shipping();
        jdbc.sql("""
                        INSERT INTO pricing.quotes (id, cart_id, customer_id, coupon_id, coupon_code,
                            supply_state_code, delivery_state_code, tax_regime, gross_paise, discount_paise,
                            goods_paise, shipping_paise, shipping_gst_rate_bps, shipping_cgst_paise,
                            shipping_sgst_paise, shipping_igst_paise, taxable_value_paise, cgst_paise, sgst_paise,
                            igst_paise, grand_total_paise, valid_until, created_at)
                        VALUES (:id, :cartId, :customerId, :couponId, :couponCode, :supplyState, :deliveryState,
                            :taxRegime, :gross, :discount, :goods, :shipping, :shippingRate, :shippingCgst,
                            :shippingSgst, :shippingIgst, :taxable, :cgst, :sgst, :igst, :grandTotal, :validUntil,
                            :createdAt)
                        """)
                .param("id", quote.id())
                .param("cartId", quote.cartId())
                .param("customerId", quote.customerId())
                .param("couponId", quote.couponId())
                .param("couponCode", quote.couponCode())
                .param("supplyState", quote.supplyState().code())
                .param("deliveryState", quote.deliveryState().code())
                .param("taxRegime", quote.taxRegime().name())
                .param("gross", totals.grossPaise())
                .param("discount", totals.discountPaise())
                .param("goods", totals.goodsPaise())
                .param("shipping", totals.shippingPaise())
                .param("shippingRate", shipping.gstRateBps())
                .param("shippingCgst", shipping.cgstPaise())
                .param("shippingSgst", shipping.sgstPaise())
                .param("shippingIgst", shipping.igstPaise())
                .param("taxable", totals.taxableValuePaise())
                .param("cgst", totals.cgstPaise())
                .param("sgst", totals.sgstPaise())
                .param("igst", totals.igstPaise())
                .param("grandTotal", totals.grandTotalPaise())
                .param("validUntil", utc(quote.validUntil()))
                .param("createdAt", utc(quote.createdAt()))
                .update();
        for (int i = 0; i < quote.lines().size(); i++) {
            Quote.Line line = quote.lines().get(i);
            jdbc.sql("""
                            INSERT INTO pricing.quote_lines (quote_id, line_no, sku, product_id, variant_id, title,
                                option_values, image_key, quantity, unit_price_paise, previous_unit_price_paise,
                                gross_paise, discount_paise, amount_paise, taxable_value_paise, gst_rate_bps,
                                cgst_paise, sgst_paise, igst_paise)
                            VALUES (:quoteId, :lineNo, :sku, :productId, :variantId, :title,
                                CAST(:optionValues AS jsonb), :imageKey, :quantity, :unitPrice, :previousUnitPrice,
                                :gross, :discount, :amount, :taxable, :rate, :cgst, :sgst, :igst)
                            """)
                    .param("quoteId", quote.id())
                    .param("lineNo", i + 1)
                    .param("sku", line.sku())
                    .param("productId", line.productId())
                    .param("variantId", line.variantId())
                    .param("title", line.title())
                    .param("optionValues", json.writeValueAsString(line.optionValues().entrySet().stream()
                            .map(entry -> Map.of("name", entry.getKey(), "value", entry.getValue()))
                            .toList()))
                    .param("imageKey", line.imageKey())
                    .param("quantity", line.quantity())
                    .param("unitPrice", line.unitPricePaise())
                    .param("previousUnitPrice", line.previousUnitPricePaise())
                    .param("gross", line.grossPaise())
                    .param("discount", line.discountPaise())
                    .param("amount", line.amountPaise())
                    .param("taxable", line.taxableValuePaise())
                    .param("rate", line.gstRateBps())
                    .param("cgst", line.cgstPaise())
                    .param("sgst", line.sgstPaise())
                    .param("igst", line.igstPaise())
                    .update();
        }
    }

    Optional<Quote> findForCustomer(UUID quoteId, UUID customerId) {
        return find("WHERE id = :id AND customer_id = :owner", quoteId, customerId);
    }

    Optional<Quote> findForGuestCart(UUID quoteId, UUID cartId) {
        return find("WHERE id = :id AND cart_id = :owner AND customer_id IS NULL", quoteId, cartId);
    }

    /** Deletes up to {@code limit} quotes that expired before {@code cutoff}; their lines go with them. */
    int deleteExpiredBefore(Instant cutoff, int limit) {
        return jdbc.sql("""
                        DELETE FROM pricing.quotes WHERE id IN (
                            SELECT id FROM pricing.quotes WHERE valid_until < :cutoff LIMIT :limit)
                        """)
                .param("cutoff", utc(cutoff))
                .param("limit", limit)
                .update();
    }

    private Optional<Quote> find(String condition, UUID quoteId, UUID owner) {
        return jdbc.sql("SELECT * FROM pricing.quotes " + condition)
                .param("id", quoteId)
                .param("owner", owner)
                .query((row, rowNumber) -> quote(row, lines(quoteId)))
                .optional();
    }

    private List<Quote.Line> lines(UUID quoteId) {
        return jdbc.sql("SELECT * FROM pricing.quote_lines WHERE quote_id = :quoteId ORDER BY line_no")
                .param("quoteId", quoteId)
                .query((row, rowNumber) -> new Quote.Line(
                        row.getString("sku"),
                        row.getObject("product_id", UUID.class),
                        row.getObject("variant_id", UUID.class),
                        row.getString("title"),
                        optionValues(row.getString("option_values")),
                        row.getString("image_key"),
                        row.getInt("quantity"),
                        row.getLong("unit_price_paise"),
                        row.getObject("previous_unit_price_paise", Long.class),
                        row.getLong("gross_paise"),
                        row.getLong("discount_paise"),
                        row.getLong("amount_paise"),
                        row.getLong("taxable_value_paise"),
                        row.getInt("gst_rate_bps"),
                        row.getLong("cgst_paise"),
                        row.getLong("sgst_paise"),
                        row.getLong("igst_paise")))
                .list();
    }

    private Map<String, String> optionValues(String stored) {
        Map<String, String> values = new LinkedHashMap<>();
        json.readValue(stored, OPTION_VALUES).forEach(pair -> values.put(pair.get("name"), pair.get("value")));
        return values;
    }

    private static Quote quote(ResultSet row, List<Quote.Line> lines) throws SQLException {
        return new Quote(
                row.getObject("id", UUID.class),
                row.getObject("cart_id", UUID.class),
                row.getObject("customer_id", UUID.class),
                row.getObject("coupon_id", UUID.class),
                row.getString("coupon_code"),
                IndianState.fromCode(row.getString("supply_state_code")).orElseThrow(),
                IndianState.fromCode(row.getString("delivery_state_code")).orElseThrow(),
                TaxRegime.valueOf(row.getString("tax_regime")),
                lines,
                new Quote.Shipping(row.getLong("shipping_paise"),
                        row.getLong("shipping_paise") - row.getLong("shipping_cgst_paise")
                                - row.getLong("shipping_sgst_paise") - row.getLong("shipping_igst_paise"),
                        row.getInt("shipping_gst_rate_bps"), row.getLong("shipping_cgst_paise"),
                        row.getLong("shipping_sgst_paise"), row.getLong("shipping_igst_paise")),
                new Quote.Totals(row.getLong("gross_paise"), row.getLong("discount_paise"), row.getLong("goods_paise"),
                        row.getLong("shipping_paise"), row.getLong("taxable_value_paise"), row.getLong("cgst_paise"),
                        row.getLong("sgst_paise"), row.getLong("igst_paise"), row.getLong("grand_total_paise")),
                row.getObject("valid_until", OffsetDateTime.class).toInstant(),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
