# Portfolio security essentials

Scope: P4 of [the portfolio milestone](portfolio-milestone.md), not a completed
production security review or OWASP certification. The original full phase plan
is unchanged.

## Application limits

[ADR-030](decisions/ADR-030-application-rate-limits.md) records the boundary and
trade-offs. Bucket4j token buckets refill continuously over one minute:

| Setting under `ecom.security.rate-limits` | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Enforce application quotas |
| `ip-per-minute` | 3000 | All ordinary `/v1/` requests from the direct socket peer |
| `principal-writes-per-minute` | 120 | Authenticated non-GET/HEAD/OPTIONS attempts per JWT subject |
| `webhook-ip-per-minute` | 6000 | Webhook requests per direct peer, separate from ordinary API traffic |
| `instance-per-minute` | 20000 | Process-wide ceiling for each API/webhook lane |
| `max-keys` | 10000 | Maximum cached client/subject buckets |

Spring's standard environment binding is supported, for example
`ECOM_SECURITY_RATE_LIMITS_PRINCIPAL_WRITES_PER_MINUTE=60`. Limits reject invalid
nonpositive settings at startup. Buckets start full, so these numbers are burst
capacities as well as sustained refill budgets. Idle entries expire after two
minutes, and client identifiers are hashed in the bounded cache.

The early peer/global limit runs before bearer-token validation. The subject
limit runs after JWT validation and before business handling, including
idempotency storage. Rejected requests return `429 application/problem+json`,
code `rate_limited`, `Cache-Control: no-store`, request ID, and `Retry-After` in
whole seconds rounded up. The generated OpenAPI contract documents this response.
Health probes and management scrapes do not consume the application quota.

`X-Forwarded-For` and `Forwarded` are not trusted. `server.forward-headers-strategy`
is explicitly `none`. A trusted proxy deployment requires a separate peer/edge
policy; do not turn on arbitrary forwarded-header trust to get per-user addresses.
Request rejection metrics use only five fixed `scope` labels, never raw IPs,
subjects, tokens, URLs, or idempotency keys.

These limits reset on restart and multiply across replicas. Cache eviction can
reset one client's allowance but not its lane's process ceiling. NAT/proxy users
share the peer limit. This is not DDoS or bot protection and does not implement the
full flash-sale waiting room. Benchmarks must record overrides and classify 429s.

## Sensitive diagnostics

Authentication logs contain only exception type. Asynchronous retry records and
worker/unexpected-HTTP failure logs retain the root exception class and a
validated SQLSTATE, not exception messages, SQL text, failed row values, stack
causes, or suppressed messages. P3 correlation IDs and generated work IDs link
these categories to the durable workflow for restricted operational diagnosis.

The payment and fulfillment configuration records mask their existing API/webhook
secrets. Object-storage configuration now masks access/secret keys and avoids
rendering endpoints that may contain embedded credentials. No request-body,
Authorization-header, or HTTP-client wire logging is enabled by the application.
Do not enable third-party wire/debug logging around credentials in a deployment.

This prevents new unsafe diagnostic copies; it does not retroactively redact
historic database errors or logs created by older versions. Treat existing
operational stores as sensitive, review access, and apply retention/cleanup under
an explicit policy. No historical customer/order records are silently deleted.

## Dependency and secret gates

CI uses Trivy 0.76.0 with its setup action pinned to the release commit. The build
job scans the real boot jar with `rootfs`, not a source-only filesystem scan; the
container job scans the exact application image that ran the demos. The report
policy requires the inventory to include Tomcat, Jackson 3, and Bucket4j; image
reports must also contain operating-system packages. Empty inventory is a failure.

Both gates fail on fixable HIGH/CRITICAL findings. Unfixed HIGH/CRITICAL findings
remain in the report and printed count for explicit review; they are not evidence
of a clean artifact. No vulnerability ignore file is honored. Scanner failures,
malformed reports, and missing inventories fail the script. Dependency reports
are retained as CI artifacts for seven days.

Source is also scanned for recognized secret patterns, excluding only Git and
generated/build output. Secret reports are temporary, are never uploaded, and
the policy output does not echo matched secret values. A clean pattern scan is
not proof that no unknown/custom-format secret exists, and does not scan Git
history or another repository.

Local commands require JDK 25, Node 18+, Docker for the image export, and Trivy:

```sh
./gradlew bootJar
node scripts/check-security-report.mjs --self-test
bash scripts/check-security.sh jar
bash scripts/check-security.sh secrets
bash scripts/check-security.sh image ecommerce-order-system-app
```

Set `TRIVY` to the scanner executable path when it is not on `PATH`. Reports are
under `build/reports/security`. Build the image before its scan; a local jar scan
does not prove that an older running image has the same dependency versions.

The initial scan inventoried 150 dependencies and found 14 HIGH/CRITICAL entries.
Compatible fixes pin Tomcat 11.0.25, Jackson 3.1.7 and Jackson 2.21.7 BOMs, and LZ4
1.11.4. The rebuilt jar had zero HIGH/CRITICAL findings with the scanner database
downloaded on 2026-10-14. Findings can change as advisory databases evolve; rerun
the gates and inspect the actual report rather than relying on this historical
result. Revisit overrides when Spring Boot manages fixed versions itself.

The first rebuilt image also contained three fixable HIGH Go-runtime findings
inside `/usr/bin/pebble`, inherited from the Ubuntu-based JRE image. The final
image removes that unused supervisor: its entrypoint starts Java directly as
the unprivileged `app` user. This is removal of unused vulnerable code, not a
scanner ignore or a change to the application's process supervision contract.

## Deployment boundaries

- Compose/Keycloak users, passwords, S3 keys, and simulator webhook secrets are
  explicit local demonstration fixtures, not production credentials. Keep real
  secrets out of source and image layers; supply them through the deployment's
  secret mechanism and rotate anything exposed.
- Real payment-gateway settings already require a merchant API key and webhook
  secrets when a gateway URL is configured. Webhook HMAC, timestamp tolerance,
  replay/inbox handling, access control, and customer isolation remain tested.
- The observability overlay binds management, Prometheus, and Grafana to loopback.
  Management remains unauthenticated inside that local network. A deployment needs
  network controls and appropriate authentication; do not expose those ports.
- HTTPS termination, managed secret rotation, encrypted storage/backups, central
  log retention, distributed quotas, and perimeter protection remain deployment
  responsibilities. The local volume layout does not claim encrypted storage.
- Cookie login is not used by this API: it is a stateless bearer-token resource
  server. The Keycloak local password grant is a demonstration convenience, not a
  recommended production browser authentication flow.
- The application jar/image is scanned. Third-party local service images and the
  independent gateway/scheduler repositories are not certified by this gate.