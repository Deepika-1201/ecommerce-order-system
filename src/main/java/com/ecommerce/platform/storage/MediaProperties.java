package com.ecommerce.platform.storage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Object storage settings (LLD §3.13). Without endpoints and keys the AWS defaults apply: the regional S3 endpoint
 * and the default credential chain.
 */
@Validated
@ConfigurationProperties(prefix = "ecom.media")
public record MediaProperties(
        @NotBlank String bucket,
        @NotBlank String region,
        URI endpoint,
        URI presignEndpoint,
        @NotNull URI publicBaseUrl,
        @DefaultValue("NONE") ObjectAcl objectAcl,
        @DefaultValue("false") boolean pathStyle,
        String accessKey,
        String secretKey) {

    /** {@code PUBLIC_READ} where storage serves images directly (locally); {@code NONE} behind a CDN (AWS). */
    public enum ObjectAcl {
        NONE,
        PUBLIC_READ
    }
}
