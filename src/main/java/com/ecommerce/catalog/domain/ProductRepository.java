package com.ecommerce.catalog.domain;

import com.ecommerce.catalog.GstCategory;
import com.ecommerce.platform.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** SQL for the product aggregate (products, variants, images) and product lists. */
@Repository
class ProductRepository {

    private static final TypeReference<List<ProductOption>> OPTIONS = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> OPTION_VALUES = new TypeReference<>() { };

    private static final String PRODUCT_COLUMNS = """
            p.id, p.title, p.description, p.gst_category, p.options, p.status, p.version, p.created_at, p.updated_at,
            c.id AS category_id, c.parent_id AS category_parent_id, c.name AS category_name, c.slug AS category_slug
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    ProductRepository(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    Optional<Product> find(UUID id) {
        return load(id, false);
    }

    /** Locks the product row for the rest of the transaction: admin changes to one product run one at a time. */
    Optional<Product> lock(UUID id) {
        return load(id, true);
    }

    void insert(UUID id, UUID categoryId, String title, String description, GstCategory gstCategory,
            List<ProductOption> options) {
        OffsetDateTime now = now();
        jdbc.sql("""
                        INSERT INTO catalog.products (id, category_id, title, description, gst_category, options, status,
                                                      version, created_at, updated_at)
                        VALUES (:id, :categoryId, :title, :description, :gstCategory, CAST(:options AS jsonb), 'DRAFT',
                                1, :now, :now)
                        """)
                .param("id", id)
                .param("categoryId", categoryId)
                .param("title", title)
                .param("description", description)
                .param("gstCategory", gstCategory.name())
                .param("options", json.writeValueAsString(options))
                .param("now", now)
                .update();
    }

    /** Writes the product's own fields and increments its version. */
    void update(UUID id, UUID categoryId, String title, String description, GstCategory gstCategory,
            List<ProductOption> options, ProductStatus status) {
        jdbc.sql("""
                        UPDATE catalog.products
                        SET category_id = :categoryId, title = :title, description = :description,
                            gst_category = :gstCategory, options = CAST(:options AS jsonb), status = :status,
                            version = version + 1, updated_at = :now
                        WHERE id = :id
                        """)
                .param("categoryId", categoryId)
                .param("title", title)
                .param("description", description)
                .param("gstCategory", gstCategory.name())
                .param("options", json.writeValueAsString(options))
                .param("status", status.name())
                .param("now", now())
                .param("id", id)
                .update();
    }

    /** Increments the version after a change to the product's variants or images. */
    void touch(UUID id) {
        jdbc.sql("UPDATE catalog.products SET version = version + 1, updated_at = :now WHERE id = :id")
                .param("now", now())
                .param("id", id)
                .update();
    }

    void insertVariant(UUID productId, Variant variant, String combinationKey) {
        OffsetDateTime now = now();
        try {
            jdbc.sql("""
                            INSERT INTO catalog.variants (id, product_id, sku, option_values, combination_key, price_paise,
                                                          status, created_at, updated_at)
                            VALUES (:id, :productId, :sku, CAST(:optionValues AS jsonb), :combinationKey, :pricePaise,
                                    :status, :now, :now)
                            """)
                    .param("id", variant.id())
                    .param("productId", productId)
                    .param("sku", variant.sku())
                    .param("optionValues", json.writeValueAsString(variant.optionValues()))
                    .param("combinationKey", combinationKey)
                    .param("pricePaise", variant.pricePaise())
                    .param("status", variant.status().name())
                    .param("now", now)
                    .update();
        } catch (DuplicateKeyException e) {
            throw switch (constraint(e)) {
                case "variants_sku_unique" -> new ApiException(HttpStatus.CONFLICT, "sku_taken",
                        "Another variant already has SKU " + variant.sku() + ".");
                case "variants_combination_unique" -> new ApiException(HttpStatus.CONFLICT, "variant_exists",
                        "The product already has a variant with these option values.");
                default -> e;
            };
        }
    }

    void updateVariant(UUID productId, UUID variantId, long pricePaise, VariantStatus status) {
        jdbc.sql("""
                        UPDATE catalog.variants SET price_paise = :pricePaise, status = :status, updated_at = :now
                        WHERE product_id = :productId AND id = :id
                        """)
                .param("pricePaise", pricePaise)
                .param("status", status.name())
                .param("now", now())
                .param("productId", productId)
                .param("id", variantId)
                .update();
    }

    void insertImage(UUID productId, ProductImage image) {
        jdbc.sql("""
                        INSERT INTO catalog.product_images (id, product_id, object_key, content_type, size_bytes, alt_text,
                                                            status, created_at)
                        VALUES (:id, :productId, :objectKey, :contentType, :sizeBytes, :altText, 'PENDING', :now)
                        """)
                .param("id", image.id())
                .param("productId", productId)
                .param("objectKey", image.objectKey())
                .param("contentType", image.contentType())
                .param("sizeBytes", image.sizeBytes())
                .param("altText", image.altText())
                .param("now", now())
                .update();
    }

    /** Appends the image to the gallery. */
    void markImageReady(UUID productId, UUID imageId) {
        jdbc.sql("""
                        UPDATE catalog.product_images
                        SET status = 'READY', completed_at = :now,
                            position = (SELECT coalesce(max(position), 0) + 1 FROM catalog.product_images
                                        WHERE product_id = :productId)
                        WHERE product_id = :productId AND id = :id
                        """)
                .param("now", now())
                .param("productId", productId)
                .param("id", imageId)
                .update();
    }

    void deleteImage(UUID productId, UUID imageId) {
        jdbc.sql("DELETE FROM catalog.product_images WHERE product_id = :productId AND id = :id")
                .param("productId", productId)
                .param("id", imageId)
                .update();
    }

    /** Uploads still pending after the cutoff, oldest first, with their products. */
    List<PendingImage> pendingImagesBefore(OffsetDateTime cutoff, int limit) {
        return jdbc.sql("""
                        SELECT product_id, id, object_key FROM catalog.product_images
                        WHERE status = 'PENDING' AND created_at < :cutoff
                        ORDER BY created_at LIMIT :limit
                        """)
                .param("cutoff", cutoff)
                .param("limit", limit)
                .query((row, rowNumber) -> new PendingImage(row.getObject("product_id", UUID.class),
                        row.getObject("id", UUID.class), row.getString("object_key")))
                .list();
    }

    /**
     * A page of products, newest first, or by relevance when there is a search text. One more row than the limit is
     * read, to know whether another page follows.
     */
    List<ProductSummary> list(Collection<ProductStatus> statuses, Collection<UUID> categoryIds, String text,
            PageCursor cursor, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.title, p.status,
                       c.id AS category_id, c.parent_id AS category_parent_id, c.name AS category_name,
                       c.slug AS category_slug,
                       (SELECT min(v.price_paise) FROM catalog.variants v
                        WHERE v.product_id = p.id AND v.status = 'ACTIVE') AS min_price_paise,
                       (SELECT i.object_key FROM catalog.product_images i
                        WHERE i.product_id = p.id AND i.status = 'READY' ORDER BY i.position, i.id LIMIT 1)
                           AS first_image_key
                FROM catalog.products p
                JOIN catalog.categories c ON c.id = p.category_id
                """);
        if (text != null) {
            sql.append(" CROSS JOIN websearch_to_tsquery('english', :text) AS query");
        }
        sql.append(" WHERE p.status = ANY(:statuses)");
        if (categoryIds != null) {
            sql.append(" AND p.category_id = ANY(CAST(:categoryIds AS uuid[]))");
        }
        if (text != null) {
            sql.append(" AND p.search_vector @@ query");
        }
        if (cursor instanceof PageCursor.AfterId) {
            sql.append(" AND p.id < :after");
        }
        sql.append(text != null ? " ORDER BY ts_rank(p.search_vector, query) DESC, p.id DESC" : " ORDER BY p.id DESC");
        sql.append(" LIMIT :limit OFFSET :offset");

        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString())
                .param("statuses", statuses.stream().map(Enum::name).toArray(String[]::new))
                .param("limit", limit + 1)
                .param("offset", cursor instanceof PageCursor.Offset offset ? offset.offset() : 0);
        if (categoryIds != null) {
            statement = statement.param("categoryIds", categoryIds.stream().map(UUID::toString).toArray(String[]::new));
        }
        if (text != null) {
            statement = statement.param("text", text);
        }
        if (cursor instanceof PageCursor.AfterId after) {
            statement = statement.param("after", after.id());
        }
        return statement.query((row, rowNumber) -> new ProductSummary(
                        row.getObject("id", UUID.class),
                        row.getString("title"),
                        category(row),
                        ProductStatus.valueOf(row.getString("status")),
                        row.getObject("min_price_paise", Long.class),
                        row.getString("first_image_key")))
                .list();
    }

    private Optional<Product> load(UUID id, boolean lock) {
        Optional<Product> header = jdbc.sql("SELECT " + PRODUCT_COLUMNS
                        + " FROM catalog.products p JOIN catalog.categories c ON c.id = p.category_id WHERE p.id = :id"
                        + (lock ? " FOR UPDATE OF p" : ""))
                .param("id", id)
                .query(this::product)
                .optional();
        return header.map(product -> new Product(product.id(), product.category(), product.title(),
                product.description(), product.gstCategory(), product.options(), product.status(), product.version(),
                product.createdAt(), product.updatedAt(), variants(id, product.options()), images(id)));
    }

    private List<Variant> variants(UUID productId, List<ProductOption> options) {
        return jdbc.sql("""
                        SELECT id, sku, option_values, price_paise, status FROM catalog.variants
                        WHERE product_id = :productId ORDER BY created_at, id
                        """)
                .param("productId", productId)
                .query((row, rowNumber) -> new Variant(
                        row.getObject("id", UUID.class),
                        row.getString("sku"),
                        inDimensionOrder(options, json.readValue(row.getString("option_values"), OPTION_VALUES)),
                        row.getLong("price_paise"),
                        VariantStatus.valueOf(row.getString("status"))))
                .list();
    }

    private List<ProductImage> images(UUID productId) {
        return jdbc.sql("""
                        SELECT id, object_key, content_type, size_bytes, alt_text, status, position
                        FROM catalog.product_images WHERE product_id = :productId
                        ORDER BY position NULLS LAST, created_at, id
                        """)
                .param("productId", productId)
                .query((row, rowNumber) -> new ProductImage(
                        row.getObject("id", UUID.class),
                        row.getString("object_key"),
                        row.getString("content_type"),
                        row.getLong("size_bytes"),
                        row.getString("alt_text"),
                        ImageStatus.valueOf(row.getString("status")),
                        row.getObject("position", Integer.class)))
                .list();
    }

    private Product product(ResultSet row, int rowNumber) throws SQLException {
        return new Product(
                row.getObject("id", UUID.class),
                category(row),
                row.getString("title"),
                row.getString("description"),
                GstCategory.valueOf(row.getString("gst_category")),
                json.readValue(row.getString("options"), OPTIONS),
                ProductStatus.valueOf(row.getString("status")),
                row.getLong("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant(),
                List.of(),
                List.of());
    }

    private static Category category(ResultSet row) throws SQLException {
        return new Category(row.getObject("category_id", UUID.class), row.getObject("category_parent_id", UUID.class),
                row.getString("category_name"), row.getString("category_slug"));
    }

    /** jsonb does not keep key order; the product's dimension order is the one shown. */
    static Map<String, String> inDimensionOrder(List<ProductOption> options, Map<String, String> values) {
        Map<String, String> ordered = new LinkedHashMap<>();
        options.forEach(option -> {
            if (values.containsKey(option.name())) {
                ordered.put(option.name(), values.get(option.name()));
            }
        });
        return ordered;
    }

    private static String constraint(DuplicateKeyException e) {
        if (e.getMostSpecificCause() instanceof PSQLException sql
                && sql.getServerErrorMessage() instanceof ServerErrorMessage message
                && message.getConstraint() != null) {
            return message.getConstraint();
        }
        return "";
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    record PendingImage(UUID productId, UUID imageId, String objectKey) {
    }
}
