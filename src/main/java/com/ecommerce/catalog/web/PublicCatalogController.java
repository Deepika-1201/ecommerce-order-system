package com.ecommerce.catalog.web;

import com.ecommerce.catalog.domain.Category;
import com.ecommerce.catalog.domain.CategoryService;
import com.ecommerce.catalog.domain.CategoryTree;
import com.ecommerce.catalog.domain.ProductPage;
import com.ecommerce.catalog.domain.ProductService;
import com.ecommerce.catalog.domain.ProductStatus;
import com.ecommerce.catalog.web.CatalogApi.CategoryNode;
import com.ecommerce.catalog.web.CatalogApi.CategoryTreeResponse;
import com.ecommerce.catalog.web.CatalogApi.ProductList;
import com.ecommerce.catalog.web.CatalogApi.ProductListItem;
import com.ecommerce.catalog.web.CatalogApi.PublicProduct;
import com.ecommerce.platform.ApiController;
import com.ecommerce.platform.ObjectStorage;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * What anyone may read, without a token (LLD §3.7). Responses may be cached for 30 seconds; a filter adds an ETag
 * from the body, so a revalidation that finds no change gets {@code 304}.
 */
@ApiController
class PublicCatalogController {

    static final CacheControl CACHE = CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic();

    private final CategoryService categories;
    private final ProductService products;
    private final ObjectStorage storage;

    PublicCatalogController(CategoryService categories, ProductService products, ObjectStorage storage) {
        this.categories = categories;
        this.products = products;
        this.storage = storage;
    }

    @GetMapping("/v1/categories")
    ResponseEntity<CategoryTreeResponse> categories() {
        CategoryTree tree = categories.tree();
        return ResponseEntity.ok().cacheControl(CACHE).body(new CategoryTreeResponse(nodes(tree, null)));
    }

    @GetMapping("/v1/products")
    ResponseEntity<ProductList> products(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit,
            @RequestParam(required = false) String cursor) {
        ProductPage page = products.list(EnumSet.of(ProductStatus.ACTIVE), category,
                q == null || q.isBlank() ? null : q.strip(), Cursors.decode(cursor), limit);
        return ResponseEntity.ok().cacheControl(CACHE).body(new ProductList(
                page.items().stream().map(item -> ProductListItem.of(item, storage, false)).toList(),
                page.next().map(Cursors::encode).orElse(null)));
    }

    @GetMapping("/v1/products/{id}")
    ResponseEntity<PublicProduct> product(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CACHE).body(PublicProduct.of(products.getActive(id), storage));
    }

    private static List<CategoryNode> nodes(CategoryTree tree, UUID parentId) {
        return tree.childrenOf(parentId).stream()
                .map((Category category) -> new CategoryNode(category.id(), category.name(), category.slug(),
                        nodes(tree, category.id())))
                .toList();
    }
}
