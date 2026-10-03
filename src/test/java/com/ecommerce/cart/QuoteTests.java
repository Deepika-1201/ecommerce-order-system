package com.ecommerce.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.ecommerce.pricing.CouponRedemptions;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Quotes through the API, including the worked examples of LLD §4.7. */
class QuoteTests extends CartTest {

    private static final String KARNATAKA = "{\"delivery_state_code\": \"29\"}";
    private static final String MAHARASHTRA = "{\"delivery_state_code\": \"27\"}";

    @Autowired
    private CouponRedemptions redemptions;

    @Test
    void workedExampleOneThroughTheApi() {
        Item shirt = item("Oxford shirt", "APPAREL", 129_900);
        Item shoes = item("Running shoes", "FOOTWEAR", 349_900);
        Item bottle = item("Bottle", "STANDARD", 59_900);
        coupon("TENOFF", "\"kind\": \"PERCENT\", \"percent_bps\": 1000, \"max_discount_paise\": 50000");
        me("PUT", "/lines/" + shirt.sku(), asha, quantity(2));
        me("PUT", "/lines/" + shoes.sku(), asha, quantity(1));
        me("PUT", "/lines/" + bottle.sku(), asha, quantity(1));
        me("PUT", "/coupon", asha, "{\"code\": \"TENOFF\"}");

        HttpResponse<String> response = me("POST", "/quotes", asha, KARNATAKA);

        JsonNode quote = created(response);
        assertThat(response.headers().firstValue("Location"))
                .hasValue("/v1/me/cart/quotes/" + quote.get("id").asString());
        assertThat(quote.get("tax_regime").asString()).isEqualTo("INTRA_STATE");
        assertThat(quote.get("supply_state_code").asString()).isEqualTo("29");
        assertThat(quote.get("coupon_code").asString()).isEqualTo("TENOFF");
        assertThat(quote.get("currency").asString()).isEqualTo("INR");
        assertTotals(quote, 669_600, 50_000, 619_600, 0, 550_308, 34_646, 34_646, 0, 619_600);
        JsonNode first = quote.get("lines").get(0);
        assertThat(first.get("sku").asString()).isEqualTo(shirt.sku());
        assertThat(first.get("title").asString()).isEqualTo("Oxford shirt");
        assertThat(first.has("previous_unit_price_paise")).as("the price has not changed").isFalse();
        assertThat(first.get("discount_paise").asLong()).isEqualTo(19_400);
        assertThat(first.get("gst_rate_bps").asInt()).isEqualTo(500);
        assertThat(first.get("cgst_paise").asLong()).isEqualTo(5_724);
        assertThat(first.get("taxable_value_paise").asLong()).isEqualTo(228_952);
        assertThat(quote.get("lines").get(1).get("gst_rate_bps").asInt()).isEqualTo(1_800);
        assertThat(Instant.parse(quote.get("valid_until").asString()))
                .isCloseTo(Instant.now().plus(Duration.ofMinutes(10)), within(Duration.ofMinutes(1)));
        assertThat(ok(me("GET", "/quotes/" + quote.get("id").asString(), asha, null))).isEqualTo(quote);
    }

    @Test
    void workedExampleTwoForAGuestInAnotherState() {
        Item tea = item("Assam tea", "REDUCED", 34_900);
        String token = guestToken();
        guest("PUT", "/lines/" + tea.sku(), token, quantity(1));

        JsonNode quote = created(guest("POST", "/quotes", token, MAHARASHTRA));

        assertThat(quote.get("tax_regime").asString()).isEqualTo("INTER_STATE");
        assertTotals(quote, 34_900, 0, 34_900, 4_900, 37_905, 0, 0, 1_895, 39_800);
        assertThat(quote.get("shipping").get("gst_rate_bps").asInt()).isEqualTo(500);
        assertThat(quote.get("shipping").get("igst_paise").asLong()).isEqualTo(233);
        assertThat(quote.has("coupon_code")).isFalse();
        assertThat(ok(guest("GET", "/quotes/" + quote.get("id").asString(), token, null))).isEqualTo(quote);
    }

    @Test
    void anEmptyCartCannotBeQuoted() {
        Item bottle = item("Bottle", "STANDARD", 59_900);

        assertCode(me("POST", "/quotes", asha, KARNATAKA), 409, "cart_empty");
        me("PUT", "/lines/" + bottle.sku(), asha, quantity(1));
        me("DELETE", "/lines/" + bottle.sku(), asha, null);
        assertCode(me("POST", "/quotes", asha, KARNATAKA), 409, "cart_empty");
    }

    @Test
    void itemsThatCanNoLongerBeBoughtFailTheQuote() {
        Item shirt = item("Oxford shirt", "APPAREL", 129_900);
        Item bottle = item("Bottle", "STANDARD", 59_900);
        me("PUT", "/lines/" + shirt.sku(), asha, quantity(1));
        me("PUT", "/lines/" + bottle.sku(), asha, quantity(1));
        archive(shirt);

        HttpResponse<String> response = me("POST", "/quotes", asha, KARNATAKA);

        assertCode(response, 409, "cart_has_unavailable_items");
        assertThat(json(response).get("detail").asString()).contains(shirt.sku()).doesNotContain(bottle.sku());
    }

    @Test
    void aPriceChangeSinceAddingIsShownOnTheQuote() {
        Item shirt = item("Oxford shirt", "APPAREL", 129_900);
        me("PUT", "/lines/" + shirt.sku(), asha, quantity(1));
        reprice(shirt, 119_900);

        JsonNode line = created(me("POST", "/quotes", asha, KARNATAKA)).get("lines").get(0);

        assertThat(line.get("unit_price_paise").asLong()).isEqualTo(119_900);
        assertThat(line.get("previous_unit_price_paise").asLong()).isEqualTo(129_900);
    }

    @Test
    void couponProblemsSurfaceAtQuoteTime() {
        Item bottle = item("Bottle", "STANDARD", 59_900);
        me("PUT", "/lines/" + bottle.sku(), asha, quantity(1));
        coupon("BIG-ORDERS", "\"kind\": \"FLAT\", \"amount_paise\": 5000, \"min_order_paise\": 100000");
        UUID scarce = coupon("SCARCE", "\"kind\": \"FLAT\", \"amount_paise\": 5000, \"total_limit\": 1");
        UUID once = coupon("ONCE", "\"kind\": \"FLAT\", \"amount_paise\": 5000, \"per_customer_limit\": 1");
        UUID retired = coupon("RETIRED", "\"kind\": \"FLAT\", \"amount_paise\": 5000");

        me("PUT", "/coupon", asha, "{\"code\": \"BIG-ORDERS\"}");
        HttpResponse<String> minimum = me("POST", "/quotes", asha, KARNATAKA);
        me("PUT", "/coupon", asha, "{\"code\": \"SCARCE\"}");
        redemptions.reserve(UUID.randomUUID(), scarce, null);
        HttpResponse<String> exhausted = me("POST", "/quotes", asha, KARNATAKA);
        me("PUT", "/coupon", asha, "{\"code\": \"ONCE\"}");
        redemptions.reserve(UUID.randomUUID(), once, customerId(ashaSubject));
        HttpResponse<String> alreadyUsed = me("POST", "/quotes", asha, KARNATAKA);
        me("PUT", "/coupon", asha, "{\"code\": \"RETIRED\"}");
        call("PATCH", "/v1/admin/pricing/coupons/" + retired, admin, "{\"active\": false}");
        HttpResponse<String> switchedOff = me("POST", "/quotes", asha, KARNATAKA);

        assertCode(minimum, 422, "coupon_minimum_not_met");
        assertThat(json(minimum).get("detail").asString()).contains("₹1000");
        assertCode(exhausted, 409, "coupon_exhausted");
        assertCode(alreadyUsed, 409, "coupon_already_used");
        assertCode(switchedOff, 422, "coupon_not_found");
    }

    @Test
    void theDeliveryStateMustBeAGstStateCode() {
        Item bottle = item("Bottle", "STANDARD", 59_900);
        me("PUT", "/lines/" + bottle.sku(), asha, quantity(1));

        assertCode(me("POST", "/quotes", asha, "{\"delivery_state_code\": \"99\"}"), 400, "validation_failed");
        assertCode(me("POST", "/quotes", asha, "{}"), 400, "validation_failed");
    }

    private UUID customerId(String subject) {
        return jdbc.sql("SELECT id FROM customer.customers WHERE subject = ?")
                .param(subject)
                .query(UUID.class)
                .single();
    }

    private static void assertTotals(JsonNode quote, long gross, long discount, long goods, long shipping,
            long taxable, long cgst, long sgst, long igst, long grandTotal) {
        JsonNode totals = quote.get("totals");
        assertThat(totals.get("gross_paise").asLong()).as("gross").isEqualTo(gross);
        assertThat(totals.get("discount_paise").asLong()).as("discount").isEqualTo(discount);
        assertThat(totals.get("goods_paise").asLong()).as("goods").isEqualTo(goods);
        assertThat(totals.get("shipping_paise").asLong()).as("shipping").isEqualTo(shipping);
        assertThat(totals.get("taxable_value_paise").asLong()).as("taxable value").isEqualTo(taxable);
        assertThat(totals.get("cgst_paise").asLong()).as("CGST").isEqualTo(cgst);
        assertThat(totals.get("sgst_paise").asLong()).as("SGST").isEqualTo(sgst);
        assertThat(totals.get("igst_paise").asLong()).as("IGST").isEqualTo(igst);
        assertThat(totals.get("tax_paise").asLong()).as("tax").isEqualTo(cgst + sgst + igst);
        assertThat(totals.get("grand_total_paise").asLong()).as("grand total").isEqualTo(grandTotal);
    }
}
