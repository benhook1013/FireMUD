# Deployment Environments

This document outlines how FireMUD is deployed across environments including local Docker Compose, hosted pull-request preview environments, self-hosted hobby deployments, and Kubernetes-backed shared environments (dev/demo, staging, production). It includes discovery mechanisms, health check strategies, and environment-specific control expectations.

## Table of Contents

- [Canonical Environment Classes](#canonical-environment-classes)
- [Local Development: Docker Compose](#local-development-docker-compose)
- [Production: Kubernetes](#production-kubernetes)
- [Telnet TLS Deployment](#telnet-tls-deployment)
- [Monitoring & Logging](#monitoring--logging)
- [Spring Profile Configuration](#spring-profile-configuration)
- [Staging Environment for Playtesting](#staging-environment-for-playtesting)
- [Related Documentation](#related-documentation)

---

## Quick Environment Decision Guide

- Use **Docker Compose** for local development only.
- Use **Kubernetes shared or player-facing environment classes** (`dev-demo-cluster`, `staging`, `production`, or `hobby-self-hosted`) where autoscaling, high availability, and full observability are required.
- Use **`pr-preview`** for reviewer-accessible pull-request environments that must expose the full stack over HTTPS for the lifetime of the PR.
- Prefer **staging** for playtests that should mirror production routing, TLS, and Redis/Postgres topologies before promoting changes. `pr-preview` is for isolated PR validation, not for canonical creator playtests or promotion evidence.

## Implementation Notes

The environment matrix below is the canonical target state. Current repository implementation has partial support only for several deployment gates:

- `./dev-tools/deploy/preflight.py` is the intended entrypoint, but it does not yet enforce every player-facing policy listed in `system-architecture-deploy-preflight-policy.md`; missing enforcement remains a blocker for first player-facing deployment and traffic-open events.
- The player-facing expected-binding manifests under `design/operations/environments/` currently describe the intended shape but are not yet complete authoritative inputs for preflight or restore validation.
- Hosted `pr-preview` and `dev-demo-cluster` currently reuse the preview Helm values path, and the live preview value renderer now injects per-render signing-key and JWKS content rather than relying on one static inline shared JWT secret. The remaining JWT/JWKS gap is lifecycle/rotation proof, not generation or resource wiring.
- The player-facing Kustomize path (`k8s/overlays/*` over `k8s/base`) now carries digest-pinned workload images and relies on externally managed bootstrap secrets/TLS bindings enforced through expected-bindings plus preflight, rather than shipping placeholder bootstrap secrets inside the rendered player-facing manifests.

When this document says an environment “must” satisfy a gate, that is a target-state requirement. If the current tooling cannot prove the gate yet, the deployment remains blocked or requires explicit manual evidence recorded outside the incomplete tool output.

## Terms

- `player-facing`: any environment that may accept real player traffic or is used to validate player-visible operational correctness. In FireMUD this includes `hobby-self-hosted`, `staging`, and `production`.
- `quarantined`: an environment boundary that would otherwise be player-facing by class but is temporarily prevented from serving external player traffic during restore, drill, or detached maintenance. Quarantined staging or production work does not count as player-facing for traffic-open or player-impact severity decisions until quarantine is removed.
- `traffic-open`: the operational state in which an environment is allowed to accept external player traffic. Player-facing environments do not become traffic-open merely because workloads are healthy; they must also satisfy the applicable backup, recovery, and preflight gates for that event.
- `promotion candidate`: a staging deployment record that is eligible to produce production promotion evidence. Detached or quarantined staging drills may be valid operational exercises without being promotion candidates.
- `production`: the primary player-facing environment with the strictest change gates and mandatory scheduled backup posture.
- `shared canonical-runtime Kubernetes environments`: Kubernetes-backed environments that run the shared canonical runtime configuration and Kubernetes Secret delivery model. This includes `dev-demo-cluster`, `hobby-self-hosted`, `staging`, and `production`, though only the player-facing subset inherits the stricter traffic-open controls.

---

## Canonical Environment Classes

Use these classes as the source of truth for environment roles and control expectations across architecture docs:

| Class | Typical Topology | Secret Source | Rotation/Hardening | Backup/Restore Posture | Deploy Path |
| --- | --- | --- | --- | --- | --- |
| `local-dev` | Docker Compose on a developer machine | `.env` plus local files; generated certs/keys allowed | Convenience-first; manual | Local snapshots/ad hoc restore | `./gradlew devUp` / `devDown` |
| `pr-preview` | Hosted single-node Kubernetes preview cluster with one namespace per PR | Kubernetes Secrets/ConfigMaps plus registry pull credentials | Production-like for HTTPS, auth, and session flows; simplified operator controls acceptable | Namespace is reset on redeploy; no backup/restore guarantee | GitHub Actions deploys PR-tagged images via Helm |
| `dev-demo-cluster` | Shared but non-player-facing Kubernetes cluster | Kubernetes Secrets/ConfigMaps | Basic hardening; can prioritize iteration speed | Ad hoc unless explicitly scheduled | Fixed `develop` deploy workflow |
| `hobby-self-hosted` | Small player-facing deployment with production-like roles at low scale | Kubernetes Secrets/ConfigMaps | Production-like for Tier A credentials; simplified ops acceptable | Operator-managed backups expected | Operator-applied manifests/charts |
| `staging` | Prod-like Kubernetes cluster with smaller sizing | Kubernetes Secrets/ConfigMaps | Production-like controls; required post-restore secret hardening before playtests | Disposable by default unless explicitly enabling schedules | Git-tracked Kustomize overlays + operator `kubectl apply -k` |
| `production` | Player-facing Kubernetes cluster | Kubernetes Secrets/ConfigMaps | Strictest controls and change gates | Scheduled backups + verification + mandatory post-restore hardening | Git-tracked Kustomize overlays + operator `kubectl apply -k` |

Cross-document rules:

- Canonical class names are exactly: `local-dev`, `pr-preview`, `dev-demo-cluster`, `hobby-self-hosted`, `staging`, `production`. Terms such as `qa` are aliases only and must be mapped explicitly to one of these classes in environment documentation and automation.
- Staging and production overlay updates must use immutable image digests and follow the promotion/attestation model defined in `system-architecture-cicd.md`.
- Player-facing classes (`hobby-self-hosted`, `staging`, `production`) must treat JWT secret files (`FIREMUD_AUTH_JWT_SECRET_PATH`) and TCP Proxy `GATEWAY_WS_URL` listener alignment as required preflight invariants.
- Player-facing classes (`hobby-self-hosted`, `staging`, `production`) must complete environment bootstrap before first deploy: baseline secrets, JWT/JWKS resources, certificate issuer bindings, registry pull credentials, and environment-specific external integration credentials must exist and pass preflight before workloads are applied.
- Player-facing classes (`hobby-self-hosted`, `staging`, `production`) must keep a single expected-binding manifest at `design/operations/environments/<environment>/expected-bindings.yaml` so deployment preflight and restore validation use the same environment-isolation contract for internal state/trust bindings (PostgreSQL, Redis, JWT/JWKS, certificate issuer, registry pull credentials) and external bindings (backup, asset, outbound-communications, operator bindings). For backup and asset storage, that manifest must also capture the binding identity proving the environment owns the object-store target.
- Classes documented without backup/SLO guarantees (`pr-preview`, default `staging`) must not be used as evidence for backup/SLO guarantees unless their controls are explicitly upgraded.
- `pr-preview` keeps the production-like auth/session and service-isolation model, but its expected-bindings/preflight contract is intentionally preview-scoped rather than player-facing. Preview namespaces must still use preview-unique JWT/JWKS material and isolated service bindings, but they are not required to satisfy the full player-facing backup/admission binding posture before opening reviewer traffic.
- `pr-preview` keeps the same Spring gRPC SSL-bundle mTLS contract as the rest of the Kubernetes-backed environments; preview does not define a separate long-term internal gRPC transport mode.
- Staging and production deployments must run the canonical preflight policy gate defined in `system-architecture-deploy-preflight-policy.md`; production promotions must satisfy the attestation contract in `system-architecture-promotion-attestation.md`.
- `hobby-self-hosted` deployments are also player-facing and must run equivalent checks for player-facing invariants (JWT file-path contract, JWKS resource contract, Redis role split, and `GATEWAY_WS_URL` alignment) before opening traffic, even when not using the staging/production Kustomize overlay workflow. In this class, operator preflight is mandatory while overlay PR CI checks are optional/recommended.
- Player-facing classes (`hobby-self-hosted`, `staging`, `production`) must either use default in-environment service discovery or explicitly allowlist every `FIREMUD_SERVICES_*` override in preflight evidence; undeclared or cross-environment overrides are non-compliant.
- In the player-facing edge-terminated Telnet mode, startup, readiness, and traffic-open evidence must prove that the edge-to-TCP Proxy binding is authenticated and cryptographically protected before player traffic is admitted. Unauthenticated PROXY framing, network/source allowlists without cryptographic channel authentication, or disabling per-IP controls are not acceptable substitutes.
- `hobby-self-hosted` first-live and post-restore reopen events must also prove backup-baseline compliance before player traffic opens, using the canonical traffic-open evidence defined in `system-architecture-backup-recovery.md`.
- When this document refers to “preflight” or “promotion evidence,” the authoritative owning contracts are `system-architecture-deploy-preflight-policy.md` and `system-architecture-promotion-attestation.md`; this document defines environment intent, not a parallel policy schema.
- `dev-demo-cluster` is explicitly **non-promotable** and **non-attestable**. It must not be used as the source of production promotion evidence, rollback evidence, or DR-readiness sign-off. Any validation performed there is informative only.
- `dev-demo-cluster` may reuse the expected-bindings manifest pattern for local operator convenience, but that manifest is optional and is not part of any player-facing preflight or promotion contract.
- The current hosted `dev-demo-cluster` deployment may share the same Hetzner preview cluster used for `pr-preview`, but it must remain operationally separate:
  - fixed namespace `dev`
  - fixed Helm release `dev`
  - fixed hostname `dev.preview.firedevops.net`
  - fixed TCP port `32016`
  - no inclusion in PR preview capacity counting, janitor cleanup, or PR-comment reporting
- A light scheduled reconciler may redeploy the fixed `develop` environment when the `dev` namespace is missing or its recorded deployed SHA no longer matches the current `develop` head.
- The fixed `develop` environment currently favors clean redeploy reproducibility over persistent shared state: the namespace/address stay stable, but the workflow may rebuild the namespace from scratch on each new `develop` deployment.
- The fixed `develop` environment is intended for stable shared-branch proof and manual smoke, not for promotion evidence or as a substitute for staging.

---

## Local Development: Docker Compose

FireMUD uses Docker Compose for local development and testing:

### Docker Compose Characteristics

- All services, including the gateway, are built locally via `Dockerfile`s.
- Docker Compose orchestrates container startup. `depends_on` is configured with
  `condition: service_healthy` so services wait for PostgreSQL and Redis to pass
  health checks before starting.
- Service discovery is handled by Docker's internal DNS (for example `game-session-service:8080`), with the local stack relying on defaults in each service's `application.yml` plus the Compose override env vars rather than on a separate Spring `dev` profile lane.
- Connection settings for PostgreSQL and Redis are loaded from a `.env` file.
  A sample `.env.sample` is provided with default credentials. Additional variables are described in [Environment & Secrets Management](./environment-and-secrets.md).
- Start the stack with `./gradlew devUp` and shut it down with `./gradlew devDown` (see [Developer Setup](../../../DEVELOPER_SETUP.md)).
- The stack also runs a `pg-dump-cron` container that creates and rotates PostgreSQL dumps under `docker/backups/`.
- For details on all configuration variables, see [Environment Variables & Secrets Management](./environment-and-secrets.md).
  Standard ports include **8080** for HTTP, **6565** for gRPC, and **2323** for
  the TCP proxy.

### Docker Health Checks

- Services expose Spring Boot actuator health groups and must publish:
  - `/actuator/health/liveness` for process-local liveness only.
  - `/actuator/health/readiness` for traffic-admission readiness.
- Liveness means the process is alive and not wedged. It must not fail only because a downstream dependency is unavailable.
- Readiness means the service can safely accept new traffic for the contract it currently exposes. For user-facing and gameplay-path services, readiness is dependency-aware rather than process-only.
- Docker Compose can monitor health using `healthcheck` blocks in `docker/docker-compose.yml`.
- Health status is visible via `docker ps` (e.g., `healthy`, `unhealthy`), but:
  - Docker does **not** automatically restart containers that become `unhealthy`.
      Even with `restart: unless-stopped` configured, services remain running
      until manually restarted.
    - `depends_on` is bootstrap ordering only. It does not make a service safe for player traffic unless that service’s own readiness semantics are truthful.
    - Ongoing readiness still requires manual monitoring in Docker Compose because Compose does not remove unhealthy containers from traffic automatically.
    - See [Reconnection Strategy](../system-architecture-reconnection.md) for how sessions survive service restarts in Docker Compose.

Readiness rules for the currently implemented player path:

- `tcp-proxy-service` is ready only when its Telnet listener is bound and the downstream gameplay admission path is safe for new connections. When edge-terminated Telnet mode is selected, readiness is also false until current authenticated, cryptographically protected edge-to-TCP Proxy binding evidence is available.
- `spring-cloud-gateway` is ready only when `/ws/game/**` can be upgraded and the Game Session backend path required for new gameplay sockets is reachable.
- `game-session-service` is ready only when its local persistence is usable and the currently exposed `LOGIN` + first-command gameplay path is safe.
- For `game-session-service`, that safety check includes reserved readiness-only round trips through the session-context store and command-queue store so the first command path is not admitted on downstream reachability alone. Readiness-only downstream gRPC canaries also run with explicit short per-call deadlines rather than inheriting ambient channel timing.
- `game-logic-service` is ready only when the downstream services required for `ResolveLook` are reachable.
- `account-service`, `world-management-service`, and `entity-management-service` use truthful local readiness for the currently implemented slice.
- `game-session-service` now uses the same canonical `dev` topology for local Docker Compose and smoke environments, rather than a separate dependency-light mode.

Gateway retry filters, `wait-for-it.sh`, and similar startup helpers are convenience/bootstrap mechanisms only. They must not be treated as substitutes for correct readiness semantics.

---

## Production: Kubernetes

In production, FireMUD is deployed into Kubernetes (e.g., AWS EKS, Google GKE, or self-managed clusters).

### Kubernetes Characteristics

- Services are deployed as Pods and exposed via Kubernetes Services.
- DNS-based discovery is built into Kubernetes (e.g., `game-session-service.default.svc.cluster.local`).
- Route URIs in Spring Cloud Gateway use service names configured in the `prod`
  profile of `application.yml`.
- Internal microservices communicate directly over gRPC, bypassing the Spring Cloud Gateway.
- Kubernetes-backed environments use mTLS for internal gRPC via Spring Boot SSL bundles and Spring gRPC server SSL bundle binding, including the hosted preview/dev-demo Helm path.
- The **Spring Cloud Gateway** and, only for direct TCP Proxy TLS mode, the selected public TCP Proxy TLS listener are typically exposed using Kubernetes `LoadBalancer` Services so external clients can connect to the chosen public listeners. Raw Telnet and PROXY-protocol listeners remain internal-only; edge-terminated Telnet uses an external TLS edge proxy rather than exposing those listeners. See [Telnet TLS Deployment](#telnet-tls-deployment) for the accepted direct and edge-terminated modes.
- See [Security Architecture](../system-architecture-security.md#tls-termination-for-gateway) and [Gateway Architecture](../system-architecture-gateway.md#tls-termination-for-gateway) for the full TLS termination chain (browser/Telnet clients → load balancer → Spring Cloud Gateway → backend services) and DMZ boundary details; this document avoids duplicating those rules.
- Canonical baseline `NetworkPolicy` manifests for the staged player-facing Kustomize path live in
  [`k8s/base`](../../../k8s/base) and are included by the staging and production
  overlays. The [`k8s/network-policies`](../../../k8s/network-policies)
  directory documents that baseline policy set. Hosted preview/dev-demo now
  render matching checked-in baseline policies from the Helm chart using
  chart-specific selectors for the preview stack's labels.
- Configuration and secrets are managed through ConfigMaps and Secrets.
- Certificates for TLS termination and mTLS are issued by **cert-manager** and mounted from Kubernetes Secrets.
- The cluster uses **IPVS** (or a similar load-balancing mode) to route service traffic efficiently.
- Redis is deployed as **two logical roles** in both Kubernetes and Docker Compose (see [Redis Architecture](../system-architecture-redis.md)):
  - A **Coordination Redis** deployment runs as a clustered StatefulSet with automatic failover in production and smaller clusters in non-production. It uses AOF, persistent volumes, and `noeviction` in player-facing environments to preserve volatile coordination state across normal rollouts and pod restarts within the documented tail-loss envelope; Redis is not the authoritative recovery or audit log. AOF, persistent volumes, and `noeviction` alone are insufficient for player-facing replay safety: the deployment must also satisfy the [ADR 0029 replay-readiness contract](../decisions/adr-0029-single-use-gameplay-connect-token-carriage.md), including Redis 7.2+, ACL isolation, reserved capacity and an admission-readiness threshold, atomic marker consumption, configured `WAITAOF` local/replica acknowledgement thresholds, and a shared reset/uncertain-durability quarantine fence. A profile that cannot prove those controls is not eligible to admit new player handshakes.
  - A **Cache/Rate-Limit Redis** deployment runs as a separate StatefulSet or Deployment tuned for eviction-driven workloads (for example using `allkeys-lru`), with sizing independent of Coordination Redis. It may use lighter durability (RDB snapshots or even ephemeral volumes) because its keys are best-effort and recomputable.
  - Local development runs **two Redis containers** under Docker Compose with the same role split: a Coordination Redis service and a Cache/Rate-Limit Redis service, with Coordination Redis durable via AOF and Cache/Rate-Limit Redis configured for eviction-driven workloads. When operators want to **reset** coordination state (for example, to test reset-tolerant behavior or remediate mis-keyed data), they use the explicit coordination-reset Job or script described in the Redis Operations runbook rather than relying on Helm to clear data automatically on every deploy. A reset that affects connect-token replay state must advance `replayAdmissionFence`, record shared `QUARANTINED` readiness, reject affected tokens for the complete ADR 0029 barrier, and reopen only after the disposable-marker plus configured `WAITAOF` proof succeeds; wiping the AOF volume alone never restores admission readiness.
- PostgreSQL is deployed within the cluster (or provided as a managed database service) to store persistent domain data. See [System Architecture Overview](../system-architecture-overview.md#data-and-state-management). Backup and restore procedures are outlined in [Backup & Disaster Recovery](../system-architecture-backup-recovery.md) and the [Operational Runbooks](../system-architecture-runbooks.md#recovery).
- **Hosted dev/demo Kubernetes environments** use the dedicated [`dev-demo.yml`](../../../.github/workflows/dev-demo.yml) and [`preview.yml`](../../../.github/workflows/preview.yml) workflows, which render the full-stack chart under [`k8s/helm/firemud`](../../../k8s/helm/firemud) from environment-specific example values and deployment metadata. These environments are intentionally excluded from the staging → production promotion chain and must not emit attestation artifacts.
- **Staging and production** deployments are applied from a secure operator environment using **Kustomize overlays** (for example `kubectl apply -k k8s/overlays/stage` and `kubectl apply -k k8s/overlays/prod`). Immutable image digest changes for these overlays are the promotion model, and the player-facing bootstrap contract is now intentionally expressed through environment-owned bindings plus preflight rather than through checked-in placeholder Secret content. See [Deployment Runbook](../system-architecture-deployment-runbook.md) and [CI/CD Pipeline](../system-architecture-cicd.md#promotion--rollback-model).

A sample Terraform module for a local Kind cluster is provided in [k8s/terraform](../../../k8s/terraform). This demo module creates a `firemud` namespace and optional Redis Helm release for quick testing. For local Kubernetes iteration, use direct `helm template` / `helm install` or `kubectl apply -k` commands against the manifests and charts under [k8s/](../../../k8s).

- All tenants share this cluster with data separated by `tenantId` per service. See [Multi-Tenancy](../system-architecture-multi-tenancy.md) for more.

### Kubernetes Health Monitoring

- Kubernetes uses explicit actuator health groups:
  - **Readiness probes** call `/actuator/health/readiness` to determine whether a pod should receive new traffic.
  - **Liveness probes** call `/actuator/health/liveness` to detect wedged or dead processes.
- Readiness must represent safe traffic admission for the service’s current public contract, not merely successful boot.
- Dependency-aware readiness checks should prefer bounded, operation-shaped canaries over raw ping endpoints when the user-visible contract immediately depends on a downstream RPC path. These canaries must remain side-effect free. A synthetic probe that intentionally exercises an RPC with invalid or missing identifiers may still count as reachable when it returns an application-level error such as `INVALID_ARGUMENT`, `NOT_FOUND`, or `AUTH_INVALID_CREDENTIALS`; transport failures, timeouts, and upstream-failure responses do not count as ready.
- Readiness-only downstream RPC canaries must use explicit short deadlines so readiness timing remains bounded even when the normal client channel uses a longer retry or timeout budget.
- Synthetic probe identifiers must be explicitly reserved for readiness-only traffic rather than borrowing plausible real IDs like `0`. Use obvious sentinel values such as `__readiness__` or dedicated out-of-band numeric ranges for internal probes.
- Liveness must remain local-only and must not fail because a dependency is degraded.
- When startup is materially slower than steady-state readiness evaluation, use a `startupProbe` rather than inflating liveness or readiness thresholds.
- For the Telnet edge path, the TCP Proxy Service must refuse new sockets with an explicit startup-unavailable disconnect until the downstream `connect -> LOGIN -> first LOOK` path is ready rather than accepting the connection and allowing later gameplay commands to stall or fail.
- Dependency-aware readiness payloads use one shared shape:
  - `contract`: the traffic contract protected by readiness.
  - `admissionMeaning`: a short canonical statement of what `UP` means for new traffic.
  - `dependencies`: curated dependency keys with per-dependency `status`, `check`, `target`, and `outcome` or `reason`.
  - `failingDependency`: present only when readiness is refusing traffic.
- Critical services emit readiness transition observability with one shared contract:
  - metric `firemud.readiness.current{component="<service>"}` is `1` when the component is currently ready to admit new traffic and `0` otherwise.
  - metric `firemud.readiness.transitions{component="<service>",to_status="<status>",failing_dependency="<dependency|none>"}` increments only when the effective readiness state changes.
  - structured logs on readiness transitions include `component`, `contract`, `admissionMeaning`, and `failingDependency` when readiness goes false.

### Kubernetes Auto Recovery

- Kubernetes automatically:
  - Removes unready pods from Services
  - Restarts failing pods based on probe failures
  - Scales services up/down via deployments or Horizontal Pod Autoscalers (HPA). An example manifest is provided in `k8s/base/hpa-example.yaml` and serves as the default configuration.
- Pod restarts are transparent to players; see [Reconnection Strategy](../system-architecture-reconnection.md) for cross-environment behavior.

---

## Telnet TLS Deployment

Player-facing deployments select exactly one public Telnet TLS mode per endpoint:

- **Edge termination plus internal PROXY mode** – Expose a small Telnet edge proxy (for example, HAProxy) as the public TLS-terminating `LoadBalancer`. Forward the decrypted stream with PROXY protocol to the dedicated internal-only `TCP_PROXY_PROXY_PROTOCOL_PORT`. A separate `Service` plus source allowlist and `NetworkPolicy` must make that listener reachable only from the network-restricted edge path, and the edge-to-proxy channel must also provide authenticated cryptographic protection such as mTLS. PROXY framing alone does not authenticate its sender. Player-facing startup/readiness and traffic admission must fail closed until evidence for that protected binding is current; unauthenticated framing, network restriction alone, or disabling per-IP throttles and abuse controls are not substitutes. TCP Proxy parses the header, forwards the recovered address as `X-Proxy-Client-IP`, and Gateway standardizes it as `X-Client-IP`.
- **Direct TCP Proxy TLS mode** – Expose only the TCP Proxy TLS listener with `TCP_PROXY_TLS_ENABLED=true`. TCP Proxy owns the public certificate and derives the client address from the direct TCP peer. No preceding TLS-terminating edge or PROXY header is used on this path.

Both modes use the environment's certificate lifecycle and include expiry/rotation readiness in deployment proof. Raw and PROXY-protocol listeners remain private, plaintext handshakes are rejected at public endpoints, and no endpoint enables both modes. When the direct mode cannot preserve the original client address through infrastructure load balancing, per-IP limits are best-effort and must be sized conservatively. See [Protocol Bridging](../system-architecture-protocol-bridging.md#public-telnet-tls-modes) and [Security Architecture](../system-architecture-security.md#tls-termination-for-gateway) for the complete boundary.

---

## Monitoring & Logging

FireMUD relies on a consistent observability stack across environments. Expectations for log fields, metric naming, and alert labels live in [Logging & Monitoring](../system-architecture-logging-monitoring.md).

### Kubernetes (Default)

The full observability stack is deployed in Kubernetes. Example manifests for the collector, Jaeger, and exporters live under [`k8s/monitoring`](../../../k8s/monitoring).

Typical components:

- Prometheus scrapes metrics from all services.
- Grafana dashboards visualize performance metrics.
- Alertmanager notifies on failures or latency spikes.
- OpenTelemetry spans are emitted by services for distributed tracing.
- Jaeger stores these traces for debugging and analysis.
- Fluent Bit ships logs to Elasticsearch; Kibana is used for log queries.

### Docker Compose (Optional)

The Docker Compose environment omits the full observability stack by default. Operators may run a small local observability stack for debugging when needed, but Docker Compose is not treated as the canonical, prod-like observability deployment.

See [Logging & Monitoring](../system-architecture-logging-monitoring.md) for the signal conventions that apply regardless of environment.

---

## Spring Profile Configuration

Spring Boot services define `dev` and `prod` profiles inside `application.yml`.
Select the desired profile via the `SPRING_PROFILES_ACTIVE` environment variable.

- **dev** profile:
  - Used with Docker Compose
  - Static URI-based routing
  - Dev-mode databases or in-memory stores

- **prod** profile:
  - Used in Kubernetes
  - DNS-based routing to Kubernetes Services
  - Integration with persistent infrastructure such as the PostgreSQL cluster

## Staging Environment for Playtesting

A dedicated staging cluster mirrors production using smaller node sizes. Pull requests may also deploy a hosted [`pr-preview`](#canonical-environment-classes) environment through [preview.yml](../../../.github/workflows/preview.yml), but those previews remain isolated per PR and are not promotion candidates; staging is the intended environment for prod-like playtests and routing/TLS validation.
Staging test data may be reset on a schedule once operators explicitly install staging-specific automation; by default staging is not scheduled (see `schedule.md`).
For details on collecting tester feedback see [Playtesting & Feedback](../../project-management/slice-support/playtesting-feedback.md).

Environment-boundary contract: staging and production are separate environment boundaries with separate cluster credentials and per-environment secret sources. Shared namespace defaults (`firemud`) apply within each environment boundary and must not be interpreted as permission to share credentials, buckets, or control-plane trust roots across staging and production.
Normal deployments, not only restores, must validate that backup storage, asset storage, outbound communications, and operator credential bindings point at the intended environment boundary before player traffic is opened.

By default, staging is treated as **disposable**: it is not protected by the production backup schedule and can be rebuilt from manifests and fresh data as needed.

Operators may temporarily restore staging from **production** backups for disaster recovery rehearsals or investigations. When doing so, staging must follow the same post-restore secret hardening steps as production (see `../system-architecture-post-restore-hardening.md#post-restore-secret-hardening`) so JWT keys and database credentials are rotated before opening the environment to playtests.
When staging is restored from production-origin snapshots, operators must also run mandatory staging data sanitization and record evidence before playtests reopen (see `../system-architecture-post-restore-hardening.md#post-restore-secret-hardening` for the restore hardening sequence).

Staging does not run the production backup CronJobs listed in `schedule.md` unless staging-specific schedules are explicitly installed.
PRs that modify `k8s/` are checked by [`.github/workflows/validate-kustomize-overlays.yml`](../../../.github/workflows/validate-kustomize-overlays.yml), which blocks staging backup schedules unless operators intentionally add `k8s/overlays/stage/STAGING_BACKUPS_ENABLED`.

## PR Preview Environment

FireMUD's preview environment is a hosted single-node k3s cluster intended for reviewer-accessible pull-request validation, not a CI-only Docker Compose smoke stack.

- Same-repo pull requests may deploy one Helm release into namespace `pr-<PR_NUMBER>` with matching hostname `https://pr-<PR_NUMBER>.preview.<DOMAIN>`.
- The preview deployment uses the full application stack, including the gateway, TCP proxy, backend microservices, and stateful supporting services required for normal gameplay flows.
- GitHub Actions builds and smoke-tests PR-tagged images without registry credentials. A separate trusted default-branch workflow publishes only the successful fixed head-SHA tags to private GHCR, after which the preview workflow deploys or upgrades the Helm release. The cluster authenticates to GHCR using image pull credentials.
- Each preview namespace must use PR-unique JWT signing material and JWKS data, even when that material is stored as low-sensitivity test-only ConfigMap or Secret content. Preview auth tokens must not validate across PR namespaces.
- The first reviewer-usable proof target for preview is manual `LOGIN -> PLAY -> LOOK` over the TCP/Telnet path. A browser-first preview experience is useful later, but it is not the first hosted proof milestone.
- Long-term first-party browser hosting should live in a dedicated first-party web application service, not in Spring Cloud Gateway. If temporary preview-only browser helpers exist before that service lands, they must not redefine the long-term architecture.
- Preview TCP exposure uses a small reserved preview-only external port range, with one TCP port per live preview namespace on the shared preview host/IP. This is a preview multiplexing concern, not the long-term player-facing Telnet architecture.
- Preview deploys currently reset the namespace before each Helm apply, then reseed the minimum bootstrap state needed for reviewer proof. Within one deployed preview instance, PostgreSQL, MinIO/object storage, and other stateful components persist across normal pod restarts and VM reboot, but preview state does not currently persist across preview redeploys.
- Preview state is not backed up and is not durable beyond the single preview node. If the node or its attached storage is lost, preview state is lost.
- The initial Hetzner/k3s sizing target is one reliably usable full-stack preview on an 8 GB shared-CPU x86 VM. A second concurrent preview is best-effort only and may fail to deploy if capacity is exhausted.
- When capacity is exhausted, existing previews remain in place and the new preview deployment fails rather than evicting older namespaces.
- Preview namespaces are removed when the PR closes or merges.

---

## Related Documentation

- [Infrastructure Overview](./README.md)
- [Gateway Architecture](../system-architecture-gateway.md)
- [Protocol Bridging](../system-architecture-protocol-bridging.md)
- [CI/CD Pipeline](../system-architecture-cicd.md)
