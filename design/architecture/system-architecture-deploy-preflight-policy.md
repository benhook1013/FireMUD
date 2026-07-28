# FireMUD Deployment Preflight Policy Contract

This document defines the authoritative preflight policy gate for staging and production deployments, plus the equivalent policy contract that player-facing hobby/self-hosted operators must run before opening traffic.

## Purpose

- Provide one deterministic preflight entrypoint used by both CI and operators.
- Ensure secret contracts, digest pinning, and bridge/security invariants are enforced before apply.
- Produce a reusable pass/fail artifact for deployment evidence.

## Implementation Notes

`./dev-tools/deploy/preflight.py` is the canonical executable preflight entrypoint for the checks it currently implements. It consumes `design/operations/environments/<environment>/expected-bindings.yaml`, writes `expectedBindingsRef` into reports, validates the mounted JWT/JWKS path and currently implemented legacy resource contract, validates expected binding shape, and enforces cross-environment uniqueness for external player-facing bindings unless the manifests explicitly mark them shared with a rationale. In the current executable, `PREFLIGHT-JWT-001` checks the signing path and only requires the canonical `jwt-signing-keys` mount when the resolved private path is under `/var/run/secrets/firemud/jwt/`; `PREFLIGHT-JWKS-001` requires `jwt-jwks` as a `Secret` or Secret reference and only requires the canonical Account mount when the resolved public path is under `/var/run/secrets/firemud/jwks/`. A custom legacy path is checked under its resolved Secret/reference contract and does not imply the canonical mount. The policy separately defines a fixed `ConfigMap` JWKS as the target Account-owned publication mode; the executable currently rejects that mode and must fail closed until its target branch is implemented. Routine online backup does not require `backupControlPlaneClientRef`; that identity is validated only when an expected-bindings manifest explicitly enables an exceptional maintenance pause workflow.

The recovery controller's public continuation contract is `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)`. Its internal pause/lock phase is not a standalone public recovery verb. No recovery-controller RPC currently exists in the checked-in `protos/` source, so this remains a target-state contract rather than an implemented gRPC surface.

Preflight is a prerequisite gate, not the release authority. It validates the applicable environment, recovery, security, binding, and evidence prerequisites and may produce passing prerequisite evidence that permits an authorized operator to invoke `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` only when the live recovery controller is at `ready_to_reopen` with matching evidence. That continuation uses `expectedPhase=ready_to_reopen` and may reconcile only into `AWAITING_RESUME`; the public `resume(operationId, expectedPhase, scope, maintenanceLockToken, evidenceRef)` then uses `expectedPhase=AWAITING_RESUME` to record `RESUME_AUTHORIZED`. The durable recovery controller alone owns the guarded internal `releasing -> finalized` transition and the actual quarantine/traffic release; preflight must not infer release permission from a checked-in projection or report.

The production first-live and reopen gate is intentionally fail-closed until a real durable environment-wide recovery-controller read is implemented. Pre-release authorization consumes a fresh read of the live actual-recovery controller at `ready_to_reopen` together with immutable pre-release evidence; `readyToReopenAt` must not be future-dated or outside the applicable 30-minute event-evidence window. An operation in `releasing` is an in-flight continuation, not a fresh authorization: preflight must not treat it as `ready_to_reopen`, and a stale or ambiguous `releasing` state remains fail-closed until the controller reconciles it. Checked-in recovery/traffic-open projections and caller-supplied tenant, game-instance, region, or timestamp evidence cannot produce a passing `PREFLIGHT-BACKUP-002`. A production-equivalent drill may use current production lineage in an isolated production-equivalent boundary with compatible contracts and tooling, but it cannot authorize production traffic. After controller finalization, an exporter may write canonical `traffic-open-record/v1` and recovery projections as retained evidence; those projections are never preflight inputs or transaction authority.

The target controller and preflight integration must prove the required cold-start convergence, immutable erasure high-water capture and gap-free replay, session invalidation, durable-participant and external-effect dispositions, hardening, external-credential validation, secret-compliance refresh, smoke evidence, and lifecycle ordering before controlled reopen. Those controller reads, inventory dereferences, and lifecycle checks are not yet implemented, so production and hobby player-facing first-live and reopen remain blocked. The checked-in traffic-open records are post-finalization retained projections, not preflight authority.

The current executable does not yet enforce `PREFLIGHT-JWT-002` or `PREFLIGHT-JWT-ROTATION-001`: it does not prove Account-only asymmetric signing, absence of private-key mounts from validators, validator `kid`/JWKS behavior, or planned and compromise rotation drills. In the canonical player-facing model, `FIREMUD_AUTH_JWT_SECRET_PATH` names the versioned asymmetric private signing bundle mounted from `jwt-signing-keys` into Account Service only; it is not an HMAC secret path and is never mounted into validators. `FIREMUD_AUTH_JWKS_PATH` identifies the public JWKS consumed by Account and validators; it is not Account-only. Validators require the token `kid` to resolve through Account-published JWKS and disable HMAC fallback. That is a missing security gate, not only evidence depth. Until it is implemented and passes with current environment evidence, player-facing JWT readiness remains blocked. A legacy-mode pass must not be reused as proof for target ConfigMap mode, and a target-mode manifest must fail closed until the Account-owned publication checks are implemented. Other expected-binding checks still validate repository manifests and declared binding refs rather than complete live state; a successful static report without the applicable traffic-open authority is not enough to open player-facing traffic.

## Bootstrap Contract

For a brand-new player-facing environment (`hobby-self-hosted`, `staging`, or `production`), preflight must verify the baseline trust and secret set before any workload apply. The minimum bootstrap set is:

- registry pull credentials for workload image access,
- PostgreSQL application credentials and admin rotation credentials when rotation Jobs are used,
- the current legacy bootstrap pair of `jwt-signing-keys` and Secret-backed `jwt-jwks` resources, or the target-mode pair once target preflight is implemented,
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
- Operator pre-apply execution (`preflight.py`) currently enforces resolved-manifest and target-environment checks for required secret/key bindings, Redis role split, bridge alignment, bootstrap completeness, and external integration isolation. In explicit legacy JWKS mode (`Secret`), its legacy JWT/JWKS branch requires every primary workload to declare `FIREMUD_AUTH_JWT_SECRET_PATH` and the legacy `jwt-jwks` Secret/reference. It requires the canonical `jwt-jwks` mount only for the Account workload when the resolved `FIREMUD_AUTH_JWKS_PATH` is under `/var/run/secrets/firemud/jwks/`; a custom resolved JWKS path does not imply that canonical mount and is accepted only under the resolved legacy resource/reference contract. It additionally requires a `jwt-signing-keys` mount only when that resolved private path is under `/var/run/secrets/firemud/jwt/`. In target ConfigMap mode, preflight must instead require the fixed `jwt-jwks` ConfigMap, Account-only name-scoped publication/CAS authority, read-only projection at `FIREMUD_AUTH_JWKS_PATH`, and validator public-JWKS-only consumption, while rejecting the legacy Secret branch. The current executable has not yet implemented that target branch, so passing legacy checks is not evidence of Account-only private-key distribution. Convergence to the accepted ConfigMap authority, Account-only private-key distribution, validator `kid`/JWKS behavior, and traffic-open JWT rotation evidence remain target-state gates; `PREFLIGHT-JWT-002` and `PREFLIGHT-JWT-ROTATION-001` are not yet emitted by the executable.
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
- `PREFLIGHT-JWT-001` – the current executable rejects inline JWT secret configuration and applies the legacy shared path/mount contract only when the resolved mode is legacy `Secret`: every primary workload declares `FIREMUD_AUTH_JWT_SECRET_PATH`, and a workload whose resolved path is under `/var/run/secrets/firemud/jwt/` mounts `jwt-signing-keys` at that root. A custom resolved private path does not imply the canonical mount. Passing this check does not prove asymmetric signing, Account-only custody, or validator separation.
- `PREFLIGHT-JWKS-001` – in legacy `Secret` mode, the current executable verifies that player-facing rendered workloads supply the legacy `jwt-jwks` Secret/reference and requires the canonical mount only when the Account workload's resolved `FIREMUD_AUTH_JWKS_PATH` is under `/var/run/secrets/firemud/jwks/`; custom resolved paths are conditional on their legacy resource/reference resolution and do not imply the canonical mount. It must not treat that branch as a pass for target ConfigMap mode; target mode requires the separate Account-owned publication check below.
- `PREFLIGHT-JWT-002` (target-state-only; not currently emitted) – when target ConfigMap mode is selected, player-facing resolved manifests use fixed, pre-created `jwt-signing-keys` Secret and `jwt-jwks` ConfigMap resources. The materialization controller alone has name-scoped get, update, and patch authority for the signing Secret so it can read the current `resourceVersion` and perform CAS; Account has no Kubernetes API authority over that Secret and has name-scoped get/update/patch authority only for public JWKS; rotation automation has no resource write authority. Neither actor may list, create, or delete the pre-created resources. Projected mounts are read-only and classpath fallback is limited to explicit local/test use. The Account JWT private signing bundle is mounted only into Account Service; every validator uses asymmetric Account `kid`/JWKS verification with HMAC fallback disabled and receives no Account private key. A target-mode manifest using a Secret-backed JWKS fails closed.
- `PREFLIGHT-JWT-ROTATION-001` (target-state-only; not currently emitted) – player-facing first-live, reopen, and promotion evidence references successful planned-rotation and compromise-cutover drills using the production rotation artifact, including validator inventory/convergence, old/new `kid` acceptance and rejection, pruning, and immutable evidence identity.
- `PREFLIGHT-BRIDGE-001` – `GATEWAY_WS_URL` matches the expected internal Gateway listener for the target environment.
- `PREFLIGHT-REDIS-001` – player-facing environments resolve distinct Coordination vs Cache Redis endpoints.
- `PREFLIGHT-BOOTSTRAP-001` – player-facing environments confirm the minimum bootstrap secret and trust resources exist before apply.
- `PREFLIGHT-EXTERNAL-001` – player-facing environments validate that backup storage, asset storage, outbound communications, and operator credential bindings match the target environment and do not cross environment boundaries. For backup and asset storage, the proof must include the credential-binding identity that owns the object-store target.
- `PREFLIGHT-SERVICES-001` – player-facing environments either run with default in-environment service discovery or declare explicit `FIREMUD_SERVICES_*` overrides that are allowlisted for the target environment and do not resolve across environment boundaries.
- `PREFLIGHT-PROMOTION-001` – production promotions reference a valid staging attestation with matching digests.
- `PREFLIGHT-BACKUP-001` – every production promotion includes the compact recovery-compatibility result; `compatibilityStatus=incompatible` is an unconditional failed result, compatible rollback releases may reuse the current baseline, and `compatibilityStatus=drill_required` remains non-promotable until a fresh drill produces a regenerated compatible result. `roll-forward-only` releases set `newDrillRequired=true` and require that compatible result plus a full release-candidate recovery drill bound to exact candidate lineage, finalized controller lineage, and backup-confidentiality proof.
- `PREFLIGHT-BACKUP-002` – production first-live or traffic-reopen events verify a readable environment-wide PostgreSQL backup, a successful production-equivalent `cold_start_restore` drill within the required freshness window, backup-confidentiality evidence, and the current environment-specific actual-recovery controller at `ready_to_reopen` before traffic is opened. The evidence record must retain the environment-wide artifact identity/readability result and the confidentiality evidence reference together; a tenant-, game-instance-, region-, or cluster-scoped substitute must not overwrite or satisfy either environment-wide fact. Checked-in recovery/traffic-open JSON is emitted only after finalization and is not pre-release authority.
- `PREFLIGHT-BACKUP-003` – hobby/self-hosted first-live or traffic-reopen events verify current backup-baseline compliance evidence before player traffic is opened.

Policy applicability:

- `PREFLIGHT-PROMOTION-001` is required for `production` and `not_applicable` for `staging` and `hobby-self-hosted`.
- `PREFLIGHT-BACKUP-001` is required for every `production` promotion. An `incompatible` result fails unconditionally and cannot be made promotable by attaching drill evidence. A `rollback-compatible` release may reuse only a fresh finalized baseline whose recovery-contract fingerprint is unchanged and whose changed dimensions contain no invalidating or unknown contract change; the compact result does not create another full recovery record. A `drill_required` result fails until a new production-equivalent drill passes and the classifier replaces it with a compatible result bound to that drill. A `roll-forward-only` release requires that regenerated compatible result and a drill that restores a current-production-lineage artifact under candidate recovery tooling and proves the exact candidate service digests, migration path, config, and bindings through controlled reopen.
- `PREFLIGHT-BACKUP-002` is required for `production` on first-live opens and reopen-after-restore events, and `not_applicable` for routine steady-state rollouts that do not change traffic-open status. Its target implementation reads the current environment-specific actual-recovery controller at `ready_to_reopen`, verifies event-matching traffic exposure and target boundary plus a fresh controller state and a drill completed within 30 days, then emits passing prerequisite evidence that permits an authorized operator to invoke `continueRecovery(... expectedPhase=ready_to_reopen ...)`; the continuation must reach `AWAITING_RESUME`, after which public `resume(... expectedPhase=AWAITING_RESUME ...)` records `RESUME_AUTHORIZED`. `releasing` is never a passing preflight state. The controller remains the sole release authority and must idempotently reconcile the authorized internal release through `releasing -> finalized`, apply and observe quarantine release, and keep traffic closed on failure. The current executable fails this check closed because that controller read is not implemented. Tenant/game-instance/region/cluster pause or reset evidence may be retained as supporting maintenance evidence, but it cannot replace the readable environment-wide PostgreSQL artifact or its backup-confidentiality evidence, and checked-in projections are neither required nor sufficient authority.
- `PREFLIGHT-BACKUP-003` is required for `hobby-self-hosted` on first-live opens and reopen-after-restore events, and `not_applicable` otherwise. Pre-release authorization consumes current backup-baseline compliance, the general preflight report, immutable pre-release evidence, and a fresh live actual-recovery controller at `ready_to_reopen`; `continueRecovery(... expectedPhase=ready_to_reopen ...)` must reach `AWAITING_RESUME`, and public `resume(... expectedPhase=AWAITING_RESUME ...)` must record `RESUME_AUTHORIZED` before the internal release reaches `finalized`. `releasing` is never a passing preflight state. The checked-in traffic-open projection under `design/operations/deployments/hobby-self-hosted/traffic-open/<deployment-ref>/<deploymentEventId>.json` is exported only after finalization and is not a preflight input. The current executable fails closed because controller-backed authorization and post-finalization projection export are not implemented.
- `PREFLIGHT-DIGEST-001` is required for any flow using Kustomize overlays (`staging`, `production`) and `not_applicable` for `hobby-self-hosted`.
- `PREFLIGHT-DIGEST-002` is recommended/advisory for `hobby-self-hosted` and `not_applicable` for `staging`/`production`.
- `PREFLIGHT-SECRETS-002`, `PREFLIGHT-BOOTSTRAP-001`, `PREFLIGHT-EXTERNAL-001`, and `PREFLIGHT-SERVICES-001` are required for all player-facing environments.
- Target-state `PREFLIGHT-JWT-002` is required for all player-facing environments once implemented; `PREFLIGHT-JWT-ROTATION-001` is event-scoped to first-live, reopen, and production promotion evidence.

## Canonical Expected-Binding Inputs

`PREFLIGHT-EXTERNAL-001` must validate the target environment against one canonical expected-binding input set so deployment preflight and restore validation use the same contract.

Canonical source:

- `design/operations/environments/<environment>/expected-bindings.yaml`

Minimum required keys:

- `internalBindings.postgres.endpoint`
- `internalBindings.postgres.credentialsRef`
- `internalBindings.redis.coordination.endpoint`
- `internalBindings.redis.cache.endpoint`
- `internalBindings.jwt.signingKeysRef`
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
  - `internalBindings.jwt.signingKeysRef`
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

The illustrative staging manifest below reflects the current checked-in Secret-backed JWKS contract (`secret://firemud/jwt-jwks`). A ConfigMap-backed `jwt-jwks` is target-state-only and must be explicitly identified as that target mode; it must not be inferred from this current example.

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
