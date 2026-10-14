# ADR-028: Durable notification intents with at-least-once SMTP delivery

Status: Accepted, 2026-10-14 (portfolio milestone P1).

## Context

Kafka may redeliver order events. SMTP is external to the database transaction
and does not provide a transactional idempotency API.

## Problem

Sending email in the consumer blocks its partition and can lose email if offsets
advance before sending. Claiming exactly-once SMTP delivery hides the crash window
between SMTP acceptance and recording success.

## Options considered

1. Send inline, accepting partition stalls and coupling offsets to SMTP outages.
2. Record a durable intent and scheduled task atomically, then send asynchronously.
3. Add an email provider with an idempotent HTTP API, an additional external system.

## Decision

Use option 2 with the existing in-process task adapter. In one inbox transaction,
advance a per-order sequence, insert at most one notification per `(order_id,
kind)`, and schedule its send. Duplicate or older events do not schedule work.
Only then may the listener acknowledge Kafka.

The sender looks up email through the Customer module at send time; neither Kafka
payloads nor notification rows store it. SMTP uses five-second connection, read,
and write timeouts and a stable Message-ID. The task retries with existing backoff
up to 20 attempts, after which the row is visibly FAILED and an ID-only error is
logged. A missing email is explicitly SKIPPED. Delivery exceptions do not retain
raw SMTP errors that can contain recipients or credentials.

## Trade-offs

Database intent creation is deduplicated; email delivery is at least once. A crash
after SMTP acceptance can send the same message again. Stable Message-ID assists
diagnosis but is not a recipient-side deduplication guarantee. Already queued
milestones may arrive out of order after retries; this is not a strict mail-order
guarantee. Older Kafka events are suppressed when a newer sequence was applied.

## Consequences

SMTP outages do not stop Kafka consumption. Operators can distinguish PENDING,
SENT, SKIPPED, and FAILED delivery. Refund wording reflects the recorded refund
status, not an assumption that a requested refund settled. The local demo uses
Mailpit; production SMTP authentication and required TLS are deployment settings.

## Revisit when

Recipient-level exactly-once delivery, strict email ordering, or a production
provider with an idempotent send API becomes a requirement.