package com.ecommerce.platform;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;

/**
 * What the webhook endpoints share (LLD §7.7, §8.8): a body read up to 64 KB, and the signature
 * {@code t=<unix seconds>,v1=<hex>[,v1=<hex>]}. The timestamp must be within the tolerance of now, either way, and one
 * {@code v1} must equal {@code HMAC-SHA256(secret, t + "." + body)} for one of the secrets, compared in constant time.
 */
public final class SignedWebhooks {

    public static final int MAX_BODY_BYTES = 64 * 1024;

    private final List<String> secrets;
    private final Duration tolerance;
    private final Clock clock;

    public SignedWebhooks(List<String> secrets, Duration tolerance, Clock clock) {
        this.secrets = List.copyOf(secrets);
        this.tolerance = tolerance;
        this.clock = clock;
    }

    /** The body, refused with {@code 413 payload_too_large} beyond 64 KB before the rest is read. */
    public static byte[] read(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw tooLarge();
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw tooLarge();
            }
            return body;
        }
    }

    public boolean verify(String header, byte[] body) {
        if (header == null || secrets.isEmpty()) {
            return false;
        }
        Long timestamp = null;
        List<byte[]> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            int equals = part.indexOf('=');
            if (equals < 0) {
                return false;
            }
            String name = part.substring(0, equals).strip();
            String value = part.substring(equals + 1).strip();
            try {
                if (name.equals("t")) {
                    timestamp = Long.parseLong(value);
                } else if (name.equals("v1")) {
                    signatures.add(HexFormat.of().parseHex(value));
                }
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        if (timestamp == null || signatures.isEmpty() || !recent(timestamp)) {
            return false;
        }
        byte[] signed = signedContent(timestamp, body);
        for (String secret : secrets) {
            byte[] expected = hmac(secret, signed);
            for (byte[] signature : signatures) {
                if (MessageDigest.isEqual(expected, signature)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean recent(long timestamp) {
        Instant signedAt;
        try {
            signedAt = Instant.ofEpochSecond(timestamp);
        } catch (DateTimeException e) {
            return false;
        }
        return Duration.between(signedAt, clock.instant()).abs().compareTo(tolerance) <= 0;
    }

    private static byte[] signedContent(long timestamp, byte[] body) {
        byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, content, 0, prefix.length);
        System.arraycopy(body, 0, content, prefix.length, body.length);
        return content;
    }

    private static byte[] hmac(String secret, byte[] content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(content);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "payload_too_large",
                "A webhook body may have at most " + MAX_BODY_BYTES + " bytes");
    }
}
