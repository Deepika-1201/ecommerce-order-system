package com.ecommerce.platform;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/** Object storage behind the S3 API: S3 in AWS, S3Proxy locally and in tests (ADR-015, LLD §3.8). */
public interface ObjectStorage {

    /** A URL that accepts exactly one {@code PUT} of this type and size until it expires. */
    PresignedUpload presignUpload(String key, String contentType, long sizeBytes, Duration validity);

    Optional<StoredObject> find(String key);

    /** Deleting a missing object is not an error. */
    void delete(String key);

    /** Where clients read the object: the bucket locally, a CDN in AWS. */
    URI publicUrl(String key);
}
