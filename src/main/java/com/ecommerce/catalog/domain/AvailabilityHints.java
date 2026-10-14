package com.ecommerce.catalog.domain;

import com.ecommerce.platform.IntegrationEvent;
import com.ecommerce.platform.IntegrationInbox;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AvailabilityHints {

    static final String CONSUMER = "ecom-catalog-availability";

    private final JdbcClient jdbc;
    private final IntegrationInbox inbox;

    AvailabilityHints(JdbcClient jdbc, IntegrationInbox inbox) {
        this.jdbc = jdbc;
        this.inbox = inbox;
    }

    public enum Hint {
        UNKNOWN, IN_STOCK, SOLD_OUT
    }

    public void apply(IntegrationEvent event) {
        inbox.receive(CONSUMER, event.messageId(), () -> jdbc.sql("""
                        INSERT INTO catalog.availability_hints AS hint
                            (sku, location_code, available, sequence, occurred_at)
                        VALUES (:sku, :location, :available, :sequence, :at)
                        ON CONFLICT (sku, location_code) DO UPDATE
                        SET available = EXCLUDED.available, sequence = EXCLUDED.sequence, occurred_at = EXCLUDED.occurred_at
                        WHERE hint.sequence < EXCLUDED.sequence
                        """)
                .param("sku", event.data().path("sku").asString())
                .param("location", event.data().path("location_code").asString())
                .param("available", event.data().path("available").asLong())
                .param("sequence", event.sequence()).param("at", event.occurredAt().atOffset(ZoneOffset.UTC)).update());
    }

    public Map<String, Hint> forSkus(Collection<String> skus) {
        if (skus.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                        SELECT sku, bool_or(available > 0) AS in_stock FROM catalog.availability_hints
                        WHERE sku = ANY(:skus) GROUP BY sku
                        """)
                .param("skus", skus.toArray(String[]::new))
                .query((row, rowNumber) -> Map.entry(row.getString("sku"),
                        row.getBoolean("in_stock") ? Hint.IN_STOCK : Hint.SOLD_OUT))
                .list().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}