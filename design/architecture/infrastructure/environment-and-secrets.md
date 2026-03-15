# Environment Variables & Secrets Management

This document is the **hub/entry point** for environment variables and secrets in FireMUD. It explains where to start, and then points you to:

- `environment-and-secrets-overview.md` – conceptual overview and operator quick reference.
- `environment-and-secrets-catalog.md` – detailed environment variable catalog and rotation notes.

## Table of Contents

- [Overview](#overview)
- [Operator & Architecture Overview](#operator--architecture-overview)
- [Environment Variable Catalog](#environment-variable-catalog)

---

## Overview

FireMUD relies on environment variables and Kubernetes Secrets to configure services across local development, CI, and production. This hub document is intentionally short; it exists to route readers to the right level of detail:

- Use the **overview** document for conceptual understanding and on-call triage.
- Use the **catalog** document when you need exact variable names, defaults, and rotation behavior.

Existing links to sections like “gRPC TLS Certificates” and “Authentication” still resolve here, but the detailed tables now live in the catalog. Each section below includes a short summary and links to the appropriate document.

---

## Operator & Architecture Overview

For a narrative explanation of how configuration and secrets flow through FireMUD, start with `environment-and-secrets-overview.md`. That document covers:

- The Operator Quick Reference for core PostgreSQL, Redis, and TLS/JWT variables.
- How `.env` is used in local development.
- How Kubernetes `ConfigMap` and `Secret` objects are used in production.
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

- `environment-and-secrets-overview.md#operator-quick-reference`
- `../system-architecture-redis.md`
- `../system-architecture-redis-usage-and-profiles.md`

### gRPC TLS Certificates

Environment variables that configure gRPC TLS certificate paths and the TCP Proxy → Gateway WebSocket mTLS hop are documented in `environment-and-secrets-catalog.md#tls--certificates`. Conceptual TLS and rotation behavior is covered in:

- `environment-and-secrets-overview.md#certificate-management--watchers`
- `../system-architecture-security.md#tls-termination-for-gateway`
- `../system-architecture-security.md#key-and-certificate-rotation`

### Authentication

JWT and session-related environment variables, including `FIREMUD_AUTH_JWT_SECRET`, `FIREMUD_AUTH_JWT_SECRET_PATH`, `FIREMUD_AUTH_JWT_EXPIRATION_MS`, `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`, and `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP`, are documented in `environment-and-secrets-catalog.md#authentication--jwt`. The authentication architecture and Telnet 2FA/plaintext rules are described in:

- `../system-architecture-authentication.md`
- `../system-architecture-security.md`

The catalog’s Authentication section also documents how the JWT expiration and session safety margin combine into a single derived session TTL, including operational guidance for tightening or relaxing this window.
For player-facing environments (`hobby-self-hosted`, staging, production), use file-mounted JWT key material via `FIREMUD_AUTH_JWT_SECRET_PATH`; inline-only JWT secrets are for local/dev and explicitly ephemeral stacks.

### Service Discovery

Service discovery overrides based on the `FIREMUD_SERVICES_*` environment variables are documented in `environment-and-secrets-catalog.md#service-discovery`. This section explains how `ServiceEndpointsProperties` and Spring Cloud Gateway consume these overrides.

### Observability

Observability-related environment variables, including `OTEL_ENDPOINT` and Fluent Bit / Elasticsearch configuration, are documented in `environment-and-secrets-catalog.md#observability`. Additional details on tracing and logging live in:

- `../system-architecture-logging-monitoring.md`
- `../system-architecture-tracing.md`

### Asset Storage

Asset storage environment variables (for example `ASSET_STORE_ENDPOINT`, `ASSET_STORE_BUCKET`, `ASSET_STORE_REGION`, and access keys) are documented in `environment-and-secrets-catalog.md#asset-storage`. For operational runbooks related to asset storage, see:

- `../system-architecture-asset-store-runbook.md`

### Backup & Restore Variables

Variables used by backup and restore tooling (such as `PG_DUMP_BUCKET`, `PG_DUMP_ENDPOINT`, and `FIREMUD_K8S_NAMESPACE`) are documented in `environment-and-secrets-catalog.md#backup--restore-variables`. Backup schedules and retention policies are covered in:

- `../system-architecture-backup-recovery.md`

### Additional Notes

Service-specific environment variables (such as SMTP credentials for the Account Service or `GAME_TICK_DURATION_MS` for the Game Session Service) remain documented in each microservice’s design README, for example:

- `../microservices/account-service/README.md#environment-variables`
- `../microservices/game-session-service/README.md#environment-variables`

Shared keys and patterns that apply across services are summarized in the catalog; per-service specifics stay close to their owning service docs.

---

## Related Documentation

- `environment-and-secrets-overview.md` – Conceptual overview and operator quick reference for environment variables and secrets.
- `environment-and-secrets-catalog.md` – Detailed environment variable catalog and rotation notes.
- `deployment-environments.md` – How dev/staging/production environments are structured.
- `../system-architecture-security.md` – Security and TLS architecture, including key and certificate rotation.
- `../system-architecture-redis.md` – Redis architecture hub.
- `../system-architecture-authentication.md` – Authentication and authorization flows.
- `../system-architecture-redis-usage-and-profiles.md` – How Redis roles and profiles are wired in different environments.
- `../system-architecture-runbooks.md` – Operational runbooks, including Redis session cleanup and rotation jobs.
