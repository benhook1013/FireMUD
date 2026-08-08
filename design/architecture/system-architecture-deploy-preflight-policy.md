# FireMUD Deployment Preflight Policy Contract

This document defines the authoritative preflight policy gate for staging and production deployments, plus the equivalent policy contract that player-facing hobby/self-hosted operators must run before opening traffic.

## Purpose

- Provide one deterministic preflight entrypoint used by both CI and operators.
- Ensure secret contracts, digest pinning, and bridge/security invariants are enforced before apply.
- Produce a reusable pass/fail artifact for deployment evidence.

JWT signer custody, Account cutover authority, and public-JWKS convergence are canonical in [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative). This document defines only their deployment-preflight evidence, mode selection, and fail-closed applicability consequences.

## Implementation Notes

`./dev-tools/deploy/preflight.py` is the canonical executable preflight entrypoint for the checks it currently implements. It consumes `design/operations/environments/<environment>/expected-bindings.yaml`, writes `expectedBindingsRef` into reports, validates the mounted JWT/JWKS path and currently implemented legacy resource contract, validates expected binding shape, and enforces cross-environment uniqueness for external player-facing bindings unless the manifests explicitly mark them shared with a rationale. In the current executable, `PREFLIGHT-JWT-001` checks the signing path and only requires the canonical `jwt-signing-keys` mount when the resolved private path is under `/var/run/secrets/firemud/jwt/`; `PREFLIGHT-JWKS-001` requires `jwt-jwks` as a `Secret` or Secret reference and only requires the canonical Account mount when the resolved public path is under `/var/run/secrets/firemud/jwks/`. A custom legacy path is checked under its resolved Secret/reference contract and does not imply the canonical mount. The policy separately defines a fixed `ConfigMap` JWKS as the target Account-owned publication mode; the executable currently rejects that mode and must fail closed until its target branch is implemented. The executable does not inspect deployed public Telnet listener topology or emit a public-listener exposure-exclusivity result; TCP Proxy runtime configuration cannot infer deployed exposure. Routine online backup does not require `backupControlPlaneClientRef`; that identity is validated only when an expected-bindings manifest explicitly enables an exceptional maintenance pause workflow.

The restore mode, continuation, replay, and reopen lifecycle is canonical in [Backup & Disaster Recovery](./system-architecture-backup-recovery.md). Target preflight will validate controller-owned live recovery state and immutable evidence and emit prerequisite evidence. It is never release authority and does not perform continuation, authorization, or release. The current executable has no controller-backed result because no recovery-controller RPC currently exists in the checked-in `protos/` source, so this remains a target-state contract rather than an implemented gRPC surface.

Production and hobby player-facing first-live/reopen remain fail-closed until preflight can read the durable environment-wide controller and verify the owner-defined cold-start convergence, fixed erasure replay, session invalidation, participant and external-effect dispositions, hardening, external credentials, secret-compliance refresh, smoke evidence, and lifecycle ordering. Checked-in recovery/traffic-open projections are retained evidence only and never preflight authority.

The current executable does not yet enforce `PREFLIGHT-JWT-INTERIM-001`, `PREFLIGHT-JWT-002`, or `PREFLIGHT-JWT-ROTATION-001`: it does not prove the complete interim mounted-fallback custody contract, target non-exportable signer health, absence of private-key mounts or distribution to any application workload, validator `kid`/JWKS behavior, or planned and compromise rotation drills. In the target player-facing model, Account authenticates a healthy signer reference/generation and proves challenge-signature correspondence with the Account-published JWK; no application workload mounts or receives private signing material, `jwt-signing-keys` is not a target resource, and `FIREMUD_AUTH_JWT_SECRET_PATH` is not configured. `FIREMUD_AUTH_JWKS_PATH` identifies the public JWKS consumed by Account and validators. Validators require the token `kid` to resolve through Account-published JWKS and disable HMAC fallback. The fixed `jwt-signing-keys` Secret, materialization-controller write/prune path, `FIREMUD_AUTH_JWT_SECRET_PATH`, and Account-mounted private material are checked only by the separate target-only interim mounted-fallback proof, which is not currently emitted. These are missing security gates, not only evidence-depth gaps. The current partial legacy JWT path/resource checks are static, non-authorizing checks: they cannot satisfy or participate in authorization for player-facing readiness. Player-facing readiness remains blocked until the complete applicable interim or target proof covers signer custody, JWKS projection, validator behavior, and rotation/no-private-material evidence. A legacy-mode pass must not be reused as proof for the target-only interim mounted-fallback or target non-exportable signer modes, and a target-mode manifest must fail closed until the applicable Account-owned signer/publication checks are implemented. Other expected-binding checks still validate repository manifests and declared binding refs rather than complete live state; a successful static report without the applicable traffic-open authority is not enough to open player-facing traffic.

## Bootstrap Contract

For a brand-new player-facing environment (`hobby-self-hosted`, `staging`, or `production`), preflight must verify the baseline trust and secret set before any workload apply. The minimum bootstrap set is:

- registry pull credentials for workload image access,
- PostgreSQL application credentials and admin rotation credentials when rotation Jobs are used,
- the JWT custody/publication baseline, explicitly distinguished by mode: current legacy Secret-backed wiring (`jwt-signing-keys`/`jwt-jwks` Secret and path checks), which is non-authorizing wiring evidence only; target-only interim Account-mounted asymmetric fallback, with its fixed `jwt-signing-keys` Secret, Account-only private mount, and public `jwt-jwks` projection proof; or target non-exportable signer mode, with signer-health/no-private-mount evidence and the fixed Account-published public `jwt-jwks` resource,
- cert-manager issuer or issuer reference for workload and bridge certificates,
- backup/object-store credentials when the environment requires backups, including the binding identity that owns the bucket or object-store target,
- asset-store and outbound-communications credentials when those integrations are enabled, including the binding identity that owns the asset bucket or object-store target,
- operator credential bindings required for environment-scoped control-plane access.

Bootstrap resources must be unique to the environment boundary. Reusing staging and production bootstrap secrets, buckets, or operator trust bindings is non-compliant.

## Authoritative Entrypoint

- Command: `./dev-tools/deploy/preflight.py <staging|production|hobby-self-hosted>`
- Input: target environment and resolved overlay/manifests for that environment.
- `hobby-self-hosted` runs must provide an explicit render input via `FIREMUD_PREFLIGHT_RENDER_PATH`; falling back to the stage overlay is not an allowed substitute for hobby deployment validation.
- Output: non-zero exit code on failure and a machine-readable report artifact (for example JSON).
- Context:
  - `operator` (default): required checks are blocking for real applies.
  - `ci-static`: uses the same policy IDs/report schema but may mark runtime-only checks (for example production attestation when not in a production promotion flow) as `not_applicable`.

`hobby-self-hosted` deployments may use different packaging/manifests, but they must evaluate the same player-facing policy IDs that apply to their environment class and produce the same evidence shape.

## Enforcement Boundaries

- Overlay PR CI (`validate-kustomize-overlays.yml`) always enforces the staging backup marker and production evidence-file selection rules. When no production attestation context applies, it also renders both overlays and checks image existence. For production-applicable changes, the current preflight stops at the fail-closed recovery-baseline authority check before attestation digest matching, expanded backup-readiness validation, or the later image-existence steps; those remain target-state enforcement gaps rather than completed checks.
- Operator pre-apply execution (`preflight.py`) currently enforces resolved-manifest and target-environment checks for required secret/key bindings, Redis role split, bridge alignment, bootstrap completeness, and external integration isolation. In explicit legacy JWKS mode (`Secret`), its legacy JWT/JWKS branch requires every primary workload to declare `FIREMUD_AUTH_JWT_SECRET_PATH` and the legacy `jwt-jwks` Secret/reference. It requires the canonical `jwt-jwks` mount only for the Account workload when the resolved `FIREMUD_AUTH_JWKS_PATH` is under `/var/run/secrets/firemud/jwks/`; a custom resolved JWKS path does not imply that canonical mount and is accepted only under the resolved legacy resource/reference contract. It additionally requires a `jwt-signing-keys` mount only when that resolved private path is under `/var/run/secrets/firemud/jwt/`. Those current checks are legacy Secret/path evidence only; they do not emit or satisfy the target-only interim mounted-fallback proof. In target non-exportable-signer mode, preflight must instead require the fixed Account-published `jwt-jwks` ConfigMap, Account-only name-scoped publication/CAS authority, read-only public projection at `FIREMUD_AUTH_JWKS_PATH`, authenticated signer health/generation and challenge-signature correspondence, validator public-JWKS-only consumption, and proof that no application workload has a private-key mount or distribution; it must reject `jwt-signing-keys`, `FIREMUD_AUTH_JWT_SECRET_PATH`, and the legacy Secret branch. The current executable has not yet implemented that target branch, so passing legacy checks is not evidence of target signer custody. Convergence to the accepted public-JWKS authority, signer health, no-private-mount proof, validator `kid`/JWKS behavior, and traffic-open JWT rotation evidence remain target-state gates; `PREFLIGHT-JWT-INTERIM-001`, `PREFLIGHT-JWT-002`, and `PREFLIGHT-JWT-ROTATION-001` are not yet emitted by the executable. The complete interim proof is also not implemented; selecting that mode remains fail-closed.
- Deployment apply is blocked unless every required check for the target class passes. The target-state waiver path is event-scoped; the current executable rejects waiver input and does not provide a waiver bypass.

## Environment Applicability

| Environment class | Overlay PR CI required | Operator preflight required | Notes |
| --- | --- | --- | --- |
| `staging` | Yes | Yes | Both gates mandatory before apply. |
| `production` | Yes | Yes | Both gates mandatory before apply. |
| `hobby-self-hosted` | Optional (recommended) | Yes | Operator preflight is mandatory; CI may be unavailable in single-operator setups. |

## Required Policy Checks

Every run must emit one result per implemented policy ID below, with status `pass`, `fail`, or `not_applicable` (with reason). Entries marked target-state-only are not emitted until their executable checks and contract proof land:

- `PREFLIGHT-DIGEST-001` – all staging/production workload images are immutable digests (`image@sha256:...`).
- `PREFLIGHT-DIGEST-002` – hobby/self-hosted workload manifests are digest-pinned where the operator packaging format supports digest references.
- `PREFLIGHT-SECRETS-001` – required trust resources, Secrets, and keys exist for the target environment.
- `PREFLIGHT-SECRETS-002` – player-facing environments validate internal state/trust bindings (PostgreSQL endpoint and credential binding, Redis role endpoints, JWT/JWKS resource bindings, certificate issuer binding, registry pull credentials) against the target environment boundary and fail on cross-environment reuse.
- `PREFLIGHT-JWT-001` – current executable legacy Secret/path check only. In legacy `Secret` mode, it rejects inline JWT secret configuration and checks the shared path/mount contract where every primary workload declares `FIREMUD_AUTH_JWT_SECRET_PATH` and a workload whose resolved path is under `/var/run/secrets/firemud/jwt/` mounts `jwt-signing-keys` at that root. A custom resolved private path does not imply the canonical mount. This legacy result is not the interim mounted-fallback proof and cannot satisfy any target custody or player-facing readiness gate.
- `PREFLIGHT-JWT-INTERIM-001` (target-state-only; not currently emitted) – complete interim Account-only mounted-fallback proof. This is distinct from `PREFLIGHT-JWT-001`. Its authenticated result must carry the exact identity `proofId: PREFLIGHT-JWT-INTERIM-001`, `custodyMode: INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK`, and `contractVersion: 1`. Consumers must verify the result authentication and exact-match `proofId`, `custodyMode`, and `contractVersion` against the expected values before accepting it; missing, aliased, unsupported, or mismatched mode/version fails closed. When implemented, the proof must cover the fixed pre-created `jwt-signing-keys` Secret, the materialization controller's name-scoped write/prune RBAC, Account-only `FIREMUD_AUTH_JWT_SECRET_PATH` and private mount, the public `jwt-jwks` projection, and validator/rotation-job absence of private material. A `PREFLIGHT-JWT-001` pass, Secret/path inspection, or inferred mode cannot satisfy this proof.
- `PREFLIGHT-JWKS-001` – in legacy `Secret` mode, the current executable verifies that player-facing rendered workloads supply the legacy `jwt-jwks` Secret/reference and requires the canonical mount only when the Account workload's resolved `FIREMUD_AUTH_JWKS_PATH` is under `/var/run/secrets/firemud/jwks/`; custom resolved paths are conditional on their legacy resource/reference resolution and do not imply the canonical mount. It must not treat that branch as a pass for target ConfigMap mode; target mode requires the separate Account-owned publication check below.
- `PREFLIGHT-JWT-002` (target-state-only; not currently emitted) – target non-exportable-signer health and no-private-mount proof. Player-facing resolved manifests use the fixed, pre-created Account-published `jwt-jwks` ConfigMap and its read-only public projection at `FIREMUD_AUTH_JWKS_PATH`; Account proves an authenticated healthy signer reference/generation and challenge-signature correspondence with the published JWK. No application workload, including Account, validators, recovery/rotation Jobs, or the materialization controller, mounts or receives private signing material; target manifests reject `jwt-signing-keys`, `FIREMUD_AUTH_JWT_SECRET_PATH`, and Secret-backed JWKS. Account retains name-scoped publication/CAS authority for public JWKS, validators use asymmetric Account `kid`/JWKS verification with HMAC fallback disabled, and every required validator consumes public JWKS only. This target proof is distinct from `PREFLIGHT-JWT-001`; a Secret/mount pass cannot satisfy it.
- `PREFLIGHT-JWT-ROTATION-001` (target-state-only; not currently emitted) – player-facing first-live, reopen, and promotion evidence references successful planned-rotation and compromise-cutover drills for the selected custody backend using the production rotation artifact, including Account-owned cutover, public-JWKS convergence, validator inventory, old/new `kid` acceptance and rejection, applicable pruning, and immutable evidence identity.
- `PREFLIGHT-TELNET-001` (target-state-only; not currently emitted) – public Telnet listener exposure exclusivity. For each player-facing endpoint that exposes Telnet, deployment preflight must validate the deployed topology and prove exactly one public TLS ingress mode: `EDGE_PROXY` exposes only the TLS-terminating edge, with the TCP Proxy PROXY-protocol and direct/raw listeners private; `DIRECT_TLS` exposes only the TCP Proxy TLS listener, with no public edge/PROXY path and raw/plaintext listeners private. It must reject an unset or mismatched mode, both public modes, public raw/plaintext or PROXY-protocol listeners, and any externally exposed listener not selected by the mode. TCP Proxy runtime configuration cannot infer this deployment topology. The current executable does not implement or emit this check.
- `PREFLIGHT-BRIDGE-001` – `GATEWAY_WS_URL` matches the expected internal Gateway listener for the target environment.
- `PREFLIGHT-REDIS-001` – player-facing environments resolve distinct Coordination vs Cache Redis endpoints.
- `PREFLIGHT-BOOTSTRAP-001` – player-facing environments confirm the minimum bootstrap secret and trust resources exist before apply.
- `PREFLIGHT-EXTERNAL-001` – player-facing environments validate that backup storage, asset storage, outbound communications, and operator credential bindings match the target environment and do not cross environment boundaries. For backup and asset storage, the proof must include the credential-binding identity that owns the object-store target.
- `PREFLIGHT-SERVICES-001` – player-facing environments either run with default in-environment service discovery or declare explicit `FIREMUD_SERVICES_*` overrides that are allowlisted for the target environment and do not resolve across environment boundaries.
- `PREFLIGHT-PROMOTION-001` – production promotions reference a valid staging attestation with matching digests.
- `PREFLIGHT-BACKUP-001` – every production promotion includes the compact recovery-compatibility result; `compatibilityStatus=incompatible` is an unconditional failed result, compatible rollback releases may reuse the current baseline, and `compatibilityStatus=drill_required` remains non-promotable until a fresh drill produces a regenerated compatible result. `roll-forward-only` releases set `newDrillRequired=true` and require that compatible result plus a full release-candidate recovery drill bound to exact candidate lineage, finalized controller lineage, and backup-confidentiality proof.
- `PREFLIGHT-BACKUP-002` – the target production first-live or traffic-reopen gate requires a `backupReadinessRef` that resolves to a backup-readiness artifact whose `restoreRecoveryRecordRef` independently resolves to a finalized production-equivalent, environment-wide `cold_start_restore` drill, plus an independent `baselineRecoveryRecordRef` that identifies a finalized environment-wide `cold_start_restore` drill. It also verifies a readable environment-wide PostgreSQL backup, backup-confidentiality evidence, and controller-owned live recovery state from the current environment-specific actual-recovery controller at the owner-defined `ready_to_reopen` boundary before traffic is opened. The two recovery references are distinct prerequisites; the restore record resolved through `backupReadinessRef` cannot substitute for `baselineRecoveryRecordRef`. It emits prerequisite evidence only; it never invokes the recovery continuation or release path. Tenant-, game-instance-, region-, or cluster-scoped substitutes cannot satisfy either environment-wide fact. The current executable has no controller-backed result and therefore fails this gate closed; checked-in projections are post-finalization evidence and are not pre-release authority.
- `PREFLIGHT-BACKUP-003` – the target hobby/self-hosted first-live or traffic-reopen gate verifies current backup-baseline compliance evidence and controller-owned live recovery state from the current environment-specific actual-recovery controller at the owner-defined `ready_to_reopen` boundary. It emits prerequisite evidence only; it never invokes the recovery continuation or release path. The current executable has no controller-backed result and therefore fails this gate closed; static baseline results, checked-in projections, or caller-supplied scope/timestamp evidence cannot authorize traffic.

Policy applicability:

- `PREFLIGHT-PROMOTION-001` is required for `production` and `not_applicable` for `staging` and `hobby-self-hosted`.
- `PREFLIGHT-BACKUP-001` is required for every `production` promotion. An `incompatible` result fails unconditionally and cannot be made promotable by attaching drill evidence. A `rollback-compatible` release may reuse only a fresh finalized baseline whose recovery-contract fingerprint is unchanged and whose changed dimensions contain no invalidating or unknown contract change; the compact result does not create another full recovery record. A `drill_required` result fails until a new production-equivalent drill passes and the classifier replaces it with a compatible result bound to that drill. A `roll-forward-only` release requires that regenerated compatible result and a drill that restores a current-production-lineage artifact under candidate recovery tooling and proves the exact candidate service digests, migration path, config, and bindings through controlled reopen.
- `PREFLIGHT-BACKUP-002` is required for `production` on first-live opens and reopen-after-restore events, and `not_applicable` for routine steady-state rollouts that do not change traffic-open status. Its target implementation reads the current environment-specific controller and emits prerequisite evidence only when the owner-defined `ready_to_reopen` boundary and evidence lineage match; it does not authorize continuation or release. The current executable fails this check closed because that controller read is not implemented; maintenance-scope pause/reset evidence and checked-in projections cannot replace the environment-wide artifact or confidentiality evidence.
- `PREFLIGHT-BACKUP-003` is required for `hobby-self-hosted` on first-live opens and reopen-after-restore events, and `not_applicable` otherwise. Its target implementation consumes current backup-baseline compliance, immutable pre-release evidence, and the live actual-recovery controller at the owner-defined `ready_to_reopen` boundary and emits prerequisite evidence only; it does not authorize continuation or release. The checked-in projection is exported only after owner-defined finalization and is not a preflight input. The current executable fails closed for the same missing controller-backed evidence.
- `PREFLIGHT-DIGEST-001` is required for any flow using Kustomize overlays (`staging`, `production`) and `not_applicable` for `hobby-self-hosted`.
- `PREFLIGHT-DIGEST-002` is recommended/advisory for `hobby-self-hosted` and `not_applicable` for `staging`/`production`.
- `PREFLIGHT-SECRETS-002`, `PREFLIGHT-BOOTSTRAP-001`, `PREFLIGHT-EXTERNAL-001`, and `PREFLIGHT-SERVICES-001` are required for all player-facing environments.
- `PREFLIGHT-JWT-001` is required for the current legacy Secret/path mode; `PREFLIGHT-JWKS-001` is required only for the current legacy Secret-backed JWKS mode. Target-only `PREFLIGHT-JWT-INTERIM-001` is required when the interim mounted fallback is selected once implemented; because it is not currently emitted, that mode remains fail-closed.
- Target-state `PREFLIGHT-JWT-002` is required for all player-facing environments using non-exportable signer custody once implemented; `PREFLIGHT-JWT-ROTATION-001` is event-scoped to first-live, reopen, and production promotion evidence for the selected custody backend.
- Target-state `PREFLIGHT-TELNET-001` is required for player-facing environments that expose a public Telnet endpoint and is `not_applicable` only when the deployment inputs explicitly declare that no public Telnet endpoint exists.

## Canonical Expected-Binding Inputs

`PREFLIGHT-EXTERNAL-001` must validate the target environment against one canonical expected-binding input set so deployment preflight and restore validation use the same contract.

Canonical source:

- `design/operations/environments/<environment>/expected-bindings.yaml`

Minimum required keys:

- `internalBindings.postgres.endpoint`
- `internalBindings.postgres.credentialsRef`
- `internalBindings.redis.coordination.endpoint`
- `internalBindings.redis.cache.endpoint`
- `internalBindings.jwt.signingKeysRef` when the interim mounted fallback or current legacy Secret mode is selected
- `internalBindings.jwt.jwksRef`
- `internalBindings.certificates.issuerRef`
- `internalBindings.registry.imagePullSecretRef`
- `backupStorage.bucket`
- `backupStorage.endpoint` when using a non-default S3-compatible endpoint
- `backupStorage.bindingRef` or `backupStorage.fingerprint`
- `assetStorage.bucket`
- `assetStorage.endpoint`
- `assetStorage.bindingRef` or `assetStorage.fingerprint`
- `outboundComms.smtpHost` and/or environment-classified webhook target identifiers
- `operatorCredentials.bindingRef` or `operatorCredentials.fingerprint`

Service-discovery policy keys:

- `serviceDiscovery.mode` with value `kubernetes-dns-default` or `explicit-overrides`
- `serviceDiscovery.allowedOverrides` when `mode=explicit-overrides`

Service-discovery validation rule:

- In player-facing environments, `serviceDiscovery.mode: kubernetes-dns-default` is the default and preferred contract.
- If a player-facing environment must set any `FIREMUD_SERVICES_*` override, the manifest must use `serviceDiscovery.mode: explicit-overrides` and list every permitted override key/value pair under `serviceDiscovery.allowedOverrides`.
- Any undeclared override, or any declared override that resolves outside the target environment boundary, fails `PREFLIGHT-SERVICES-001`.

Operator credential representation rule:

- Use `operatorCredentials.bindingRef` when the environment binds operator access through a platform-native resource identifier (for example a cert-manager certificate binding, workload identity binding, or a named operator Secret reference).
- Use `operatorCredentials.fingerprint` when the environment contract is anchored to a concrete certificate or key fingerprint rather than a stable platform binding identifier.
- If both are available, `bindingRef` is the canonical expected-binding field and `fingerprint` may be included as supporting validation detail rather than a second competing source of truth.

Storage binding representation rule:

- Use `backupStorage.bindingRef` and `assetStorage.bindingRef` when the environment binds object-store credentials through a platform-native Secret, service account, or workload identity reference.
- Use `backupStorage.fingerprint` and `assetStorage.fingerprint` only when the environment contract is anchored to a concrete credential or object-store identity fingerprint rather than a stable binding identifier.
- If both are available, `bindingRef` is the canonical expected-binding field and `fingerprint` may be included as supporting validation detail rather than a second competing source of truth.

Compact schema appendix for `expected-bindings.yaml`:

- Required top-level sections:
  - `internalBindings`
  - `backupStorage`
  - `assetStorage` when published/runtime assets use external object storage
  - `outboundComms` when email or webhook integrations are enabled
  - `operatorCredentials`
  - `serviceDiscovery`
- Required internal binding keys:
  - `internalBindings.postgres.endpoint`
  - `internalBindings.postgres.credentialsRef`
  - `internalBindings.redis.coordination.endpoint`
  - `internalBindings.redis.cache.endpoint`
  - `internalBindings.jwt.signingKeysRef` when the interim mounted fallback or current legacy Secret mode is selected
  - `internalBindings.jwt.jwksRef`
  - `internalBindings.certificates.issuerRef`
  - `internalBindings.certificates.workloadMtlsRef`
  - `internalBindings.certificates.gatewayInternalWsListenerRef` when the environment exposes the Gateway internal mTLS WebSocket listener
  - `internalBindings.certificates.tcpProxyBridgeClientRef` when the TCP Proxy bridge uses mTLS
  - `internalBindings.certificates.backupControlPlaneClientRef` only when `backupMaintenancePause.enabled: true` explicitly enables an exceptional backup-related maintenance workflow that invokes `PauseTicks` / `ResumeTicks`; routine online backup does not require this identity
  - `internalBindings.registry.imagePullSecretRef`
- Required external binding keys:
  - `backupStorage.bucket`
  - `backupStorage.endpoint` when non-default
  - `backupStorage.bindingRef` or `backupStorage.fingerprint`
  - `assetStorage.bucket`
  - `assetStorage.endpoint`
  - `assetStorage.bindingRef` or `assetStorage.fingerprint`
  - `outboundComms.smtpHost` and/or environment-classified webhook identifiers when enabled
  - `operatorCredentials.bindingRef` or `operatorCredentials.fingerprint`
- Precedence rules:
  - When both `bindingRef` and `fingerprint` are present for the same binding, `bindingRef` is canonical and `fingerprint` is supporting validation detail only.
  - The same precedence applies to `backupStorage`, `assetStorage`, and `operatorCredentials`.
- Optional supporting sections:
  - `observability`
  - environment-owned non-secret shared values explicitly marked as shared with rationale

The illustrative staging manifest below reflects the current checked-in Secret-backed JWKS and private-path contract (`secret://firemud/jwt-jwks` and `secret://firemud/jwt-signing-keys`). A ConfigMap-backed `jwt-jwks` with target signer-health/no-private-mount proof is target-state-only and must be explicitly identified as that target mode; it must not be inferred from this current example.

Illustrative example:

```yaml
environment: staging
internalBindings:
  postgres:
    endpoint: postgres.firemud.svc.cluster.local:5432
    credentialsRef: secret://firemud/postgres-credentials
  redis:
    coordination:
      endpoint: redis-coord.firemud.svc.cluster.local:6379
    cache:
      endpoint: redis-cache.firemud.svc.cluster.local:6379
  jwt:
    signingKeysRef: secret://firemud/jwt-signing-keys
    jwksRef: secret://firemud/jwt-jwks
  certificates:
    issuerRef: cert-manager://firemud/clusterissuers/firemud-staging
    workloadMtlsRef: cert-manager://firemud/staging-workload-mtls
    gatewayInternalWsListenerRef: cert-manager://firemud/staging-gateway-internal-ws
    tcpProxyBridgeClientRef: cert-manager://firemud/staging-tcp-proxy-bridge
  registry:
    imagePullSecretRef: secret://firemud/ghcr-pull-staging
backupStorage:
  bucket: firemud-staging-backups
  endpoint: https://minio.staging.internal
  bindingRef: secret://firemud/staging-backup-object-store
assetStorage:
  bucket: firemud-staging-assets
  endpoint: https://minio.staging.internal
  bindingRef: secret://firemud/staging-asset-object-store
outboundComms:
  smtpHost: smtp.staging.internal
  webhookTargets:
    accountNotifications: staging-only
operatorCredentials:
  bindingRef: cert-manager://firemud/staging-operator-client
serviceDiscovery:
  mode: kubernetes-dns-default
```

Illustrative intentionally shared non-sensitive field:

```yaml
observability:
  otelCollectorEndpoint:
    value: https://otel.shared.internal:4317
    shared: true
    sharedRationale: shared collector endpoint; credentials and tenant separation remain environment-specific
```

An exceptional backup maintenance pause workflow must opt in explicitly before its client identity is required or validated:

```yaml
backupMaintenancePause:
  enabled: true
internalBindings:
  certificates:
    backupControlPlaneClientRef: cert-manager://firemud/staging-backup-control-plane
```

This opt-in is not part of the routine online-backup contract.

Validation contract:

- Preflight fails if the manifest is missing for a player-facing environment.
- The resolved deployment inputs must match the manifest for the target environment.
- The manifest must prove environment isolation. Staging and production cannot share environment-owned PostgreSQL credential bindings, Redis deployments, JWT/JWKS bindings, certificate issuer bindings, registry pull credentials, bucket names, endpoints, SMTP targets, webhook target classes, or operator credential bindings unless the field is explicitly documented as non-sensitive shared infrastructure.
- When a field is intentionally shared, the manifest must mark it explicitly with `shared: true` plus a short `sharedRationale` string. Absence of those fields means the binding is treated as environment-unique by default.
- Restore validation tooling may derive shell environment variables such as `EXPECTED_PG_DUMP_BUCKET`, `EXPECTED_ASSET_STORE_BUCKET`, `EXPECTED_ASSET_STORE_ENDPOINT`, `EXPECTED_SMTP_HOST`, and operator-binding fingerprints from this manifest rather than maintaining a second source of truth.
- When validating operator-only credentials, preflight should compare like-for-like against the expected binding form: compare `bindingRef` values when the manifest declares `bindingRef`, and compare fingerprints when the manifest declares `fingerprint`. Implementations should not invent a second canonical representation during validation.
- Preflight should validate player-facing internal state/trust inputs from the same manifest rather than treating them as implicit cluster-local defaults. Cluster-local naming alone is not sufficient proof of environment isolation, and identical cluster-local literals may be valid across separate environment boundaries when the underlying cluster, namespace boundary, and bound Secret/trust resources belong to the target environment.
- Deployment and recovery evidence must reference the same manifest path so auditors can answer “what binding did we expect?” from one record family.

Internal-binding comparison rule:

- For cluster-local internal bindings, preflight should validate environment-scoped ownership rather than raw literal uniqueness across environments.
- Reusing names such as `postgres.firemud.svc.cluster.local`, `secret://firemud/postgres-credentials`, or `secret://firemud/jwt-signing-keys` is allowed when those names resolve inside different environment boundaries with separate cluster credentials and separate underlying resources.
- Raw literal equality is still insufficient for external or globally addressed bindings such as object-store buckets/endpoints, SMTP targets, webhook targets, and operator credential bindings; those remain environment-unique unless explicitly marked shared.
- When a player-facing external binding is intentionally shared across environment manifests, declare it as an object with `value` (or `bindingRef` / `fingerprint` for credential-shaped fields), `shared: true`, and the same non-empty `sharedRationale` string in every manifest that shares it. Matching values without that explicit declaration must fail preflight.

## Evidence Contract

The preflight report is prerequisite evidence for deployment or traffic-open evaluation. It does not continue the recovery controller, authorize release, or consume the finalized traffic-open projection that the event later produces. The recovery owner remains the sole authority for the canonical continuation and release path.

The report artifact must include:

- `environment`
- `deploymentRef` object with one of:
  - `overlayCommitSha` for overlay-driven deployments (`staging`, `production`), or
  - `manifestRef` / `chartVersion` for hobby/self-hosted deployments.
- `deploymentEventId`, a canonical UUID generated by preflight and unique to the current preflight/apply event; retries or later re-applies must use a new value
- `trafficOpenEvent` (`first-live`, `reopen`, or `null` for a general pre-apply report)
- `checkResults[]` with `policyId`, boolean `required`, `status`, and `message`
- `expectedBindingsRef` for player-facing environments
- `startedAt` and `completedAt` timestamps
- `toolVersion`
- `context` (`operator` or `ci-static`)

For `ci-static` runs, `expectedBindingsRef` should point to the same repository path that operator preflight would use for the target environment, even when CI validates only static contracts and not live cluster bindings. A consumed deployment or traffic-open report must have `context=operator`, the canonical `preflight.py-v1` tool version, ordered non-future execution timestamps, exact environment/event applicability, and every required policy result at `pass`; a `ci-static` report cannot authorize promotion or player traffic. A deployment record may consume only the canonical event-scoped report path whose `deploymentEventId` matches both the path and that record, whose `completedAt` is not later than `appliedAt`, and whose completion is no more than 30 minutes before apply. A consumer without an apply timestamp, including traffic-open evidence creation, must consume the report within 30 minutes of completion.

Illustrative `ci-static` report shape:

```json
{
  "environment": "staging",
  "deploymentRef": {
    "overlayCommitSha": "abc123def456"
  },
  "deploymentEventId": "9db17a4b-8271-4e81-82f4-b8b1c724b06a",
  "trafficOpenEvent": null,
  "checkResults": [
    {
      "policyId": "PREFLIGHT-DIGEST-001",
      "required": true,
      "status": "pass",
      "message": "all images are digest pinned"
    }
  ],
  "expectedBindingsRef": "design/operations/environments/staging/expected-bindings.yaml",
  "startedAt": "2026-03-13T08:00:00Z",
  "completedAt": "2026-03-13T08:00:03Z",
  "toolVersion": "preflight.py-v1",
  "context": "ci-static"
}
```

CI and manual operator runs must produce the same report shape so audit tooling can compare them.

### Evidence Storage and Retention

- Preflight report artifacts are stored in-repo under:
  - `design/operations/deployments/<environment>/preflight/<deployment-ref>/<deploymentEventId>.json`
- Break-glass waivers are stored beside the report artifact as:
  - `design/operations/deployments/<environment>/preflight/<deployment-ref>/<deploymentEventId>.waiver.json`
- `deployment-ref` is:
  - `<overlayCommitSha>` for overlay-driven staging/production deployments, or
  - a normalized manifest/chart reference token for hobby/self-hosted deployments.
- Naming rule: `<deployment-ref>` and similar artifact tokens must use lowercase ASCII plus digits and `-`. `deploymentEventId` uses canonical UUID text and changes for every retry or re-apply so immutable event evidence is never overwritten.
- Retention requirement: keep preflight reports and waivers for at least as long as release/rollback audit history is retained.
- Waiver records must include: `deploymentEventId` matching the current report, approver identity, incident/change ticket, scope (policy IDs waived), expiration (deployment event only), and timestamp.
- Current implementation status: `preflight.py` rejects `FIREMUD_PREFLIGHT_WAIVER` before generating a deployment event or report, and rejects consumed reports containing `waiverPath`, until a trusted authority can issue and atomically consume each waiver exactly once. Event-ID equality alone is not replay protection.

## Failure Handling

- Any failed required check blocks deployment.
- Waivers are a target-state break-glass mechanism only, must be explicit, and must include approver + incident/change ticket in the report. They are not currently executable.
- Waivers expire after the specific deployment event and must not silently carry forward. A retry or re-apply uses a new `deploymentEventId`, so a prior waiver fails binding validation even when the same `deploymentRef` is reused.
- `PREFLIGHT-BACKUP-001`, `PREFLIGHT-BACKUP-002`, and `PREFLIGHT-BACKUP-003` are non-waivable readiness gates. A waiver may authorize an isolated drill or salvage action, but not the player-facing promotion/open transition those gates protect.

## Related Documentation

- `design/architecture/system-architecture-deployment-runbook.md`
- `design/architecture/system-architecture-cicd.md`
- `design/architecture/system-architecture-promotion-attestation.md`
