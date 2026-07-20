# FireMUD Deployment Preflight Policy Contract

This document defines the authoritative preflight policy gate for staging and production deployments, plus the equivalent policy contract that player-facing hobby/self-hosted operators must run before opening traffic.

## Purpose

- Provide one deterministic policy catalogue, report contract, and CLI facade used by CI and operators.
- Ensure secret contracts, digest pinning, and bridge/security invariants are enforced before apply.
- Keep static CI, live pre-apply, and post-apply promotion/traffic-open evidence distinct.
- Produce event-bound artifacts that identify the evaluated environment, candidate, policy version, phase, and expected-binding content.

## Implementation Notes

`./dev-tools/deploy/preflight.py` is the canonical executable preflight entrypoint for this contract. It currently consumes `design/operations/environments/<environment>/expected-bindings.yaml`, writes `expectedBindingsRef` into reports, validates the mounted JWT/JWKS path and resource-type contract, validates first-pass expected binding shape, enforces cross-environment uniqueness for external player-facing bindings unless the manifests explicitly mark them shared with a rationale, and enforces production and hobby/self-hosted traffic-open backup gates when the run declares `FIREMUD_TRAFFIC_OPEN_EVENT=first-live|reopen`. Production promotion validation now also checks that the referenced staging deployment record points to a readable staging preflight report with the expected bindings manifest reference and no failing required checks; hobby traffic-open evidence now proves the same link for its referenced preflight report.

The current executable does not yet establish the accepted phased or backup/recovery contract. It does not bind reports to a policy-catalogue version, expected-binding content digest, authoritative environment/cluster identity, or a distinct post-apply observation. `PREFLIGHT-BACKUP-001`, `PREFLIGHT-BACKUP-002`, and `PREFLIGHT-BACKUP-003` can validate caller-supplied timestamps, references, and selected field values without dereferencing a canonical recovery record, proving environment-wide artifact lineage, checking empty-Redis cold-start evidence, validating the complete recovery-participant inventory, or enforcing recovery-contract fingerprint compatibility. It also still requires the legacy `backupControlPlaneClientRef` in player-facing expected bindings even though routine online backup does not use tick pause. These checks must fail player-facing readiness until those validators and a production-equivalent drill exist; an evidence-shaped file is not proof.

The current executable does not yet enforce `PREFLIGHT-JWT-002` or `PREFLIGHT-JWT-ROTATION-001`: it does not prove Account-only asymmetric signing, absence of private-key mounts from validators, validator `kid`/JWKS behavior, or planned and compromise rotation drills. That is a missing security gate, not only evidence depth. Until it is implemented and passes with current environment evidence, player-facing JWT readiness remains blocked even if the older mounted-path checks pass. Other expected-binding checks still validate repository manifests and declared binding refs rather than complete live state; a successful static report without traffic-open evidence is not enough to open player-facing traffic.

## Bootstrap Contract

For a brand-new player-facing environment (`hobby-self-hosted`, `staging`, or `production`), preflight must verify the baseline trust and secret set before any workload apply. The minimum bootstrap set is:

- registry pull credentials for workload image access,
- PostgreSQL application credentials and admin rotation credentials when rotation Jobs are used,
- an Account-only asymmetric JWT signing resource (`jwt-signing-keys`) and public validator resource (`jwt-jwks`),
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

The CLI is a facade over modular validators rather than one permanent monolithic script. Every validator uses the canonical policy catalogue, policy IDs, applicability rules, enforcement categories, and report schema.

## Phased Evaluation and Enforcement Categories

Preflight has three ordered phases:

1. `static-ci` validates repository inputs, schemas, digest pinning, declared sharing, and deterministic renders without claiming target-environment observation.
2. `live-pre-apply` binds the run to the target environment and cluster identity and compares declared expected bindings with the exact candidate render and observed resources before apply.
3. `post-apply` verifies the actual deployed state plus promotion or traffic-open evidence before the protected transition.

A later phase may reference earlier evidence, but a static pass never authorizes apply, promotion, or player traffic. Reports across phases bind to one deployment event, target identity, candidate identity, policy-catalogue version, and expected-binding content digest.

Every policy ID declares one enforcement category: `advisory`, `apply-blocking`, or `non-waivable-promotion-traffic-open`. Apply-blocking checks may accept a valid event-scoped waiver. A waiver may authorize isolated repair or a quarantined drill, but cannot authorize a transition protected by a non-waivable check.

## Enforcement Boundaries

- Overlay PR CI (`validate-kustomize-overlays.yml`) supplies `static-ci` evidence: digest pinning, image existence, attestation schema/digest matching, repository policy markers, and production backup-readiness binding when required.
- Operator pre-apply execution supplies `live-pre-apply` evidence from resolved manifests and the observed target environment: required secret/key contracts, Account-only private-key distribution, validator JWKS configuration, Redis role split, bridge alignment, bootstrap completeness, and external integration isolation.
- Post-apply validation observes the deployed candidate and evaluates the applicable promotion, recovery, and traffic-open evidence.
- Deployment apply is blocked on failed applicable `apply-blocking` checks unless a valid event-scoped waiver covers the exact check. Promotion and traffic opening remain blocked on any failed applicable non-waivable check.

## Environment Applicability

| Environment class | Overlay PR CI required | Operator preflight required | Notes |
| --- | --- | --- | --- |
| `staging` | Yes | Yes | Both gates mandatory before apply. |
| `production` | Yes | Yes | Both gates mandatory before apply. |
| `hobby-self-hosted` | Optional (recommended) | Yes | Operator preflight is mandatory; CI may be unavailable in single-operator setups. |

## Required Policy Checks

Every run must emit one result per policy ID below, with status `pass`, `fail`, or `not_applicable` (with reason):

- `PREFLIGHT-DIGEST-001` – all staging/production workload images are immutable digests (`image@sha256:...`).
- `PREFLIGHT-DIGEST-002` – hobby/self-hosted workload manifests are digest-pinned where the operator packaging format supports digest references.
- `PREFLIGHT-SECRETS-001` – required Secrets and keys exist for the target environment.
- `PREFLIGHT-SECRETS-002` – player-facing environments validate internal state/trust bindings (PostgreSQL endpoint and credential binding, Redis role endpoints, JWT/JWKS resource bindings, certificate issuer binding, registry pull credentials) against the target environment boundary and fail on cross-environment reuse.
- `PREFLIGHT-JWT-001` – Account Service in player-facing environments uses `FIREMUD_AUTH_JWT_SECRET_PATH` and does not rely on inline-only JWT secrets.
- `PREFLIGHT-JWKS-001` – JWKS resource type matches environment policy (`jwt-jwks` Secret for player-facing environments; ConfigMap only for explicitly non-player-facing/test environments).
- `PREFLIGHT-JWT-002` – player-facing resolved manifests give the Account JWT private signing bundle only to Account Service; every validator uses asymmetric Account `kid`/JWKS verification with HMAC fallback disabled and receives no Account private key.
- `PREFLIGHT-JWT-ROTATION-001` – player-facing first-live, reopen, and promotion evidence references successful planned-rotation and compromise-cutover drills using the production rotation artifact, including validator inventory/convergence, old/new `kid` acceptance and rejection, pruning, and immutable evidence identity.
- `PREFLIGHT-BRIDGE-001` – `GATEWAY_WS_URL` matches the expected internal Gateway listener for the target environment.
- `PREFLIGHT-REDIS-001` – player-facing environments resolve distinct Coordination vs Cache Redis endpoints.
- `PREFLIGHT-BOOTSTRAP-001` – player-facing environments confirm the minimum bootstrap secret and trust resources exist before apply.
- `PREFLIGHT-EXTERNAL-001` – player-facing environments validate that backup storage, asset storage, outbound communications, and operator credential bindings match the target environment and do not cross environment boundaries. For backup and asset storage, the proof must include the credential-binding identity that owns the object-store target.
- `PREFLIGHT-SERVICES-001` – player-facing environments either run with default in-environment service discovery or declare explicit `FIREMUD_SERVICES_*` overrides that are allowlisted for the target environment and do not resolve across environment boundaries.
- `PREFLIGHT-PROMOTION-001` – production promotions reference a valid staging attestation with matching digests.
- `PREFLIGHT-BACKUP-001` – every production promotion includes the compact recovery-compatibility result; compatible rollback releases reuse the current baseline, while `drill_required` and `roll-forward-only` releases reference a full release-candidate recovery drill bound to exact candidate lineage.
- `PREFLIGHT-BACKUP-002` – production first-live or traffic-reopen events verify a readable environment-wide PostgreSQL backup, a successful production-equivalent `cold_start_restore` drill within the required freshness window, and environment-specific recovery evidence before traffic is opened.
- `PREFLIGHT-BACKUP-003` – hobby/self-hosted first-live or traffic-reopen events verify current backup-baseline compliance evidence before player traffic is opened.

Policy applicability:

- `PREFLIGHT-PROMOTION-001` is required for `production` and `not_applicable` for `staging` and `hobby-self-hosted`.
- `PREFLIGHT-BACKUP-001` is required for every `production` promotion. A `rollback-compatible` release passes with the small recovery-compatibility result when tool digests, database/migration restore compatibility, recovery-contract fingerprint, enabled participant inventory, secret/binding contract, and environment binding remain compatible; it does not create another full recovery record. A `drill_required` result fails until a new production-equivalent drill passes. A `roll-forward-only` release always fails without a drill that restores a current-production-lineage artifact under candidate recovery tooling and proves the exact candidate service digests, migration path, config, and bindings through controlled reopen.
- `PREFLIGHT-BACKUP-002` is required for `production` on first-live opens and reopen-after-restore events, and `not_applicable` for routine steady-state rollouts that do not change traffic-open status. It fails unless environment-specific evidence references a drill completed within 30 days that proves the complete `cold_start_restore` record, empty Coordination Redis, environment-wide session and epoch/fence invalidation, safe durable-participant and external-effect dispositions, hardening, smoke validation, and controlled reopen. A `reopen` event additionally requires the actual-recovery record in `ready_to_reopen` for the exact boundary; the gated transition finalizes that record, so preflight must not require it to be finalized before authorizing the transition.
- `PREFLIGHT-BACKUP-003` is required for `hobby-self-hosted` on first-live opens and reopen-after-restore events, and `not_applicable` otherwise. This check validates both `design/operations/deployments/hobby-self-hosted/backup-compliance.yaml` and `design/operations/deployments/hobby-self-hosted/traffic-open/<deployment-ref>.json`, including the referenced environment-wide cold-start recovery record for the player-facing boundary.
- `PREFLIGHT-JWT-002` is required for every player-facing apply.
- `PREFLIGHT-JWT-ROTATION-001` is required for player-facing first-live and reopen events and for any staging evidence used in production promotion. Routine deployments may reuse still-current drill evidence only when its artifact digest, key lifecycle contract, complete validator inventory, and environment binding remain unchanged and its configured freshness window has not expired.
- `PREFLIGHT-DIGEST-001` is required for any flow using Kustomize overlays (`staging`, `production`) and `not_applicable` for `hobby-self-hosted`.
- `PREFLIGHT-DIGEST-002` is recommended/advisory for `hobby-self-hosted` and `not_applicable` for `staging`/`production`.
- `PREFLIGHT-SECRETS-002`, `PREFLIGHT-JWT-002`, `PREFLIGHT-BOOTSTRAP-001`, `PREFLIGHT-EXTERNAL-001`, and `PREFLIGHT-SERVICES-001` are required for all player-facing environments.

## Canonical Expected-Binding Inputs

`PREFLIGHT-EXTERNAL-001` must validate the target environment against one canonical expected-binding input set so deployment preflight and restore validation use the same contract.

Canonical source:

- `design/operations/environments/<environment>/expected-bindings.yaml`

This manifest declares intended ownership; it is not live proof. Every phase records the content digest of the exact bytes consumed. Live phases also bind the active cluster/environment identity and compare declared, rendered, and observed resource identities. Repeated cluster-local names are acceptable only when observation proves they belong to distinct environment boundaries.

The policy catalogue owns a binding-type shareability matrix. Production PostgreSQL and Redis authorities, JWT signing/JWKS trust, certificate issuers and workload private identities, production-capable registry credentials, backup/asset write principals, and operator-control identities are environment-exclusive. `shared: true` is accepted only for a class the matrix marks shareable or conditionally shareable, with matching declarations, rationale, and required isolation evidence in every participating environment. It cannot override an environment-exclusive class.

Optional asset storage, outbound communications, non-default object storage, webhooks, and similar integrations appear as required bindings only when their canonical enablement input is active. Disabled integrations do not require placeholder targets or credentials.

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
  - `internalBindings.certificates.backupControlPlaneClientRef` only when an exceptional backup-related maintenance workflow invokes `PauseTicks` / `ResumeTicks`; routine online backup does not require this identity
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
    backupControlPlaneClientRef: cert-manager://firemud/staging-backup-control-plane
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
- `checkResults[]` with `policyId`, `status`, `message`
- `expectedBindingsRef` for player-facing environments
- `expectedBindingsDigest` for the exact manifest bytes consumed
- `policyCatalogVersion` and evaluation `phase`
- target environment and cluster identity for live phases
- `startedAt` and `completedAt` timestamps
- `toolVersion`

For `ci-static` runs, `expectedBindingsRef` should point to the same repository path that operator preflight would use for the target environment, even when CI validates only static contracts and not live cluster bindings.

Illustrative `ci-static` report shape:

```json
{
  "environment": "staging",
  "deploymentRef": {
    "overlayCommitSha": "abc123def456"
  },
  "checkResults": [
    {
      "policyId": "PREFLIGHT-DIGEST-001",
      "status": "pass",
      "message": "all images are digest pinned"
    }
  ],
  "expectedBindingsRef": "design/operations/environments/staging/expected-bindings.yaml",
  "startedAt": "2026-03-13T08:00:00Z",
  "completedAt": "2026-03-13T08:00:03Z",
  "toolVersion": "preflight/v1"
}
```

CI and manual operator runs must produce the same report shape so audit tooling can compare them.

### Evidence Storage and Retention

- Preflight report artifacts are stored in-repo under:
  - `design/operations/deployments/<environment>/preflight/<deployment-ref>.json`
- Break-glass waivers are stored beside the report artifact as:
  - `design/operations/deployments/<environment>/preflight/<deployment-ref>.waiver.json`
- `deployment-ref` is:
  - `<overlayCommitSha>` for overlay-driven staging/production deployments, or
  - a normalized manifest/chart reference token for hobby/self-hosted deployments.
- Naming rule: `<deployment-ref>` and similar artifact tokens must use lowercase ASCII plus digits and `-`, and should be stable across re-runs of the same deployment event so evidence does not fork accidentally.
- Retention requirement: keep preflight reports and waivers for at least as long as release/rollback audit history is retained.
- Waiver records must include: authorized approver identity, incident/change ticket, rationale, exact policy IDs and phase, target environment, deployment event identity, issue timestamp, and event-bounded expiration.

## Failure Handling

- Any failed applicable apply-blocking check blocks deployment; any failed applicable non-waivable check blocks promotion or traffic opening.
- Waivers are break-glass only and fail closed when malformed, expired, unauthorized, prohibited for the policy category, or mismatched to the event, phase, environment, or policy ID.
- Waivers expire after the specific deployment event and must not silently carry forward.
- `PREFLIGHT-BACKUP-001` whenever `newDrillRequired=true` (including every `roll-forward-only` promotion), plus `PREFLIGHT-BACKUP-002` for first-live or post-rewind reopen, are non-waivable readiness gates. A waiver may authorize an isolated drill or salvage action, but not the player-facing promotion/open transition those gates protect.

## Related Documentation

- `design/architecture/system-architecture-deployment-runbook.md`
- `design/architecture/system-architecture-cicd.md`
- `design/architecture/system-architecture-promotion-attestation.md`
