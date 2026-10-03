package com.ecommerce.payments.web;

import com.ecommerce.payments.gateway.PaymentsProperties;
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
import org.springframework.stereotype.Component;

/**
 * Checks {@code PG-Signature: t=<unix seconds>,v1=<hex>[,v1=<hex>]}: the timestamp within the tolerance of now, either
 * way, and one {@code v1} equal to {@code HMAC-SHA256(secret, t + "." + body)} for one of the configured secrets,
 * compared in constant time (LLD §7.7).
 */
@Component
class WebhookSignature {

    private final List<String> secrets;
    private final Duration tolerance;
    private final Clock clock;

    WebhookSignature(PaymentsProperties properties, Clock clock) {
        this.secrets = properties.gateway().webhookSecrets();
        this.tolerance = properties.webhookTolerance();
        this.clock = clock;
    }

    boolean verify(String header, byte[] body) {
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
}
