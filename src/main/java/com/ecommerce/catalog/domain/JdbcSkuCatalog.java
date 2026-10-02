package com.ecommerce.catalog.domain;

import com.ecommerce.catalog.GstCategory;
import com.ecommerce.catalog.SkuCatalog;
import com.ecommerce.catalog.SkuDetails;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
class JdbcSkuCatalog implements SkuCatalog {

    private static final TypeReference<List<ProductOption>> OPTIONS = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> OPTION_VALUES = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcSkuCatalog(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Map<String, SkuDetails> find(Collection<String> skus) {
        if (skus.isEmpty()) {
            return Map.of();
        }
        String[] codes = skus.stream().map(sku -> sku.toUpperCase(Locale.ROOT)).distinct().toArray(String[]::new);
        return jdbc.sql("""
                        SELECT v.sku, v.id AS variant_id, v.option_values, v.price_paise, v.status AS variant_status,
                               p.id AS product_id, p.title, p.options, p.gst_category, p.status AS product_status,
                               (SELECT i.object_key FROM catalog.product_images i
                                WHERE i.product_id = p.id AND i.status = 'READY' ORDER BY i.position, i.id LIMIT 1)
                                   AS image_key
                        FROM catalog.variants v
                        JOIN catalog.products p ON p.id = v.product_id
                        WHERE v.sku = ANY(:skus)
                        """)
                .param("skus", codes)
                .query((row, rowNumber) -> new SkuDetails(
                        row.getString("sku"),
                        row.getObject("product_id", UUID.class),
                        row.getObject("variant_id", UUID.class),
                        row.getString("title"),
                        ProductRepository.inDimensionOrder(json.readValue(row.getString("options"), OPTIONS),
                                json.readValue(row.getString("option_values"), OPTION_VALUES)),
                        row.getString("image_key"),
                        row.getLong("price_paise"),
                        GstCategory.valueOf(row.getString("gst_category")),
                        ProductStatus.ACTIVE.name().equals(row.getString("product_status"))
                                && VariantStatus.ACTIVE.name().equals(row.getString("variant_status"))))
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableMap(SkuDetails::sku, details -> details));
    }
}
