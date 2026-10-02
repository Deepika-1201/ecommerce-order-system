package com.ecommerce.platform.storage;

import com.ecommerce.platform.ObjectStorage;
import com.ecommerce.platform.PresignedUpload;
import com.ecommerce.platform.StoredObject;
import com.ecommerce.platform.storage.MediaProperties.ObjectAcl;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/**
 * {@link ObjectStorage} on the AWS SDK. Upload URLs sign the content type and length (and locally the ACL), so storage
 * itself refuses any other upload (ADR-015).
 */
@Component
class S3ObjectStorage implements ObjectStorage, DisposableBean {

    private final MediaProperties properties;
    private final S3Client s3;
    private final S3Presigner presigner;

    S3ObjectStorage(MediaProperties properties) {
        this.properties = properties;
        AwsCredentialsProvider credentials = properties.accessKey() != null && properties.secretKey() != null
                ? StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.accessKey(),
                        properties.secretKey()))
                : DefaultCredentialsProvider.builder().build();
        Region region = Region.of(properties.region());
        S3ClientBuilder client = S3Client.builder()
                .region(region)
                .credentialsProvider(credentials)
                .forcePathStyle(properties.pathStyle())
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(1))
                        .socketTimeout(Duration.ofSeconds(5)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(10))
                        .build());
        if (properties.endpoint() != null) {
            client.endpointOverride(properties.endpoint());
        }
        this.s3 = client.build();
        S3Presigner.Builder presign = S3Presigner.builder()
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(properties.pathStyle()).build());
        URI presignEndpoint = properties.presignEndpoint() != null ? properties.presignEndpoint() : properties.endpoint();
        if (presignEndpoint != null) {
            presign.endpointOverride(presignEndpoint);
        }
        this.presigner = presign.build();
    }

    @Override
    public PresignedUpload presignUpload(String key, String contentType, long sizeBytes, Duration validity) {
        PutObjectRequest.Builder put = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .contentType(contentType)
                .contentLength(sizeBytes);
        if (properties.objectAcl() == ObjectAcl.PUBLIC_READ) {
            put.acl(ObjectCannedACL.PUBLIC_READ);
        }
        PresignedPutObjectRequest presigned = presigner.presignPutObject(request -> request
                .signatureDuration(validity)
                .putObjectRequest(put.build()));
        Map<String, String> headers = new LinkedHashMap<>();
        presigned.signedHeaders().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("host")) {
                headers.put(name, String.join(",", values));
            }
        });
        try {
            return new PresignedUpload(presigned.url().toURI(), "PUT", headers, presigned.expiration());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("The SDK produced an invalid URL", e);
        }
    }

    @Override
    public Optional<StoredObject> find(String key) {
        try {
            HeadObjectResponse head = s3.headObject(request -> request.bucket(properties.bucket()).key(key));
            return Optional.of(new StoredObject(key, head.contentLength(), head.contentType()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(request -> request.bucket(properties.bucket()).key(key));
    }

    @Override
    public URI publicUrl(String key) {
        String base = properties.publicBaseUrl().toString();
        return URI.create(base.endsWith("/") ? base + key : base + "/" + key);
    }

    @Override
    public void destroy() {
        presigner.close();
        s3.close();
    }
}
