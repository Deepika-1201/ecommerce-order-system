package com.ecommerce.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.platform.tasks.DueTasks;
import com.ecommerce.support.S3ProxySupport;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.JsonNode;

/** Image uploads against a real S3 API: S3Proxy (ADR-015). */
class ProductImageTests extends CatalogTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private ApplicationContext context;

    private final HttpClient http = HttpClient.newHttpClient();
    private String productId;

    @BeforeEach
    void product() {
        productId = activeProduct("Linen shirt", "", createCategory("Shirts", "shirts", null), 149_900);
    }

    @Test
    void anUploadThroughTheSignedUrlBecomesAPublicImage() throws Exception {
        byte[] bytes = randomBytes(2048);
        HttpResponse<String> requested = requestUpload("image/png", bytes.length, "Front view");
        JsonNode upload = json(requested).get("upload");
        String imageId = id(json(requested).get("image"));

        HttpResponse<String> stored = put(upload, bytes);
        HttpResponse<String> completed = complete(imageId);

        assertThat(requested.statusCode()).isEqualTo(201);
        assertThat(json(requested).get("image").get("status").asString()).isEqualTo("PENDING");
        assertThat(json(requested).get("image").has("url")).isFalse();
        assertThat(upload.get("method").asString()).isEqualTo("PUT");
        assertThat(upload.get("headers").get("content-type").asString()).isEqualTo("image/png");
        assertThat(upload.get("headers").get("x-amz-acl").asString()).isEqualTo("public-read");
        assertThat(upload.has("expires_at")).isTrue();
        assertThat(stored.statusCode()).as(stored.body()).isEqualTo(200);
        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(200);
        JsonNode image = json(completed);
        assertThat(image.get("status").asString()).isEqualTo("READY");
        assertThat(image.get("position").asInt()).isEqualTo(1);
        String url = image.get("url").asString();
        assertThat(url).isEqualTo(S3ProxySupport.publicBaseUrl() + "/products/" + productId + "/" + imageId + ".png");

        HttpResponse<byte[]> served = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(served.statusCode()).as("anyone can read the image").isEqualTo(200);
        assertThat(served.body()).isEqualTo(bytes);

        JsonNode product = json(call("GET", "/v1/products/" + productId, null, null));
        assertThat(product.get("images").get(0).get("url").asString()).isEqualTo(url);
        assertThat(product.get("images").get(0).get("alt_text").asString()).isEqualTo("Front view");
        assertThat(json(call("GET", "/v1/products", null, null)).get("items").get(0).get("image_url").asString())
                .isEqualTo(url);
        assertThat(json(complete(imageId))).as("completing twice changes nothing").isEqualTo(image);
    }

    @Test
    void storageRefusesAnyOtherUpload() throws Exception {
        HttpResponse<String> requested = requestUpload("image/png", 2048, null);
        JsonNode upload = json(requested).get("upload");

        HttpResponse<String> tooLong = put(upload, randomBytes(2049));
        HttpResponse<String> otherType = put(upload, randomBytes(2048), "content-type", "image/jpeg");
        HttpResponse<String> privateObject = put(upload, randomBytes(2048), "x-amz-acl", "private");
        HttpResponse<String> completed = complete(id(json(requested).get("image")));

        assertThat(tooLong.statusCode()).isEqualTo(403);
        assertThat(otherType.statusCode()).isEqualTo(403);
        assertThat(privateObject.statusCode()).isEqualTo(403);
        assertThat(completed.statusCode()).isEqualTo(409);
        assertThat(json(completed).get("code").asString()).isEqualTo("upload_not_found");
    }

    @Test
    void completionChecksWhatStorageHolds() {
        String imageId = id(json(requestUpload("image/png", 2048, null)).get("image"));
        try (S3Client s3 = S3ProxySupport.client()) {
            s3.putObject(request -> request.bucket(S3ProxySupport.BUCKET).key(key(imageId, "png"))
                    .contentType("image/png"), RequestBody.fromBytes(randomBytes(100)));
        }

        HttpResponse<String> completed = complete(imageId);

        assertThat(completed.statusCode()).isEqualTo(422);
        assertThat(json(completed).get("code").asString()).isEqualTo("upload_mismatch");
        assertThat(adminImage(0).get("status").asString()).isEqualTo("PENDING");
    }

    @Test
    void uploadRequestsAreChecked() {
        for (HttpResponse<String> refused : List.of(
                requestUpload("image/gif", 2048, null),
                requestUpload("image/png", 5 * 1024 * 1024 + 1, null),
                requestUpload("image/png", 0, null))) {
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
            assertThat(json(refused).get("code").asString()).isEqualTo("validation_failed");
        }
        assertThat(call("POST", "/v1/admin/catalog/products/" + UUID.randomUUID() + "/images", admin,
                "{\"content_type\": \"image/png\", \"size_bytes\": 10}").statusCode()).isEqualTo(404);
        assertThat(requestUpload("image/webp", 5 * 1024 * 1024, null).statusCode()).isEqualTo(201);
    }

    @Test
    void aProductHasAtMostTenImages() {
        for (int i = 0; i < 10; i++) {
            assertThat(requestUpload("image/jpeg", 1000, null).statusCode()).isEqualTo(201);
        }

        HttpResponse<String> eleventh = requestUpload("image/jpeg", 1000, null);

        assertThat(eleventh.statusCode()).isEqualTo(409);
        assertThat(json(eleventh).get("code").asString()).isEqualTo("image_limit_reached");
    }

    @Test
    void aDeletedImageLosesItsObjectThroughATask() throws Exception {
        String imageId = uploadedImage();

        HttpResponse<String> deleted = call("DELETE", imagePath(imageId), admin, null);

        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(json(call("GET", "/v1/products/" + productId, null, null)).get("images").isEmpty()).isTrue();
        assertThat(exists(key(imageId, "png"))).as("deleted after the commit, by a task").isTrue();
        assertThat(DueTasks.runAll(context)).isEqualTo(1);
        assertThat(exists(key(imageId, "png"))).isFalse();
        assertThat(call("DELETE", imagePath(imageId), admin, null).statusCode()).isEqualTo(404);
    }

    @Test
    void uploadsNeverConfirmedExpireAfterADay() throws Exception {
        HttpResponse<String> stale = requestUpload("image/png", 2048, null);
        put(json(stale).get("upload"), randomBytes(2048));
        String staleId = id(json(stale).get("image"));
        String freshId = id(json(requestUpload("image/png", 2048, null)).get("image"));
        jdbc.sql("UPDATE catalog.product_images SET created_at = created_at - interval '25 hours' WHERE id = ?::uuid")
                .param(staleId)
                .update();

        DueTasks.runRecurring(context, "catalog.expire-pending-images");

        JsonNode images = json(call("GET", "/v1/admin/catalog/products/" + productId, admin, null)).get("images");
        assertThat(images).hasSize(1);
        assertThat(id(images.get(0))).isEqualTo(freshId);
        assertThat(exists(key(staleId, "png"))).isFalse();
    }

    private String uploadedImage() throws Exception {
        byte[] bytes = randomBytes(2048);
        HttpResponse<String> requested = requestUpload("image/png", bytes.length, null);
        assertThat(put(json(requested).get("upload"), bytes).statusCode()).isEqualTo(200);
        String imageId = id(json(requested).get("image"));
        assertThat(complete(imageId).statusCode()).isEqualTo(200);
        return imageId;
    }

    private HttpResponse<String> requestUpload(String contentType, long sizeBytes, String altText) {
        return call("POST", "/v1/admin/catalog/products/" + productId + "/images", admin, """
                {"content_type": "%s", "size_bytes": %d, "alt_text": %s}
                """.formatted(contentType, sizeBytes, altText == null ? "null" : '"' + altText + '"'));
    }

    private HttpResponse<String> complete(String imageId) {
        return call("POST", imagePath(imageId) + "/complete", admin, null);
    }

    private String imagePath(String imageId) {
        return "/v1/admin/catalog/products/" + productId + "/images/" + imageId;
    }

    private JsonNode adminImage(int index) {
        return json(call("GET", "/v1/admin/catalog/products/" + productId, admin, null)).get("images").get(index);
    }

    private String key(String imageId, String extension) {
        return "products/" + productId + "/" + imageId + "." + extension;
    }

    /** Sends the upload as instructed, with some headers replaced; the client sets Content-Length from the body. */
    private HttpResponse<String> put(JsonNode upload, byte[] body, String... replacedHeaders)
            throws IOException, InterruptedException {
        Map<String, String> headers = new LinkedHashMap<>();
        upload.get("headers").properties().forEach(header -> headers.put(header.getKey(), header.getValue().asString()));
        for (int i = 0; i < replacedHeaders.length; i += 2) {
            headers.put(replacedHeaders[i], replacedHeaders[i + 1]);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(upload.get("url").asString()))
                .method(upload.get("method").asString(), HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach((name, value) -> {
            if (!name.equalsIgnoreCase("content-length")) {
                request.header(name, value);
            }
        });
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static boolean exists(String key) {
        try (S3Client s3 = S3ProxySupport.client()) {
            s3.headObject(request -> request.bucket(S3ProxySupport.BUCKET).key(key));
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
