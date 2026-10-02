# ADR-015: S3Proxy for local and test object storage, instead of MinIO

- **Status:** Accepted (2026-10-02)
- **Date:** 2026-10-02
- **Related:** [ADR-013](ADR-013-technology-stack.md) (amended by this ADR), [ADR-014](ADR-014-deployment.md), [LLD §3.8](../low-level-design.md#38-product-images)

## Context

- Product images live in object storage and are uploaded through pre-signed URLs (FR-CAT5). AWS uses S3 ([ADR-014](ADR-014-deployment.md)). Locally and in tests an S3-compatible server stands in for it.
- [ADR-013](ADR-013-technology-stack.md) chose MinIO for `docker compose`. On 2026-10-02, MinIO's community repository is archived (since April 2026) and the community edition is distributed as source only: no binaries, no images, no maintained releases. Its free successor, AIStor Free, has its own license and registration.
- Tests must run without Docker ([ADR-013](ADR-013-technology-stack.md)).
- A spike on 2026-10-02 ran S3Proxy 4.1.1 with the AWS SDK for Java 2.55. A pre-signed `PUT` signs `content-length`, `content-type` and `x-amz-acl`. A wrong size, a wrong type, a missing ACL header or an expired URL each got `403`; the matching upload got `200`. `HEAD` returned the real size and type, and a `public-read` object could be read anonymously.

## Problem

Which S3-compatible server runs in `docker compose` and in tests?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| MinIO, last community image | Familiar, full-featured | Archived: no fixes, including security fixes |
| AIStor Free | Maintained by MinIO | Separate license and registration; heavier |
| **S3Proxy** (Apache 2.0) | Releases every few weeks; verifies Signature V4 and pre-signed URLs; in-memory or filesystem storage; Docker image for amd64 and arm64; a self-contained jar tests can start without Docker | No bucket policies or lifecycle rules; object ACLs limited to private and public-read; a 56 MB jar |
| SeaweedFS, Garage | Maintained, full object stores | Tests would need Docker or native binaries; more setup |
| Adobe S3Mock | Built for tests | Does not verify signatures, so pre-signed URL tests would prove nothing; it is a Spring Boot application itself |
| A fake inside the tests | Fast | Proves nothing about S3 behavior |

## Decision

**S3Proxy 4.1.1.**

- **Tests** start the self-contained jar (`jar-with-dependencies`, resolved by Gradle) in a separate JVM, in memory, with Signature V4 authentication. No Docker is needed, and S3Proxy's own dependencies (Jackson 3.2, cloud SDKs) never meet the application's classpath.
- **`docker compose`** runs `andrewgaul/s3proxy:4.1.1` with filesystem storage.
- **The application only speaks the S3 API**, through the AWS SDK for Java v2: S3 in AWS, S3Proxy elsewhere. Nothing in the main code is specific to S3Proxy.

## Trade-offs

- **AWS-only behavior is tested later.** S3Proxy has no bucket policies, lifecycle rules or CloudFront, so blocked public access, CloudFront with origin access control and lifecycle rules are covered by phase 17.
- **Images are served differently.** Locally, uploaded images are `public-read` objects. In AWS, ACLs are disabled (bucket owner enforced) and CloudFront serves the bucket. The ACL header is configuration (`ecom.media.object-acl`).
- **Download size:** a 56 MB test dependency, cached by Gradle and by CI.

## Consequences

- [ADR-013](ADR-013-technology-stack.md)'s local environment uses S3Proxy instead of MinIO, and [architecture §19](../architecture.md#19-deployment) is updated.
- If S3Proxy stops being maintained, the switch is limited to the test launcher and the compose service, because the application depends only on the S3 API.
