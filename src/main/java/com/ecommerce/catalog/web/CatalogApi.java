package com.ecommerce.catalog.web;

import com.ecommerce.catalog.domain.Category;
import com.ecommerce.catalog.domain.GstCategory;
import com.ecommerce.catalog.domain.ImageStatus;
import com.ecommerce.catalog.domain.Product;
import com.ecommerce.catalog.domain.ProductImage;
import com.ecommerce.catalog.domain.ProductOption;
import com.ecommerce.catalog.domain.ProductStatus;
import com.ecommerce.catalog.domain.ProductSummary;
import com.ecommerce.catalog.domain.VariantStatus;
import com.ecommerce.platform.ObjectStorage;
import com.ecommerce.platform.PresignedUpload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request and response bodies of the catalog API (LLD §3.6–3.8). Money is integer paise in INR. */
final class CatalogApi {

    static final String CURRENCY = "INR";
    static final String SLUG = "[a-z0-9]+(-[a-z0-9]+)*";

    private CatalogApi() {
    }

    record CreateCategory(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 60) @Pattern(regexp = SLUG, message = "must be lower case words joined by hyphens")
            String slug,
            UUID parentId) {
    }

    /** Fields left out are unchanged. */
    record UpdateCategory(
            @Size(min = 1, max = 80) String name,
            @Size(max = 60) @Pattern(regexp = SLUG, message = "must be lower case words joined by hyphens")
            String slug) {
    }

    /** A {@code null} parent moves the category to the top level. */
    record MoveCategory(UUID parentId) {
    }

    record CategoryResponse(UUID id, String name, String slug, UUID parentId) {

        static CategoryResponse of(Category category) {
            return new CategoryResponse(category.id(), category.name(), category.slug(), category.parentId());
        }
    }

    record CategoryNode(UUID id, String name, String slug, List<CategoryNode> children) {
    }

    record CategoryTreeResponse(List<CategoryNode> items) {
    }

    record CategoryRef(UUID id, String slug, String name) {

        static CategoryRef of(Category category) {
            return new CategoryRef(category.id(), category.slug(), category.name());
        }
    }

    record OptionBody(@NotBlank String name, @NotNull @Size(min = 1, max = 30) List<String> values) {

        ProductOption toOption() {
            return new ProductOption(name, values);
        }

        static List<ProductOption> toOptions(List<OptionBody> options) {
            return options == null ? null : options.stream().map(OptionBody::toOption).toList();
        }

        static List<OptionBody> of(List<ProductOption> options) {
            return options.stream().map(option -> new OptionBody(option.name(), option.values())).toList();
        }
    }

    record CreateProduct(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 5000) String description,
            @NotNull UUID categoryId,
            @NotNull GstCategory gstCategory,
            @Size(max = 3) List<@Valid OptionBody> options) {
    }

    /** Fields left out are unchanged. */
    record UpdateProduct(
            @Size(min = 1, max = 200) String title,
            @Size(max = 5000) String description,
            UUID categoryId,
            GstCategory gstCategory,
            @Size(max = 3) List<@Valid OptionBody> options) {
    }

    record CreateVariant(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9-]{2,39}",
                    message = "must be 3 to 40 letters, digits and hyphens") String sku,
            Map<String, String> optionValues,
            @NotNull @Min(1) @Max(1_000_000_000) Long pricePaise) {
    }

    /** Fields left out are unchanged. */
    record UpdateVariant(@Min(1) @Max(1_000_000_000) Long pricePaise, VariantStatus status) {
    }

    record AdminVariant(UUID id, String sku, Map<String, String> optionValues, long pricePaise, VariantStatus status) {
    }

    record AdminImage(UUID id, ImageStatus status, URI url, String altText, String contentType, long sizeBytes,
            Integer position) {

        static AdminImage of(ProductImage image, ObjectStorage storage) {
            return new AdminImage(image.id(), image.status(),
                    image.status() == ImageStatus.READY ? storage.publicUrl(image.objectKey()) : null,
                    image.altText(), image.contentType(), image.sizeBytes(), image.position());
        }
    }

    record AdminProduct(
            UUID id,
            String title,
            String description,
            CategoryRef category,
            GstCategory gstCategory,
            List<OptionBody> options,
            ProductStatus status,
            long version,
            String currency,
            List<AdminVariant> variants,
            List<AdminImage> images,
            Instant createdAt,
            Instant updatedAt) {

        static AdminProduct of(Product product, ObjectStorage storage) {
            return new AdminProduct(product.id(), product.title(), product.description(),
                    CategoryRef.of(product.category()), product.gstCategory(), OptionBody.of(product.options()),
                    product.status(), product.version(), CURRENCY,
                    product.variants().stream().map(variant -> new AdminVariant(variant.id(), variant.sku(),
                            variant.optionValues(), variant.pricePaise(), variant.status())).toList(),
                    product.images().stream().map(image -> AdminImage.of(image, storage)).toList(),
                    product.createdAt(), product.updatedAt());
        }
    }

    record PublicVariant(UUID id, String sku, Map<String, String> optionValues, long pricePaise) {
    }

    record PublicImage(UUID id, URI url, String altText) {
    }

    /** Only active variants and confirmed images. */
    record PublicProduct(
            UUID id,
            String title,
            String description,
            CategoryRef category,
            List<OptionBody> options,
            String currency,
            List<PublicVariant> variants,
            List<PublicImage> images) {

        static PublicProduct of(Product product, ObjectStorage storage) {
            return new PublicProduct(product.id(), product.title(), product.description(),
                    CategoryRef.of(product.category()), OptionBody.of(product.options()), CURRENCY,
                    product.variants().stream()
                            .filter(variant -> variant.status() == VariantStatus.ACTIVE)
                            .map(variant -> new PublicVariant(variant.id(), variant.sku(), variant.optionValues(),
                                    variant.pricePaise()))
                            .toList(),
                    product.images().stream()
                            .filter(image -> image.status() == ImageStatus.READY)
                            .map(image -> new PublicImage(image.id(), storage.publicUrl(image.objectKey()),
                                    image.altText()))
                            .toList());
        }
    }

    /** {@code status} appears only in admin lists. */
    record ProductListItem(UUID id, String title, CategoryRef category, ProductStatus status, Long minPricePaise,
            String currency, URI imageUrl) {

        static ProductListItem of(ProductSummary summary, ObjectStorage storage, boolean withStatus) {
            return new ProductListItem(summary.id(), summary.title(), CategoryRef.of(summary.category()),
                    withStatus ? summary.status() : null, summary.minPricePaise(), CURRENCY,
                    summary.firstImageKey() == null ? null : storage.publicUrl(summary.firstImageKey()));
        }
    }

    record ProductList(List<ProductListItem> items, String nextCursor) {
    }

    record RequestUpload(
            @NotNull @Pattern(regexp = "image/(jpeg|png|webp)", message = "must be image/jpeg, image/png or image/webp")
            String contentType,
            @NotNull @Min(1) @Max(5 * 1024 * 1024) Long sizeBytes,
            @Size(max = 200) String altText) {
    }

    /** Send {@code method} to {@code url} with exactly these headers and {@code size_bytes} bytes. */
    record UploadInstructions(String method, URI url, Map<String, String> headers, Instant expiresAt) {

        static UploadInstructions of(PresignedUpload upload) {
            return new UploadInstructions(upload.method(), upload.url(), upload.headers(), upload.expiresAt());
        }
    }

    record ImageUpload(AdminImage image, UploadInstructions upload) {
    }
}
