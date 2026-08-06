# Environment Variables & Secrets Catalog

This document is the detailed catalog of environment variables used for configuration and secrets. It is intended as a reference manual: open it when you need to know **“what does `FIREMUD_XYZ` do and where should I set it?”**

For a conceptual overview and operator quick reference, see `environment-and-secrets-overview.md`. For a minimal hub/entry point, see `environment-and-secrets.md`.

## Implementation Status

The target gameplay-continuity policy is independent of JWT lifetime. Its target default for `FIREMUD_AUTH_SESSION_EXPIRATION_MS` is `300000` ms (five minutes), and its inclusive valid range is `1..300000` ms. Current Game Session code still defaults this setting to one hour (`3600000` ms) and does not enforce the target range; the catalog records that out-of-range runtime default as implementation drift rather than target policy.

JWT implementation drift: the current runtime loads and immediately replaces one shared HMAC secret, permits the packaged classpath fallback when the mounted JWKS path is absent, and downstream validators do not yet consume Account JWKS. The checked-in Kubernetes baseline supplies signing material to workloads beyond Account, and the current deployment preflight checks signing paths and mounts for every primary workload rather than enforcing the Account-only boundary. Account-only asymmetric signing, `kid`/JWKS validation, profile-aware fail-closed startup, projected-volume reload proof, and the required JWT contract evidence remain incomplete. This catalog records the target and the drift; it does not claim runtime, preflight, or manifest convergence.

## Table of Contents

- [Common Application Settings](#common-application-settings)
- [PostgreSQL Credentials](#postgresql-credentials)
- [Redis Coordination & Cache](#redis-coordination--cache)
- [TLS & Certificates](#tls--certificates)
- [Authentication & JWT](#authentication--jwt)
- [Service Discovery](#service-discovery)
- [Observability](#observability)
- [Asset Storage](#asset-storage)
- [Backup & Restore Variables](#backup--restore-variables)
- [Additional Notes](#additional-notes)
- [Related Documentation](#related-documentation)

---

## Common Application Settings

Shared libraries support overriding default settings with environment variables using the `FIREMUD_` prefix (for example `FIREMUD_POSTGRES_HOST`, `FIREMUD_POSTGRES_PORT`). Each service merges these variables with its own canonical `application.yml`, and automated tests may additionally activate the `test` profile.

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `SPRING_PROFILES_ACTIVE` | Optional Spring profile override; reserved for cases such as `test` where a service deliberately activates test-only behavior | *(unset)* |

Shared and player-facing environments should normally leave `SPRING_PROFILES_ACTIVE` unset and run the canonical runtime defined by `application.yml`. Do not reintroduce local-vs-production contract drift by using ad hoc runtime profiles outside automated tests.

---

## PostgreSQL Credentials

Services connect to the shared PostgreSQL database using the following variables. These values are typically provided via Kubernetes Secrets in shared or player-facing Kubernetes environments.

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_POSTGRES_HOST` | Database host | `postgres` |
| `FIREMUD_POSTGRES_PORT` | Database port | `5432` |
| `FIREMUD_POSTGRES_DB` | Database name | `firemud` |
| `FIREMUD_POSTGRES_USER` | Username | `firemud` |
| `FIREMUD_POSTGRES_PASSWORD` | Password | `firemud` |

These defaults exist only to make local development and ephemeral stacks easy to bootstrap. Any non-ephemeral player-facing Kubernetes environment (`SPRING_PROFILES_ACTIVE=prod` in hobby-self-hosted/staging/production) must supply real, per-environment credentials via Kubernetes Secrets and must not run with `.env.sample`-style defaults.

In production, these variables are normally sourced from a Secret such as `postgres-credentials`. Higher-privilege credentials (for example in a `postgres-admin-credentials` Secret) are used by Kubernetes Jobs like `db-credential-rotation` to rotate application passwords as described in `../system-architecture-post-restore-hardening.md#post-restore-secret-hardening` and `../system-architecture-post-restore-hardening.md#planned-db-credential-rotation`. Routine rotation uses explicit operator runbooks rather than an automatic schedule.

---

## Redis Coordination & Cache

Redis stores transient queues and caches. All environments, including local development, use **separate Redis deployments** for:

- **Coordination Redis** – tick locks, timers, sessions, and other gameplay‑critical coordination keys.
- **Cache/Rate‑Limit Redis** – gateway rate limiting and best‑effort read‑side caches.

Coordination Redis (ticks, locks, timers, sessions):

| Variable | Purpose |
| -------- | ------- |
| `FIREMUD_REDIS_COORD_HOST` | Coordination Redis host |
| `FIREMUD_REDIS_COORD_PORT` | Coordination Redis port |
| `FIREMUD_REDIS_COORD_URL` | Coordination Redis URL (for example `redis://redis-coord:6379`) |

Cache/Rate‑Limit Redis (gateway rate limiting, caches):

| Variable | Purpose |
| -------- | ------- |
| `FIREMUD_REDIS_CACHE_HOST` | Cache/Rate‑Limit Redis host |
| `FIREMUD_REDIS_CACHE_PORT` | Cache/Rate‑Limit Redis port |
| `FIREMUD_REDIS_CACHE_URL` | Cache/Rate‑Limit Redis URL (for example `redis://redis-cache:6379`) |

Precedence rule:

- If `FIREMUD_REDIS_*_URL` is set for a role, it takes precedence over the corresponding `*_HOST` / `*_PORT` pair for that role.

Precedence and safety rules:

- All Spring profiles (dev and non‑dev) **must** configure explicit, **distinct** endpoints for coordination and cache/rate-limit traffic:
  - Coordination clients resolve their connection from `FIREMUD_REDIS_COORD_URL` (if set) or `FIREMUD_REDIS_COORD_HOST` / `FIREMUD_REDIS_COORD_PORT`.
  - Cache/rate‑limit clients resolve their connection from `FIREMUD_REDIS_CACHE_URL` (if set) or `FIREMUD_REDIS_CACHE_HOST` / `FIREMUD_REDIS_CACHE_PORT`.
- Services **fail fast at startup** if:
  - They require Coordination Redis but lack either `FIREMUD_REDIS_COORD_URL` or `FIREMUD_REDIS_COORD_HOST` / `FIREMUD_REDIS_COORD_PORT`, or
  - They require Cache/Rate‑Limit Redis but lack either `FIREMUD_REDIS_CACHE_URL` or `FIREMUD_REDIS_CACHE_HOST` / `FIREMUD_REDIS_CACHE_PORT`.
- It is **not supported** to point Coordination and Cache/Rate‑Limit roles at the same resolved endpoint in any **non-ephemeral** environment, including local development, staging, and production. This prohibition applies regardless of whether configuration uses `*_URL` or `*_HOST`/`*_PORT`. Coordination and cache/rate‑limit roles run on separate Redis deployments (for example, two containers on the same developer machine).

Player‑facing environments (`hobby-self-hosted`, staging, production, and any environment used to validate performance or correctness) **must** configure Coordination Redis and Cache/Rate‑Limit Redis as **distinct logical Redis deployments**. Reusing the same host/port for both is considered non‑compliant with the Redis architecture because it reintroduces eviction and latency coupling between coordination keys and cache/rate‑limit traffic. Any ad-hoc “single Redis for all roles” topology is treated as an unsupported experiment and must not be used for shared or player-facing environments or for any cluster that runs coordination reset tooling.

Truly ephemeral environments (for example one-shot CI utility stacks) may collapse roles into a single Redis instance only when explicitly documented and guarded as an **ephemeral topology**. Hosted `pr-preview` environments are not part of that exception; they should preserve the normal role split because they are intended to exercise reviewer-facing full-stack behavior. Any environment using the ephemeral exception must make it obvious in dashboards/health output that roles are sharing an endpoint so it cannot be mistaken for a production-like configuration. See `../system-architecture-redis-usage-and-profiles.md#environment-mappings` for the allowed exception and guardrails.

---

## TLS & Certificates

Mutual TLS protects internal service-to-service traffic in shared and player-facing Kubernetes environments. Certificates are normally provisioned by **cert-manager** and mounted from distinct per-workload Kubernetes Secrets so every service has a concrete private identity. Explicit local-development and throwaway-test profiles may use plaintext internal transport, including the documented `ws://` Proxy-to-Gateway bridge, and do not provide player-facing or promotion evidence. These certificates secure:

- All gRPC calls between services
- Any internal WebSocket bridges that require mTLS (for example, the TCP Proxy Service connecting to Spring Cloud Gateway over `wss://`)

A sample `Certificate` manifest is provided at `k8s/base/firemud-grpc-certificate.yaml`.

### gRPC TLS Certificates

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_GRPC_CERT_CHAIN_PATH` | Filesystem path to the certificate chain for this service | `certs/client.crt` |
| `FIREMUD_GRPC_PRIVATE_KEY_PATH` | Filesystem path to the private key matching the certificate chain | `certs/client.key` |
| `FIREMUD_GRPC_CA_CERT_PATH` | Filesystem path to the CA bundle used to verify peer services | `certs/ca.crt` |

### Gateway HTTP Management-Plane TLS (Target State)

Gateway-owned HTTP management endpoints use a dedicated operator client identity and listener trust bundle. These paths are separate from the Gateway service's `FIREMUD_GRPC_*` workload identity and are supplied to operator tooling from a dedicated credential Secret:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_GATEWAY_HTTP_CLIENT_CERT_CHAIN_PATH` | Filesystem path to the operator client certificate chain presented to the Gateway HTTP management plane | *(none)* |
| `FIREMUD_GATEWAY_HTTP_CLIENT_PRIVATE_KEY_PATH` | Filesystem path to the private key matching the Gateway HTTP management-plane client certificate chain | *(none)* |
| `FIREMUD_GATEWAY_HTTP_CA_CERT_PATH` | Filesystem path to the CA bundle used to validate the Gateway HTTP management-plane listener certificate | *(none)* |

These variables describe the target-state HTTP mTLS contract; the current Gateway implementation status is tracked in the [Gateway service README](../microservices/spring-cloud-gateway/README.md#implementation-status).

For the **TCP Proxy Service → Spring Cloud Gateway WebSocket mTLS hop**, the following variables configure the dedicated client identity and trust bundle used by the proxy’s WebSocket bridge:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `GATEWAY_WS_URL` | WebSocket URL for the proxy’s bridge to Spring Cloud Gateway (for example `ws://spring-cloud-gateway:8080/ws/game` in local dev or `wss://spring-cloud-gateway-mtls:8443/ws/game` in production) | *(none)* |
| `FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH` | Filesystem path to the client certificate chain presented by the TCP Proxy when connecting to the Gateway’s mTLS WebSocket listener | `certs/client.crt` |
| `FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH` | Filesystem path to the private key matching the WebSocket client certificate chain | `certs/client.key` |
| `FIREMUD_GATEWAY_WS_CA_CERT_PATH` | Filesystem path to the CA bundle used to validate the Gateway’s mTLS WebSocket listener certificate | `certs/ca.crt` |

In development and CI environments it is acceptable to point `GATEWAY_WS_URL` at a `ws://` endpoint without configuring the `FIREMUD_GATEWAY_WS_*` variables. In any player-facing environment (`hobby-self-hosted`, staging, production), `GATEWAY_WS_URL` must target the Gateway’s internal-only mTLS WebSocket listener and the `FIREMUD_GATEWAY_WS_*` paths must be set so the proxy can both authenticate the Gateway and present its own client certificate, as described in `../system-architecture-security.md#tls-termination-for-gateway` and the TCP Proxy Service design (`../microservices/tcp-proxy-service/README.md#websocket-mtls-to-spring-cloud-gateway-target-state-see-implementation-status`).
Player-facing deployments must also enforce an explicit alignment check between `GATEWAY_WS_URL` and the expected environment listener (for example via preflight policy validation and TCP Proxy readiness checks). If the resolved `GATEWAY_WS_URL` target does not match the intended internal Gateway endpoint for that environment, deployment and readiness should fail.

The canonical bridge target is the same gameplay entry point used by native WebSocket clients (`/ws/game/**`) so Telnet and WebSocket flows traverse the same gateway filters, metrics, and downstream routing.

During local development these values are generated automatically, so the variables may be omitted.

Docker Compose mounts `dev-tools/certs` into each service container at `/app/certs` so the default paths above resolve correctly.

In Kubernetes deployments the certificates are mounted at `/tls`, and the environment variables point to that directory (for example, `FIREMUD_GRPC_CERT_CHAIN_PATH=/tls/client.crt`). Services watch these files for changes so new certificates are loaded without restarts via `TlsCertificateWatcher`. Certificate reload for gRPC servers uses `GrpcServerTlsReloader` to hot reload certificates when Secrets change. See `../system-architecture-security.md#key-and-certificate-rotation` for details on the hot reload mechanism.

> Note: Certificate files should be loaded from the filesystem rather than packaged inside the application. Avoid `classpath:` URIs so that TLS materials can be mounted securely via volumes or Secrets.

---

## Authentication & JWT

The canonical JWT profile, registry, authority-generation, signing, publication, convergence, and rotation contract is [JWT and Token Contracts](../system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative). This catalog owns environment variables, filesystem mounts, and Kubernetes resource modes; JWT implementation status is summarized above. Certificate and secret watching is described in [Security](../system-architecture-security.md#key-and-certificate-rotation).

Target state delivers the public JWKS to Account and every JWT validator through each workload's read-only `FIREMUD_AUTH_JWKS_PATH`. Signing material remains Account-only: the interim asymmetric fallback uses a file-mounted bundle in Account, and only Account uses `JwtSecretWatcher` for that private path; validators reload the public JWKS projection. The packaged classpath JWKS fallback is permitted only in explicit local/test profiles. Player-facing Account and validator startup/readiness fails closed when the path or file is unset, missing, unreadable, malformed, or cannot support the required asymmetric `kid`; validators never receive private signing material. The current checked-in environment manifests and preflight still use the Secret-backed JWKS resource contract; the target ConfigMap resource mode and validator-specific delivery are distinct and must be explicitly selected and proved by target preflight.

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_AUTH_JWT_SECRET` | Inline JWT signing key material for local/dev and ephemeral stacks only (legacy compatibility mode) | *(none)* |
| `FIREMUD_AUTH_JWT_SECRET_PATH` | Interim asymmetric-fallback path to a versioned JWT signing bundle for Account Service in player-facing environments. Current code still treats this as one HMAC secret. | *(none)* |
| `FIREMUD_AUTH_JWKS_PATH` | Target-state workload-specific filesystem path to the published `jwks.json` consumed by Account Service and each JWT validator; in player-facing environments it must point into that workload's read-only public `jwt-jwks` mount. It is a public-key delivery selector, not a publication or private-signing path. | *(unset; target-state `classpath:jwks.json` fallback is local/test only)* |
| `FIREMUD_AUTH_JWT_EXPIRATION_MS` | Lifetime of issued JWTs in milliseconds | `3600000` |
| `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS` | Cleanup margin added to each token's remaining lifetime for issued-token registry retention only | `300000` |
| `FIREMUD_AUTH_SESSION_EXPIRATION_MS` | Initial gameplay-continuity retention; target range is `1..300000` ms and target default is five minutes | `300000` (target; current Game Session default is `3600000`) |

Server-side gameplay sessions use a distinct bounded continuity policy:

- `session_expiration_ms = min(FIREMUD_AUTH_SESSION_EXPIRATION_MS, 300000)` after target-range validation; the existing `300000` ms maximum is a hard design bound, not permission to accept an unbounded positive input.

Startup and deployment preflight must parse both session settings as finite integer millisecond values before initializing runtime state or Redis TTLs. `FIREMUD_AUTH_SESSION_EXPIRATION_MS` must be within the inclusive range `1..300000`; zero, negative, non-integral, non-finite, and above-maximum values fail startup or preflight closed. `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS` must be non-negative, and its addition to a token's remaining lifetime must not overflow. Invalid input fails startup or preflight closed rather than producing a zero, negative, or wrapped TTL.

This value defines the initial continuity-retention and physical cleanup horizon independently of JWT lifetime, issued-token cleanup retention, and active connected-session liveness. It establishes immutable `continuityBindingExpiresAt` at admission and seeds the reconnect/recovery TTL for `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` (see `../system-architecture-redis.md#session-keys-and-gameplay-binding`). Passing that anchor atomically retires reconnect eligibility and its expiring recovery record; it does not remove the distinct fenced region-local gameplay binding or kick a continuously connected player whose transport, active-session lease, current authorization, and rotated backend token remain valid. A subsequent disconnect cannot recreate the expired continuity episode. Every refresh uses an absolute server-side minimum that preserves an earlier physical expiry and never exceeds `continuityBindingExpiresAt`. Focused proof must cover anchor expiry while connected, the later disconnect becoming non-resumable, and ordinary authorization or lease termination still closing the active binding. Each JWT remains valid only through its own `exp`.

Disconnected resume eligibility is the stricter of the remaining continuity-binding lifetime and `firemud.reconnection.policy.resume-window-ms`, measured from disconnect/suspension; the default resume window is three minutes. A stale binding follows `firemud.reconnection.policy.stale-resume-falls-through-to-fresh-entry` when current admission still permits fresh entry. Reconnect transcript retention is a separate bounded policy under `firemud.reconnection.buffer.*` and does not extend authorization or resume eligibility.

Changing `FIREMUD_AUTH_JWT_EXPIRATION_MS` in a running cluster changes the `exp` claim only for newly issued JWTs; already issued JWTs retain their signed `exp`. Deleting their registry record, advancing an applicable authority generation, or applying a hard cutover can reject them before that deadline, but none of those operations mutates the token's claim. Changing `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS` affects registry cleanup only. Changing `FIREMUD_AUTH_SESSION_EXPIRATION_MS` affects newly admitted gameplay bindings, subject to the five-minute cap. An existing binding retains its immutable `continuityBindingExpiresAt`; every physical Redis TTL refresh is capped at `min(requestedTtlMs, max(0, continuityBindingExpiresAt - now))`, so key presence and TTL never extend authentication validity or resume authority beyond that logical anchor. Tightening the continuity lifetime does not rewrite existing anchors and may leave physically retained keys after their bindings become non-resumable. When a major lifetime reduction requires a clean cut-over, operators must use the scoped session cleanup workflow described in `../system-architecture-redis-incident-runbook.md#session-schema-and-ttl-cleanup` so admission draining, active-session safety checks, audit evidence, and rollback remain part of the operation. Ad-hoc wildcard `DEL` is not a supported cleanup path.

In player-facing environments (`hobby-self-hosted`, staging, production), the Secret-mounted private bundle and Secret/ConfigMap public projection are an interim asymmetric fallback. The fixed, pre-created `jwt-signing-keys` Secret is exposed only to Account Service through the read-only `FIREMUD_AUTH_JWT_SECRET_PATH` mount (canonical bundle `/var/run/secrets/firemud/jwt/current.key`). The current checked-in public resource is a fixed `jwt-jwks` Secret mounted into Account at `/var/run/secrets/firemud/jwks` and selected by `FIREMUD_AUTH_JWKS_PATH`; target environments use a fixed `jwt-jwks` ConfigMap delivered read-only to every validator. No checked-in automation currently proves the target JWT lifecycle or validator convergence.

Initial publication uses Account's normal JWKS publication authority. Account readiness, token issuance, validation-dependent protected traffic, and validator readiness remain quarantined until the required private/public projection proof succeeds; there is no separate bootstrap writer or one-time publication authority.
Non-player-facing environments (`local-dev`, `pr-preview`, `dev-demo-cluster`) use the same public JWKS classification and may generate environment-unique test material for convenience.
Current checked-in hosted `pr-preview` manifests and `dev-tools/deploy/preflight.py` use a preview-unique, pre-created signing-key `Secret` plus a preview-unique `jwt-jwks` `Secret`; `PREFLIGHT-JWKS-001` rejects a `ConfigMap` and checks the `jwt-jwks` mount only for Account Service. A passing current preflight is legacy wiring evidence, not proof that every validator receives or can fail closed on Account JWKS.
Target hosted `pr-preview` environments instead use a preview-unique, Account-published `jwt-jwks` `ConfigMap` in the preview namespace and deliver its public projection through `FIREMUD_AUTH_JWKS_PATH` to Account and every validator. Shared preview JWT material across namespaces is not allowed.
Preview namespaces still participate in expected-bindings and preflight checks, but with a preview-scoped contract: preview-unique JWT/JWKS material, isolated internal service bindings, and the normal Redis role split are mandatory, while player-facing backup/admission binding proofs remain reserved for staging/production and other explicitly player-facing environments.
In the target player-facing contract (`hobby-self-hosted`, staging, production), `FIREMUD_AUTH_JWT_SECRET_PATH` is required for Account Service only, while `FIREMUD_AUTH_JWKS_PATH` is required for Account Service and every JWT validator. Account startup must fail closed when either Account path is unset, missing, or unreadable; when the private signing bundle is incomplete, malformed, or non-asymmetric; or when the JWKS is malformed or does not contain a public JWK matching the Account signing key and `kid`. Every validator must fail closed when its public JWKS path is unset, missing, unreadable, malformed, unavailable for asymmetric verification, or accompanied by private signing material. There is no classpath fallback in player-facing profiles, and Account must also fail on inline-only or HMAC-only signing configuration. The current preflight does not yet enforce this validator-specific JWKS path boundary.

---

## Service Discovery

The shared configuration library resolves other services using environment variables prefixed with `FIREMUD_SERVICES_`. Each variable holds a `host:port` pair for a target service. When undefined, Kubernetes DNS is used instead. These overrides are consumed by the `ServiceEndpointsProperties` class so gRPC clients can dynamically point to different hosts. Spring Cloud Gateway also reads these overrides to route requests during tests or failover scenarios.

Each variable is suffixed with `_SERVICE` to match the Spring configuration keys. Examples:

```bash
FIREMUD_SERVICES_GAME_LOGIC_SERVICE=game-logic-service:6565
FIREMUD_SERVICES_LOGGING_ADMIN_SERVICE=logging-admin-service:6565
```

Player-facing environments (`hobby-self-hosted`, staging, production) must treat these overrides as exceptional. The compliant default is to leave them unset and use in-environment Kubernetes DNS/service discovery. If any `FIREMUD_SERVICES_*` override is required in a player-facing environment, deployment preflight must validate it against `design/operations/environments/<environment>/expected-bindings.yaml`; undeclared overrides or values that resolve into another environment boundary must fail deployment.

---

## Observability

Services are instrumented to create OpenTelemetry spans, but an environment may advertise only a capability level proved end to end under ADR 0017. The collector endpoint can be overridden with the `OTEL_ENDPOINT` environment variable (mapped to the Spring property `otel.endpoint`):

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `OTEL_ENDPOINT` | gRPC endpoint for the OpenTelemetry collector | `http://otel-collector:4317` |
| `OTEL_TRACES_SAMPLER` | Target OpenTelemetry sampler control; unsupported until the shared SDK consumes and proves it | Not currently supported |
| `OTEL_TRACES_SAMPLER_ARG` | Target sampler argument; unsupported until the shared SDK consumes and proves it | Not currently supported |
| `FLUENT_ELASTICSEARCH_HOST` | Hostname of the log storage backend | `elasticsearch` |
| `FLUENT_ELASTICSEARCH_PORT` | Port for the log storage backend | `9200` |

Local Docker Compose stacks that do not run an OpenTelemetry collector should set `OTEL_ENDPOINT` to an empty value to disable exporting spans (services still create traces, but they are not exported).

Service design documents reference this table for the OpenTelemetry endpoint configuration.

### Tracing Capability Advertisement

Each environment catalog entry must advertise one of the four ADR 0017 levels and its proved workflow coverage. The current repository proof supports level 1 only:

| Level | Capability | Required proof before advertising |
| --- | --- | --- |
| `1` | Baseline observability | Metrics and structured logs are available; generic spans/export and trace-log correlation remain best-effort unless separately proved. |
| `2` | Workflow tracing | A named workflow's semantic spans, bounded attributes, context propagation, collector ingestion, and supported queries are proved end to end. |
| `3` | Service-scoped incident sampling | Level 2 coverage plus wired sampler controls and a successful increase/observe/revert drill. |
| `4` | Tenant/game-instance/region-scoped incident sampling | Candidate traces survive upstream sampling, scope attributes propagate, bounded collector tail sampling can be safely enabled/reverted, and increased visibility plus return to baseline are proved. |

| Environment class | Current advertised level | Proved workflow coverage |
| --- | --- | --- |
| Local/dev/test | `1` baseline observability | Metrics and structured logs only; no named workflow or scoped-sampling guarantee. |
| Preview/dev-demo | `1` baseline observability | Metrics and structured logs only; generic spans are best-effort and no named workflow is proved. |
| Hobby/self-hosted, staging, production | `1` baseline observability | Metrics and structured logs only; no level-2 workflow coverage, level-3 service escalation, or level-4 tenant/game-instance/region sampling is currently proved. |

The advertisement must be lowered or the environment kept fail-closed for any runbook step that requires a higher level. Scoped incident sampling support (matching by `tenantId` / `gameInstanceId` / `regionId`) depends on OpenTelemetry Collector policy configuration and cannot be inferred from environment variables, example manifests, or the collector endpoint alone.

Legacy sampling labels must not be treated as capability advertisements. If deployment metadata retains either label, it must also carry the numeric ADR 0017 level and the corresponding proof; a label alone cannot authorize a higher-level runbook step.

---

## Asset Storage

Published game assets are uploaded to an S3-compatible bucket. The following variables configure the S3 client used by services:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `ASSET_STORE_ENDPOINT` | URL of the S3-compatible service | *(none)* |
| `ASSET_STORE_BUCKET` | Bucket name for published assets | *(none)* |
| `ASSET_STORE_REGION` | Region for the S3 client | `ap-southeast-2` |
| `ASSET_STORE_ACCESS_KEY` | Access key credential | *(none)* |
| `ASSET_STORE_SECRET_KEY` | Secret key credential | *(none)* |

In Kubernetes environments, these values should be sourced from a per-environment Secret and must not be shared between staging and production. After any cluster restore, operators should confirm the restored environment is bound to the correct asset-store credentials and that staging cannot publish to production buckets.
For player-facing environments, the canonical expected-binding manifest must also name the credential-binding identity for the asset-store Secret or workload identity used by these settings, so preflight can prove the environment owns the object-store target rather than only matching bucket and endpoint text.

---

## Backup & Restore Variables

Operational scripts and CronJobs rely on the following variables when uploading or restoring database dumps.

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `PG_DUMP_BUCKET` | Object storage bucket for pg_dump files | *(none)* |
| `PG_DUMP_ENDPOINT` | Optional S3-compatible endpoint URL | *(none)* |
| `FIREMUD_K8S_NAMESPACE` | Namespace override used by restore/verification scripts for drills or non-default restores | `firemud` |
| `EXTERNAL_CREDENTIAL_EVIDENCE_REF` | In-repo recovery record with external credential validation results (`design/operations/deployments/<environment>/recovery/<recovery-ref>.json`) | *(none)* |
| `SANITIZATION_EVIDENCE_REF` | Immutable pre-release evidence path proving staging data sanitization after production-origin restore (`design/operations/deployments/staging/recovery/<recovery-ref>.sanitization.json`) | *(none)* |
| `EXPECTED_BINDINGS_REF` | Canonical expected-binding manifest consumed by deploy preflight and restore validation (`design/operations/environments/<environment>/expected-bindings.yaml`) | *(none)* |

In Kubernetes environments, object-store credentials should be stored in per-environment Secrets and must not be shared between staging and production. `PG_DUMP_ENDPOINT` is required only for S3-compatible endpoints such as MinIO; when unset, tooling uses the AWS default endpoint behavior.
Each environment boundary uses the standard `firemud` namespace by default (same namespace name, separate environment credentials/secrets). `FIREMUD_K8S_NAMESPACE` is primarily an explicit override for throwaway-namespace restore tests and rehearsals.
For player-facing environments, these bindings are part of both bootstrap validation and normal deployment preflight, not just restore validation. Operators must be able to prove that backup/object-store, asset-store, outbound-communications, and operator credential bindings resolve to the intended environment before traffic is opened. The canonical enforcement point for those checks is `../system-architecture-deploy-preflight-policy.md`.
For backup and asset storage specifically, that proof must include the binding identity used for the per-environment Secret or workload identity, not only the bucket/endpoint pair.
These variables cover the external-binding side of that contract. Internal state/trust bindings such as PostgreSQL, Redis, JWT/JWKS, certificate issuer, and registry pull credentials are validated through the same `design/operations/environments/<environment>/expected-bindings.yaml` manifest, but they are checked as environment-owned cluster-local bindings rather than as globally unique external targets.

See `../system-architecture-backup-recovery.md` for schedules and retention policies.
`dev-tools/restores/validate-external-credentials.sh` supports `hobby-self-hosted`, `staging`, and `production`; all player-facing restore validations require `EXTERNAL_CREDENTIAL_EVIDENCE_REF`, and staging validations additionally require `SANITIZATION_EVIDENCE_REF` so restore hardening cannot pass without explicit sanitization evidence.
`EXPECTED_BINDINGS_REF` should point at the same environment-specific manifest that deploy preflight uses, so deployment and recovery validate against one expected-binding source of truth.

---

## Additional Notes

Service-specific settings such as SMTP credentials for the Account Service or `GAME_TICK_DURATION_MS` for the Game Session Service are documented in each service's design README. See the "Environment Variables" sections in:

- `../microservices/account-service/README.md#environment-variables`
- `../microservices/game-session-service/README.md#environment-variables`

This catalog covers only shared configuration keys.

Operational scripts like `dev-tools/restores/restore-cluster.sh` use an optional `FIREMUD_K8S_NAMESPACE` override to target non-default namespaces during restore drills. In normal shared-environment operations, namespace selection should stay aligned with the standard overlay namespace (`firemud`).

---

## Related Documentation

- `environment-and-secrets.md` – Hub/entry point for environment variables and secrets.
- `environment-and-secrets-overview.md` – Conceptual overview and operator quick reference.
- `deployment-environments.md` – How dev/staging/production environments are structured.
- `../system-architecture-security.md` – Security and TLS architecture, including key and certificate rotation.
- `../system-architecture-redis.md` – Redis architecture hub.
- `../system-architecture-authentication.md` – Authentication and authorization flows.
- `../system-architecture-redis-usage-and-profiles.md` – How Redis roles and profiles are wired in different environments.
