package com.ecommerce.platform.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class MediaPropertiesTests {

    @Test
    void renderingSettingsDoesNotExposeCredentialsOrCredentialBearingUrls() {
        var properties = new MediaProperties("test-media", "ap-south-1",
                URI.create("http://user:private-password@localhost:9000"),
                URI.create("https://media.example.test?token=private-presign-token"),
                URI.create("https://cdn.example.test?token=private-cdn-token"),
                MediaProperties.ObjectAcl.NONE, true, "private-access-key", "private-secret-key");

        assertThat(properties.toString()).contains("test-media", "ap-south-1", "accessKey=***", "secretKey=***")
                .doesNotContain("private-password", "private-presign-token", "private-cdn-token",
                        "private-access-key", "private-secret-key");
    }

    @Test
    void theDefaultCredentialChainCanBeDescribedWithoutInventingKeys() {
        var properties = new MediaProperties("test-media", "ap-south-1", null, null,
                URI.create("https://media.example.test"), MediaProperties.ObjectAcl.NONE, false, null, null);

        assertThat(properties.toString()).contains("endpoint=default", "accessKey=unset", "secretKey=unset");
    }
}