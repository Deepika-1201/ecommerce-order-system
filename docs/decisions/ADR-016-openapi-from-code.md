# ADR-016: The OpenAPI document is generated from code, committed and checked

- **Status:** Accepted (2026-10-02)
- **Date:** 2026-10-02
- **Related:** [Requirements Q16](../requirements.md#3-scope-decisions-from-phase-1), [ADR-013](ADR-013-technology-stack.md), [LLD §3.11](../low-level-design.md#311-openapi-document)

## Context

- The system offers a REST API described by OpenAPI (Q16). Its clients are demo scripts, a future storefront and the edge gateway's route configuration.
- The API grows in every phase from 3 to 9. A contract that silently drifts from the code is worse than none.
- Reviewers see API changes only if they appear in the diff.

## Problem

Where does the OpenAPI document come from, and how is it kept true?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| Design-first: hand-written YAML, with generated code or validation against it | The contract is reviewed before the code | Generator plumbing, or the same models defined twice; drifts unless validated in every test |
| Code-first, served at runtime only | Nothing to maintain | API changes are invisible in review; nobody notices drift |
| **Code-first, committed and checked** | The code is the single source; every API change shows up as a diff of `docs/api/openapi.json`; CI fails if the committed document is stale | Annotations in controllers; the generated document needs some curation (descriptions, security requirements) |

## Decision

- **springdoc-openapi 3.1** (the API-only starter, without Swagger UI) generates the document from the controllers and validation annotations.
- It is **served on the management port only** (`/actuator/openapi`), never on the public port.
- **`docs/api/openapi.json` is committed.** `OpenApiDocumentTests` generates the document and fails if it differs from the committed file. `./gradlew updateOpenApi` rewrites the file.
- **Compatibility:** within `/v1`, changes are additive only. Removing or renaming a field, or adding a required one, needs `/v2` ([api.md](../api.md)).

## Trade-offs

- Controllers carry a few documentation annotations (tags, security requirements).
- The document describes what the code does, not what was intended. Review of the committed diff is what catches unintended changes.

## Consequences

- Every pull request that changes the API also changes `docs/api/openapi.json`, or CI fails.
- Phase 7's contract tests use the gateway's own OpenAPI document; this one is for this system's clients.
