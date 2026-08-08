# Environment Variables & Secrets Management

This document is the **hub/entry point** for environment variables and secrets in FireMUD. It explains where to start, and then points you to:

- [Environment and Secrets Overview](environment-and-secrets-overview.md) - conceptual overview and operator quick reference.
- [Environment and Secrets Catalog](environment-and-secrets-catalog.md) - detailed environment variable catalog and rotation notes.

## Table of Contents

- [Overview](#overview)
- [Implementation Status](#implementation-status)
- [Operator & Architecture Overview](#operator--architecture-overview)
- [Environment Variable Catalog](#environment-variable-catalog)

---

## Overview

FireMUD relies on environment variables and Kubernetes Secrets to configure services across local development, CI, and shared or player-facing Kubernetes environments. This hub document is intentionally short; it exists to route readers to the right level of detail:

- Use the **overview** document for conceptual understanding and on-call triage.
- Use the **catalog** document when you need exact variable names, defaults, and rotation behavior.

Existing links to sections like “gRPC TLS Certificates” and “Authentication” still resolve here, but the detailed tables now live in the catalog. Each section below includes a short summary and links to the appropriate document.

---

## Implementation Status

The current checked-in runtime and executable preflight remain in the legacy Secret-backed JWKS mode. `jwt-jwks` is a Kubernetes `Secret`, the runtime still permits shared-HMAC and classpath-fallback drift, and `PREFLIGHT-JWKS-001` rejects a `ConfigMap` named `jwt-jwks`. These checks are current wiring evidence only; they do not establish player-facing JWT readiness.

The target-only `INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK` is not currently proved. Its interim environment consequence is an environment-unique `jwt-signing-keys` Secret mounted only into Account through `FIREMUD_AUTH_JWT_SECRET_PATH`, with validators consuming only the public `jwt-jwks` projection through `FIREMUD_AUTH_JWKS_PATH`. The packaged classpath JWKS fallback is permitted only in local/test environments.

Current hosted `pr-preview` manifests use preview-unique, pre-created signing-key and `jwt-jwks` Secrets; current preflight rejects a `ConfigMap`. Target hosted previews use preview-unique, Account-published `jwt-jwks` ConfigMap data delivered through `FIREMUD_AUTH_JWKS_PATH` to Account and every validator. Shared preview trust material across namespaces is not allowed. The custody and publication contract is canonical in [JWT and Token Contracts](../system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative).

---

## Operator & Architecture Overview

For a narrative explanation of how configuration and secrets flow through FireMUD, start with `environment-and-secrets-overview.md`. That document covers:

- The Operator Quick Reference for core PostgreSQL, Redis, and TLS/JWT variables.
- How `.env` is used in local development.
- How Kubernetes `ConfigMap` and `Secret` objects are used in shared or player-facing Kubernetes environments.
- The distinction between configuration and secrets.
- How cert-manager, Kubernetes Secrets, and watchers like `TlsCertificateWatcher`, `JwtSecretWatcher`, and `GrpcServerTlsReloader` fit together.

### Operator Quick Reference

The full operator quick reference (including tables for `SPRING_PROFILES_ACTIVE`, PostgreSQL, Redis, and secret rotation notes) now lives in `environment-and-secrets-overview.md` under “Operator Quick Reference”. This section is preserved as an anchor for existing links.

---

## Environment Variable Catalog

For the exhaustive list of environment variables, their defaults, and rotation notes, open `environment-and-secrets-catalog.md`. That document groups variables by subsystem (PostgreSQL, Redis, TLS, Authentication, Service Discovery, Observability, Asset Storage, Backup & Restore) and keeps detailed explanations close to their tables.

The subsections below are short stubs maintained to keep existing anchors working. Each one summarizes the topic and points to the catalog (and, where useful, the overview).

### Common Application Settings

Common application settings, including the `SPRING_PROFILES_ACTIVE` profile selector, are documented in `environment-and-secrets-catalog.md#common-application-settings`. See the overview’s Operator Quick Reference for the scoped rule: Kubernetes manifests and any shared environment must set `SPRING_PROFILES_ACTIVE` explicitly (do not rely on defaults).

### PostgreSQL Credentials

The PostgreSQL environment variables (host, port, database name, user, password) are described in detail in `environment-and-secrets-catalog.md#postgresql-credentials`. Operators can cross-check high-level expectations in the overview’s PostgreSQL quick-reference table.

### Redis Connection

Redis coordination and cache/rate‑limit variables, along with the precedence and safety rules that enforce separate roles, are documented in `environment-and-secrets-catalog.md#redis-coordination--cache`. Conceptual guidance on Coordination vs Cache/Rate‑Limit Redis lives in:

- [Environment and Secrets Overview](environment-and-secrets-overview.md#operator-quick-reference)
- [Redis architecture](../system-architecture-redis.md)
- [Redis usage and profiles](../system-architecture-redis-usage-and-profiles.md)

### gRPC TLS Certificates

Environment variables that configure gRPC TLS certificate paths and the TCP Proxy → Gateway WebSocket mTLS hop are documented in the [TLS and certificates catalog](./environment-and-secrets-catalog.md#tls--certificates). Conceptual TLS and rotation behavior is covered in:

- [Certificate management and watchers](environment-and-secrets-overview.md#certificate-management-and-watchers)
- [TLS termination for Gateway](../system-architecture-security.md#tls-termination-for-gateway)
- [Key and certificate rotation](../system-architecture-security.md#key-and-certificate-rotation)

### Authentication

JWT and session-related environment variables, including `FIREMUD_AUTH_JWT_SECRET`, `FIREMUD_AUTH_JWT_SECRET_PATH`, `FIREMUD_AUTH_JWKS_PATH`, `FIREMUD_AUTH_JWT_EXPIRATION_MS`, `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`, and `FIREMUD_AUTH_SESSION_EXPIRATION_MS`, are documented in the [authentication and JWT catalog](./environment-and-secrets-catalog.md#authentication--jwt). The authentication architecture and Telnet transport guidance are described in:

- [Authentication architecture](../system-architecture-authentication.md)
- [Security architecture](../system-architecture-security.md)

The catalog’s Authentication section documents the separate gameplay continuity-retention formula. Issued-token registry records instead use each token's actual `exp` plus the cleanup margin, as described in [JWT and Token Contracts](../system-architecture-jwt-and-token-contracts.md).
The target mode uses non-exportable signer custody: Account owns signing authority and lifecycle; the approved signer performs private-key operations; application workloads receive only the Account-published public JWKS, and no application workload receives or mounts private signing material. The signer-custody, publication, rotation, and convergence rules are canonical in [JWT and Token Contracts](../system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative). This hub records only environment and resource consequences; see [Implementation Status](#implementation-status) for current legacy, interim, and hosted-preview state.

### Service Discovery

Service discovery overrides based on the `FIREMUD_SERVICES_*` environment variables are documented in `environment-and-secrets-catalog.md#service-discovery`. This section explains how `ServiceEndpointsProperties` and Spring Cloud Gateway consume these overrides.

### Observability

Observability-related environment variables, including `OTEL_ENDPOINT` and Fluent Bit / Elasticsearch configuration, are documented in `environment-and-secrets-catalog.md#observability`. Additional details on tracing and logging live in:

- [Logging and monitoring architecture](../system-architecture-logging-monitoring.md)
- [Tracing architecture](../system-architecture-tracing.md)

### Asset Storage

Asset storage environment variables (for example `ASSET_STORE_ENDPOINT`, `ASSET_STORE_BUCKET`, `ASSET_STORE_REGION`, and access keys) are documented in `environment-and-secrets-catalog.md#asset-storage`. For operational runbooks related to asset storage, see:

- [Asset store runbook](../system-architecture-asset-store-runbook.md)

### Backup & Restore Variables

Variables used by backup and restore tooling (such as `PG_DUMP_BUCKET`, `PG_DUMP_ENDPOINT`, and `FIREMUD_K8S_NAMESPACE`) are documented in `environment-and-secrets-catalog.md#backup--restore-variables`. Backup schedules and retention policies are covered in:

- [Backup and recovery architecture](../system-architecture-backup-recovery.md)

### Additional Notes

Service-specific environment variables (such as SMTP credentials for the Account Service or `GAME_TICK_DURATION_MS` for the Game Session Service) remain documented in each microservice’s design README, for example:

- [Account Service environment variables](../microservices/account-service/README.md#environment-variables)
- [Game Session Service environment variables](../microservices/game-session-service/README.md#environment-variables)

Shared keys and patterns that apply across services are summarized in the catalog; per-service specifics stay close to their owning service docs.

---

## Related Documentation

- [Environment and Secrets Overview](environment-and-secrets-overview.md) - Conceptual overview and operator quick reference for environment variables and secrets.
- [Environment and Secrets Catalog](environment-and-secrets-catalog.md) - Detailed environment variable catalog and rotation notes.
- [Deployment environments](deployment-environments.md) - How dev/staging/production environments are structured.
- [Security architecture](../system-architecture-security.md) - Security and TLS architecture, including key and certificate rotation.
- [Redis architecture](../system-architecture-redis.md) - Redis architecture hub.
- [Authentication architecture](../system-architecture-authentication.md) - Authentication and authorization flows.
- [Redis usage and profiles](../system-architecture-redis-usage-and-profiles.md) - How Redis roles and profiles are wired in different environments.
- [Operations documentation](../../operations/README.md) - Operator procedures, including Redis session cleanup and credential handling.
