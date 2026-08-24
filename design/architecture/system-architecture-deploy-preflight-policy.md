# FireMUD Deployment Preflight Policy Contract

This document defines the authoritative preflight policy gate for staging and production deployments, plus the equivalent policy contract that player-facing hobby/self-hosted operators must run before opening traffic.

## Purpose

- Provide one deterministic, versioned policy catalogue, report contract, and CLI facade used by CI and operators.
- Ensure secret contracts, digest pinning, and bridge/security invariants are enforced before apply.
- Keep static CI, live pre-apply, and post-apply promotion/traffic-open evidence distinct.
- Produce event-bound artifacts that identify the evaluated environment, candidate, policy version, phase, and expected-binding content.

JWT signer custody, Account cutover authority, and public-JWKS convergence are canonical in [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative). This document defines only their deployment-preflight evidence, mode selection, and fail-closed applicability consequences.

The P0 preflight authority is [phased environment-bound preflight and expected bindings](./decisions/adr-0152-phased-environment-bound-deployment-preflight-and-expected-bindings.md). This document owns the policy catalogue, phase boundaries, evidence shape, and applicability; environment-specific binding declarations remain under `design/operations/environments/`.

## Implementation Status

`./dev-tools/deploy/preflight.py` is the canonical executable preflight entrypoint for the checks it currently implements. The one actual checked-in deployment mode remains legacy Secret-backed JWT signing with fixed public `jwt-jwks` ConfigMap wiring: it consumes `design/operations/environments/<environment>/expected-bindings.yaml`, writes `expectedBindingsRef` into reports, validates the mounted JWT path and legacy resource contract, validates expected binding shape, and enforces cross-environment uniqueness for external player-facing bindings unless the manifests explicitly mark them shared with a rationale. In the current executable, `PREFLIGHT-JWT-001` checks the signing path and only requires the canonical `jwt-signing-keys` mount when the resolved private path is under `/var/run/secrets/firemud/jwt/`; `PREFLIGHT-JWKS-001` requires the explicit `configmap://firemud/jwt-jwks` binding, a public `jwt-jwks` `ConfigMap` with a non-empty `data.jwks.json` string, and the Account Service to resolve `FIREMUD_AUTH_JWKS_PATH` under `/var/run/secrets/firemud/jwks/` and mount that ConfigMap at the canonical root. These two results are diagnostic resource/path wiring evidence only; they do not prove runtime JWKS acceptance, validator convergence, or signer custody. The policy separately defines the interim Account-only mounted fallback and target non-exportable signer states, each with deeper mode-specific proof that the executable does not yet emit. The executable does not inspect deployed public Telnet listener topology or emit a public-listener exposure-exclusivity result; TCP Proxy runtime configuration cannot infer deployed exposure. Routine online backup does not require `backupControlPlaneClientRef`; that identity is validated only when an expected-bindings manifest explicitly enables an exceptional maintenance pause workflow.

The current report generator and validator do not yet emit or enforce the target `expectedBindingsDigest`, `policyCatalogVersion`, evaluation `phase`, live target/cluster identity, or conditional authorizing `jwtCustodyProof`. Until those fields and their focused proof are implemented together, current reports remain partial non-authorizing evidence and cannot satisfy a protected player-facing deployment, promotion, first-live, reopen, or fresh-boundary restore gate.

The restore mode, continuation, replay, and reopen lifecycle is canonical in [Backup & Disaster Recovery](./system-architecture-backup-recovery.md). Target preflight will validate controller-owned live recovery state and immutable evidence and emit prerequisite evidence. It is never release authority and does not perform continuation, authorization, or release. The current executable has no controller-backed result because no recovery-controller RPC currently exists in the checked-in `protos/` source, so this remains a target-state contract rather than an implemented gRPC surface.

Production and hobby player-facing first-live/reopen remain fail-closed until preflight can read the durable environment-wide controller and verify the owner-defined cold-start convergence, fixed erasure replay, session invalidation, participant and external-effect dispositions, hardening, external credentials, secret-compliance refresh, smoke evidence, and lifecycle ordering. Checked-in recovery/traffic-open projections are retained evidence only and never preflight authority.

The current executable does not yet enforce `PREFLIGHT-JWT-INTERIM-001`, `PREFLIGHT-JWT-002`, or `PREFLIGHT-JWT-ROTATION-001`: it does not prove the complete interim mounted-fallback custody contract, target non-exportable signer health, absence of private-key mounts or distribution to any application workload, validator `kid`/JWKS behavior, or planned and compromise rotation drills. Pre-apply trusted bootstrap evidence is limited to the selected state's resource, binding, and RBAC boundary; it is not live signer or validator convergence. Post-apply live convergence is owner-produced: Account owns JWT signing/publication/reconciliation, the recovery controller owns recovery reconciliation, and the rotation-evidence workload is observation-only. In the target player-facing model, Account authenticates a healthy signer reference/generation and proves challenge-signature correspondence with the target Account-published JWK; no application workload mounts or receives private signing material, `jwt-signing-keys` is not a target resource, and `FIREMUD_AUTH_JWT_SECRET_PATH` is not configured. In the interim model, the materialization controller may materialize/generate/prune private slots only under Account authorization and its name-scoped RBAC, while Account alone consumes the private mount and the separate interim Account-owned public JWKS projection. `FIREMUD_AUTH_JWKS_PATH` is mandatory for Account and every validator in either accepted mode and identifies the selected mode's public JWKS. Private-file path and mount validation applies only to the interim mounted fallback; target mode requires `FIREMUD_AUTH_JWT_SECRET_PATH` unset and rejects private material. Validators require the token `kid` to resolve through Account-published JWKS and disable HMAC fallback. These are missing security gates, not only evidence-depth gaps. The current partial legacy JWT path/resource checks are static, non-authorizing checks: they cannot satisfy or participate in authorization for player-facing readiness. Player-facing readiness remains blocked until the complete applicable interim or target proof covers signer custody, JWKS projection, validator behavior, and post-apply rotation/convergence evidence. A legacy-mode pass must not be reused as proof for either accepted state, and an accepted-mode manifest must fail closed until its applicable Account-owned signer/publication checks are implemented. Other expected-binding checks still validate repository manifests and declared binding refs rather than complete live state; a successful static report without the applicable traffic-open authority is not enough to open player-facing traffic.

## Bootstrap Contract

For a brand-new player-facing environment (`hobby-self-hosted`, `staging`, or `production`), preflight must verify the baseline trust and secret set before any workload apply. The minimum bootstrap set is:

- registry pull credentials for workload image access,
- PostgreSQL application credentials and admin rotation credentials when rotation Jobs are used,
- trusted pre-apply bootstrap evidence for the selected state's public-JWKS resource, publication/binding authority, and exactly one accepted player-facing custody proof. The authenticated proof must exactly match the selected `proofId`, `custodyMode`, and `contractVersion`:
  - interim Account-only mounted-fallback mode selects `PREFLIGHT-JWT-INTERIM-001`; only this mode requires the fixed `jwt-signing-keys` Secret, Account private mount, materialization-controller name-scoped RBAC, and separate interim public `jwt-jwks` ConfigMap projection,
  - target non-exportable-signer mode selects `PREFLIGHT-JWT-002`; it requires the target Account-owned public `jwt-jwks` ConfigMap, signer reference/publication authority, and no private-material mount/distribution. Live signer health, challenge-signature correspondence, and validator convergence are post-apply evidence and are not established by bootstrap alone,
  - `PREFLIGHT-JWT-001` and `PREFLIGHT-JWKS-001` are legacy diagnostic-only wiring checks for the one actual checked-in deployment mode, never a selectable player-facing custody mode or accepted proof,
  - a missing, unknown, not-yet-implemented, or mode-mismatched selected proof fails closed rather than falling back to another mode,
- cert-manager issuer or issuer reference for workload and bridge certificates,
- backup/object-store credentials when the environment requires backups, including the binding identity that owns the bucket or object-store target,
- asset-store and outbound-communications credentials when those integrations are enabled, including the binding identity that owns the asset bucket or object-store target,
- operator credential bindings required for environment-scoped control-plane access.

Bootstrap resources must be unique to the environment boundary. Reusing staging and production bootstrap secrets, buckets, or operator trust bindings is non-compliant.

Fresh-boundary restores use the same custody selection rule before restored workloads can progress: exactly one authenticated proof, `PREFLIGHT-JWT-INTERIM-001` for `INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK` or `PREFLIGHT-JWT-002` for `TARGET_NON_EXPORTABLE_SIGNER`, must be present with an exact matching `proofId`, `custodyMode`, and `contractVersion`. The selected tuple is preserved in the fresh-boundary deployment/recovery record and must match on replay. `PREFLIGHT-JWT-001` and `PREFLIGHT-JWKS-001` remain legacy diagnostic wiring checks and are never accepted as fresh-boundary custody proof or as a substitute for the selected proof. A missing, unknown, unsupported, or not-yet-implemented selected state fails closed.

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

The policy catalogue owns the binding-type shareability matrix. Production PostgreSQL and Redis authorities, JWT signing/JWKS trust, certificate issuers and workload private identities, production-capable registry credentials, backup/asset write principals, and operator-control identities are environment-exclusive. `shared: true` is accepted only for a class the matrix marks shareable or conditionally shareable, with matching declarations, rationale, and required isolation evidence in every participating environment; it cannot override an environment-exclusive class. Optional asset storage, outbound communications, non-default object storage, webhooks, and similar integrations are required only when their canonical enablement input is active; disabled integrations do not require placeholder targets or credentials.

## Enforcement Boundaries

- Overlay PR CI (`validate-kustomize-overlays.yml`) always enforces the staging backup marker and production evidence-file selection rules. When no production attestation context applies, it also renders both overlays and checks image existence. For production-applicable changes, the current preflight stops at the fail-closed recovery-baseline authority check before attestation digest matching, expanded backup-readiness validation, or the later image-existence steps; those remain target-state enforcement gaps rather than completed checks.
- Operator pre-apply execution (`preflight.py`) currently enforces resolved-manifest and target-environment checks for required secret/key bindings, Redis role split, bridge alignment, bootstrap completeness, and external integration isolation. In the one actual checked-in legacy Secret-backed signing plus public ConfigMap deployment mode, its legacy JWT/JWKS branch still emits diagnostic path/resource checks; those checks are not an accepted custody proof. Accepted-state private-file path and mount validation applies only to the interim mounted fallback. In the interim mounted fallback, preflight must prove the separate interim Account-owned public `jwt-jwks` ConfigMap, Account-authorized materialization-controller name-scoped RBAC, Account-only private mount, and no private material in validators or rotation-evidence workloads. In target non-exportable-signer mode, preflight must instead require the separate target Account-owned `jwt-jwks` ConfigMap, Account-only name-scoped publication/CAS authority, read-only public projection at the mandatory `FIREMUD_AUTH_JWKS_PATH` for Account and every validator, `FIREMUD_AUTH_JWT_SECRET_PATH` unset, and proof that no application workload has a private-key mount or distribution; live signer health/generation, challenge-signature correspondence, validator `kid`/JWKS behavior, and public-JWKS convergence are post-apply owner evidence. The current executable has not yet implemented those accepted-state branches, so passing legacy checks is not evidence of either accepted custody state. `PREFLIGHT-JWT-INTERIM-001`, `PREFLIGHT-JWT-002`, and `PREFLIGHT-JWT-ROTATION-001` are not yet emitted by the executable; selecting an accepted state remains fail-closed.
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
- `PREFLIGHT-JWT-001` – current executable diagnostic check for the one checked-in legacy Secret/path deployment mode only. In legacy `Secret` mode, it rejects inline JWT secret configuration and checks the shared path/mount contract where every primary workload declares `FIREMUD_AUTH_JWT_SECRET_PATH` and a workload whose resolved path is under `/var/run/secrets/firemud/jwt/` mounts `jwt-signing-keys` at that root. A custom resolved private path does not imply the canonical mount. This legacy result is diagnostic wiring evidence only: it is not selectable player-facing custody, is not the interim mounted-fallback proof, and cannot satisfy any target custody or player-facing readiness gate.
- `PREFLIGHT-JWT-INTERIM-001` (target-state-only; not currently emitted) – complete pre-apply trusted bootstrap proof for the interim Account-only mounted fallback. This is distinct from `PREFLIGHT-JWT-001`. Its authenticated result must carry the exact identity `proofId: PREFLIGHT-JWT-INTERIM-001`, `custodyMode: INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK`, and `contractVersion: 1`. Consumers must verify the result authentication and exact-match `proofId`, `custodyMode`, and `contractVersion` against the expected values before accepting it; missing, aliased, unsupported, or mismatched mode/version fails closed. When implemented, the proof must cover the fixed pre-created `jwt-signing-keys` Secret, the materialization controller's name-scoped write/generate/materialize/prune RBAC, Account authorization for each private-slot operation, Account-only `FIREMUD_AUTH_JWT_SECRET_PATH` and private mount, the mandatory public `FIREMUD_AUTH_JWKS_PATH` for Account and every validator, the separate interim public `jwt-jwks` projection, and validator/rotation-evidence-workload absence of private material. The materialization controller is excluded from the general observation-only rule only for these bounded Account-authorized private-slot operations; it is not rotation authority. It is bootstrap evidence, not post-apply signer or validator convergence evidence. A `PREFLIGHT-JWT-001` pass, Secret/path inspection, or inferred mode cannot satisfy this proof.
- `PREFLIGHT-JWKS-001` – current public-resource/path wiring diagnostic check only. The current executable requires a `jwt-jwks` `ConfigMap` with a non-empty `data.jwks.json` string, an Account Service `FIREMUD_AUTH_JWKS_PATH` under `/var/run/secrets/firemud/jwks/`, and an Account volume mount of that ConfigMap at `/var/run/secrets/firemud/jwks`. It does not prove runtime JWKS acceptance, validator convergence, signer custody, or either accepted player-facing custody proof; those deeper mode-specific checks remain separate target-state evidence.
- `PREFLIGHT-JWT-002` (target-state-only; not currently emitted) – target non-exportable-signer pre-apply trusted bootstrap proof and no-private-mount boundary. Its authenticated result must carry the exact identity `proofId: PREFLIGHT-JWT-002`, `custodyMode: TARGET_NON_EXPORTABLE_SIGNER`, and `contractVersion: 1`; consumers must verify the result authentication and exact-match tuple before accepting it. Player-facing resolved manifests use the fixed, pre-created target Account-published `jwt-jwks` ConfigMap and its read-only public projection at the mandatory `FIREMUD_AUTH_JWKS_PATH` for Account and every validator; the bootstrap proof binds the signer reference and Account publication/CAS authority. No application workload, including Account, validators, recovery/rotation Jobs, or the materialization controller, mounts or receives private signing material; target manifests reject `jwt-signing-keys`, `FIREMUD_AUTH_JWT_SECRET_PATH`, and Secret-backed JWKS. Post-apply Account evidence must prove healthy signer generation and challenge-signature correspondence with the published JWK, and validators must prove asymmetric Account `kid`/JWKS verification with HMAC fallback disabled. Every required validator consumes public JWKS only. This target proof is distinct from `PREFLIGHT-JWT-001`; a Secret/mount pass cannot satisfy it.
- `PREFLIGHT-JWT-ROTATION-001` (target-state-only; not currently emitted) – post-apply player-facing first-live, reopen, and promotion evidence referencing successful planned-rotation and compromise-cutover drills for the selected custody state using the production rotation artifact. Account alone reconciles and advances rotation; the recovery controller persists the operation/evidence, invokes the Account-owned operation, and observes returned convergence. The rotation-evidence workload is observation-only and may record public-JWKS convergence, validator inventory, old/new `kid` acceptance and rejection, applicable pruning evidence, and immutable evidence identity without requesting or mutating signer state.
- `PREFLIGHT-TELNET-001` (target-state-only; not currently emitted) – public Telnet listener exposure exclusivity. For each player-facing endpoint that exposes Telnet, deployment preflight must validate the deployed topology and prove exactly one public TLS ingress mode: `EDGE_PROXY` exposes only the TLS-terminating edge, with the TCP Proxy PROXY-protocol and direct/raw listeners private; `DIRECT_TLS` exposes only the TCP Proxy TLS listener, with no public edge/PROXY path and raw/plaintext listeners private. It must reject an unset or mismatched mode, both public modes, public raw/plaintext or PROXY-protocol listeners, and any externally exposed listener not selected by the mode. TCP Proxy runtime configuration cannot infer this deployment topology. The current executable does not implement or emit this check.
- `PREFLIGHT-BRIDGE-001` – `GATEWAY_WS_URL` matches the expected internal Gateway listener for the target environment.
- `PREFLIGHT-REDIS-001` – player-facing environments resolve distinct Coordination vs Cache Redis endpoints.
- `PREFLIGHT-BOOTSTRAP-001` – player-facing environments confirm the minimum bootstrap secret and trust resources exist before apply.
- `PREFLIGHT-EXTERNAL-001` – player-facing environments validate that backup storage, asset storage, outbound communications, and operator credential bindings match the target environment and do not cross environment boundaries. For backup and asset storage, the proof must include the credential-binding identity that owns the object-store target.
- `PREFLIGHT-SERVICES-001` – player-facing environments either run with default in-environment service discovery or declare explicit `FIREMUD_SERVICES_*` overrides that are allowlisted for the target environment and do not resolve across environment boundaries.
- `PREFLIGHT-PROMOTION-001` – production promotions reference a valid staging attestation with matching digests.
- `PREFLIGHT-BACKUP-001` – every production promotion includes the compact recovery-compatibility result; `compatibilityStatus=incompatible` is an unconditional failed result, compatible rollback releases may reuse the current baseline, and `compatibilityStatus=drill_required` remains non-promotable until a fresh drill produces a regenerated compatible result. `roll-forward-only` releases set `newDrillRequired=true` and require that compatible result plus a full release-candidate recovery drill bound to exact candidate lineage, finalized controller lineage, and backup-confidentiality proof.
- `PREFLIGHT-BACKUP-002` – the target production first-live or traffic-reopen gate requires the [production traffic-open backup evidence](./system-architecture-backup-recovery-evidence-and-compliance.md#production-traffic-open-backup-evidence): a `backupReadinessRef` that resolves to a backup-readiness artifact whose `restoreRecoveryRecordRef` independently resolves to a finalized production-equivalent, environment-wide `cold_start_restore` drill, plus an independent `baselineRecoveryRecordRef` that identifies a finalized environment-wide `cold_start_restore` drill. It also verifies a readable environment-wide PostgreSQL backup, backup-confidentiality evidence, and controller-owned live recovery state from the current environment-specific actual-recovery controller at the owner-defined `ready_to_reopen` boundary before traffic is opened. The two recovery references are distinct prerequisites; the restore record resolved through `backupReadinessRef` cannot substitute for `baselineRecoveryRecordRef`. It emits prerequisite evidence only; it never invokes the recovery continuation or release path. Tenant-, game-instance-, region-, or cluster-scoped substitutes cannot satisfy either environment-wide fact. The current executable has no controller-backed result and therefore fails this gate closed; checked-in projections are post-finalization evidence and are not pre-release authority.
- `PREFLIGHT-BACKUP-003` – the target hobby/self-hosted first-live or traffic-reopen gate verifies current backup-baseline compliance evidence and controller-owned live recovery state from the current environment-specific actual-recovery controller at the owner-defined `ready_to_reopen` boundary. It emits prerequisite evidence only; it never invokes the recovery continuation or release path. The current executable has no controller-backed result and therefore fails this gate closed; static baseline results, checked-in projections, or caller-supplied scope/timestamp evidence cannot authorize traffic.

Policy applicability:

- `PREFLIGHT-PROMOTION-001` is required for `production` and `not_applicable` for `staging` and `hobby-self-hosted`.
- `PREFLIGHT-BACKUP-001` is required for every `production` promotion. An `incompatible` result fails unconditionally and cannot be made promotable by attaching drill evidence. A `rollback-compatible` release may reuse only a fresh finalized baseline whose recovery-contract fingerprint is unchanged and whose changed dimensions contain no invalidating or unknown contract change; the compact result does not create another full recovery record. A `drill_required` result fails until a new production-equivalent drill passes and the classifier replaces it with a compatible result bound to that drill. A `roll-forward-only` release requires that regenerated compatible result and a drill that restores a current-production-lineage artifact under candidate recovery tooling and proves the exact candidate service digests, migration path, config, and bindings through controlled reopen.
- `PREFLIGHT-BACKUP-002` is required for `production` on first-live opens and reopen-after-restore events, and `not_applicable` for routine steady-state rollouts that do not change traffic-open status. Its target implementation reads the current environment-specific controller and emits prerequisite evidence only when the owner-defined `ready_to_reopen` boundary and evidence lineage match; it does not authorize continuation or release. The current executable fails this check closed because that controller read is not implemented; maintenance-scope pause/reset evidence and checked-in projections cannot replace the environment-wide artifact or confidentiality evidence.
- `PREFLIGHT-BACKUP-003` is required for `hobby-self-hosted` on first-live opens and reopen-after-restore events, and `not_applicable` otherwise. Its target implementation consumes current backup-baseline compliance, immutable pre-release evidence, and the live actual-recovery controller at the owner-defined `ready_to_reopen` boundary and emits prerequisite evidence only; it does not authorize continuation or release. The checked-in projection is exported only after owner-defined finalization and is not a preflight input. The current executable fails closed for the same missing controller-backed evidence.
- `PREFLIGHT-DIGEST-001` is required for any flow using Kustomize overlays (`staging`, `production`) and `not_applicable` for `hobby-self-hosted`.
- `PREFLIGHT-DIGEST-002` is recommended/advisory for `hobby-self-hosted` and `not_applicable` for `staging`/`production`.
- `PREFLIGHT-SECRETS-002`, `PREFLIGHT-BOOTSTRAP-001`, `PREFLIGHT-EXTERNAL-001`, and `PREFLIGHT-SERVICES-001` are required for all player-facing environments.
- `PREFLIGHT-JWT-001` and `PREFLIGHT-JWKS-001` may run only as current legacy diagnostic checks; they are not selectable player-facing custody and never satisfy player-facing readiness. Exactly one authenticated accepted player-facing custody proof is required: `PREFLIGHT-JWT-INTERIM-001` for the interim mounted fallback or `PREFLIGHT-JWT-002` for target non-exportable signer custody, with an exact mode-matching `proofId`, `custodyMode`, and `contractVersion`. A missing, unknown, mismatched, or not-yet-implemented selected state or applicable proof fails closed. Post-apply live signer and validator convergence remains separate owner evidence.
- `PREFLIGHT-JWT-ROTATION-001` is event-scoped to first-live, reopen, and production promotion evidence for the selected custody backend.
- Target-state `PREFLIGHT-TELNET-001` is required for player-facing environments that expose a public Telnet endpoint and is `not_applicable` only when the deployment inputs explicitly declare that no public Telnet endpoint exists.

## Canonical Expected-Binding Inputs

`PREFLIGHT-EXTERNAL-001` must validate the target environment against one canonical expected-binding input set so deployment preflight and restore validation use the same contract.

Canonical source:

- `design/operations/environments/<environment>/expected-bindings.yaml`

`internalBindings.jwt.custodyMode` is the required authoritative selector for the JWT custody state represented by an expected-binding manifest. It is a closed value with exactly these options:

- `LEGACY_SECRET_DIAGNOSTIC` – the explicit current deployment classification used by all checked-in manifests. It selects only the current legacy Secret-backed diagnostic wiring and remains ineligible to satisfy player-facing custody or readiness.
- `INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK` – the interim Account-only mounted fallback state, whose custody proof is not implemented by the current executable.
- `TARGET_NON_EXPORTABLE_SIGNER` – the target non-exportable signer state, whose custody proof is not implemented by the current executable.

The selector is authoritative; `signingKeysRef` and `jwksRef` do not imply or select a custody state. Missing, unknown, or not-yet-implemented values fail closed, and no selector value implies that either target proof is implemented.

Minimum required keys:

- `internalBindings.postgres.endpoint`
- `internalBindings.postgres.credentialsRef`
- `internalBindings.redis.coordination.endpoint`
- `internalBindings.redis.cache.endpoint`
- `internalBindings.jwt.custodyMode`
- `internalBindings.jwt.signingKeysRef` when the interim mounted fallback or current legacy Secret mode is selected
- `internalBindings.jwt.jwksRef`
  - The current diagnostic contract requires the exact fixed reference `configmap://firemud/jwt-jwks`; a Secret-backed reference is invalid.
- `internalBindings.certificates.issuerRef`
- `internalBindings.certificates.workloadMtlsRef`
- `internalBindings.registry.imagePullSecretRef`
- `backupStorage.enabled` as a boolean
- `backupStorage.bucket` when `backupStorage.enabled: true`
- `backupStorage.endpoint` when enabled and using a non-default S3-compatible endpoint
- `backupStorage.bindingRef` or `backupStorage.fingerprint` when `backupStorage.enabled: true`
- `assetStorage.bucket`, `assetStorage.endpoint`, and `assetStorage.bindingRef` or `assetStorage.fingerprint` when published/runtime assets use external object storage
- `outboundComms.smtpHost` and/or environment-classified webhook target identifiers when email or webhook integrations are enabled
- Disabled asset-storage and outbound-communications integrations require neither a section nor placeholder targets or credentials
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
  - `internalBindings.jwt.custodyMode` with one of `LEGACY_SECRET_DIAGNOSTIC`, `INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK`, or `TARGET_NON_EXPORTABLE_SIGNER`
  - `internalBindings.jwt.signingKeysRef` when the interim mounted fallback or current legacy Secret mode is selected
  - `internalBindings.jwt.jwksRef`
  - `internalBindings.certificates.issuerRef`
  - `internalBindings.certificates.workloadMtlsRef`
  - `internalBindings.certificates.gatewayInternalWsListenerRef` when the environment exposes the Gateway internal mTLS WebSocket listener
  - `internalBindings.certificates.tcpProxyBridgeClientRef` when the TCP Proxy bridge uses mTLS
  - `internalBindings.certificates.backupControlPlaneClientRef` only when `backupMaintenancePause.enabled: true` explicitly enables an exceptional backup-related maintenance workflow that invokes `PauseTicks` / `ResumeTicks`; routine online backup does not require this identity
  - `internalBindings.registry.imagePullSecretRef`
- Required external binding keys:
  - `backupStorage.enabled` as a boolean; production must set it to `true`
  - `backupStorage.bucket` when `backupStorage.enabled: true`
  - `backupStorage.endpoint` when enabled and non-default
  - `backupStorage.bindingRef` or `backupStorage.fingerprint` when `backupStorage.enabled: true`
  - `assetStorage.bucket`, `assetStorage.endpoint`, and `assetStorage.bindingRef` or `assetStorage.fingerprint` when published/runtime assets use external object storage
  - `outboundComms.smtpHost` and/or environment-classified webhook identifiers when enabled
  - `operatorCredentials.bindingRef` or `operatorCredentials.fingerprint`
- Precedence rules:
  - When both `bindingRef` and `fingerprint` are present for the same binding, `bindingRef` is canonical and `fingerprint` is supporting validation detail only.
  - The same precedence applies to `backupStorage`, `assetStorage`, and `operatorCredentials`.
- Optional supporting sections:
  - `observability`
  - environment-owned non-secret shared values explicitly marked as shared with rationale

The illustrative staging manifest below reflects the one actual checked-in legacy Secret-backed signing plus public ConfigMap JWT/JWKS deployment mode (`configmap://firemud/jwt-jwks` and `secret://firemud/jwt-signing-keys`). The interim Account-only mounted fallback and target non-exportable signer state each use a separate Account-owned `jwt-jwks` ConfigMap projection with distinct mode-specific proof; neither may be inferred from this current example.

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
    custodyMode: LEGACY_SECRET_DIAGNOSTIC
    signingKeysRef: secret://firemud/jwt-signing-keys
    jwksRef: configmap://firemud/jwt-jwks
  certificates:
    issuerRef: cert-manager://firemud/clusterissuers/firemud-staging
    workloadMtlsRef: cert-manager://firemud/staging-workload-mtls
    gatewayInternalWsListenerRef: cert-manager://firemud/staging-gateway-internal-ws
    tcpProxyBridgeClientRef: cert-manager://firemud/staging-tcp-proxy-bridge
  registry:
    imagePullSecretRef: secret://firemud/ghcr-pull-staging
backupStorage:
  enabled: true
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
- `backupStorage.enabled` is required and must be a boolean. Production fails closed when it is false. An enabled backup declaration must include a bucket and `bindingRef` or `fingerprint`; a disabled declaration must omit backup bucket, endpoint, `bindingRef`, and `fingerprint` fields. The endpoint remains conditional on use of a non-default S3-compatible endpoint. Disabled backup storage is not considered for external-binding uniqueness, while all other applicable external integrations retain their normal checks.
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
- `jwtCustodyProof` only for a consumed report that can authorize a protected player-facing deployment, production promotion or production attestation, traffic-open event, or fresh-boundary restore, containing the exact accepted `proofId`, `custodyMode`, and `contractVersion`; ordinary non-player deployment, drill, maintenance, and diagnostic reports do not require this field, and `ci-static` reports may omit it because their JWT custody results are non-authorizing static evidence
- `checkResults[]` with `policyId`, boolean `required`, `status`, and `message`
- `expectedBindingsRef` for player-facing environments
- `expectedBindingsDigest` for the exact manifest bytes consumed
- for live phases, a complete `candidateResourceInventory` bound to the exact candidate-render digest; each resource records its stable candidate identity, whether pre-apply expects it `present` or `absent-before-create`, and the matching live observation. Post-apply evidence must account for every inventoried resource and exact-match the stable observed identity of each resource created or retained by the candidate; missing candidate resources, undeclared extras within the candidate-owned set, and resources expected to be created but still absent fail closed
- `policyCatalogVersion` and evaluation `phase`
- target environment and cluster identity for live phases
- `startedAt` and `completedAt` timestamps
- `toolVersion`
- `context` (`operator` or `ci-static`)

For `ci-static` runs, `expectedBindingsRef` should point to the same repository path that operator preflight would use for the target environment, even when CI validates only static contracts and not live cluster bindings. A consumed report for a protected player-facing deployment, production promotion or production attestation, traffic-open event, or fresh-boundary restore must have `context=operator`, the canonical `preflight.py-v1` tool version, ordered non-future execution timestamps, exact environment/event applicability, an authorizing `jwtCustodyProof`, and every required policy result at `pass`; ordinary non-player deployment, drill, maintenance, and diagnostic reports may be consumed without `jwtCustodyProof` but are never eligible to authorize a protected player-facing transition. A `ci-static` report cannot authorize promotion, player traffic, or a custody mode, and any JWT custody result it carries remains non-authorizing static evidence. A deployment record may consume only the canonical event-scoped report path whose `deploymentEventId` matches both the path and that record, whose `completedAt` is not later than `appliedAt`, and whose completion is no more than 30 minutes before apply. A recovery controller must consume and re-check the event-scoped report no more than 30 minutes before its traffic-release decision. No renewable report artifact is defined, so an expired report requires a new preflight event. A later finalized traffic-open projection references that already-consumed report and controller evidence; export time does not re-consume the report or create a second freshness gate.

For a protected player-facing deployment, production promotion or production attestation, first-live or reopen traffic-open event, or fresh-boundary restore, the consuming deployment, promotion, attestation, or recovery record must preserve the exact `jwtCustodyProof` tuple for retries and replay. A retry may consume only a proof with the same `proofId`, `custodyMode`, and `contractVersion`; it must not reselect a mode or treat a legacy diagnostic result as the selected proof. Ordinary non-player reports remain outside this custody-proof requirement and cannot be promoted into protected player-facing evidence.

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
  "expectedBindingsDigest": "sha256:...",
  "policyCatalogVersion": "<policy-catalogue-version>",
  "phase": "static-ci",
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
- Waiver records must include: authorized approver identity, incident/change ticket, rationale, exact policy IDs and phase, target environment, deployment event identity, issue timestamp, and event-bounded expiration.
- Current implementation status: `preflight.py` rejects `FIREMUD_PREFLIGHT_WAIVER` before generating a deployment event or report, and rejects consumed reports containing `waiverPath`, until a trusted authority can issue and atomically consume each waiver exactly once. Event-ID equality alone is not replay protection.

## Failure Handling

- Any failed applicable `apply-blocking` check blocks deployment; any failed applicable `non-waivable-promotion-traffic-open` check blocks promotion or traffic opening.
- Waivers are a target-state break-glass mechanism only and fail closed when malformed, expired, unauthorized, prohibited for the policy category, or mismatched to the event, phase, environment, or policy ID. They are not currently executable.
- Waivers expire after the specific deployment event and must not silently carry forward. A retry or re-apply uses a new `deploymentEventId`, so a prior waiver fails binding validation even when the same `deploymentRef` is reused.
- `PREFLIGHT-BACKUP-001`, `PREFLIGHT-BACKUP-002`, and `PREFLIGHT-BACKUP-003` are non-waivable readiness gates. A waiver may authorize an isolated drill or salvage action, but not the player-facing promotion/open transition those gates protect.

## Related Documentation

- `design/architecture/system-architecture-deployment-runbook.md`
- `design/architecture/system-architecture-cicd.md`
- `design/architecture/system-architecture-promotion-attestation.md`
- [Validation and Runtime Proof](../developer-workflows/validation-and-runtime-proof.md)
- [Active P0 ADR: phased environment-bound preflight and expected bindings](./decisions/adr-0152-phased-environment-bound-deployment-preflight-and-expected-bindings.md)
