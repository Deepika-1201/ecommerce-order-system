package com.ecommerce.cart;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.platform.tasks.DueTasks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** The hourly tasks that delete expired carts and old quotes (LLD §4.3, §4.5). */
class CartExpiryTests extends CartTest {

    private static final String KARNATAKA = "{\"delivery_state_code\": \"29\"}";

    @Autowired
    private ApplicationContext context;

    @Test
    void expiredCartsAreDeletedWithTheirLines() {
        Item bottle = item("Bottle", "STANDARD", 59_900);
        me("PUT", "/lines/" + bottle.sku(), asha, quantity(1));
        String token = guestToken();
        guest("PUT", "/lines/" + bottle.sku(), token, quantity(1));
        jdbc.sql("UPDATE cart.carts SET expires_at = now() - interval '1 minute' WHERE customer_id IS NULL").update();

        DueTasks.runRecurring(context, "cart.expire-carts");

        assertCode(guest("GET", "", token, null), 404, "not_found");
        assertThat(ok(me("GET", "", asha, null)).get("lines")).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM cart.cart_lines").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void quotesAreDeletedADayAfterTheyExpire() {
        Item bottle = item("Bottle", "STANDARD", 59_900);
        me("PUT", "/lines/" + bottle.sku(), asha, quantity(1));
        String old = created(me("POST", "/quotes", asha, KARNATAKA)).get("id").asString();
        String recent = created(me("POST", "/quotes", asha, KARNATAKA)).get("id").asString();
        String expiredToday = created(me("POST", "/quotes", asha, KARNATAKA)).get("id").asString();
        jdbc.sql("UPDATE pricing.quotes SET valid_until = now() - interval '25 hours' WHERE id = ?::uuid")
                .param(old)
                .update();
        jdbc.sql("UPDATE pricing.quotes SET valid_until = now() - interval '23 hours' WHERE id = ?::uuid")
                .param(expiredToday)
                .update();

        DueTasks.runRecurring(context, "pricing.purge-quotes");

        assertCode(me("GET", "/quotes/" + old, asha, null), 404, "not_found");
        ok(me("GET", "/quotes/" + recent, asha, null));
        ok(me("GET", "/quotes/" + expiredToday, asha, null));
        assertThat(jdbc.sql("SELECT count(*) FROM pricing.quote_lines WHERE quote_id = ?::uuid")
                .param(old).query(Integer.class).single()).isZero();
    }
}
