package com.ecommerce.platform.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class RequestLimits {

    enum Scope {
        GLOBAL_API, GLOBAL_WEBHOOK, IP, WEBHOOK_IP, PRINCIPAL_WRITE
    }

    record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private final RateLimitProperties settings;
    private final TimeMeter time;
    private final Cache<Key, Bucket> clients;
    private final Bucket api;
    private final Bucket webhooks;
    private final Map<Scope, Counter> rejected = new EnumMap<>(Scope.class);

    RequestLimits(RateLimitProperties settings, MeterRegistry metrics, TimeMeter time) {
        this.settings = settings;
        this.time = time;
        clients = Caffeine.newBuilder().maximumSize(settings.maxKeys())
                .expireAfterAccess(Duration.ofMinutes(2)).build();
        api = bucket(settings.instancePerMinute());
        webhooks = bucket(settings.instancePerMinute());
        for (Scope scope : Scope.values()) {
            rejected.put(scope, metrics.counter("ecom.http.throttled", "scope", scope.name().toLowerCase(Locale.ROOT)));
        }
    }

    Decision ip(String remoteAddress, boolean webhook) {
        Decision global = consume(webhook ? webhooks : api, webhook ? Scope.GLOBAL_WEBHOOK : Scope.GLOBAL_API);
        if (!global.allowed()) {
            return global;
        }
        Scope scope = webhook ? Scope.WEBHOOK_IP : Scope.IP;
        int capacity = webhook ? settings.webhookIpPerMinute() : settings.ipPerMinute();
        return consume(clients.get(new Key(scope, digest(remoteAddress)), ignored -> bucket(capacity)), scope);
    }

    Decision principal(String subject) {
        Scope scope = Scope.PRINCIPAL_WRITE;
        return consume(clients.get(new Key(scope, digest(subject)),
                ignored -> bucket(settings.principalWritesPerMinute())), scope);
    }

    long keyCount() {
        clients.cleanUp();
        return clients.estimatedSize();
    }

    private Bucket bucket(int capacity) {
        return Bucket.builder().withCustomTimePrecision(time)
                .addLimit(limit -> limit.capacity(capacity).refillGreedy(capacity, Duration.ofMinutes(1)))
                .build();
    }

    private Decision consume(Bucket bucket, Scope scope) {
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new Decision(true, 0);
        }
        rejected.get(scope).increment();
        long wait = probe.getNanosToWaitForRefill();
        long seconds = TimeUnit.NANOSECONDS.toSeconds(wait)
                + (wait % TimeUnit.SECONDS.toNanos(1) == 0 ? 0 : 1);
        return new Decision(false, Math.max(1, seconds));
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "unknown" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", unavailable);
        }
    }

    private record Key(Scope scope, String digest) {
    }
}