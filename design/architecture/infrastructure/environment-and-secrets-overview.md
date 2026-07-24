# Environment Variables & Secrets Overview

This document explains how configuration values and sensitive secrets are supplied to FireMUD services across FireMUD environment classes. It is the conceptual overview and operator starting point for environment variables and secrets.

For the full catalog of environment variables (including defaults and detailed rotation notes), see `environment-and-secrets-catalog.md`. For a minimal entry point and links, see `environment-and-secrets.md`.

## Table of Contents

- [Operator Quick Reference](#operator-quick-reference)
- [Local Development vs Kubernetes Environments](#local-development-vs-kubernetes-environments)
- [Secret Governance Tiers](#secret-governance-tiers)
- [Configuration vs Secrets](#configuration-vs-secrets)
- [Certificate Management & Watchers](#certificate-management--watchers)
- [How to Use the Catalog](#how-to-use-the-catalog)
- [Related Documentation](#related-documentation)

---

## Operator Quick Reference

This section summarizes the **most important environment variables and rotation behaviors** for on‑call operators. Refer to the catalog document for the full list of variables and detailed defaults.

### Implementation Notes

This document describes the canonical environment and secret target state. The first implementation pass now covers the highest-risk deployment-critical pieces:

- JWT signing material can come from `FIREMUD_AUTH_JWT_SECRET_PATH` without requiring inline `firemud.auth.jwt-secret`, but the current path still supplies a shared HMAC secret rather than the required Account-only asymmetric signing bundle.
- Target state: Account Service serves JWKS from the environment-provided `jwt-jwks` resource and permits the packaged classpath fallback only for explicit local/test profiles. Current runtime drift still permits that fallback when the configured file is absent; player-facing environments must not use it and must fail Account startup when the configured JWKS path or file is missing or unreadable, the JWKS is malformed, or its public JWK does not match the Account signing key and `kid`.
- Hosted `pr-preview` rendering provisions preview-unique Account-owned JWT signing material and matching Account-published JWKS data per namespace instead of relying on one shared inline JWT secret.
- `dev-tools/deploy/preflight.py` consumes player-facing expected-binding manifests under `design/operations/environments/`, emits `expectedBindingsRef`, and validates the first required binding fields and policy IDs.
- Account-only private-key distribution, downstream asymmetric JWKS validation, the phased `jwt-rotation` Job/CronJob, overlap and pruning mechanics, validator-convergence checks, and retained rotation evidence described below remain target-state design rather than checked-in deployment automation. Account Service owns private-key generation, validation, promotion, JWKS publication, and public/private pruning; a non-exportable signer may perform only the private-key operations Account delegates. Rotation automation may request Account-owned transitions through the single Account JWT rotation control/status interface, observe publication and validator convergence, and record evidence; it must never read or update `jwt-signing-keys` or write `jwt-jwks`.

Remaining deployment work includes the JWT authority and rotation boundary above as well as deeper live evidence: the traffic-open backup gates validate the first evidence shape, but real environment evidence files still need to be produced by operators or automation before first live traffic; expected-binding validation should also become stricter as richer Kubernetes live-state checks become available. Do not interpret those gaps as alternative supported behavior for staging, production, or hobby/self-hosted traffic.

### Core Profiles

| Variable | Purpose | Notes |
| -------- | ------- | ----- |
| `SPRING_PROFILES_ACTIVE` | Optional Spring profile override | Keep unset for canonical runtime; reserve it for explicit cases such as `test`. |

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
- `pr-preview` keeps the normal Redis role split and preview-unique trust material, but it uses a preview-scoped expected-bindings/preflight posture rather than the full player-facing backup/admission binding contract used for staging and production traffic-open decisions.

Operational notes:

- Coordination Redis hosts both gameplay session bindings (`session:game:*`) and Account-owned issued-token registry and revocation/version state (`session:auth:*`) as described in `system-architecture-jwt-and-token-contracts.md`.

### Authentication & JWT

| Variable | Purpose | Rotation / Safety Notes |
| -------- | ------- | ----------------------- |
| `FIREMUD_AUTH_JWT_SECRET_PATH` | Account-only path to the versioned private signing bundle | In player-facing environments, mount `jwt-signing-keys` read-only only into Account Service; the canonical mount is `/var/run/secrets/firemud/jwt` and the active bundle is `/var/run/secrets/firemud/jwt/current.key`. |
| `FIREMUD_AUTH_JWKS_PATH` | Account-only path to the published `jwks.json` file | In player-facing environments, mount the fixed Account-owned `jwt-jwks` ConfigMap read-only at `/var/run/secrets/firemud/jwks` and set this to `/var/run/secrets/firemud/jwks/jwks.json`; Account updates the resource through `resourceVersion` CAS. |
| `FIREMUD_AUTH_JWT_EXPIRATION_MS` | Lifetime of issued JWTs in milliseconds | Changing it changes the `exp` claim only for newly issued JWTs; already issued JWTs retain their existing `exp`. |
| `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS` | Issued-token registry cleanup margin | It extends registry retention beyond each token's own `exp` only; it does not extend gameplay continuity. |
| `FIREMUD_AUTH_SESSION_EXPIRATION_MS` | Initial gameplay-continuity retention | Effective value is capped at five minutes and applies only to newly admitted bindings; current code still defaults to one hour until aligned. |

For player-facing environments (`hobby-self-hosted`, staging, production), Account startup must fail closed if `FIREMUD_AUTH_JWKS_PATH` is unset, missing, unreadable, malformed, or does not contain a public JWK matching the Account signing key and `kid`. There is no classpath JWKS fallback in these environments. Account Service owns private-key generation, validation, promotion, JWKS publication, and public/private pruning; a non-exportable signer may perform only the private-key operations Account delegates. The `jwt-rotation` Job/CronJob requests Account-owned transitions through the single Account JWT rotation control/status interface, observes publication and validator convergence, records evidence, and must never read or update `jwt-signing-keys` or write `jwt-jwks`.

### TCP Proxy → Gateway Bridge (Telnet)

The TCP Proxy Service uses `GATEWAY_WS_URL` to connect to Spring Cloud Gateway over WebSocket. This endpoint is configured independently of the `FIREMUD_SERVICES_*` service discovery overrides: changing `FIREMUD_SERVICES_SPRING_CLOUD_GATEWAY_SERVICE` does not automatically update the Telnet bridge. Operators must keep `GATEWAY_WS_URL` aligned with the Gateway’s intended internal WebSocket listener for the environment as described in `system-architecture-protocol-bridging.md` and the TCP Proxy Service design.
In all player-facing classes (`hobby-self-hosted`, staging, production), this alignment is treated as a deployment preflight invariant: release tooling and readiness checks should fail when `GATEWAY_WS_URL` does not target the expected internal listener for the active environment.

### Secrets & Certificates

- Kubernetes workloads consume sensitive values through narrowly scoped Kubernetes `Secret` objects or fixed read-only mounted paths. This is the one FireMUD runtime contract; secret origin is an operator provisioning concern.
- TLS certificates are rotated automatically by **cert-manager**. JWT rotation is coordinated by dedicated Kubernetes Jobs (for example `jwt-rotation`) that request Account-owned transitions through the single Account JWT rotation control/status interface, observe Account publication and validator convergence, and record evidence; in production this Job is treated as an operator-run template rather than an unattended cadence (see `system-architecture-security.md#jwt-key--jwks-rotation-workflow`). The Job/CronJob must never read or update the private `jwt-signing-keys` Secret or write `jwt-jwks`.
- Services reload TLS material via shared utilities, and Account Service may use the JWT watcher as one implementation of atomic signing-generation promotion:
  - `TlsCertificateWatcher`
  - `JwtSecretWatcher`
  - `GrpcServerTlsReloader`

Operator actions:

- For manual secret rotation, follow the owning phased runbook rather than treating one Secret update as a complete rotation, and confirm:
  - Pods reload configuration (via watcher) or are restarted according to the runbook.
  - gRPC clients/servers establish new mTLS sessions without errors.

For full descriptions of the variables and their defaults, open `environment-and-secrets-catalog.md`.

---

## Local Development vs Kubernetes Environments

### Local Development

- Environment variables are loaded from a `.env` file when running `./gradlew devUp`.
- The sample file `.env.sample` lists the variables described in the catalog with default credentials for PostgreSQL and Redis. Copy this file to `.env` and adjust values as needed; `.env` is git‑ignored so real credentials remain local.
- Docker Compose passes these variables to each container so Spring Boot can connect to the databases.
- Secrets such as JWT signing keys are not required in development; random keys are generated on startup.
- When local or operator-run Docker mounts real private material, it uses read-only files outside the repository with restrictive host permissions. FireMUD does not bundle a Vault container or another secret-manager dependency into Compose.

### Shared and Player-Facing Kubernetes Environments

This section describes the Kubernetes-backed environments that use the canonical runtime configuration and Kubernetes Secrets delivery model. Unless a bullet explicitly says `production` only, the rules here apply to `hobby-self-hosted`, `staging`, and `production`.

- Kubernetes `ConfigMap` objects store non‑secret configuration values like host names or feature flags.
- Sensitive values (database passwords, JWT signing keys, TLS private keys) are delivered through Kubernetes `Secret` objects.
- TLS certificates are issued by **cert-manager** and rotated automatically; each workload receives a distinct certificate/private-key Secret and services reload updated certificates using `TlsCertificateWatcher` / `GrpcServerTlsReloader`.
- JWT signing keys are stored in a fixed Secret and public JWKS in a fixed ConfigMap. Account Service owns private-key generation, validation, promotion, JWKS publication, and public/private pruning through name-scoped `resourceVersion` CAS; a non-exportable signer may perform only the private-key operations Account delegates. Dedicated Kubernetes Jobs request Account-owned transitions, prove validator visibility, observe the Account-published key set, and record evidence; they never read or update `jwt-signing-keys` or write `jwt-jwks`. See `system-architecture-security.md#jwt-key--jwks-rotation-workflow` for details.
- In player-facing environments (`hobby-self-hosted`, staging, production), only Account Service may consume JWT private signing material from a mounted file via `FIREMUD_AUTH_JWT_SECRET_PATH`. Validators must use asymmetric Account JWKS; inline-only or HMAC-only JWT configuration and private-key mounts in validators are non-compliant.
- Database credentials are stored in Secrets and rotated via explicit operational Jobs and runbooks (for example `db-credential-rotation` in `system-architecture-backup-recovery.md#post-restore-secret-hardening`); there is no fully automatic cadence today.
- The manifests in `k8s/base/` demonstrate loading Secrets and ConfigMaps via `envFrom` so that services receive the same variables as in development.
- Services reload TLS certificates for gRPC client and server channels using `TlsCertificateWatcher` and `GrpcServerTlsReloader`. Account may use `JwtSecretWatcher` only as part of validated atomic signer promotion; validators refresh bounded JWKS caches and never reload a private Account signing key.
- FireMUD applications support only this Kubernetes Secret/mounted-file delivery contract. They do not call or deploy Vault or cloud-provider secret APIs. Operators may synchronize an external source into the canonical Secret names, but that is transparent infrastructure and must leave already-materialized healthy workload secrets usable during an upstream outage.
- Staging and production require verified Kubernetes Secret encryption at rest, minimal service-account RBAC, namespace isolation, and Kubernetes API audit logging. Hobby/self-hosted player-facing clusters must pass the common binding, least-privilege, and credential-age preflight; operators should enable the same control-plane encryption/audit controls where their distribution supports them.
- Re-creatable leaf certificates and service credentials are reissued after loss. Backup-decryption keys or an intentionally retained offline CA root must have encrypted out-of-cluster custody rather than relying on the live cluster as the sole copy.

The Account-only asymmetric/JWKS boundary above is target state, not proof that the current stack enforces it. Current implementation drift includes the runtime classpath JWKS fallback when the configured file is absent, deployment preflight checks that require signing paths and mounts across primary workloads, and checked-in baseline resources that mount shared `jwt-signing-keys` beyond Account. This documentation records the required convergence without changing runtime, preflight, or manifest behavior.

---

## JWT Trust Model by Environment

JWT signing key and JWKS behavior differs slightly by environment to balance safety and operational complexity:

- **Development**
  - Environment variables are loaded from `.env`, and secrets such as JWT signing keys may be generated randomly on startup for convenience.
  - Cross-service JWT validation is best-effort when random keys are used; operators should not assume that tokens remain valid across service restarts unless a persistent signing key Secret is configured.
- **Staging / Non-production clusters**
  - Player-facing staging must use fixed, pre-created `jwt-signing-keys` Secret and `jwt-jwks` ConfigMap resources. Account updates them through name-scoped `resourceVersion` CAS and consumes their read-only projected mounts; `FIREMUD_AUTH_JWT_SECRET_PATH` selects the private bundle and `FIREMUD_AUTH_JWKS_PATH` selects `/var/run/secrets/firemud/jwks/jwks.json`.
  - Account startup must fail when the configured JWKS path or file is missing or unreadable, the JWKS is malformed, or its public JWK does not match the Account signing key and `kid`; classpath fallback is local/test only.
  - The same immutable `jwt-rotation` artifact and phased protocol used in production must be exercised periodically. It may run on a low-frequency schedule or as an explicit operator drill.
- **PR preview**
  - Each preview namespace must receive PR-unique JWT signing material and JWKS data, even when those resources are treated as low-sensitivity test material.
  - Canonical default: store the preview signing key in a preview-unique Kubernetes `Secret`; Account Service owns publication of the corresponding preview-unique JWKS document from a namespace-local `ConfigMap`.
  - Reusing one shared preview signing key across namespaces is non-compliant because it allows tokens minted in one PR environment to validate in another.
- **Production**
  - Required to use fixed, pre-created `jwt-signing-keys` Secret and public `jwt-jwks` ConfigMap resources. Account updates both through name-scoped `resourceVersion` CAS, mounts `jwt-jwks` read-only at `/var/run/secrets/firemud/jwks`, and sets `FIREMUD_AUTH_JWKS_PATH` to `/var/run/secrets/firemud/jwks/jwks.json`; JWKS is the canonical trust source for all validating services.
  - Account startup must fail when the configured JWKS path or file is missing or unreadable, the JWKS is malformed, or its public JWK does not match the Account signing key and `kid`; classpath fallback is not permitted.
  - The target `jwt-rotation` artifact is an operator-triggered Job template, or an equivalent CronJob kept at `spec.suspend: true`; no such manifest is checked in yet.
  - The Job/CronJob may only request Account-owned public-key transitions, observe public JWKS metadata and validator convergence, and record evidence. It must never read or update `jwt-signing-keys` or write `jwt-jwks`; private operations delegated to a non-exportable signer and all public pruning remain under Account Service authority.
  - Mounted resources alone do not establish readiness. Promotion and traffic-open evidence must prove Account-only asymmetric signing, validator `kid`/JWKS convergence, planned rotation through pruning, and compromise hard cutover as defined in `system-architecture-security.md#player-facing-jwt-readiness`.

For guidance on how to respond to a suspected JWT signing key compromise (as opposed to planned rotation), see the “JWT Key Compromise Response” section in `system-architecture-security.md`.

---

## Secret Governance Tiers

FireMUD applies a tiered governance model so the highest-risk credentials have explicit controls while Kubernetes Secrets remains the canonical workload-delivery boundary:

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

This model is designed to reduce risk without making a secret manager part of the FireMUD runtime. Operators may provision selected Tier A credentials from external orchestration without changing application-level Secret names or mounted-file contracts. A shared active-active multi-datacenter signing or secret authority requires a separate architecture decision; independent deployments use independent authorities by default, and FireMUD does not implement cross-cluster secret replication.

### Secret Compliance Controls

Tier A controls must be measurable, not policy-only. Each player-facing environment (`hobby-self-hosted`, staging, production) maintains a versioned secret compliance record with an explicit provisioning state:

- `not-provisioned` means the player-facing environment does not yet exist. Its record must contain an empty `credentialClasses` object; it is not promotion-eligible and does not start credential-age enforcement.
- `provisioned` means the environment has been bootstrapped or is live. Its record must contain all required credential classes and their current immutable evidence. CI enforces the configured credential-age limits.

Before the first player-facing deployment, change the environment record to `provisioned` in the same change that creates the namespace and bootstrap-secret evidence. A missing or malformed state is non-compliant.

A provisioned record contains:

- Credential class owner (for example platform/security owner role).
- Maximum credential age target.
- Alert rule ID for age/SLA breach.
- Exactly one freshness timestamp:
  - `lastRotationAt` after a credential has completed a documented rotation event, or
  - `lastProvisionedAt` while the environment is still on its bootstrap issuance lineage before first rotation.

The secret compliance record must be stored as versioned environment metadata in Git (for example under `design/operations/secret-compliance/<environment>.yaml`) so CI and reporting jobs can detect missing or stale records before promotion.
Each credential record must also point to immutable provisioning or rotation evidence in-repo (`evidenceRef` + `evidenceKey`) whose referenced payload includes an `immutableArtifactId` value (for example a job/run identifier that embeds a content digest such as `sha256:...`). Promotion/DR-readiness checks must fail when evidence is missing or cannot be tied to an immutable artifact identifier.

Minimum credential classes to track (canonical record keys shown in parentheses):

| Credential Class | Required Evidence |
| --- | --- |
| JWT signing keys / JWKS (`jwt-signing-keys-jwks`) | Last rotation timestamp, key IDs, rotation job outcome |
| PostgreSQL application credentials (`postgres-application-credentials`) | Last rotation timestamp, rollout restart completion evidence |
| Backup/object-store credentials (`backup-object-store-credentials`) | Validation of expected bucket/endpoint and non-production isolation |
| Asset-store credentials (`asset-store-credentials`) | Validation of expected bucket/endpoint, binding identity, and non-production isolation when external asset storage is enabled |
| Operator credentials (`operator-credentials`) | Last issuance/rotation timestamp and revocation traceability |

If a required compliance record is missing or stale, the environment is treated as non-compliant for promotion and DR-readiness reporting.

Promotion gating policy:

- `production`: non-compliant secret records are a hard block for promotion.
- `staging`: non-compliant records are warnings through **June 30, 2026** and become a hard promotion gate on **July 1, 2026**. This cutover applies to staging promotion/deployment evidence and any staging deployment intended to serve as production-promotion evidence; it does not mean every detached or quarantined staging drill must be treated as a promotion candidate.
- `hobby-self-hosted`: operators must validate records before opening player-facing traffic.

Promotion-evidence exception:

- Even before **July 1, 2026**, a staging deployment record that will be referenced by a production promotion attestation must show `secretComplianceStatus=pass` at deployment time and include a `secretComplianceEvidenceRef`. A warning-only staging deployment may still exist for playtesting, but it is not eligible to produce production promotion evidence.

Illustrative distinction:

- A staging playtest deployment with `deployStatus=pass`, `smokeStatus=pass`, and `secretComplianceStatus=warning` may remain valid for detached or non-promotion playtesting before the cutover date.
- That same deployment is invalid as production-promotion evidence; production attestation requires the referenced staging deployment to show `secretComplianceStatus=pass` and a valid `secretComplianceEvidenceRef`.

Bootstrap compliance semantics:

- A brand-new player-facing environment may satisfy `secretComplianceStatus=pass` with immutable initial-provisioning evidence before any scheduled rotation has occurred.
- Bootstrap provisioning evidence is valid only until the first planned rotation window for that credential class. After the first completed rotation, records must switch to `lastRotationAt` and reference rotation evidence rather than continuing to rely on bootstrap issuance.
- Validators must treat bootstrap provisioning and rotation as equivalent compliance sources for first deployment, provided the evidence record still includes an immutable artifact identifier and the credential age remains within the configured maximum age.

Illustrative bootstrap compliance record:

```json
{
  "environment": "staging",
  "provisioningState": "provisioned",
  "generatedAt": "2026-03-13T00:00:00Z",
  "credentialClasses": {
    "jwt-signing-keys-jwks": {
      "owner": "platform-security",
      "maxAgeDays": 30,
      "lastProvisionedAt": "2026-03-13T00:00:00Z",
      "alertRuleId": "sec.jwt.rotation.age",
      "evidenceRef": "design/operations/secret-compliance/evidence/staging-bootstrap.json",
      "evidenceKey": "jwt-signing-keys-jwks"
    },
    "postgres-application-credentials": {
      "owner": "platform-data",
      "maxAgeDays": 30,
      "lastProvisionedAt": "2026-03-13T00:00:00Z",
      "evidenceRef": "design/operations/secret-compliance/evidence/staging-bootstrap.json",
      "evidenceKey": "postgres-application-credentials"
    },
    "backup-object-store-credentials": {
      "owner": "platform-operations",
      "maxAgeDays": 30,
      "lastProvisionedAt": "2026-03-13T00:00:00Z",
      "evidenceRef": "design/operations/secret-compliance/evidence/staging-bootstrap.json",
      "evidenceKey": "backup-object-store-credentials"
    },
    "operator-credentials": {
      "owner": "platform-operations",
      "maxAgeDays": 30,
      "lastProvisionedAt": "2026-03-13T00:00:00Z",
      "evidenceRef": "design/operations/secret-compliance/evidence/staging-bootstrap.json",
      "evidenceKey": "operator-credentials"
    }
  }
}
```

Corresponding evidence payload:

```json
{
  "environment": "staging",
  "generatedAt": "2026-03-13T00:05:00Z",
  "records": {
    "jwt-signing-keys-jwks": {
      "evidenceType": "provisioning",
      "immutableArtifactId": "change-ticket:STAGE-401:sha256:9a1b2c3d",
      "source": "bootstrap-runbook",
      "recordedBy": "platform-security"
    },
    "postgres-application-credentials": {
      "evidenceType": "provisioning",
      "immutableArtifactId": "change-ticket:STAGE-402:sha256:2b3c4d5e",
      "source": "bootstrap-runbook",
      "recordedBy": "platform-data"
    },
    "backup-object-store-credentials": {
      "evidenceType": "provisioning",
      "immutableArtifactId": "change-ticket:STAGE-403:sha256:3c4d5e6f",
      "source": "bootstrap-runbook",
      "recordedBy": "platform-operations"
    },
    "operator-credentials": {
      "evidenceType": "provisioning",
      "immutableArtifactId": "change-ticket:STAGE-404:sha256:4d5e6f70",
      "source": "bootstrap-runbook",
      "recordedBy": "platform-operations"
    }
  }
}
```

## Player-Facing Environment Bootstrap Requirements

Before the first deployment into `hobby-self-hosted`, `staging`, or `production`, operators must provision a minimum bootstrap set of secrets and trust resources. This is the canonical bootstrap contract for environment and secret readiness:

- `postgres-credentials`
- `postgres-admin-credentials` when rotation Jobs are used
- `jwt-signing-keys`
- `jwt-jwks`
- cert-manager issuer or issuer reference used by workload and bridge certificates
- concrete certificate bindings for workload gRPC mTLS, the Gateway internal mTLS WebSocket listener where used, the TCP Proxy bridge client identity where used, and a maintenance control-plane client identity when an exceptional backup-related maintenance workflow invokes `PauseTicks` / `ResumeTicks`; routine online backup does not require that pause identity
- registry pull credential secret
- backup/object-store credentials when the environment requires backups, including the binding identity used to prove the intended environment owns the bucket or object-store target
- asset-store credentials when the environment publishes or serves assets from external object storage, including the binding identity used to prove the intended environment owns the asset bucket or object-store target
- outbound communications credentials when email or webhook integrations are enabled
- operator credential binding used for environment-scoped control-plane access

Bootstrap resources must be unique to the environment boundary. Shared namespace names such as `firemud` do not relax the requirement for separate staging and production secret sources, bucket bindings, or operator trust bindings. For cluster-local internal bindings, uniqueness is evaluated by environment ownership of the underlying resource, not by requiring globally unique literal names.

Expected bindings for player-facing deployment and recovery checks must be declared once per environment in `design/operations/environments/<environment>/expected-bindings.yaml`. Deploy preflight and restore validation both consume this same manifest so internal state/trust bindings (PostgreSQL, Redis, JWT/JWKS, certificate issuer, concrete workload/bridge/backup control-plane certificate bindings, registry pull credentials) and external bindings (backup storage, asset storage, outbound communications, operator credential bindings) do not drift between deployment and recovery procedures. For backup and asset storage, the manifest must also prove the credential binding identity that owns the bucket or endpoint. Internal bindings are evaluated relative to the target environment boundary, so the same cluster-local literal may appear in multiple manifests when it resolves to environment-owned resources in separate boundaries.

Player-facing preflight must fail when this bootstrap set is incomplete or when an external binding resolves to another environment’s target. The authoritative preflight policy IDs and evidence contract for these checks are defined in `../system-architecture-deploy-preflight-policy.md`.

Operator bootstrap matrix:

| Environment class | Always required | Conditionally required |
| --- | --- | --- |
| `hobby-self-hosted` | PostgreSQL credentials, JWT signing/JWKS resources, certificate/issuer binding when TLS automation is used, registry pull credentials when private images are used, operator credential binding, canonical `expected-bindings.yaml` | backup/object-store bindings when backups are enabled, asset-store bindings when published/runtime assets use external object storage, outbound communications bindings when email/webhooks are enabled |
| `staging` | PostgreSQL credentials, JWT signing/JWKS resources, cert-manager issuer binding, registry pull credentials, operator credential binding, canonical `expected-bindings.yaml` | backup/object-store bindings, asset-store bindings when published/runtime assets use external object storage, outbound communications bindings when email/webhooks are enabled |
| `production` | PostgreSQL credentials, JWT signing/JWKS resources, cert-manager issuer binding, registry pull credentials, operator credential binding, canonical `expected-bindings.yaml`, backup/object-store bindings | asset-store bindings when published/runtime assets use external object storage, outbound communications bindings when email/webhooks are enabled |

Use this matrix only as a quick operator checklist. The canonical field-by-field schema and `bindingRef` versus `fingerprint` precedence rules live in `../system-architecture-deploy-preflight-policy.md`.

---

## Configuration vs Secrets

FireMUD distinguishes between **configuration** and **secrets** but delivers both through environment variables so services can be configured consistently across local development, CI, and Kubernetes deployments.

- **Configuration (non‑secret)**:
  - Examples: service hostnames, ports, feature flags, environment profile (`SPRING_PROFILES_ACTIVE`).
  - Typically sourced from `.env` in development and from Kubernetes `ConfigMap` objects in shared or player-facing Kubernetes environments.
  - Changes may be applied by restarting pods or reloading configuration where supported.
- **Secrets**:
  - Examples: database passwords, JWT signing keys, TLS private keys, S3 access keys.
  - Always stored in Kubernetes `Secret` objects in shared or player-facing Kubernetes environments.
  - May be generated or randomized automatically for local development.
  - Rotation uses the appropriate owner and automation for each secret family:
    - TLS certificates are rotated automatically by cert-manager.
    - Account Service owns JWT private-key generation, validation, promotion, JWKS publication, and public/private pruning; a non-exportable signer may perform only the private-key operations Account delegates. Jobs such as `jwt-rotation` request Account-owned transitions through the single Account JWT rotation control/status interface, prove convergence, observe the published key set, and record evidence; they never read or update `jwt-signing-keys` or write `jwt-jwks`.
    - Database credentials are rotated by Jobs such as `db-credential-rotation` that update the relevant Secrets and restart consumers.
  - Direct, ad hoc edits to Secrets should be treated as emergency measures only and reconciled back into the appropriate Job/runbook flow so future rotations remain automated and repeatable.

Shared libraries support overriding default settings with environment variables using the `FIREMUD_` prefix (for example `FIREMUD_POSTGRES_HOST`, `FIREMUD_POSTGRES_PORT`). Each service merges these variables with its own `application.yml` profile.

The catalog document groups these variables by subsystem (PostgreSQL, Redis, TLS, Authentication, Service Discovery, Observability, Asset Storage, Backup & Restore) and documents their defaults.

---

## Certificate Management & Watchers

Mutual TLS protects all internal service-to-service traffic. Certificates are normally provisioned by **cert-manager** and mounted from per-workload Kubernetes Secrets so each service has a concrete private identity. These certificates secure:

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
