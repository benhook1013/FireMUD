# Environment Variables & Secrets Overview

This document explains how configuration values and sensitive secrets are supplied to FireMUD services across FireMUD environment classes. It is the conceptual overview and operator starting point for environment variables and secrets.

For the full catalog of environment variables (including defaults and environment-specific delivery details), see [environment-and-secrets-catalog.md](./environment-and-secrets-catalog.md). For a minimal entry point and links, see [environment-and-secrets.md](./environment-and-secrets.md).

The front owner contracts for this overview are [event-scoped Tier A credential compliance](../decisions/adr-0151-event-scoped-automated-tier-a-credential-compliance.md) and [phased environment-bound preflight and expected bindings](../decisions/adr-0152-phased-environment-bound-deployment-preflight-and-expected-bindings.md). This document owns environment-specific resource, binding, and compliance consequences; the linked decisions own the cross-cutting gates.

## Table of Contents

- [Operator Quick Reference](#operator-quick-reference)
- [Local Development vs Kubernetes Environments](#local-development-vs-kubernetes-environments)
- [Secret Governance Tiers](#secret-governance-tiers)
- [Configuration vs Secrets](#configuration-vs-secrets)
- [Certificate Management and Watchers](#certificate-management-and-watchers)
- [How to Use the Catalog](#how-to-use-the-catalog)
- [Related Documentation](#related-documentation)

---

## Operator Quick Reference

This section summarizes the **most important environment variables and delivery/readiness behaviors** for on‑call operators. Refer to the catalog document for the full list of variables and detailed defaults.

### Implementation Notes

This document describes the canonical environment and secret target state. The first implementation pass now documents or partially implements the highest-risk deployment-critical pieces:

- `LEGACY_SECRET_DIAGNOSTIC` is the current executable's shared-HMAC `Secret`/`FIREMUD_AUTH_JWT_SECRET_PATH` mode. It proves legacy path and resource wiring only; its static checks never authorize player-facing traffic. The hosted preview path currently signs and validates with this shared-HMAC Secret/path contract. Its separately mounted `jwt-jwks` ConfigMap is diagnostic-only and must not be treated as token-validation evidence.
- `INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK` is the separate interim player-facing mode: only Account receives the environment-unique `jwt-signing-keys` private bundle, while Account and every validator consume the public `jwt-jwks` projection. `TARGET_NON_EXPORTABLE_SIGNER` is the target mode: `FIREMUD_AUTH_JWT_SECRET_PATH` is absent, no application workload mounts or receives private signing keys, and Account plus every JWT validator consume the public projection through their read-only `FIREMUD_AUTH_JWKS_PATH`. Current runtime and preflight do not prove validator consumption. The canonical signer-custody contract is [JWT and Token Contracts](../system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative); this overview records only the environment-specific resource and mount consequences.
- Hosted `pr-preview` must bind one preview-unique shared-HMAC signing key and its non-secret diagnostic `jwt-jwks` content to each deployment event. The exact rendered values/artifact must be reused unchanged through render, dry-run, preflight, Helm apply, and retries; new signing material is permitted only through an explicit rotation or an explicitly new deployment event, including an intentional clean namespace reset. The current preview and dev-demo renderers regenerate material per invocation, so the current workflow does not yet prove retry-stable behavior. Helm materializes the signing key as the namespace-scoped `jwt-signing-keys` Secret and the diagnostic content as the namespace-scoped `jwt-jwks` ConfigMap, with Account mounting that ConfigMap at `FIREMUD_AUTH_JWKS_PATH`. The ConfigMap content is not the shared-HMAC validation key and cannot validate preview tokens; current preview validators use the Secret/path contract. The preview workflow's render, dry-run, Helm apply, and `helm-jwks-contract.sh` resource/mount check do not prove runtime JWKS acceptance. Target hosted preview keeps Account-published public JWKS and non-exportable signer custody as separate contracts.
- `dev-tools/deploy/preflight.py` consumes player-facing expected-binding manifests under `design/operations/environments/`, emits `expectedBindingsRef`, and validates the first required binding fields and policy IDs.
- The cross-service JWT authority, signing, publication, pruning, rotation, and validator-convergence contract is owned by [JWT and Token Contracts](../system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative). Those target requirements and deeper live evidence remain incomplete in checked-in deployment automation.

### Current and Target JWT/JWKS Resource Modes

The current hosted `pr-preview` path uses the renderer and Helm resource contract described above: `jwt-signing-keys` is a namespace-scoped `Secret`, public `jwt-jwks` is a namespace-scoped `ConfigMap`, and Account mounts the ConfigMap. The separate `helm-jwks-contract.sh` proof checks that preview resource and Account mount; it is concrete wiring evidence only, not proof of live JWKS acceptance by every validator or player-facing JWT readiness. The target JWT authority, validator convergence, and signer-custody contract is canonical in [JWT and Token Contracts](../system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative); this document retains only environment resource and mount consequences. Checked-in player-facing Kustomize fixtures remain on legacy Secret-backed signing with a public `jwt-jwks` Secret resource and advisory `PREFLIGHT-JWKS-001` Secret/path/mount diagnostics; environment-owned resources still supply the external bindings.

The expected-binding selector is separate from the hosted preview resource shape. Player-facing manifests select exactly one of `LEGACY_SECRET_DIAGNOSTIC`, `INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK`, or `TARGET_NON_EXPORTABLE_SIGNER`. The current `preflight.py` constant `IMPLEMENTED_JWT_CUSTODY_MODE` is `LEGACY_SECRET_DIAGNOSTIC`; its required `FIREMUD_AUTH_JWT_SECRET_PATH` and shared-HMAC Secret checks are diagnostic only and never satisfy player-facing readiness. The interim mode requires Account-only private-bundle proof plus public-JWKS consumption by Account and every validator, while target mode requires the private path to be absent and no application private material. Only the separately authenticated interim or target custody proof can authorize player-facing traffic; neither current legacy checks nor the preview ConfigMap/mount check claims that authority.

Remaining deployment work includes enforcing the target JWT resource/readiness boundary and producing deeper live evidence: the traffic-open backup gates validate the first evidence shape, but real environment evidence files still need to be produced by operators or automation before first live traffic; expected-binding validation should also become stricter as richer Kubernetes live-state checks become available. Do not interpret those gaps as alternative supported behavior for staging, production, or hobby/self-hosted traffic.

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
- Truly ephemeral CI/preview stacks may collapse roles into a single Redis instance only when explicitly documented as ephemeral and not used to validate coordination SLOs; see [Redis usage and profiles](../system-architecture-redis-usage-and-profiles.md#environment-mappings).
- `pr-preview` keeps the normal Redis role split and preview-unique trust material, but it uses a preview-scoped expected-bindings/preflight posture rather than the full player-facing backup/admission binding contract used for staging and production traffic-open decisions.

Operational notes:

- Coordination Redis hosts both gameplay session bindings (`session:game:*`) and Account-owned issued-token registry and revocation/version state (`session:auth:*`) as described in [JWT and Token Contracts](../system-architecture-jwt-and-token-contracts.md).

### Authentication & JWT

The resource and startup requirements in this subsection are the accepted target-mode contract. Current hosted preview wiring is described under [Current and Target JWT/JWKS Resource Modes](#current-and-target-jwtjwks-resource-modes); its preflight result is limited to concrete ConfigMap/resource/mount wiring and does not prove these target requirements.

| Variable | Purpose | Rotation / Safety Notes |
| -------- | ------- | ----------------------- |
| `FIREMUD_AUTH_JWT_SECRET_PATH` | Current legacy shared-HMAC Secret/path mode and interim Account-only mounted fallback | In the legacy mode, this selects the shared-HMAC Secret/path wiring and remains diagnostic only. In the interim mode, mount `jwt-signing-keys` read-only only into Account Service at `/var/run/secrets/firemud/jwt`, with active bundle `/var/run/secrets/firemud/jwt/current.key`; target non-exportable signer custody leaves this variable unset. |
| `FIREMUD_AUTH_JWKS_PATH` | Read-only public `jwks.json` path; target Account and JWT validators consume this projection | In hosted preview, Account mounts the fixed `jwt-jwks` ConfigMap read-only at `/var/run/secrets/firemud/jwks` and this is set to `/var/run/secrets/firemud/jwks/jwks.json`. Player-facing target environments use the same public projection contract. |
| `FIREMUD_AUTH_JWT_EXPIRATION_MS` | Lifetime of issued JWTs in milliseconds | Changing it changes the `exp` claim only for newly issued JWTs; already issued JWTs retain their existing `exp`. |
| `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS` | Issued-token registry cleanup margin | It extends registry retention beyond each token's own `exp` only; it does not extend gameplay continuity. |
| `FIREMUD_AUTH_SESSION_EXPIRATION_MS` | Initial gameplay-continuity retention | Target default is `300000` ms (five minutes), with an inclusive valid range of `1..300000`; current code still defaults to `3600000` ms (one hour) and does not enforce that range. |

For player-facing environments, every JWT validator must fail closed when `FIREMUD_AUTH_JWKS_PATH` is unset, missing, unreadable, malformed, unusable, or unavailable; there is no classpath fallback. Account additionally must verify that the public key set contains the active signing key and matching `kid`. The JWT contract defines the required cross-service proof; environment-specific paths and mounts remain the local contract here.

Account and validator readiness, token issuance, and protected traffic remain quarantined until the [JWT and Token Contracts](../system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative) proof succeeds; for the interim mounted fallback, that includes private/public projection proof. This overview records only the resulting environment-specific resource, mount, and readiness consequences.

### TCP Proxy → Gateway Bridge (Telnet)

The TCP Proxy Service uses `GATEWAY_WS_URL` to connect to Spring Cloud Gateway over WebSocket. This endpoint is configured independently of the `FIREMUD_SERVICES_*` service discovery overrides: changing `FIREMUD_SERVICES_SPRING_CLOUD_GATEWAY_SERVICE` does not automatically update the Telnet bridge. Operators must keep `GATEWAY_WS_URL` aligned with the Gateway’s intended internal WebSocket listener for the environment as described in `system-architecture-protocol-bridging.md` and the TCP Proxy Service design.
In all player-facing classes (`hobby-self-hosted`, staging, production), this alignment is treated as a deployment preflight invariant: release tooling and readiness checks should fail when `GATEWAY_WS_URL` does not target the expected internal listener for the active environment.

### Secrets & Certificates

- In shared or player-facing Kubernetes environments, workloads consume sensitive values through narrowly scoped Kubernetes `Secret` objects or fixed read-only mounted paths. This is the one FireMUD Kubernetes runtime contract; secret origin is an operator provisioning concern. Local development intentionally uses ignored `.env` values and generated throwaway credentials instead.
- TLS certificates are rotated automatically by **cert-manager**. The JWT lifecycle is owned by [JWT and Token Contracts](../system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative); this overview retains the mounted-file and watcher delivery consequences.
- Services reload mounted credentials via shared utilities:
  - `TlsCertificateWatcher`
  - `GrpcServerTlsReloader`
- `JwtSecretWatcher` is Account-only for the interim private signing path; target JWT validators reload the public JWKS projection. Current preflight does not prove that runtime reload or validator consumption occurs.

Operator actions:

- For manual secret or certificate changes, follow the owning runbook and confirm:
  - Pods reload configuration (via watcher) or are restarted according to the runbook.
  - gRPC clients/servers establish new mTLS sessions without errors.

For full descriptions of the variables and their defaults, open [environment-and-secrets-catalog.md](./environment-and-secrets-catalog.md).

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
- Sensitive values (database passwords, TLS private keys, and interim JWT signing bundles) are delivered through Kubernetes `Secret` objects. The public `jwt-jwks` projection is delivered separately as read-only public material; hosted preview materializes it as a `ConfigMap`.
- TLS certificates are issued by **cert-manager** and rotated automatically; each workload receives a distinct certificate/private-key Secret and services reload updated certificates using `TlsCertificateWatcher` / `GrpcServerTlsReloader`.
- JWT authority and lifecycle are defined by [JWT and Token Contracts](../system-architecture-jwt-and-token-contracts.md). This overview records only the interim Account private-bundle mount, public JWKS mounts, and watcher delivery.
- In player-facing environments (`hobby-self-hosted`, staging, production), the selected `INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK` mode allows only Account Service to consume JWT private signing material from a mounted file via `FIREMUD_AUTH_JWT_SECRET_PATH`, and only Account uses `JwtSecretWatcher` on that path; Account and every validator must consume the public JWKS projection. The selected `TARGET_NON_EXPORTABLE_SIGNER` mode has no private path or application private material and uses the same public-JWKS consumer boundary. The current `LEGACY_SECRET_DIAGNOSTIC` mode is shared-HMAC Secret/path wiring evidence only and is never player-facing authorization; inline-only or HMAC-only configuration and private-key mounts in validators are non-compliant for accepted modes. Current hosted preview is a separate HMAC/test wiring path and its preflight does not prove validator consumption.
- Database credentials are stored in Secrets and rotated via explicit operational Jobs and runbooks (for example `db-credential-rotation` in `system-architecture-backup-recovery.md#post-restore-secret-hardening`); there is no fully automatic cadence today.
- The manifests in `k8s/base/` demonstrate loading Secrets and ConfigMaps via `envFrom` so that services receive the same variables as in development.
- Services reload TLS certificates for gRPC client and server channels using `TlsCertificateWatcher` and `GrpcServerTlsReloader`.
- FireMUD applications support only this Kubernetes Secret/mounted-file delivery contract. They do not call or deploy Vault or cloud-provider secret APIs. Operators may synchronize an external source into the canonical Secret names, but that is transparent infrastructure and must leave already-materialized healthy workload secrets usable during an upstream outage.
- Staging and production require verified Kubernetes Secret encryption at rest, namespace isolation, and Kubernetes API audit logging. Hobby/self-hosted player-facing clusters must pass the common binding and credential-age preflight; operators should enable the same control-plane encryption/audit controls where their distribution supports them.
- Re-creatable leaf certificates and service credentials are reissued after loss. Backup-decryption keys or an intentionally retained offline CA root must have encrypted out-of-cluster custody rather than relying on the live cluster as the sole copy.

Current implementation drift includes the runtime classpath JWKS fallback when the configured file is absent and signing-path mounts across primary workloads. Checked-in player-facing baseline resources materialize public `jwt-jwks` as a Secret and Account mounts it; hosted preview remains separately ConfigMap-backed through Helm. `PREFLIGHT-JWKS-001` is an advisory resource/path/mount diagnostic for the applicable current path and does not establish public JWKS consumption by validators, and target validators receive no private signing material.

---

## JWT Trust Model by Environment

The canonical JWT profile, registry, authority-generation, outage, signing, and rotation contract is [JWT and Token Contracts](../system-architecture-jwt-and-token-contracts.md). The matrix below records only environment-local resource and readiness consequences:

| Environment class | JWT resource posture | Local readiness consequence |
| --- | --- | --- |
| `local-dev` | `.env` and throwaway generated keys are allowed; an explicit local/test profile may use the packaged classpath fixture. | Cross-service token validity need not survive restarts unless operators configure persistent material. |
| `dev-demo-cluster` | Use environment-unique test material; this non-player-facing class may use convenience provisioning and must not share trust material across environments. | It is not promotion, rollback, or DR-readiness evidence. |
| `pr-preview` | One preview-unique shared-HMAC signing key and non-secret diagnostic `jwt-jwks` content per deployment event, reused unchanged across render, dry-run, preflight, Helm apply, and retries; explicit rotation or an explicitly new deployment event may replace it. Current renderers regenerate per invocation, so retry-stable behavior remains unproved. Helm materializes `jwt-signing-keys` as a namespace-scoped Secret and the diagnostic `jwt-jwks` as a namespace-scoped ConfigMap; Account mounts the ConfigMap at `FIREMUD_AUTH_JWKS_PATH`, but current validators use the Secret/path rather than that ConfigMap. Target hosted previews replace this diagnostic with Account-published public JWKS and non-exportable signer custody, with every-validator consumption proven separately. | Use the preview-scoped Helm proof; `helm-jwks-contract.sh` proves only the diagnostic ConfigMap and Account mount wiring, not live validator acceptance. Player-facing backup/admission evidence is not required. |
| `hobby-self-hosted` | Target: non-exportable signer custody; no application workload mounts or receives private signing material, and Account-owned public `jwt-jwks` is delivered to Account and every validator. Interim drift: Account-only `jwt-signing-keys` private mount plus the mounted public projection. | Target requires signer health, no-private-mount proof, and public-JWKS convergence; interim drift additionally requires private/public projection proof. Common binding, credential-age, and applicable backup evidence are required. |
| `staging` | Target: non-exportable signer custody; no application workload mounts or receives private signing material, and Account-owned public `jwt-jwks` is delivered to Account and every validator. Interim drift: Account-only `jwt-signing-keys` private mount plus the mounted public projection. | Target requires signer health, no-private-mount proof, and public-JWKS convergence; interim drift additionally requires private/public projection proof. Player-facing preflight and promotion evidence apply. |
| `production` | Target: non-exportable signer custody; no application workload mounts or receives private signing material, and Account-owned public `jwt-jwks` is delivered to Account and every validator. Interim drift: Account-only `jwt-signing-keys` private mount plus the mounted public projection. | Target requires signer health, no-private-mount proof, and public-JWKS convergence; interim drift additionally requires private/public projection proof. Strict player-facing preflight, promotion, and backup evidence apply. |

Mounted resources alone do not establish readiness; the JWT contract and applicable environment preflight policy define the required evidence.

## Secret Governance Tiers

FireMUD applies a tiered governance model so the highest-risk credentials have explicit controls while Kubernetes Secret objects remain the canonical workload-delivery boundary:

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

Tier A controls must be measurable, not policy-only. Each player-facing environment (`hobby-self-hosted`, staging, production) maintains a versioned secret compliance record with two distinct fields: `provisioningState` is the environment/compliance projection and is exactly one of `not-provisioned`, `noncompliant`, or `provisioned`; after bootstrap starts or any required resource exists, `bootstrapOperationStatus` is present and exactly one of `pending`, `blocked`, `failed`, or `completed`, while it is absent for `not-provisioned`.

- `not-provisioned` means the player-facing environment and its resources do not yet exist. Its record must contain an empty `credentialClasses` object and no `bootstrapOperationId`, `bootstrapOperationStatus`, or `provisioningGeneration` fields. Environment inventory must independently confirm that no namespace or required bootstrap resource exists. It is not promotion-eligible and does not start credential-age enforcement.
- `noncompliant` during bootstrap or a fresh-boundary restore means the affected player-facing boundary stays closed while operation, inventory, binding, resource, or evidence work is pending, blocked, failed, partial, or mismatched. If an already healthy deployment later has a missing or stale compliance record, it alerts and blocks only the future promotion, first-live, reopen, staging-evidence-for-production, or disaster-recovery-readiness claims listed below; it does not by itself stop existing sessions, routine new admission, or routine gameplay unless another runtime authority independently fails. It may use any valid `bootstrapOperationStatus`, including `completed` when the completed operation's evidence or bindings fail validation, and must not be projected as `not-provisioned` or `provisioned`.
- `provisioned` means the environment has completed one exact bootstrap generation. Its record must contain `bootstrapOperationStatus=completed`, a stable non-empty `bootstrapOperationId`, a positive `provisioningGeneration`, all required credential classes and immutable evidence, and exact operation/generation bindings throughout those records. CI enforces the configured credential-age limits.

Bootstrap is one durable operation lifecycle, not a claim that inventory, bindings, resources, and evidence committed in one cross-system transaction. The operation records a stable `bootstrapOperationId` and positive monotonic `provisioningGeneration`, and binds inventory confirmation, expected credential bindings, namespace/resource creation, and bootstrap evidence to that tuple. An operation starts with `bootstrapOperationStatus=pending` and may become `blocked`, `failed`, or `completed`; the environment projection remains `noncompliant` for any unresolved operation or evidence condition. Once an operation starts or any required resource exists, the projection cannot return to `not-provisioned`; retries may resume or advance the same operation identity but cannot erase that history. Only `bootstrapOperationStatus=completed` with an exact generation present in every required binding and evidence record may project `provisioned`; a missing, malformed, conflicting, or stale generation remains `noncompliant`.

A provisioned record contains:

- Credential class owner (for example platform/security owner role).
- Maximum credential age target.
- Alert rule ID for age/SLA breach.
- Exactly one freshness timestamp:
  - `lastRotationAt` after a credential has completed a documented rotation event, or
  - `lastProvisionedAt` while the environment is still on its bootstrap issuance lineage before first rotation.
- The current `bootstrapOperationId` and `provisioningGeneration` when the record belongs to bootstrap, or the durable rotation or rebinding operation/event identity for a later evidence refresh. Later evidence uses its own durable event identity and does not copy or invent a bootstrap `bootstrapOperationId` or `provisioningGeneration`. A rebinding-only refresh retains the actual underlying material's existing `lastProvisionedAt` or `lastRotationAt`; it records the rebind operation separately and never resets credential age to the rebind time.

For the current compliance validator, the bootstrap tuple is enforced in the credential record, evidence payload, and selected evidence record only when the credential uses `lastProvisionedAt` and the corresponding evidence is on the bootstrap provisioning lineage. `lastRotationAt` records and later rotation or rebinding evidence retain their own event identity and are not required to copy the bootstrap tuple; their broader lineage checks remain owner-controller responsibilities.

The secret compliance record must be stored as versioned environment metadata in Git (for example under `design/operations/secret-compliance/<environment>.yaml`) so CI and reporting jobs can detect missing or stale records before an applicable readiness event. This record is an audit index, not proof by itself.
Each credential record must point to content-addressed output generated by the actual provisioning, rotation, or rebinding playbook (`evidenceRef` + `evidenceKey`). The selected `records[evidenceKey]` object is serialized as UTF-8 RFC 8785 JSON Canonicalization Scheme bytes using the owner-defined evidence schema: object-member ordering and number rendering are canonical, producer-specific whitespace is absent, and array elements retain their schema-defined order. For the hash preimage, omit only that selected object's `immutableArtifactId` member and include every other member. The selected object must include an `immutableArtifactId` equal to a complete SHA-256 digest of those bytes, encoded as `sha256:` plus 64 lowercase hexadecimal characters. This serialization rule defines digest bytes without adding or renaming evidence fields; consumers must not invent another schema or local ordering. Truncated prefixes, mutable references, manually entered digest-looking values, Git timestamps, or successful process exits are insufficient. The payload environment and target environment must exact-match the parent record; its durable provisioning, rotation, or rebinding event/operation identity and any applicable generation must exact-match the parent; and `records[evidenceKey].credentialClass` must exact-match the parent `evidenceKey` and target. Rebinding evidence must also exact-match the retained material-lineage identity and target binding without changing the material's freshness timestamp. Production promotion, first-live/reopen, staging evidence used for production, and disaster-recovery-readiness checks fail when evidence is missing, cannot be dereferenced, or fails any exact-match check.

Minimum credential classes to track (canonical record keys shown in parentheses):

| Credential Class | Required Evidence |
| --- | --- |
| JWT signing keys / JWKS (`jwt-signing-keys-jwks`) | Last rotation timestamp, key IDs, rotation job outcome |
| PostgreSQL application credentials (`postgres-application-credentials`) | Last rotation timestamp, rollout restart completion evidence |
| Backup/object-store credentials (`backup-object-store-credentials`) | Validation of expected bucket/endpoint and non-production isolation |
| Asset-store credentials (`asset-store-credentials`) | Validation of expected bucket/endpoint, binding identity, and non-production isolation when external asset storage is enabled |
| Operator credentials (`operator-credentials`) | Last issuance/rotation timestamp and revocation traceability |

If a required compliance record is missing or stale, the environment is non-compliant for the applicable readiness claim. It alerts immediately and blocks the next production promotion, production first-live/reopen, staging evidence used for production, or disaster-recovery-readiness gate. It does not automatically stop existing sessions, routine new admission, or routine gameplay, or block credential rotation, remediation, rollback, detached testing, or quarantined recovery, unless another runtime authority independently fails.

Promotion gating policy:

- `production`: non-compliant secret records hard-block promotion, first-live, reopen, and a disaster-recovery-readiness claim.
- `staging`: non-compliant records hard-block use of that deployment as production-promotion or production-readiness evidence. Detached playtesting, remediation, and quarantined drills remain available.
- `hobby-self-hosted`: compliance validation reports the environment's readiness posture without requiring an external secret manager. Recovery-readiness behavior follows the explicit verified or `recovery-unverified` profile rather than treating a checked-in record as proof.

Provisioning, rotation, cold-start, and recovery playbooks generate their applicable compliance evidence automatically and are tested before they are trusted. Once legitimately triggered, owner-defined retry-safe/idempotent phases must run unattended; destructive restore and credential mutations require durable operation identity and ambiguous-outcome validation rather than presumed idempotency. Unresolved failures leave traffic closed or the environment quarantined with actionable diagnostics instead of requiring a lone operator to reconstruct the procedure.

Promotion-evidence policy:

- Any staging deployment record referenced by a production promotion attestation must show `secretComplianceStatus=pass` at that deployment event and include a `secretComplianceEvidenceRef`. A warning-only staging deployment may exist for detached or non-promotion playtesting, but it is never eligible to produce production promotion evidence.

Illustrative distinction:

- A staging playtest deployment with `deployStatus=pass`, `smokeStatus=pass`, and `secretComplianceStatus=warning` may remain valid for detached or non-promotion playtesting.
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
  "bootstrapOperationStatus": "completed",
  "bootstrapOperationId": "bootstrap-staging-20260313-01",
  "provisioningGeneration": 1,
  "generatedAt": "2026-03-13T00:00:00Z",
  "credentialClasses": {
    "jwt-signing-keys-jwks": {
      "owner": "platform-security",
      "maxAgeDays": 30,
      "lastProvisionedAt": "2026-03-13T00:00:00Z",
      "bootstrapOperationId": "bootstrap-staging-20260313-01",
      "provisioningGeneration": 1,
      "targetEnvironment": "staging",
      "alertRuleId": "sec.jwt.rotation.age",
      "evidenceRef": "design/operations/secret-compliance/evidence/staging-bootstrap.json",
      "evidenceKey": "jwt-signing-keys-jwks"
    },
    "postgres-application-credentials": {
      "owner": "platform-data",
      "maxAgeDays": 30,
      "lastProvisionedAt": "2026-03-13T00:00:00Z",
      "bootstrapOperationId": "bootstrap-staging-20260313-01",
      "provisioningGeneration": 1,
      "targetEnvironment": "staging",
      "alertRuleId": "sec.postgres.rotation.age",
      "evidenceRef": "design/operations/secret-compliance/evidence/staging-bootstrap.json",
      "evidenceKey": "postgres-application-credentials"
    },
    "backup-object-store-credentials": {
      "owner": "platform-operations",
      "maxAgeDays": 30,
      "lastProvisionedAt": "2026-03-13T00:00:00Z",
      "bootstrapOperationId": "bootstrap-staging-20260313-01",
      "provisioningGeneration": 1,
      "targetEnvironment": "staging",
      "alertRuleId": "sec.backup-object-store.rotation.age",
      "evidenceRef": "design/operations/secret-compliance/evidence/staging-bootstrap.json",
      "evidenceKey": "backup-object-store-credentials"
    },
    "operator-credentials": {
      "owner": "platform-operations",
      "maxAgeDays": 30,
      "lastProvisionedAt": "2026-03-13T00:00:00Z",
      "bootstrapOperationId": "bootstrap-staging-20260313-01",
      "provisioningGeneration": 1,
      "targetEnvironment": "staging",
      "alertRuleId": "sec.operator.rotation.age",
      "evidenceRef": "design/operations/secret-compliance/evidence/staging-bootstrap.json",
      "evidenceKey": "operator-credentials"
    }
  }
}
```

Corresponding evidence payload:

The repeated hexadecimal digest values below are non-authorizing schema placeholders. Real records must use playbook-generated digests of their actual canonical evidence bytes; copying an example value fails evidence verification.

```json
{
  "environment": "staging",
  "bootstrapOperationId": "bootstrap-staging-20260313-01",
  "provisioningGeneration": 1,
  "generatedAt": "2026-03-13T00:05:00Z",
  "records": {
    "jwt-signing-keys-jwks": {
      "evidenceType": "provisioning",
      "credentialClass": "jwt-signing-keys-jwks",
      "targetEnvironment": "staging",
      "bootstrapOperationId": "bootstrap-staging-20260313-01",
      "provisioningGeneration": 1,
      "immutableArtifactId": "sha256:1111111111111111111111111111111111111111111111111111111111111111",
      "source": "bootstrap-runbook",
      "recordedBy": "platform-security"
    },
    "postgres-application-credentials": {
      "evidenceType": "provisioning",
      "credentialClass": "postgres-application-credentials",
      "targetEnvironment": "staging",
      "bootstrapOperationId": "bootstrap-staging-20260313-01",
      "provisioningGeneration": 1,
      "immutableArtifactId": "sha256:2222222222222222222222222222222222222222222222222222222222222222",
      "source": "bootstrap-runbook",
      "recordedBy": "platform-data"
    },
    "backup-object-store-credentials": {
      "evidenceType": "provisioning",
      "credentialClass": "backup-object-store-credentials",
      "targetEnvironment": "staging",
      "bootstrapOperationId": "bootstrap-staging-20260313-01",
      "provisioningGeneration": 1,
      "immutableArtifactId": "sha256:3333333333333333333333333333333333333333333333333333333333333333",
      "source": "bootstrap-runbook",
      "recordedBy": "platform-operations"
    },
    "operator-credentials": {
      "evidenceType": "provisioning",
      "credentialClass": "operator-credentials",
      "targetEnvironment": "staging",
      "bootstrapOperationId": "bootstrap-staging-20260313-01",
      "provisioningGeneration": 1,
      "immutableArtifactId": "sha256:4444444444444444444444444444444444444444444444444444444444444444",
      "source": "bootstrap-runbook",
      "recordedBy": "platform-operations"
    }
  }
}
```

## Player-Facing Environment Bootstrap Requirements

Before the first deployment into `hobby-self-hosted`, `staging`, or `production`, operators must provision a minimum bootstrap set of secrets and trust resources. This is the canonical bootstrap contract for environment and secret readiness:

The bootstrap workflow creates one durable `bootstrapOperationId`, starts with `bootstrapOperationStatus=pending`, and advances one positive `provisioningGeneration`. Inventory confirmation, the expected-binding manifest, namespace/resources, and every bootstrap evidence payload are bound to that operation and generation. A crash or partial apply leaves the environment `provisioningState=noncompliant` while the operation is `pending`, `blocked`, or `failed` (and it may remain `noncompliant` after `bootstrapOperationStatus=completed` when evidence or bindings do not validate); it does not return to `provisioningState=not-provisioned` after any required resource exists. Only `bootstrapOperationStatus=completed` with an exact generation match may project `provisioningState=provisioned`.

- `postgres-credentials`
- `postgres-admin-credentials` when rotation Jobs are used
- the Account-owned `jwt-jwks` ConfigMap and public-JWKS consumption evidence required by every player-facing custody mode
- exactly one selected JWT custody mode: the interim Account-only mounted fallback or target non-exportable signer custody
- for the interim mounted fallback only, `jwt-signing-keys` and `internalBindings.jwt.signingKeysRef`, with the private mount limited to Account Service
- for target non-exportable signer custody only, signer-health evidence and proof that no private signing material is mounted or distributed to application workloads outside the approved signer boundary
- a missing, unknown, or unimplemented selected custody mode fails closed; public JWKS readiness does not imply that private signing material is required
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

### `EDGE_PROXY` Deployment Evidence

This is target-state evidence, not a current readiness claim. No implemented `EDGE_PROXY`-specific preflight policy ID currently proves this contract. A deployment is not evidenced by a certificate binding reference alone; the deployment record and readiness evidence must identify the exact values used for the active environment:

- selected `TCP_PROXY_TELNET_MODE=EDGE_PROXY`, deployment identity, and environment boundary;
- the public edge listener and TLS terminator identity, including address, port, protocol, SNI or listener name, and certificate or issuer binding;
- the private TCP Proxy PROXY-protocol listener configured by `TCP_PROXY_PROXY_PROTOCOL_PORT`, including address, port, exposure boundary, protocol version, and the exact edge-to-proxy trust root;
- the permitted edge identity, expressed as the exact mTLS certificate identity or URI SAN accepted by TCP Proxy, plus the certificate or issuer binding used to establish it;
- the exact `GATEWAY_WS_URL`, derived Gateway readiness endpoint, TCP Proxy bridge client identity, and Gateway listener trust or permitted-identity binding; and
- readiness observations showing `/actuator/health/readiness` and `trafficAdmissionReadiness` as ready, including `telnetListener=LISTENING`, `gatewayGameplayPath=READY`, and the exact readiness URI, with observation time and deployment/evidence reference.

The target preflight must reject an `EDGE_PROXY` deployment when any listener, trust root, permitted identity, or readiness value is absent, resolves outside the environment boundary, or does not match the deployed binding. No implemented policy ID currently enforces that rejection: the checked-in environment manifests declare certificate binding references but do not contain this edge-specific listener, trust, permitted-identity, or live-readiness evidence. This remains an implementation and operational proof gap, not evidence that the edge contract is satisfied.

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
    - JWT private-material custody, Account issuance, JWKS publication, and rotation are governed by [JWT and Token Contracts](../system-architecture-jwt-and-token-contracts.md); environment configuration supplies only the approved mounts and watcher paths.
    - Database credentials are rotated by Jobs such as `db-credential-rotation` that update the relevant Secrets and restart consumers.
  - Direct, ad hoc edits to Secrets should be treated as emergency measures only and reconciled back into the appropriate Job/runbook flow so future rotations remain automated and repeatable.

Shared libraries support overriding default settings with environment variables using the `FIREMUD_` prefix (for example `FIREMUD_POSTGRES_HOST`, `FIREMUD_POSTGRES_PORT`). Each service merges these variables with its own `application.yml` profile.

The catalog document groups these variables by subsystem (PostgreSQL, Redis, TLS, Authentication, Service Discovery, Observability, Asset Storage, Backup & Restore) and documents their defaults.

---

## Certificate Management and Watchers

Mutual TLS protects internal service-to-service traffic in shared and player-facing Kubernetes environments. Explicit local-development and throwaway-test profiles may use the documented plaintext internal transports and do not provide player-facing or promotion evidence. Certificates are normally provisioned by **cert-manager** and mounted from per-workload Kubernetes Secrets so each service has a concrete private identity. These certificates secure:

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
  - `JwtSecretWatcher` monitors the Account-only JWT private signing file referenced by `FIREMUD_AUTH_JWT_SECRET_PATH`; JWT validators reload public JWKS instead.

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

The catalog groups variables by subsystem and keeps the tables and environment notes close to one another. When this overview mentions a concept that is backed by environment variables (for example Redis coordination roles or JWT TTL behavior), the catalog contains the concrete variable names and defaults.

---

## Related Documentation

- `environment-and-secrets.md` – Hub/entry point for environment variables and secrets.
- `environment-and-secrets-catalog.md` – Detailed environment variable catalog and resource/readiness notes.
- `deployment-environments.md` – How dev/staging/production environments are structured.
- `../system-architecture-security.md` – Security and TLS architecture, including key and certificate rotation.
- `../system-architecture-redis.md` – Redis architecture hub.
- `../system-architecture-authentication.md` – Authentication and authorization flows.
- `../system-architecture-redis-usage-and-profiles.md` – How Redis roles and profiles are wired in different environments.
