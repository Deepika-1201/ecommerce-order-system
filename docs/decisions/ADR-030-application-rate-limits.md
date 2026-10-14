# ADR-030: Bounded process-local application rate limits

Status: Accepted, 2026-10-14 (portfolio milestone P4).

## Context

The architecture calls for edge abuse protection and per-principal write limits.
The portfolio runs one application deployment without a shared edge or Redis.

## Problem

API requests can exhaust authentication/database resources. A per-client limiter
alone can grow without bound under identity churn, while using forwarded headers
without a trusted proxy boundary lets clients invent new addresses.

## Options considered

1. Implement distributed edge limits and a shared backing store now.
2. Add bounded process-local token buckets and document their limitations.
3. Leave application traffic unlimited until the cloud/edge deployment phase.

## Decision

Use option 2 with Bucket4j. Before bearer-token processing, meter all `/v1/`
requests by the direct socket peer plus a process-wide ceiling. Webhooks use a
separate lane so ordinary API traffic cannot consume their quota. After token
validation, authenticated write attempts also consume a per-subject bucket.
Probes and management endpoints are outside these application quotas.

Defaults per minute are 3,000 per API peer, 6,000 per webhook peer, 120 writes per
validated principal, and 20,000 per process for each API/webhook lane. Buckets
start full and refill greedily. Responses use `429` problem+json with a rounded-up
positive `Retry-After`; no business handler or idempotency operation runs after
the refusal. Invalid tokens still consume the pre-authentication quota.

Client keys are SHA-256 digests held in a maximum 10,000-entry Caffeine cache,
expiring after two minutes idle. Only fixed scope labels appear in rejection
metrics. This cache holds limiter state, not cached business reads (ADR-012).
Forwarded headers are not trusted; deployment behind a proxy needs a reviewed
trusted-peer/edge policy rather than enabling arbitrary client-supplied headers.

## Trade-offs

Limits reset on restart and multiply across instances. Cache eviction can reset
an individual client's allowance, but cannot reset its lane's process ceiling.
Shared NAT/proxy peers share a quota. These controls are not bot detection, a
distributed quota, or network-level denial-of-service protection. The defaults
are a configurable portfolio safety budget, not proof of the flash-sale SLO.

## Consequences

Real API limits are enabled by default. Existing integration fixtures disable
them except dedicated rate-limit tests, which use small quotas and a controlled
clock for refill checks. Load tests must record any rate-limit overrides and
classify 429 responses rather than count them as successful order placements.

## Revisit when

A trusted edge is deployed, multiple API instances need shared quotas, traffic
patterns are measured, or network/bot defenses are required.