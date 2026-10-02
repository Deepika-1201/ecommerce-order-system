package com.ecommerce.catalog.web;

import com.ecommerce.catalog.domain.CategoryService;
import com.ecommerce.catalog.domain.ImageService;
import com.ecommerce.catalog.domain.Product;
import com.ecommerce.catalog.domain.ProductCommands.NewProduct;
import com.ecommerce.catalog.domain.ProductCommands.NewVariant;
import com.ecommerce.catalog.domain.ProductCommands.ProductChanges;
import com.ecommerce.catalog.domain.ProductCommands.VariantChanges;
import com.ecommerce.catalog.domain.ProductPage;
import com.ecommerce.catalog.domain.ProductService;
import com.ecommerce.catalog.domain.ProductStatus;
import com.ecommerce.catalog.web.CatalogApi.AdminImage;
import com.ecommerce.catalog.web.CatalogApi.AdminProduct;
import com.ecommerce.catalog.web.CatalogApi.CategoryResponse;
import com.ecommerce.catalog.web.CatalogApi.CreateCategory;
import com.ecommerce.catalog.web.CatalogApi.CreateProduct;
import com.ecommerce.catalog.web.CatalogApi.CreateVariant;
import com.ecommerce.catalog.web.CatalogApi.ImageUpload;
import com.ecommerce.catalog.web.CatalogApi.MoveCategory;
import com.ecommerce.catalog.web.CatalogApi.OptionBody;
import com.ecommerce.catalog.web.CatalogApi.ProductList;
import com.ecommerce.catalog.web.CatalogApi.ProductListItem;
import com.ecommerce.catalog.web.CatalogApi.RequestUpload;
import com.ecommerce.catalog.web.CatalogApi.UpdateCategory;
import com.ecommerce.catalog.web.CatalogApi.UpdateProduct;
import com.ecommerce.catalog.web.CatalogApi.UpdateVariant;
import com.ecommerce.catalog.web.CatalogApi.UploadInstructions;
import com.ecommerce.platform.ApiController;
import com.ecommerce.platform.ApiException;
import com.ecommerce.platform.Caller;
import com.ecommerce.platform.ObjectStorage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Catalog administration (LLD §3.6). Product responses carry the product's version as a strong {@code ETag}. */
@ApiController
@RequestMapping("/v1/admin/catalog")
@Tag(name = "Catalog administration", description = "Role admin. Product responses carry the version as ETag.")
@SecurityRequirement(name = ApiController.BEARER_AUTH)
class CatalogAdminController {

    private static final Pattern STRONG_ETAG = Pattern.compile("\"(\\d{1,18})\"");

    private final CategoryService categories;
    private final ProductService products;
    private final ImageService images;
    private final ObjectStorage storage;

    CatalogAdminController(CategoryService categories, ProductService products, ImageService images,
            ObjectStorage storage) {
        this.categories = categories;
        this.products = products;
        this.images = images;
        this.storage = storage;
    }

    @Operation(summary = "Create a category, at most 4 levels deep")
    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<CategoryResponse> createCategory(Caller admin, @Valid @RequestBody CreateCategory request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(CategoryResponse.of(
                categories.create(admin, request.name(), request.slug(), request.parentId())));
    }

    @Operation(summary = "Rename a category or change its slug")
    @PatchMapping("/categories/{id}")
    CategoryResponse updateCategory(Caller admin, @PathVariable UUID id, @Valid @RequestBody UpdateCategory request) {
        return CategoryResponse.of(categories.rename(admin, id, request.name(), request.slug()));
    }

    @Operation(summary = "Move a category with its subtree; a null parent moves it to the top")
    @PostMapping("/categories/{id}/move")
    CategoryResponse moveCategory(Caller admin, @PathVariable UUID id, @RequestBody MoveCategory request) {
        return CategoryResponse.of(categories.move(admin, id, request.parentId()));
    }

    @Operation(summary = "List products in any status, newest first, or by relevance with q")
    @GetMapping("/products")
    ProductList listProducts(
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit,
            @RequestParam(required = false) String cursor) {
        Set<ProductStatus> statuses = status == null ? EnumSet.allOf(ProductStatus.class) : EnumSet.of(status);
        ProductPage page = products.list(statuses, category, blankToNull(q), Cursors.decode(cursor), limit);
        return new ProductList(page.items().stream().map(item -> ProductListItem.of(item, storage, true)).toList(),
                page.next().map(Cursors::encode).orElse(null));
    }

    @Operation(summary = "Create a draft product")
    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<AdminProduct> createProduct(Caller admin, @Valid @RequestBody CreateProduct request) {
        Product product = products.create(admin, new NewProduct(request.title(), request.description(),
                request.categoryId(), request.gstCategory(), OptionBody.toOptions(request.options())));
        return withEtag(ResponseEntity.created(URI.create("/v1/admin/catalog/products/" + product.id())), product);
    }

    @Operation(summary = "Read a product in any status, with its version as ETag")
    @GetMapping("/products/{id}")
    ResponseEntity<AdminProduct> getProduct(@PathVariable UUID id) {
        return withEtag(ResponseEntity.ok(), products.get(id));
    }

    @Operation(summary = "Change a product; needs If-Match with its ETag")
    @PatchMapping("/products/{id}")
    ResponseEntity<AdminProduct> updateProduct(Caller admin, @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody UpdateProduct request) {
        Product product = products.update(admin, id, expectedVersion(ifMatch), new ProductChanges(request.title(),
                request.description(), request.categoryId(), request.gstCategory(),
                OptionBody.toOptions(request.options())));
        return withEtag(ResponseEntity.ok(), product);
    }

    @Operation(summary = "Make a product public; it needs an active variant")
    @PostMapping("/products/{id}/activate")
    ResponseEntity<AdminProduct> activate(Caller admin, @PathVariable UUID id) {
        return withEtag(ResponseEntity.ok(), products.activate(admin, id));
    }

    @Operation(summary = "Hide a product; products are never deleted")
    @PostMapping("/products/{id}/archive")
    ResponseEntity<AdminProduct> archive(Caller admin, @PathVariable UUID id) {
        return withEtag(ResponseEntity.ok(), products.archive(admin, id));
    }

    @Operation(summary = "Add a variant naming one value of every option")
    @PostMapping("/products/{id}/variants")
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<AdminProduct> addVariant(Caller admin, @PathVariable UUID id,
            @Valid @RequestBody CreateVariant request) {
        Product product = products.addVariant(admin, id, new NewVariant(request.sku(), request.optionValues(),
                request.pricePaise()));
        return withEtag(ResponseEntity.status(HttpStatus.CREATED), product);
    }

    @Operation(summary = "Change a variant's price or status")
    @PatchMapping("/products/{id}/variants/{variantId}")
    ResponseEntity<AdminProduct> updateVariant(Caller admin, @PathVariable UUID id, @PathVariable UUID variantId,
            @Valid @RequestBody UpdateVariant request) {
        Product product = products.updateVariant(admin, id, variantId,
                new VariantChanges(request.pricePaise(), request.status()));
        return withEtag(ResponseEntity.ok(), product);
    }

    @Operation(summary = "Start an image upload: returns a pre-signed URL valid for 5 minutes")
    @PostMapping("/products/{id}/images")
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<ImageUpload> requestImageUpload(Caller admin, @PathVariable UUID id,
            @Valid @RequestBody RequestUpload request) {
        ImageService.Upload upload = images.requestUpload(admin, id, request.contentType(), request.sizeBytes(),
                request.altText());
        return ResponseEntity.status(HttpStatus.CREATED).body(new ImageUpload(AdminImage.of(upload.image(), storage),
                UploadInstructions.of(upload.upload())));
    }

    @Operation(summary = "Confirm an upload once storage holds exactly the announced object")
    @PostMapping("/products/{id}/images/{imageId}/complete")
    AdminImage completeImageUpload(Caller admin, @PathVariable UUID id, @PathVariable UUID imageId) {
        return AdminImage.of(images.complete(admin, id, imageId), storage);
    }

    @Operation(summary = "Remove an image; its object is deleted afterwards")
    @DeleteMapping("/products/{id}/images/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    ResponseEntity<Void> deleteImage(Caller admin, @PathVariable UUID id, @PathVariable UUID imageId) {
        images.delete(admin, id, imageId);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<AdminProduct> withEtag(ResponseEntity.BodyBuilder response, Product product) {
        return response.eTag("\"" + product.version() + "\"").body(AdminProduct.of(product, storage));
    }

    /** {@code null} when the header is missing; a weak or unknown tag never matches. */
    private static Long expectedVersion(String ifMatch) {
        if (ifMatch == null) {
            return null;
        }
        Matcher strong = STRONG_ETAG.matcher(ifMatch.strip());
        if (!strong.matches()) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "precondition_failed",
                    "If-Match must carry the product's ETag.");
        }
        return Long.parseLong(strong.group(1));
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
