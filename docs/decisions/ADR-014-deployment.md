# ADR-014: Deployment on AWS with EKS and Terraform

- **Status:** Accepted (2026-10-02)
- **Date:** 2026-10-02
- **Related:** [Architecture §19](../architecture.md#19-deployment), [ADR-001](ADR-001-ecosystem-boundaries.md), [ADR-006](ADR-006-kafka.md)

## Context

- Accepted defaults: AWS in Mumbai (ap-south-1) alongside the gateway; environments created on demand and destroyed; Terraform tested without an AWS account; a real account only as an explicit step with a cost estimate.
- Payment-Orchestrator already deploys on ECS Fargate; the scheduler left EKS vs. ECS open.
- The workload is two stateless roles (api, worker) with different scaling signals: request rate, and consumer lag or outbox age.

## Problem

Where does the system run in production, and how is it provisioned?

## Options considered

| Criterion | **EKS (Kubernetes)** | ECS on Fargate | App Runner | EC2 with systemd |
|---|---|---|---|---|
| Learning value | High, and new to the portfolio | Already shown by the gateway | Low | Low |
| Scaling on custom signals | KEDA on Kafka lag and outbox age | Through CloudWatch metrics | Requests only | Manual |
| Local parity | kind or k3d | Weak | None | None |
| Cost | Control plane about $0.10/h, plus nodes | Per task | Per request | Cheapest |
| Operational burden | Highest: upgrades, add-ons | Low | Lowest | High: manual rollouts |

## Decision

**EKS in ap-south-1, provisioned with Terraform** (proposed).

- **Runtime:** EKS, with separate Deployments for api and worker. api scales on CPU and p99 latency; worker scales on Kafka lag and outbox age through KEDA.
- **Data:**
  - PostgreSQL on RDS Multi-AZ with a synchronous standby (RPO 0) and point-in-time recovery; Aurora is compared on cost in phase 17.
  - Kafka on MSK Serverless, provisioned MSK or Strimzi, chosen in phase 17 with a cost estimate ([ADR-006](ADR-006-kafka.md)).
  - S3 for objects; Secrets Manager and KMS for secrets and encryption.
- **Edge:** an ALB with WAF rate rules. The gateway's webhooks arrive through it, because its SSRF guard refuses private addresses ([ADR-001](ADR-001-ecosystem-boundaries.md)).
- **Infrastructure as code:** Terraform modules for network, EKS, RDS, MSK, S3, secrets and observability, tested with `terraform test` and mocked providers so that CI needs no AWS account.
- **Environments:** created from CI on demand and destroyed after use. Applying to a real account is a separate, explicit step with a cost estimate.
- **Releases:** rolling deploys with expand/contract migrations. Liveness checks don't touch dependencies; readiness checks do.

## Trade-offs

- EKS costs more, and is more to operate, than ECS for one system. It is chosen for portfolio breadth and KEDA-based scaling, a stated driver rather than a hidden one.
- Mocked-provider tests prove the Terraform's structure, not that AWS will accept it; only an applied environment does.

## Consequences

- Phase 17 produces the Terraform, manifests, CI/CD and `deployment.md`, with a cost estimate before anything is applied.
- Connectivity to the separately deployed gateway and scheduler (public webhook path, private API calls) is designed in phase 17.
