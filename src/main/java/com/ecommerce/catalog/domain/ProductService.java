package com.ecommerce.catalog.domain;

import com.ecommerce.catalog.GstCategory;
import com.ecommerce.catalog.domain.ProductCommands.NewProduct;
import com.ecommerce.catalog.domain.ProductCommands.NewVariant;
import com.ecommerce.catalog.domain.ProductCommands.ProductChanges;
import com.ecommerce.catalog.domain.ProductCommands.VariantChanges;
import com.ecommerce.platform.ApiException;
import com.ecommerce.platform.AuditEntry;
import com.ecommerce.platform.AuditLog;
import com.ecommerce.platform.Caller;
import com.ecommerce.shared.Ids;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Products with their variants (LLD §3.5–3.7). Every change locks the product row, checks the invariants, writes,
 * and increments the version; every admin change is audited.
 */
@Service
public class ProductService {

    static final int MAX_VARIANTS = 100;
    static final int MAX_SEARCH_RESULTS = 1_000;

    private final ProductRepository products;
    private final CategoryService categories;
    private final AuditLog audit;

    ProductService(ProductRepository products, CategoryService categories, AuditLog audit) {
        this.products = products;
        this.categories = categories;
        this.audit = audit;
    }

    @Transactional
    public Product create(Caller admin, NewProduct request) {
        requireCategory(request.categoryId());
        List<ProductOption> options = ProductOptions.normalize(request.options());
        UUID id = Ids.newId();
        products.insert(id, request.categoryId(), request.title().strip(), description(request.description()),
                request.gstCategory(), options);
        record(admin, "catalog.product.created", id, details("title", request.title().strip(),
                "category_id", request.categoryId(), "gst_category", request.gstCategory()));
        return get(id);
    }

    /** Needs the version the caller last read: a stale one gets {@code 412}, a missing one {@code 428}. */
    @Transactional
    public Product update(Caller admin, UUID id, Long expectedVersion, ProductChanges changes) {
        if (expectedVersion == null) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "precondition_required",
                    "Send If-Match with the product's ETag.");
        }
        Product product = lock(id);
        if (product.version() != expectedVersion) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "precondition_failed",
                    "The product changed since it was read; read it again and reapply the change.");
        }
        if (changes.categoryId() != null) {
            requireCategory(changes.categoryId());
        }
        List<ProductOption> options = product.options();
        if (changes.options() != null) {
            options = ProductOptions.normalize(changes.options());
            if (!product.variants().isEmpty()) {
                ProductOptions.checkChangeAllowed(product.options(), options);
            }
        }
        String title = changes.title() != null ? changes.title().strip() : product.title();
        String description = changes.description() != null ? changes.description().strip() : product.description();
        UUID categoryId = changes.categoryId() != null ? changes.categoryId() : product.category().id();
        GstCategory gstCategory = changes.gstCategory() != null ? changes.gstCategory() : product.gstCategory();
        products.update(id, categoryId, title, description, gstCategory, options, product.status());
        Map<String, Object> changed = new LinkedHashMap<>();
        putIfChanged(changed, "title", product.title(), title);
        putIfChanged(changed, "description", product.description(), description);
        putIfChanged(changed, "category_id", product.category().id(), categoryId);
        putIfChanged(changed, "gst_category", product.gstCategory(), gstCategory);
        putIfChanged(changed, "options", product.options(), options);
        record(admin, "catalog.product.updated", id, changed);
        return get(id);
    }

    @Transactional
    public Product activate(Caller admin, UUID id) {
        Product product = lock(id);
        if (product.status() == ProductStatus.ACTIVE) {
            return product;
        }
        if (product.activeVariants() == 0) {
            throw needsActiveVariant();
        }
        setStatus(admin, product, ProductStatus.ACTIVE, "catalog.product.activated");
        return get(id);
    }

    @Transactional
    public Product archive(Caller admin, UUID id) {
        Product product = lock(id);
        if (product.status() == ProductStatus.ARCHIVED) {
            return product;
        }
        setStatus(admin, product, ProductStatus.ARCHIVED, "catalog.product.archived");
        return get(id);
    }

    @Transactional
    public Product addVariant(Caller admin, UUID productId, NewVariant request) {
        Product product = lock(productId);
        if (product.variants().size() >= MAX_VARIANTS) {
            throw new ApiException(HttpStatus.CONFLICT, "too_many_variants",
                    "A product has at most " + MAX_VARIANTS + " variants.");
        }
        Map<String, String> values = ProductOptions.resolve(product.options(), request.optionValues());
        Variant variant = new Variant(Ids.newId(), request.sku().strip().toUpperCase(Locale.ROOT), values,
                request.pricePaise(), VariantStatus.ACTIVE);
        products.insertVariant(productId, variant, ProductOptions.combinationKey(values));
        products.touch(productId);
        record(admin, "catalog.variant.created", productId, details("variant_id", variant.id(), "sku", variant.sku(),
                "option_values", values, "price_paise", variant.pricePaise()));
        return get(productId);
    }

    @Transactional
    public Product updateVariant(Caller admin, UUID productId, UUID variantId, VariantChanges changes) {
        Product product = lock(productId);
        Variant variant = product.variants().stream()
                .filter(candidate -> candidate.id().equals(variantId))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such variant."));
        long price = changes.pricePaise() != null ? changes.pricePaise() : variant.pricePaise();
        VariantStatus status = changes.status() != null ? changes.status() : variant.status();
        boolean deactivatesLastActive = product.status() == ProductStatus.ACTIVE
                && variant.status() == VariantStatus.ACTIVE && status == VariantStatus.INACTIVE
                && product.activeVariants() == 1;
        if (deactivatesLastActive) {
            throw needsActiveVariant();
        }
        products.updateVariant(productId, variantId, price, status);
        products.touch(productId);
        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("variant_id", variantId);
        putIfChanged(changed, "price_paise", variant.pricePaise(), price);
        putIfChanged(changed, "status", variant.status(), status);
        record(admin, "catalog.variant.updated", productId, changed);
        return get(productId);
    }

    @Transactional(readOnly = true)
    public Product get(UUID id) {
        return products.find(id).orElseThrow(ProductService::notFound);
    }

    /** What anyone may see: only active products. */
    @Transactional(readOnly = true)
    public Product getActive(UUID id) {
        return products.find(id).filter(product -> product.status() == ProductStatus.ACTIVE)
                .orElseThrow(ProductService::notFound);
    }

    /**
     * Products with the given statuses, optionally in a category (with its subcategories) and matching a search
     * text. Browsing pages by id; ranked search results page by offset, up to {@value #MAX_SEARCH_RESULTS}.
     */
    @Transactional(readOnly = true)
    public ProductPage list(Set<ProductStatus> statuses, String categorySlug, String text, PageCursor cursor,
            int limit) {
        boolean search = text != null;
        if ((search && cursor instanceof PageCursor.AfterId) || (!search && cursor instanceof PageCursor.Offset)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_cursor",
                    "This cursor belongs to a different listing.");
        }
        Collection<UUID> categoryIds = null;
        if (categorySlug != null) {
            CategoryTree tree = categories.tree();
            categoryIds = tree.findBySlug(categorySlug).map(category -> tree.subtree(category.id())).orElse(Set.of());
            if (categoryIds.isEmpty()) {
                return new ProductPage(List.of(), Optional.empty());
            }
        }
        int offset = cursor instanceof PageCursor.Offset start ? start.offset() : 0;
        if (offset >= MAX_SEARCH_RESULTS) {
            return new ProductPage(List.of(), Optional.empty());
        }
        int pageSize = search ? Math.min(limit, MAX_SEARCH_RESULTS - offset) : limit;
        List<ProductSummary> rows = products.list(statuses, categoryIds, text, cursor, pageSize);
        boolean more = rows.size() > pageSize;
        List<ProductSummary> page = List.copyOf(more ? rows.subList(0, pageSize) : rows);
        Optional<PageCursor> next = !more ? Optional.empty()
                : Optional.of(search ? new PageCursor.Offset(offset + pageSize)
                        : new PageCursor.AfterId(page.getLast().id()));
        return new ProductPage(page, next);
    }

    Product lock(UUID id) {
        return products.lock(id).orElseThrow(ProductService::notFound);
    }

    private void setStatus(Caller admin, Product product, ProductStatus status, String action) {
        products.update(product.id(), product.category().id(), product.title(), product.description(),
                product.gstCategory(), product.options(), status);
        record(admin, action, product.id(), details("from", product.status(), "to", status));
    }

    private void requireCategory(UUID categoryId) {
        categories.tree().find(categoryId).orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                "unknown_category", "No such category."));
    }

    private void record(Caller admin, String action, UUID productId, Map<String, Object> details) {
        audit.record(new AuditEntry("staff", admin.subject(), action, "product", productId.toString(), null,
                details));
    }

    private static void putIfChanged(Map<String, Object> changed, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changed.put(field, after);
        }
    }

    private static Map<String, Object> details(Object... keysAndValues) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            details.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return details;
    }

    private static String description(String description) {
        return description == null ? "" : description.strip();
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such product.");
    }

    private static ApiException needsActiveVariant() {
        return new ApiException(HttpStatus.CONFLICT, "product_needs_active_variant",
                "An active product needs at least one active variant.");
    }
}
