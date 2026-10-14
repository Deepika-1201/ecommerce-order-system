package com.ecommerce.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RequestLimitsTests {

    private final AtomicLong nanos = new AtomicLong();
    private final TimeMeter time = new TimeMeter() {
        @Override
        public long currentTimeNanos() {
            return nanos.get();
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }
    };
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    @Test
    void capacityAndGreedyRefillProduceAnExactRetryAfter() {
        RequestLimits limits = limits(2, 2, 3, 100, 100);
        assertThat(limits.ip("127.0.0.1", false).allowed()).isTrue();
        assertThat(limits.ip("127.0.0.1", false).allowed()).isTrue();
        assertThat(limits.ip("127.0.0.1", false)).isEqualTo(new RequestLimits.Decision(false, 30));

        nanos.addAndGet(Duration.ofSeconds(29).toNanos());
        assertThat(limits.ip("127.0.0.1", false)).isEqualTo(new RequestLimits.Decision(false, 1));
        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        assertThat(limits.ip("127.0.0.1", false).allowed()).isTrue();
    }

    @Test
    void webhookAndPrincipalQuotasAreIndependent() {
        RequestLimits limits = limits(1, 1, 2, 100, 100);
        assertThat(limits.ip("shared-address", false).allowed()).isTrue();
        assertThat(limits.ip("shared-address", false).allowed()).isFalse();
        assertThat(limits.ip("shared-address", true).allowed()).isTrue();
        assertThat(limits.ip("shared-address", true).allowed()).isTrue();
        assertThat(limits.ip("shared-address", true).allowed()).isFalse();
        assertThat(limits.principal("first").allowed()).isTrue();
        assertThat(limits.principal("first").allowed()).isFalse();
        assertThat(limits.principal("second").allowed()).isTrue();
    }

    @Test
    void aBoundedCacheCannotBypassTheAggregateCeilingThroughIdentityChurn() {
        RequestLimits limits = limits(10, 10, 10, 4, 2);
        for (int index = 0; index < 4; index++) {
            assertThat(limits.ip("address-" + index, false).allowed()).isTrue();
        }
        assertThat(limits.keyCount()).isLessThanOrEqualTo(2);
        assertThat(limits.ip("another-address", false).allowed()).isFalse();
        assertThat(limits.ip("webhook-address", true).allowed()).isTrue();
        assertThat(metrics.get("ecom.http.throttled").tag("scope", "global_api").counter().count()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsCannotConsumeTheSameToken() throws Exception {
        RequestLimits limits = limits(3, 3, 3, 100, 100);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> requests = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < 16; index++) {
                requests.add(executor.submit(() -> {
                    start.await();
                    return limits.ip("one-address", false).allowed();
                }));
            }
            start.countDown();
            int allowed = 0;
            for (Future<Boolean> request : requests) {
                if (request.get(5, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(3);
        }
    }

    @Test
    void metricsHaveOnlyFixedScopeLabelsAndNeverClientIdentities() {
        RequestLimits limits = limits(1, 1, 1, 100, 100);
        limits.principal("private-customer@example.test");
        limits.principal("private-customer@example.test");

        assertThat(metrics.getMeters()).hasSize(5).allSatisfy(meter -> {
            assertThat(meter.getId().getTags()).extracting(tag -> tag.getKey()).containsExactly("scope");
            assertThat(meter.getId().toString()).doesNotContain("private-customer", "example.test");
        });
    }

    @Test
    void productionDefaultsAreEnabledAndInvalidQuotasFailBinding() {
        var runner = new ApplicationContextRunner().withUserConfiguration(Binding.class);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            RateLimitProperties settings = context.getBean(RateLimitProperties.class);
            assertThat(settings.enabled()).isTrue();
            assertThat(settings.ipPerMinute()).isEqualTo(3000);
            assertThat(settings.principalWritesPerMinute()).isEqualTo(120);
            assertThat(settings.webhookIpPerMinute()).isEqualTo(6000);
            assertThat(settings.instancePerMinute()).isEqualTo(20000);
            assertThat(settings.maxKeys()).isEqualTo(10000);
        });
        for (String invalid : List.of("ip-per-minute=0", "principal-writes-per-minute=-1",
                "webhook-ip-per-minute=0", "instance-per-minute=0", "max-keys=0", "max-keys=100001")) {
            runner.withPropertyValues("ecom.security.rate-limits." + invalid)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RateLimitProperties.class)
    static class Binding {
    }

    private RequestLimits limits(int ip, int writes, int webhook, int instance, int keys) {
        return new RequestLimits(new RateLimitProperties(true, ip, writes, webhook, instance, keys), metrics, time);
    }
}