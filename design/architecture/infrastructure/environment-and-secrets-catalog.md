# Environment Variables & Secrets Catalog

This document is the detailed catalog of environment variables used for configuration and secrets. It is intended as a reference manual: open it when you need to know **“what does `FIREMUD_XYZ` do and where should I set it?”**

For a conceptual overview and operator quick reference, see `environment-and-secrets-overview.md`. For a minimal hub/entry point, see `environment-and-secrets.md`.

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

In production, these variables are normally sourced from a Secret such as `postgres-credentials`. Higher-privilege credentials (for example in a `postgres-admin-credentials` Secret) are used by Kubernetes Jobs like `db-credential-rotation` to rotate application passwords as described in `system-architecture-backup-recovery.md#post-restore-secret-hardening` and `system-architecture-backup-recovery.md#planned-db-credential-rotation`. Routine rotation uses explicit operator runbooks rather than an automatic schedule.

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

Mutual TLS protects all internal service-to-service traffic. Certificates are normally provisioned by **cert-manager** and mounted from Kubernetes Secrets. These certificates secure:

- All gRPC calls between services
- Any internal WebSocket bridges that require mTLS (for example, the TCP Proxy Service connecting to Spring Cloud Gateway over `wss://`)

A sample `Certificate` manifest is provided at `k8s/base/firemud-grpc-certificate.yaml`.

### gRPC TLS Certificates

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_GRPC_CERT_CHAIN_PATH` | Filesystem path to the certificate chain for this service | `certs/client.crt` |
| `FIREMUD_GRPC_PRIVATE_KEY_PATH` | Filesystem path to the private key matching the certificate chain | `certs/client.key` |
| `FIREMUD_GRPC_CA_CERT_PATH` | Filesystem path to the CA bundle used to verify peer services | `certs/ca.crt` |

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

JWT tokens secure internal service calls. Production keys are provided via Kubernetes Secrets and mounted key files, while development instances may generate random secrets. When `FIREMUD_AUTH_JWT_SECRET_PATH` is set, the service watches the file for changes using `JwtSecretWatcher` so keys can be rotated without restarts. Certificate and secret watching is described in `../system-architecture-security.md#key-and-certificate-rotation` and `../system-architecture-security.md#jwt-key--jwks-rotation-workflow`.

Implementation note: the file-mounted JWT contract is live. Shared security can initialize from `firemud.auth.jwt-secret-path` without an inline secret, the configured file value becomes the effective signing secret even when an inline fallback is present, and `JwtSecretWatcher` reloads file changes. Account Service reads `firemud.auth.jwks-path` on each JWKS request, with the packaged classpath resource retained only as a local/test fallback. The checked-in Kubernetes baseline supplies both mounted paths. Profile-aware startup enforcement that rejects inline-only signing material or classpath-only JWKS in every player-facing environment, plus automated coordinated signing-key/JWKS rotation proof, remains incomplete.

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_AUTH_JWT_SECRET` | Inline JWT signing key material for local/dev and ephemeral stacks only (legacy compatibility mode) | *(none)* |
| `FIREMUD_AUTH_JWT_SECRET_PATH` | Path to a file containing JWT signing key material; enables hot reload. In staging and production this file is typically sourced from the `jwt-signing-keys` Secret. | *(none)* |
| `FIREMUD_AUTH_JWT_EXPIRATION_MS` | Lifetime of issued JWTs in milliseconds | `3600000` |
| `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS` | Extra time added to the JWT lifetime when deriving server-side session TTL | `300000` |

Server-side gameplay sessions use a **derived lifetime** instead of a separately tuned TTL knob:

- `session_expiration_ms = FIREMUD_AUTH_JWT_EXPIRATION_MS + FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`

This value derives the immutable logical gameplay-session lifetime established at admission and the initial physical cleanup TTL for `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` keys in Redis (see `../system-architecture-redis.md#session-keys-and-gameplay-binding`). `gameplaySessionExpiresAt` governs binding validity and resumability; an active write may refresh the physical TTL, but any key retained after logical expiry is non-authoritative cleanup residue. This value does not extend JWT authentication: each JWT remains valid only through its own `exp`, and an expired token is rejected even if the server-side binding or Redis key remains. It is not the player-facing disconnected-resume eligibility window.

Disconnected resume eligibility is the stricter of the remaining absolute session lifetime and `firemud.reconnection.policy.resume-window-ms`, measured from disconnect/suspension; the default resume window is three minutes. A stale binding follows `firemud.reconnection.policy.stale-resume-falls-through-to-fresh-entry` when current admission still permits fresh entry. Reconnect transcript retention is a third, independent bounded policy under `firemud.reconnection.buffer.*` and does not extend either session validity or resume eligibility.

Changing `FIREMUD_AUTH_JWT_EXPIRATION_MS` or `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS` in a running cluster affects newly issued JWTs and newly admitted gameplay bindings. An existing binding retains its immutable `gameplaySessionExpiresAt`; active writes may refresh the key's physical Redis TTL, but key presence and TTL never extend authentication validity or resume authority beyond that logical anchor. Tightening JWT validity takes effect when credentials are next validated, while tightening the session lifetime does not rewrite existing anchors and may leave physically retained keys after their bindings become non-resumable. When a major lifetime reduction requires a clean cut-over, operators must use the scoped session cleanup workflow described in `../system-architecture-runbooks.md#redis-session-schema-and-ttl-cleanup` so admission draining, active-session safety checks, audit evidence, and rollback remain part of the operation. Ad-hoc wildcard `DEL` is not a supported cleanup path.

In player-facing environments (`hobby-self-hosted`, staging, production), JWT signing keys are stored in a `jwt-signing-keys` Secret and exposed to the Account Service via the file pointed to by `FIREMUD_AUTH_JWT_SECRET_PATH`. The JWKS document is also stored in a `jwt-jwks` Secret in these environments. The dedicated `jwt-rotation` Job, overlap/pruning behavior, validator convergence checks, and retained rotation proof described in `../system-architecture-security.md#jwt-key--jwks-rotation-workflow` remain target-state follow-through; no checked-in rotation Job currently updates these resources automatically.
In non-player-facing environments (`local-dev`, `pr-preview`, `dev-demo-cluster`), a JWKS ConfigMap may be used for convenience when keys are explicitly non-sensitive test material.
Hosted `pr-preview` environments use this as the canonical default: a preview-unique signing-key `Secret` plus a preview-unique JWKS `ConfigMap` in the preview namespace. Shared preview JWT material across namespaces is not allowed.
Preview namespaces still participate in expected-bindings and preflight checks, but with a preview-scoped contract: preview-unique JWT/JWKS material, isolated internal service bindings, and the normal Redis role split are mandatory, while player-facing backup/admission binding proofs remain reserved for staging/production and other explicitly player-facing environments.
In player-facing environments (`hobby-self-hosted`, staging, production), `FIREMUD_AUTH_JWT_SECRET_PATH` is required and startup should fail if the service is configured with only `FIREMUD_AUTH_JWT_SECRET`.

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

All services export OpenTelemetry spans. The collector endpoint can be overridden with the `OTEL_ENDPOINT` environment variable (mapped to the Spring property `otel.endpoint`):

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `OTEL_ENDPOINT` | gRPC endpoint for the OpenTelemetry collector | `http://otel-collector:4317` |
| `OTEL_TRACES_SAMPLER` | OpenTelemetry sampler (for example `parentbased_traceidratio`) | `parentbased_traceidratio` |
| `OTEL_TRACES_SAMPLER_ARG` | Sampler argument (for example ratio `0.01`) | `0.01` |
| `FLUENT_ELASTICSEARCH_HOST` | Hostname of the log storage backend | `elasticsearch` |
| `FLUENT_ELASTICSEARCH_PORT` | Port for the log storage backend | `9200` |

Local Docker Compose stacks that do not run an OpenTelemetry collector should set `OTEL_ENDPOINT` to an empty value to disable exporting spans (services still create traces, but they are not exported).

Service design documents reference this table for the OpenTelemetry endpoint configuration.

Scoped incident sampling support (matching by `tenantId` / `regionId`) also depends on OpenTelemetry Collector policy configuration, not only service env vars. Environments should be explicitly tagged as either:

- `service-scoped-sampling-only`, or
- `scoped-tenant-region-sampling-enabled` (tail-sampling policy support present and verified).

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
| `SANITIZATION_EVIDENCE_REF` | In-repo evidence path proving staging data sanitization after production-origin restore (`design/operations/deployments/staging/recovery/<recovery-ref>.json`) | *(none)* |
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
