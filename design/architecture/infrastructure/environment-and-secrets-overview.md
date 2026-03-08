# Environment Variables & Secrets Overview

This document explains how configuration values and sensitive secrets are supplied to FireMUD services across FireMUD environment classes. It is the conceptual overview and operator starting point for environment variables and secrets.

For the full catalog of environment variables (including defaults and detailed rotation notes), see `environment-and-secrets-catalog.md`. For a minimal entry point and links, see `environment-and-secrets.md`.

## Table of Contents

- [Operator Quick Reference](#operator-quick-reference)
- [Local Development vs Production](#local-development-vs-production)
- [Secret Governance Tiers](#secret-governance-tiers)
- [Configuration vs Secrets](#configuration-vs-secrets)
- [Certificate Management & Watchers](#certificate-management--watchers)
- [How to Use the Catalog](#how-to-use-the-catalog)
- [Related Documentation](#related-documentation)

---

## Operator Quick Reference

This section summarizes the **most important environment variables and rotation behaviors** for on‑call operators. Refer to the catalog document for the full list of variables and detailed defaults.

### Core Profiles

| Variable | Purpose | Notes |
| -------- | ------- | ----- |
| `SPRING_PROFILES_ACTIVE` | Spring profile (`dev` or `prod`) | Kubernetes manifests and any shared environment must set this explicitly (do not rely on defaults). |

### PostgreSQL (Authoritative Data)

| Variable | Purpose | Rotation / Safety Notes |
| -------- | ------- | ----------------------- |
| `FIREMUD_POSTGRES_HOST` / `FIREMUD_POSTGRES_PORT` | PostgreSQL endpoint | Backed by Kubernetes `Service` / DNS; changes should be coordinated with DB migrations and failover runbooks. |
| `FIREMUD_POSTGRES_DB` | Database name | Typically `firemud` for all environments. |
| `FIREMUD_POSTGRES_USER` / `FIREMUD_POSTGRES_PASSWORD` | DB credentials | Stored in Kubernetes `Secret`; rotate via secret updates and allow pods to restart or reload as needed. |

### Redis (Coordination vs Cache/Rate‑Limit)

| Variable | Purpose | Rotation / Safety Notes |
| -------- | ------- | ----------------------- |
| `FIREMUD_REDIS_COORD_HOST` / `FIREMUD_REDIS_COORD_PORT` | Coordination Redis (ticks, sessions, locks) | **Must point to a deployment isolated from caches.** Changing host/port is a coordinated operation; follow Redis runbooks for resets and failover. |
| `FIREMUD_REDIS_CACHE_HOST` / `FIREMUD_REDIS_CACHE_PORT` | Cache/Rate‑Limit Redis | May be scaled or replaced more aggressively; still must remain separate from Coordination Redis. |

Key rules:

- **Never** point Coordination Redis and Cache/Rate‑Limit Redis at the same instance in any non-ephemeral environment (including local development, staging, and production).
- Player‑facing environments must use **distinct logical Redis deployments** for coordination and cache/rate‑limit roles.
- Truly ephemeral CI/preview stacks may collapse roles into a single Redis instance only when explicitly documented as ephemeral and not used to validate coordination SLOs; see `system-architecture-redis-usage-and-profiles.md#environment-mappings`.

Operational notes:

- Coordination Redis hosts both gameplay session bindings (`session:game:*`) and control-plane JWT allowlist entries (`session:auth:*`) as described in `system-architecture-authentication.md`.

### TCP Proxy → Gateway Bridge (Telnet)

The TCP Proxy Service uses `GATEWAY_WS_URL` to connect to Spring Cloud Gateway over WebSocket. This endpoint is configured independently of the `FIREMUD_SERVICES_*` service discovery overrides: changing `FIREMUD_SERVICES_SPRING_CLOUD_GATEWAY_SERVICE` does not automatically update the Telnet bridge. Operators must keep `GATEWAY_WS_URL` aligned with the Gateway’s intended internal WebSocket listener for the environment as described in `system-architecture-protocol-bridging.md` and the TCP Proxy Service design.
In all player-facing classes (`hobby-self-hosted`, staging, production), this alignment is treated as a deployment preflight invariant: release tooling and readiness checks should fail when `GATEWAY_WS_URL` does not target the expected internal listener for the active environment.

### Secrets & Certificates

- All sensitive values (DB passwords, JWT signing keys, TLS certificates) live in Kubernetes `Secret` objects.
- TLS certificates are rotated automatically by **cert-manager**. JWT signing keys are rotated by dedicated Kubernetes Jobs (for example `jwt-rotation`); in production this Job is treated as an operator-run template rather than an unattended cadence (see `system-architecture-security.md#jwt-key--jwks-rotation-workflow`).
- Services reload TLS and JWT material via shared utilities:
  - `TlsCertificateWatcher`
  - `JwtSecretWatcher`
  - `GrpcServerTlsReloader`

Operator actions:

- For manual secret rotation, update the relevant Kubernetes `Secret` and confirm:
  - Pods reload configuration (via watcher) or are restarted according to the runbook.
  - gRPC clients/servers establish new mTLS sessions without errors.

For full descriptions of the variables and their defaults, open `environment-and-secrets-catalog.md`.

---

## Local Development vs Production

### Local Development

- Environment variables are loaded from a `.env` file when running `./gradlew devUp`.
- The sample file `.env.sample` lists the variables described in the catalog with default credentials for PostgreSQL and Redis. Copy this file to `.env` and adjust values as needed; `.env` is git‑ignored so real credentials remain local.
- Docker Compose passes these variables to each container so Spring Boot can connect to the databases.
- Secrets such as JWT signing keys are not required in development; random keys are generated on startup.

### Production

- Kubernetes `ConfigMap` objects store non‑secret configuration values like host names or feature flags.
- Sensitive values (database passwords, JWT signing keys, TLS certificates) are stored in Kubernetes `Secret` objects.
- TLS certificates are issued by **cert-manager** and rotated automatically; services reload updated certificates using `TlsCertificateWatcher` / `GrpcServerTlsReloader`.
- JWT signing keys are stored in Secrets and rotated by dedicated Kubernetes Jobs (for example `jwt-rotation`) that update both the signing key Secret and the JWKS document served by the Account Service. See `system-architecture-security.md#jwt-key--jwks-rotation-workflow` for details.
- In player-facing environments (`hobby-self-hosted`, staging, production), JWT signing material must be consumed from a mounted file via `FIREMUD_AUTH_JWT_SECRET_PATH`; inline-only JWT secret configuration is non-compliant in those environments.
- Database credentials are stored in Secrets and rotated via explicit operational Jobs and runbooks (for example `db-credential-rotation` in `system-architecture-backup-recovery.md#post-restore-secret-hardening`); there is no fully automatic cadence today.
- The manifests in `k8s/base/` demonstrate loading Secrets and ConfigMaps via `envFrom` so that services receive the same variables as in development.
- Services reload TLS certificates for gRPC client and server channels and JWT secrets when these Secrets update using the `TlsCertificateWatcher`, `JwtSecretWatcher`, and `GrpcServerTlsReloader` utilities from the shared library.
- **Kubernetes Secrets** is the chosen mechanism for storing all sensitive credentials. External secret stores like Vault are out of scope at this stage.

---

## JWT Trust Model by Environment

JWT signing key and JWKS behavior differs slightly by environment to balance safety and operational complexity:

- **Development**
  - Environment variables are loaded from `.env`, and secrets such as JWT signing keys may be generated randomly on startup for convenience.
  - Cross-service JWT validation is best-effort when random keys are used; operators should not assume that tokens remain valid across service restarts unless a persistent signing key Secret is configured.
- **Staging / Non-production clusters**
  - Recommended to mirror production: use a persistent `jwt-signing-keys` Secret and `jwt-jwks` Secret, with the Account Service serving JWKS from the mounted file.
  - The `jwt-rotation` CronJob may be enabled on a low-frequency schedule to exercise the rotation path.
- **Production**
  - Required to use a persistent `jwt-signing-keys` Secret and JWKS document; JWKS is the canonical trust source for all validating services.
  - The `jwt-rotation` CronJob is defined with `spec.suspend: true` and is triggered explicitly by operators as part of a rotation runbook.

For guidance on how to respond to a suspected JWT signing key compromise (as opposed to planned rotation), see the “JWT Key Compromise Response” section in `system-architecture-security.md`.

---

## Secret Governance Tiers

FireMUD applies a tiered governance model so the highest-risk credentials have explicit controls even while Kubernetes Secrets remains the storage backend:

- **Tier A (high impact)**
  - Includes JWT signing keys/JWKS, PostgreSQL application/admin credentials, object-store credentials used for backup and restore, and operator-only control-plane credentials.
  - Required controls:
    - Defined rotation SLA per credential family (for example monthly or quarterly based on risk/compliance needs).
    - Alerting on credential age or missed rotation windows.
    - Explicit incident-response runbook links for emergency rotation.
    - Post-restore validation before the environment is considered player-facing.
- **Tier B (lower impact)**
  - Includes lower-risk integration credentials and service-level secrets where compromise blast radius is narrower.
  - Rotation cadence may be manual and less frequent, but owners must still document current age, intended cadence, and emergency rotation path.

This model is designed to reduce risk now without introducing unnecessary operational complexity. If operational burden or compliance requirements increase, environments can migrate selected Tier A credential classes to external secret orchestration later without changing application-level environment variable contracts.

### Secret Compliance Controls

Tier A controls must be measurable, not policy-only. Each player-facing environment (`hobby-self-hosted`, staging, production) maintains a secret compliance record with:

- Credential class owner (for example platform/security owner role).
- Maximum credential age target.
- Alert rule ID for age/SLA breach.
- Last successful rotation evidence reference.

The secret compliance record must be stored as versioned environment metadata in Git (for example under `design/operations/secret-compliance/<environment>.yaml`) so CI and reporting jobs can detect missing or stale records before promotion.
Each credential record must also point to immutable rotation evidence in-repo (`evidenceRef` + `evidenceKey`) whose referenced payload includes an `immutableArtifactId` value (for example a job/run identifier that embeds a content digest such as `sha256:...`). Promotion/DR-readiness checks must fail when evidence is missing or cannot be tied to an immutable artifact identifier.

Minimum credential classes to track (canonical record keys shown in parentheses):

| Credential Class | Required Evidence |
| --- | --- |
| JWT signing keys / JWKS (`jwt-signing-keys-jwks`) | Last rotation timestamp, key IDs, rotation job outcome |
| PostgreSQL application credentials (`postgres-application-credentials`) | Last rotation timestamp, rollout restart completion evidence |
| Backup/object-store credentials (`backup-object-store-credentials`) | Validation of expected bucket/endpoint and non-production isolation |
| Operator credentials (`operator-credentials`) | Last issuance/rotation timestamp and revocation traceability |

If a required compliance record is missing or stale, the environment is treated as non-compliant for promotion and DR-readiness reporting.

Promotion gating policy:

- `production`: non-compliant secret records are a hard block for promotion.
- `staging`: non-compliant records are warnings through **June 30, 2026** and become a hard promotion gate on **July 1, 2026**.
- `hobby-self-hosted`: operators must validate records before opening player-facing traffic.

Promotion-evidence exception:

- Even before **July 1, 2026**, a staging deployment record that will be referenced by a production promotion attestation must show `secretComplianceStatus=pass` at deployment time and include a `secretComplianceEvidenceRef`. A warning-only staging deployment may still exist for playtesting, but it is not eligible to produce production promotion evidence.

---

## Configuration vs Secrets

FireMUD distinguishes between **configuration** and **secrets** but delivers both through environment variables so services can be configured consistently across local development, CI, and Kubernetes deployments.

- **Configuration (non‑secret)**:
  - Examples: service hostnames, ports, feature flags, environment profile (`SPRING_PROFILES_ACTIVE`).
  - Typically sourced from `.env` in development and from Kubernetes `ConfigMap` objects in production.
  - Changes may be applied by restarting pods or reloading configuration where supported.
- **Secrets**:
  - Examples: database passwords, JWT signing keys, TLS private keys, S3 access keys.
  - Always stored in Kubernetes `Secret` objects in production.
  - May be generated or randomized automatically for local development.
  - Rotation is performed by updating the backing Secret via the appropriate automation:
    - TLS certificates are rotated automatically by cert-manager.
    - JWT signing keys are rotated by Jobs such as `jwt-rotation` that update Secrets and JWKS and are picked up by `JwtSecretWatcher`.
    - Database credentials are rotated by Jobs such as `db-credential-rotation` that update the relevant Secrets and restart consumers.
  - Direct, ad hoc edits to Secrets should be treated as emergency measures only and reconciled back into the appropriate Job/runbook flow so future rotations remain automated and repeatable.

Shared libraries support overriding default settings with environment variables using the `FIREMUD_` prefix (for example `FIREMUD_POSTGRES_HOST`, `FIREMUD_POSTGRES_PORT`). Each service merges these variables with its own `application.yml` profile.

The catalog document groups these variables by subsystem (PostgreSQL, Redis, TLS, Authentication, Service Discovery, Observability, Asset Storage, Backup & Restore) and documents their defaults.

---

## Certificate Management & Watchers

Mutual TLS protects all internal service-to-service traffic. Certificates are normally provisioned by **cert-manager** and mounted from Kubernetes Secrets. These certificates secure:

- All gRPC calls between services
- Any internal WebSocket bridges that require mTLS (for example, the TCP Proxy Service connecting to Spring Cloud Gateway over `wss://`)

A sample `Certificate` manifest is provided at `k8s/base/firemud-grpc-certificate.yaml`.

Key concepts:

- **cert-manager** issues and renews TLS certificates, writing them into Kubernetes Secrets.
- Services mount these Secrets as files (for example under `/tls`) and expose their locations via environment variables such as:
  - `FIREMUD_GRPC_CERT_CHAIN_PATH`
  - `FIREMUD_GRPC_PRIVATE_KEY_PATH`
  - `FIREMUD_GRPC_CA_CERT_PATH`
  - `FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH`
  - `FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH`
  - `FIREMUD_GATEWAY_WS_CA_CERT_PATH`
- **Watchers** in the shared libraries monitor these files and reload credentials without restarting services:
  - `TlsCertificateWatcher` watches certificate and key paths and triggers reloads.
  - `GrpcServerTlsReloader` hot‑reloads gRPC server credentials when Secrets change.
  - `JwtSecretWatcher` monitors the JWT secret file referenced by `FIREMUD_AUTH_JWT_SECRET_PATH` and reloads signing keys.

> Note: Certificate files should be loaded from the filesystem rather than packaged inside the application. Avoid `classpath:` URIs so that TLS materials can be mounted securely via volumes or Secrets.

The exact environment variables and defaults for these paths are documented in the TLS and Authentication sections of `environment-and-secrets-catalog.md`.

---

## How to Use the Catalog

Use this overview when you need to:

- Understand how configuration and secrets flow through FireMUD across environments.
- Confirm high-level rules for PostgreSQL, Redis, TLS, JWTs, and cert-manager behavior.
- Triage incidents where configuration or secrets may be misconfigured at a conceptual level.

Open `environment-and-secrets-catalog.md` when you need to answer **precise variable questions**, for example:

- “What does `FIREMUD_POSTGRES_HOST` do and what is its default?”
- “Which variables configure Coordination Redis vs Cache/Rate‑Limit Redis?”
- “Which environment variable controls the JWT TTL, and how is the session TTL derived?”
- “Where do I configure the gRPC certificate chain path for a given service?”

The catalog groups variables by subsystem and keeps the tables and rotation notes close to one another. When this overview mentions a concept that is backed by environment variables (for example Redis coordination roles or JWT TTL behavior), the catalog contains the concrete variable names and defaults.

---

## Related Documentation

- `environment-and-secrets.md` – Hub/entry point for environment variables and secrets.
- `environment-and-secrets-catalog.md` – Detailed environment variable catalog and rotation notes.
- `deployment-environments.md` – How dev/staging/production environments are structured.
- `../system-architecture-security.md` – Security and TLS architecture, including key and certificate rotation.
- `../system-architecture-redis.md` – Redis architecture hub.
- `../system-architecture-authentication.md` – Authentication and authorization flows.
- `../system-architecture-redis-usage-and-profiles.md` – How Redis roles and profiles are wired in different environments.
