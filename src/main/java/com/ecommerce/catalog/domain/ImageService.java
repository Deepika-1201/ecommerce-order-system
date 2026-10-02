package com.ecommerce.catalog.domain;

import com.ecommerce.catalog.domain.ProductRepository.PendingImage;
import com.ecommerce.platform.ApiException;
import com.ecommerce.platform.AuditEntry;
import com.ecommerce.platform.AuditLog;
import com.ecommerce.platform.Caller;
import com.ecommerce.platform.HandlesTask;
import com.ecommerce.platform.ObjectStorage;
import com.ecommerce.platform.PresignedUpload;
import com.ecommerce.platform.StoredObject;
import com.ecommerce.platform.TaskExecution;
import com.ecommerce.platform.TaskRequest;
import com.ecommerce.platform.TaskScheduler;
import com.ecommerce.shared.Ids;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Product images through pre-signed uploads (LLD §3.8). Storage is never called while the product row is locked:
 * the stored object is checked before the transaction, and objects are deleted by tasks after it.
 */
@Service
public class ImageService {

    static final int MAX_IMAGES = 10;
    static final Duration UPLOAD_VALIDITY = Duration.ofMinutes(5);
    static final Duration PENDING_LIFETIME = Duration.ofHours(24);
    static final String DELETE_OBJECT_TASK = "catalog.delete-image-object";
    static final String EXPIRE_PENDING_TASK = "catalog.expire-pending-images";

    private static final Logger log = LoggerFactory.getLogger(ImageService.class);
    private static final Map<String, String> EXTENSIONS =
            Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");
    private static final int EXPIRY_BATCH = 100;
    private static final int MAX_EXPIRY_BATCHES = 50;

    private final ProductRepository products;
    private final ObjectStorage storage;
    private final TaskScheduler tasks;
    private final AuditLog audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    ImageService(ProductRepository products, ObjectStorage storage, TaskScheduler tasks, AuditLog audit,
            TransactionTemplate transactions, Clock clock) {
        this.products = products;
        this.storage = storage;
        this.tasks = tasks;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Records a pending image and returns where to upload it. */
    public Upload requestUpload(Caller admin, UUID productId, String contentType, long sizeBytes, String altText) {
        String extension = EXTENSIONS.get(contentType);
        if (extension == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "unsupported_image_type", "Images are JPEG, PNG or WebP.");
        }
        return transactions.execute(status -> {
            Product product = lock(productId);
            if (product.images().size() >= MAX_IMAGES) {
                throw new ApiException(HttpStatus.CONFLICT, "image_limit_reached",
                        "A product has at most " + MAX_IMAGES + " images.");
            }
            UUID imageId = Ids.newId();
            ProductImage image = new ProductImage(imageId, "products/" + productId + "/" + imageId + "." + extension,
                    contentType, sizeBytes, altText == null ? "" : altText.strip(), ImageStatus.PENDING, null);
            products.insertImage(productId, image);
            products.touch(productId);
            PresignedUpload upload = storage.presignUpload(image.objectKey(), contentType, sizeBytes, UPLOAD_VALIDITY);
            return new Upload(image, upload);
        });
    }

    /** Confirms an upload once storage holds exactly the announced object; confirming twice is harmless. */
    public ProductImage complete(Caller admin, UUID productId, UUID imageId) {
        ProductImage pending = image(products.find(productId).orElseThrow(ImageService::productNotFound), imageId);
        if (pending.status() == ImageStatus.READY) {
            return pending;
        }
        StoredObject stored = storage.find(pending.objectKey()).orElseThrow(() -> new ApiException(
                HttpStatus.CONFLICT, "upload_not_found", "Nothing has been uploaded for this image yet."));
        if (stored.sizeBytes() != pending.sizeBytes() || !pending.contentType().equalsIgnoreCase(stored.contentType())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "upload_mismatch",
                    "The uploaded object differs from the announced type or size.");
        }
        return transactions.execute(status -> {
            ProductImage current = image(lock(productId), imageId);
            if (current.status() == ImageStatus.PENDING) {
                products.markImageReady(productId, imageId);
                products.touch(productId);
                audit.record(new AuditEntry("staff", admin.subject(), "catalog.image.added", "product",
                        productId.toString(), null, Map.of("image_id", imageId, "object_key", current.objectKey())));
            }
            return image(products.find(productId).orElseThrow(), imageId);
        });
    }

    /** Removes the image at once; its object is deleted by a task after commit. */
    public void delete(Caller admin, UUID productId, UUID imageId) {
        transactions.executeWithoutResult(status -> {
            ProductImage image = image(lock(productId), imageId);
            remove(productId, image);
            audit.record(new AuditEntry("staff", admin.subject(), "catalog.image.removed", "product",
                    productId.toString(), null, Map.of("image_id", imageId, "object_key", image.objectKey())));
        });
    }

    @HandlesTask(type = DELETE_OBJECT_TASK)
    void deleteObject(TaskExecution<DeleteObject> task) {
        storage.delete(task.payload().key());
    }

    /** Uploads never confirmed within a day are removed with their objects; at most 50 batches per run. */
    @HandlesTask(type = EXPIRE_PENDING_TASK, every = "1h")
    void expirePendingUploads(TaskExecution<Void> task) {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(PENDING_LIFETIME);
        List<PendingImage> stale;
        int removed = 0;
        int batches = 0;
        do {
            stale = products.pendingImagesBefore(cutoff, EXPIRY_BATCH);
            for (PendingImage pending : stale) {
                transactions.executeWithoutResult(status -> products.lock(pending.productId())
                        .flatMap(product -> product.images().stream()
                                .filter(image -> image.id().equals(pending.imageId())
                                        && image.status() == ImageStatus.PENDING)
                                .findFirst())
                        .ifPresent(image -> remove(pending.productId(), image)));
                removed++;
            }
        } while (stale.size() == EXPIRY_BATCH && ++batches < MAX_EXPIRY_BATCHES);
        if (removed > 0) {
            log.info("Removed {} image uploads that were never confirmed", removed);
        }
    }

    private void remove(UUID productId, ProductImage image) {
        products.deleteImage(productId, image.id());
        products.touch(productId);
        tasks.schedule(TaskRequest.of(DELETE_OBJECT_TASK, new DeleteObject(image.objectKey()))
                .dedupeKey("delete-object:" + image.objectKey()));
    }

    private Product lock(UUID productId) {
        return products.lock(productId).orElseThrow(ImageService::productNotFound);
    }

    private static ProductImage image(Product product, UUID imageId) {
        return product.images().stream()
                .filter(image -> image.id().equals(imageId))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such image."));
    }

    private static ApiException productNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such product.");
    }

    public record Upload(ProductImage image, PresignedUpload upload) {
    }

    record DeleteObject(String key) {
    }
}
