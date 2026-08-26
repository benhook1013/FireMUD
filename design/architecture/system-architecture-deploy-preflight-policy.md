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

`./dev-tools/deploy/preflight.py` is the canonical executable preflight entrypoint for the checks it currently implements. Checked-in deployment paths remain legacy Secret-backed JWT signing: player-facing Kustomize uses a fixed public `jwt-jwks` Secret, while hosted preview Helm uses its separate diagnostic public `jwt-jwks` ConfigMap wiring. It consumes `design/operations/environments/<environment>/expected-bindings.yaml`, writes the exact consumed-byte SHA-256 as `expectedBindingsDigest` alongside `expectedBindingsRef` and `policyCatalogVersion` in reports, resolves and emits the catalogue `category` for every result, validates consumed reports against the exact implemented policy-ID set and category vocabulary, validates the mounted JWT path and legacy resource contract, validates expected binding shape, and enforces the typed binding-shareability matrix. Target-state-only catalogue IDs are excluded from that report set until implemented; every implemented ID is required, and duplicate or unknown IDs fail validation. Environment-exclusive internal state/trust, credential principals, and operator identities cannot be marked shared; only matrix-approved non-sensitive endpoints/targets can be shared with matching declarations and rationale. Optional asset and outbound integrations are validated only when their canonical `enabled` selector is true. In the current executable, `PREFLIGHT-JWT-001` checks the signing path and only requires the canonical `jwt-signing-keys` mount when the resolved private path is under `/var/run/secrets/firemud/jwt/`; advisory `PREFLIGHT-JWKS-001` checks the applicable public resource/path/mount contract, including the explicit `secret://firemud/jwt-jwks` binding for player-facing Kustomize. These two results are diagnostic resource/path wiring evidence only; they do not prove runtime JWKS acceptance, validator convergence, or signer custody. The policy separately defines the interim Account-only mounted fallback and target non-exportable signer states, each with deeper mode-specific proof that the executable does not yet emit. The executable does not inspect deployed public Telnet listener topology or emit a public-listener exposure-exclusivity result; TCP Proxy runtime configuration cannot infer deployed exposure. Routine online backup does not require `backupControlPlaneClientRef`; that identity is validated only when an expected-bindings manifest explicitly enables an exceptional maintenance pause workflow.

The current report generator emits the exact `expectedBindingsDigest`; staging deployment records must carry the immutable expected-bindings reference and digest, and promotion compares the record, event-scoped preflight report, and current manifest bytes before accepting them. The executable does not yet emit or enforce the target evaluation `phase`, live target/cluster identity, or conditional authorizing `jwtCustodyProof`. Until those remaining fields and their focused proof are implemented together, current reports remain partial non-authorizing evidence and cannot satisfy a protected player-facing deployment, promotion, first-live, reopen, or fresh-boundary restore gate.

The restore mode, continuation, replay, and reopen lifecycle is canonical in [Backup & Disaster Recovery](./system-architecture-backup-recovery.md). Target preflight will validate controller-owned live recovery state and immutable evidence and emit prerequisite evidence. It is never release authority and does not perform continuation, authorization, or release. The current executable has no controller-backed result because no recovery-controller RPC currently exists in the checked-in `protos/` source, so this remains a target-state contract rather than an implemented gRPC surface.

Production and hobby player-facing first-live/reopen remain fail-closed until preflight can read the durable environment-wide controller and verify the owner-defined cold-start convergence, fixed erasure replay, session invalidation, participant and external-effect dispositions, hardening, external credentials, secret-compliance refresh, smoke evidence, and lifecycle ordering. Checked-in recovery/traffic-open projections are retained evidence only and never preflight authority.

The current executable does not yet enforce `PREFLIGHT-JWT-INTERIM-001`, `PREFLIGHT-JWT-002`, or `PREFLIGHT-JWT-ROTATION-001`: it does not prove the complete interim mounted-fallback custody contract, target non-exportable signer health, absence of private-key mounts or distribution to any application workload, validator `kid`/JWKS behavior, or planned and compromise rotation drills. Pre-apply trusted bootstrap evidence is limited to the selected state's resource, binding, and RBAC boundary; it is not live signer or validator convergence. Post-apply live convergence is owner-produced: Account owns JWT signing/publication/reconciliation, the recovery controller owns recovery reconciliation, and the rotation-evidence workload is observation-only. In the target player-facing model, Account authenticates a healthy signer reference/generation and proves challenge-signature correspondence with the target Account-published JWK; no application workload mounts or receives private signing material, `jwt-signing-keys` is not a target resource, and `FIREMUD_AUTH_JWT_SECRET_PATH` is not configured. In the interim model, the materialization controller may materialize/generate/prune private slots only under Account authorization and its name-scoped RBAC, while Account alone consumes the private mount and the separate interim Account-owned public JWKS projection. `FIREMUD_AUTH_JWKS_PATH` is mandatory for Account and every validator in either accepted mode and identifies the selected mode's public JWKS. Private-file path and mount validation applies only to the interim mounted fallback; target mode requires `FIREMUD_AUTH_JWT_SECRET_PATH` unset and rejects private material. Validators require the token `kid` to resolve through Account-published JWKS and disable HMAC fallback. These are missing security gates, not only evidence-depth gaps. The current partial legacy JWT path/resource checks are static, non-authorizing checks: they cannot satisfy or participate in authorization for player-facing readiness. Player-facing readiness remains blocked until the complete applicable interim or target proof covers signer custody, JWKS projection, validator behavior, and post-apply rotation/convergence evidence. A legacy-mode pass must not be reused as proof for either accepted state, and an accepted-mode manifest must fail closed until its applicable Account-owned signer/publication checks are implemented. Other expected-binding checks still validate repository manifests and declared binding refs rather than complete live state; a successful static report without the applicable traffic-open authority is not enough to open player-facing traffic.

Current checks validate expected-binding shape/presence and selected fixed resources only; they do not exact-match every declared endpoint or reference against rendered or live effective configuration, including arbitrary jwksRef, so these checks remain non-authorizing. A syntactically valid not-provisioned secret-compliance record is record validation only: inventory corroboration is not performed, and it cannot be described as credential compliance or readiness authorization. The current selector cannot require postgres-admin-credentials; player-facing rotation-job use remains unavailable and non-authorizing until a canonical enablement/binding selector and evidence class are defined and implemented.

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
- Output after deployment-event creation: non-zero exit code on failure and a machine-readable report artifact (for example JSON). Rejected pre-event waiver input follows the separate non-authorizing audit rule under [Evidence Storage and Retention](#evidence-storage-and-retention).
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

Every policy ID declares one enforcement category: `advisory`, `apply-blocking`, or `non-waivable-promotion-traffic-open`. Apply-blocking checks may accept a valid event-scoped waiver, except that the custody-selection failure carried by `PREFLIGHT-BOOTSTRAP-001` is non-waivable for player-facing apply, promotion, or traffic-open. This is a subcondition of that policy, not a reclassification of all bootstrap failures: other eligible `PREFLIGHT-BOOTSTRAP-001` apply-blocking failures retain their ordinary waiver semantics. A waiver may authorize isolated repair or a quarantined drill, but cannot authorize a transition protected by a non-waivable check.

The design-owned catalogue below is mirrored by the machine-readable `PREFLIGHT_POLICY_CATALOG` mapping in `dev-tools/deploy/preflight.py` under `policyCatalogVersion: preflight-policy-v1`. The mirror is fail-closed if its ID set is not exactly the set below, if any ID has more than one mapping, or if a category is outside the three values above. The four entries marked target-state-only remain in the catalogue for stable identity and category but are not emitted by the current executable.

| Policy ID | Enforcement category | Current executable |
| --- | --- | --- |
| `PREFLIGHT-DIGEST-001` | `apply-blocking` | emitted |
| `PREFLIGHT-DIGEST-002` | `advisory` | emitted |
| `PREFLIGHT-SECRETS-001` | `apply-blocking` | emitted |
| `PREFLIGHT-SECRETS-002` | `apply-blocking` | emitted |
| `PREFLIGHT-JWT-001` | `advisory` | emitted |
| `PREFLIGHT-JWT-INTERIM-001` | `non-waivable-promotion-traffic-open` | target-state-only |
| `PREFLIGHT-JWKS-001` | `advisory` | emitted |
| `PREFLIGHT-JWT-002` | `non-waivable-promotion-traffic-open` | target-state-only |
| `PREFLIGHT-JWT-ROTATION-001` | `non-waivable-promotion-traffic-open` | target-state-only |
| `PREFLIGHT-TELNET-001` | `non-waivable-promotion-traffic-open` | target-state-only |
| `PREFLIGHT-BRIDGE-001` | `apply-blocking` | emitted |
| `PREFLIGHT-REDIS-001` | `apply-blocking` | emitted |
| `PREFLIGHT-BOOTSTRAP-001` | `apply-blocking` | emitted |
| `PREFLIGHT-EXTERNAL-001` | `apply-blocking` | emitted |
| `PREFLIGHT-SERVICES-001` | `apply-blocking` | emitted |
| `PREFLIGHT-PROMOTION-001` | `non-waivable-promotion-traffic-open` | emitted |
| `PREFLIGHT-BACKUP-001` | `non-waivable-promotion-traffic-open` | emitted |
| `PREFLIGHT-BACKUP-002` | `non-waivable-promotion-traffic-open` | emitted |
| `PREFLIGHT-BACKUP-003` | `non-waivable-promotion-traffic-open` | emitted |

The policy catalogue owns the binding-type shareability matrix. Production PostgreSQL and Redis authorities, JWT signing/JWKS trust, certificate issuers and workload private identities, production-capable registry credentials, backup/asset write principals, and operator-control identities are environment-exclusive. `shared: true` is accepted only for a class the matrix marks shareable or conditionally shareable, with matching declarations, rationale, and required isolation evidence in every participating environment; it cannot override an environment-exclusive class. Optional asset storage, outbound communications, non-default object storage, webhooks, and similar integrations are required only when their canonical enablement input is active; disabled integrations do not require placeholder targets or credentials.

## Enforcement Boundaries

- Overlay PR CI (`validate-kustomize-overlays.yml`) always enforces the staging backup marker and production evidence-file selection rules. When no production attestation context applies, it also renders both overlays and checks image existence. For production-applicable changes, the current preflight stops at the fail-closed recovery-baseline authority check before attestation digest matching, expanded backup-readiness validation, or the later image-existence steps; those remain target-state enforcement gaps rather than completed checks.
- Operator pre-apply execution (`preflight.py`) currently enforces resolved-manifest and target-environment checks for required secret/key bindings, Redis role split, bridge alignment, bootstrap completeness, and external integration isolation. The checked-in player-facing Kustomize path is legacy Secret-backed signing plus public Secret resource wiring; hosted preview Helm remains a separate ConfigMap-backed diagnostic path. The legacy JWT/JWKS branch emits diagnostic path/resource checks; those checks are not an accepted custody proof. Accepted-state private-file path and mount validation applies only to the interim mounted fallback. In the interim mounted fallback, preflight must prove the separate interim Account-owned public `jwt-jwks` ConfigMap, Account-authorized materialization-controller name-scoped RBAC, Account-only private mount, and no private material in validators or rotation-evidence workloads. In target non-exportable-signer mode, preflight must instead require the separate target Account-owned `jwt-jwks` ConfigMap, Account-only name-scoped publication/CAS authority, read-only public projection at the mandatory `FIREMUD_AUTH_JWKS_PATH` for Account and every validator, `FIREMUD_AUTH_JWT_SECRET_PATH` unset, and proof that no application workload has a private-key mount or distribution; live signer health/generation, challenge-signature correspondence, validator `kid`/JWKS behavior, and public-JWKS convergence are post-apply owner evidence. The current executable has not yet implemented those accepted-state branches, so passing legacy checks is not evidence of either accepted custody state. `PREFLIGHT-JWT-INTERIM-001`, `PREFLIGHT-JWT-002`, and `PREFLIGHT-JWT-ROTATION-001` are not yet emitted by the executable; selecting an accepted state remains fail-closed.
- Deployment apply is blocked unless every required check for the target class passes. The target-state waiver path is event-scoped; the current executable rejects waiver input and does not provide a waiver bypass.

## Environment Applicability

| Environment class | Overlay PR CI required | Operator preflight required | Notes |
| --- | --- | --- | --- |
| `staging` | Yes | Yes | Both gates mandatory before apply. |
| `production` | Yes | Yes | Both gates mandatory before apply. |
| `hobby-self-hosted` | Optional (recommended) | Yes | Operator preflight is mandatory; CI may be unavailable in single-operator setups. |

`pr-preview` and `dev-demo-cluster` are non-player-facing hosted Helm environments and are outside the `preflight.py` applicability table. Their workflow validation is the rendered Helm chart plus Kubernetes server dry-run; the separate `helm-jwks-contract.sh` test covers preview JWKS resource and Account-mount wiring. These checks are diagnostic hosted-environment proof and do not produce player-facing preflight evidence, expected-bindings reports, promotion authority, or traffic-open authorization.

## Required Policy Checks

Every run must emit one result per implemented policy ID below, with status `pass`, `fail`, or `not_applicable` (with reason). Entries marked target-state-only are not emitted until their executable checks and contract proof land:

- `PREFLIGHT-DIGEST-001` – all staging/production workload images are immutable digests (`image@sha256:...`).
- `PREFLIGHT-DIGEST-002` – hobby/self-hosted workload manifests are digest-pinned where the operator packaging format supports digest references.
- `PREFLIGHT-SECRETS-001` – required trust resources, Secrets, and keys exist for the target environment.
- `PREFLIGHT-SECRETS-002` – player-facing environments validate internal state/trust bindings (PostgreSQL endpoint and credential binding, Redis role endpoints, JWT/JWKS resource bindings, certificate issuer binding, registry pull credentials) against the target environment boundary and fail on cross-environment reuse.
- `PREFLIGHT-JWT-001` – current executable diagnostic check for the one checked-in legacy Secret/path deployment mode only. In legacy `Secret` mode, it rejects inline JWT secret configuration and checks the shared path/mount contract where every primary workload declares `FIREMUD_AUTH_JWT_SECRET_PATH` and a workload whose resolved path is under `/var/run/secrets/firemud/jwt/` mounts `jwt-signing-keys` at that root. A custom resolved private path does not imply the canonical mount. This legacy result is diagnostic wiring evidence only: it is not selectable player-facing custody, is not the interim mounted-fallback proof, and cannot satisfy any target custody or player-facing readiness gate.
- `PREFLIGHT-JWT-INTERIM-001` (target-state-only; not currently emitted) – complete pre-apply trusted bootstrap proof for the interim Account-only mounted fallback. This is distinct from `PREFLIGHT-JWT-001`. Its authenticated result must carry the exact identity `proofId: PREFLIGHT-JWT-INTERIM-001`, `custodyMode: INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK`, and `contractVersion: 1`. Consumers must verify the result authentication and exact-match `proofId`, `custodyMode`, and `contractVersion` against the expected values before accepting it; missing, aliased, unsupported, or mismatched mode/version fails closed. When implemented, the proof must cover the fixed pre-created `jwt-signing-keys` Secret, the materialization controller's name-scoped write/generate/materialize/prune RBAC, Account authorization for each private-slot operation, Account-only `FIREMUD_AUTH_JWT_SECRET_PATH` and private mount, the mandatory public `FIREMUD_AUTH_JWKS_PATH` for Account and every validator, the separate interim public `jwt-jwks` projection, and validator/rotation-evidence-workload absence of private material. The materialization controller is excluded from the general observation-only rule only for these bounded Account-authorized private-slot operations; it is not rotation authority. It is bootstrap evidence, not post-apply signer or validator convergence evidence. A `PREFLIGHT-JWT-001` pass, Secret/path inspection, or inferred mode cannot satisfy this proof.
- `PREFLIGHT-JWKS-001` – current public-resource/path wiring diagnostic check only. For checked-in player-facing Kustomize manifests, the current executable requires the `jwt-jwks` `Secret`, the canonical `secret://firemud/jwt-jwks` binding, an Account Service `FIREMUD_AUTH_JWKS_PATH` under `/var/run/secrets/firemud/jwks/`, and an Account volume mount of that Secret at the configured path. Hosted preview Helm remains ConfigMap-backed and has a separate preview resource contract. This advisory result does not prove runtime JWKS acceptance, validator convergence, signer custody, or either accepted player-facing custody proof; those deeper mode-specific checks remain separate target-state evidence.
- `PREFLIGHT-JWT-002` (target-state-only; not currently emitted) – target non-exportable-signer pre-apply trusted bootstrap proof and no-private-mount boundary. Its authenticated result must carry the exact identity `proofId: PREFLIGHT-JWT-002`, `custodyMode: TARGET_NON_EXPORTABLE_SIGNER`, and `contractVersion: 1`; consumers must verify the result authentication and exact-match tuple before accepting it. Player-facing resolved manifests use the fixed, pre-created target Account-published `jwt-jwks` ConfigMap and its read-only public projection at the mandatory `FIREMUD_AUTH_JWKS_PATH` for Account and every validator; the bootstrap proof binds the signer reference and Account publication/CAS authority. No application workload, including Account, validators, recovery/rotation Jobs, or the materialization controller, mounts or receives private signing material; target manifests reject `jwt-signing-keys`, `FIREMUD_AUTH_JWT_SECRET_PATH`, and Secret-backed JWKS. Post-apply Account evidence must prove healthy signer generation and challenge-signature correspondence with the published JWK, and validators must prove asymmetric Account `kid`/JWKS verification with HMAC fallback disabled. Every required validator consumes public JWKS only. This target proof is distinct from `PREFLIGHT-JWT-001`; a Secret/mount pass cannot satisfy it.
- `PREFLIGHT-JWT-ROTATION-001` (target-state-only; not currently emitted) – post-apply player-facing first-live, reopen, and promotion evidence referencing successful planned-rotation and compromise-cutover drills for the selected custody state using the production rotation artifact. Account alone reconciles and advances rotation; the recovery controller persists the operation/evidence, invokes the Account-owned operation, and observes returned convergence. The rotation-evidence workload is observation-only and may record public-JWKS convergence, validator inventory, old/new `kid` acceptance and rejection, applicable pruning evidence, and immutable evidence identity without requesting or mutating signer state.
- `PREFLIGHT-TELNET-001` (target-state-only; not currently emitted) – public Telnet listener exposure exclusivity. For each player-facing endpoint that exposes Telnet, deployment preflight must validate the deployed topology and prove exactly one public TLS ingress mode: `EDGE_PROXY` exposes only the TLS-terminating edge, with the TCP Proxy PROXY-protocol and direct/raw listeners private; `DIRECT_TLS` exposes only the TCP Proxy TLS listener, with no public edge/PROXY path and raw/plaintext listeners private. It must reject an unset or mismatched mode, both public modes, public raw/plaintext or PROXY-protocol listeners, and any externally exposed listener not selected by the mode. TCP Proxy runtime configuration cannot infer this deployment topology. The current executable does not implement or emit this check.
- `PREFLIGHT-BRIDGE-001` – `GATEWAY_WS_URL` matches the expected internal Gateway listener for the target environment.
- `PREFLIGHT-REDIS-001` – player-facing environments resolve distinct Coordination vs Cache Redis endpoints.
- `PREFLIGHT-BOOTSTRAP-001` – player-facing environments confirm the minimum bootstrap secret and trust resources exist before apply, and bind inventory confirmation, expected bindings, namespace/resources, and bootstrap evidence to one durable operation and exact monotonic provisioning generation. `not-provisioned` is valid only before any required resource exists; partial or crashed work remains pending, quarantined, or noncompliant and resumes that operation, and only a completed exact generation can project `provisioned`. The catalogue entry remains `apply-blocking` for ordinary bootstrap failures, but a custody-selection failure carried by this result is non-waivable for player-facing apply, promotion, or traffic-open: the protected transition requires exactly one authenticated accepted custody proof with the exact tuple `proofId`, `custodyMode`, and `contractVersion` (`PREFLIGHT-JWT-INTERIM-001`/`INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK` or `PREFLIGHT-JWT-002`/`TARGET_NON_EXPORTABLE_SIGNER`, both `contractVersion: 1`). A missing, unknown, unsupported, not-yet-implemented, or mismatched selected proof cannot be replaced by a waiver or by a legacy diagnostic result; other bootstrap failures retain the eligible apply-blocking waiver rules.
- `PREFLIGHT-EXTERNAL-001` – player-facing environments validate that backup storage, asset storage, outbound communications, and operator credential bindings match the target environment and do not cross environment boundaries. For backup and asset storage, the proof must include the credential-binding identity that owns the object-store target.
- `PREFLIGHT-SERVICES-001` – player-facing environments either run with default in-environment service discovery or declare explicit `FIREMUD_SERVICES_*` overrides that are allowlisted for the target environment and do not resolve across environment boundaries.
- `PREFLIGHT-PROMOTION-001` – production promotions reference a valid staging attestation with matching digests.
- `PREFLIGHT-BACKUP-001` – every production promotion includes the compact recovery-compatibility result plus a current environment-bound reference and complete digest for the newest verified restorable point, with its observed age within the accepted 15-minute bound. `compatibilityStatus=incompatible` is an unconditional failed result, compatible rollback releases may reuse the current baseline, and `compatibilityStatus=drill_required` remains non-promotable until a fresh drill produces a regenerated compatible result. `roll-forward-only` releases set `newDrillRequired=true` and require that compatible result plus a full release-candidate recovery drill bound to exact candidate lineage, finalized controller lineage, and backup-confidentiality proof; an upload-existence check cannot satisfy freshness.
- `PREFLIGHT-BACKUP-002` – the target production first-live or traffic-reopen gate requires the [production traffic-open backup evidence](./system-architecture-backup-recovery-evidence-and-compliance.md#production-traffic-open-backup-evidence): a current environment-bound reference and complete digest for the newest verified restorable point whose observed age is within 15 minutes, plus a `backupReadinessRef` that resolves to a backup-readiness artifact whose `restoreRecoveryRecordRef` independently resolves to a finalized production-equivalent, environment-wide `cold_start_restore` drill, plus an independent `baselineRecoveryRecordRef` that identifies a finalized environment-wide `cold_start_restore` drill. It also verifies a readable environment-wide PostgreSQL backup, backup-confidentiality evidence, and controller-owned live recovery state from the current environment-specific actual-recovery controller at the owner-defined `ready_to_reopen` boundary before traffic is opened. The two recovery references are distinct prerequisites; the restore record resolved through `backupReadinessRef` cannot substitute for `baselineRecoveryRecordRef`. It emits prerequisite evidence only; it never invokes the recovery continuation or release path. Tenant-, game-instance-, region-, or cluster-scoped substitutes cannot satisfy either environment-wide fact. The current executable has no controller-backed result and therefore fails this gate closed; checked-in projections are post-finalization evidence and are not pre-release authority.
- `PREFLIGHT-BACKUP-003` – the target hobby/self-hosted first-live or traffic-reopen gate verifies current backup-baseline compliance evidence and controller-owned live recovery state from the current environment-specific actual-recovery controller at the owner-defined `ready_to_reopen` boundary. It emits prerequisite evidence only; it never invokes the recovery continuation or release path. The current executable has no controller-backed result and therefore fails this gate closed; static baseline results, checked-in projections, or caller-supplied scope/timestamp evidence cannot authorize traffic.

Policy applicability:

- `PREFLIGHT-PROMOTION-001` is required for `production` and `not_applicable` for `staging` and `hobby-self-hosted`.
- `PREFLIGHT-BACKUP-001` is required for every `production` promotion. It must consume the current environment-bound verified-point reference/digest and prove the 15-minute bound. An `incompatible` result fails unconditionally and cannot be made promotable by attaching drill evidence. A `rollback-compatible` release may reuse only a fresh finalized baseline whose recovery-contract fingerprint is unchanged and whose changed dimensions contain no invalidating or unknown contract change; the compact result may reference the live freshness proof and does not create another full recovery record. A `drill_required` result fails until a new production-equivalent drill passes and the classifier replaces it with a compatible result bound to that drill. A `roll-forward-only` release requires that regenerated compatible result and a drill that restores a current-production-lineage artifact under candidate recovery tooling and proves the exact candidate service digests, migration path, config, and bindings through controlled reopen.
- `PREFLIGHT-BACKUP-002` is required for `production` on first-live opens and reopen-after-restore events, and `not_applicable` for routine steady-state rollouts that do not change traffic-open status. Its target implementation reads the current environment-specific controller and freshness proof and emits prerequisite evidence only when the owner-defined `ready_to_reopen` boundary, 15-minute freshness bound, and evidence lineage match; it does not authorize continuation or release. The current executable fails this check closed because that controller read is not implemented; maintenance-scope pause/reset evidence, upload existence, and checked-in projections cannot replace the environment-wide artifact or confidentiality evidence.
- `PREFLIGHT-BACKUP-003` is required for `hobby-self-hosted` on first-live opens and reopen-after-restore events, and `not_applicable` otherwise. Its target implementation consumes current backup-baseline compliance, immutable pre-release evidence, and the live actual-recovery controller at the owner-defined `ready_to_reopen` boundary and emits prerequisite evidence only; it does not authorize continuation or release. The checked-in projection is exported only after owner-defined finalization and is not a preflight input. The current executable fails closed for the same missing controller-backed evidence.
- `PREFLIGHT-DIGEST-001` is required for any flow using Kustomize overlays (`staging`, `production`) and `not_applicable` for `hobby-self-hosted`.
- `PREFLIGHT-DIGEST-002` is recommended/advisory for `hobby-self-hosted` and `not_applicable` for `staging`/`production`.
- `PREFLIGHT-SECRETS-002`, `PREFLIGHT-BOOTSTRAP-001`, `PREFLIGHT-EXTERNAL-001`, and `PREFLIGHT-SERVICES-001` are required for all player-facing environments.
- `PREFLIGHT-JWT-001` and `PREFLIGHT-JWKS-001` are advisory diagnostics: a failing result reports the wiring risk and has `required: false`, so static CI may pass while retaining the failure in the report. They are not selectable player-facing custody and never satisfy player-facing readiness. Exactly one authenticated accepted player-facing custody proof is required: `PREFLIGHT-JWT-INTERIM-001` for the interim mounted fallback or `PREFLIGHT-JWT-002` for target non-exportable signer custody, with an exact mode-matching `proofId`, `custodyMode`, and `contractVersion`. A missing, unknown, mismatched, or not-yet-implemented selected state or applicable proof fails closed through the apply-blocking `PREFLIGHT-BOOTSTRAP-001` result; no duplicate diagnostic policy ID is created. Post-apply live signer and validator convergence remains separate owner evidence.
- `PREFLIGHT-JWT-ROTATION-001` is event-scoped to first-live, reopen, and production promotion evidence for the selected custody backend. Any staging deployment record selected as production-attestation evidence must carry `jwtCustodyProof` with the exact `{proofId,custodyMode,contractVersion}` tuple from its consumed operator preflight report and an immutable `jwtRotationEvidenceRef` for a passing `PREFLIGHT-JWT-ROTATION-001` result, with both matching the production candidate and its applicable contract.
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
  - The checked-in player-facing Kustomize diagnostic contract requires the exact fixed reference `secret://firemud/jwt-jwks`; hosted preview Helm's ConfigMap reference is preview-scoped and is not a player-facing binding.
- `internalBindings.certificates.issuerRef`
- `internalBindings.certificates.workloadMtlsRef`
- `internalBindings.registry.imagePullSecretRef`
- `backupStorage.enabled` as a boolean
- `backupStorage.bucket` when `backupStorage.enabled: true`
- `backupStorage.endpoint` when enabled and using a non-default S3-compatible endpoint
- `backupStorage.bindingRef` or `backupStorage.fingerprint` when `backupStorage.enabled: true`
- `assetStorage.enabled` when the section is present; when true, `assetStorage.bucket`, `assetStorage.endpoint`, and `assetStorage.bindingRef` or `assetStorage.fingerprint` are required, while false requires all target/credential fields to be omitted
- `outboundComms.enabled` when the section is present; when true, `outboundComms.smtpHost` or a non-empty `outboundComms.webhookTargets` mapping is required, while false requires all target/credential fields to be omitted
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
  - `assetStorage` only when the optional asset integration is declared; if present, `assetStorage.enabled` is required
  - `outboundComms` only when the optional outbound integration is declared; if present, `outboundComms.enabled` is required
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
  - `assetStorage.enabled: true` requires `bucket`, `endpoint`, and `bindingRef` or `fingerprint`; `enabled: false` requires those fields to be absent
  - `outboundComms.enabled: true` requires `smtpHost` or a non-empty `webhookTargets` mapping; `enabled: false` requires those fields to be absent
  - `operatorCredentials.bindingRef` or `operatorCredentials.fingerprint`
- Precedence rules:
  - When both `bindingRef` and `fingerprint` are present for the same binding, `bindingRef` is canonical and `fingerprint` is supporting validation detail only.
  - The same precedence applies to `backupStorage`, `assetStorage`, and `operatorCredentials`.
- Optional supporting sections:
  - `observability`
  - environment-owned non-secret shared values explicitly marked as shared with rationale

The illustrative staging manifest below reflects the checked-in player-facing legacy Secret-backed signing plus public `jwt-jwks` Secret deployment mode (`secret://firemud/jwt-jwks` and `secret://firemud/jwt-signing-keys`). Hosted preview Helm remains ConfigMap-backed, while the interim Account-only mounted fallback and target non-exportable signer state each use a separate Account-owned `jwt-jwks` ConfigMap projection with distinct mode-specific proof; none may be inferred from this current example.

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
    jwksRef: secret://firemud/jwt-jwks
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
  enabled: true
  bucket: firemud-staging-assets
  endpoint: https://minio.staging.internal
  bindingRef: secret://firemud/staging-asset-object-store
outboundComms:
  enabled: true
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
- The manifest must prove environment isolation using the binding-shareability matrix. PostgreSQL/Redis state, JWT signing/JWKS trust, certificate issuer/workload identities, registry pull credentials, backup/asset credential principals, and operator identities are environment-exclusive and cannot be shared. Only matrix-approved non-sensitive bucket/endpoint/SMTP/webhook targets may be marked shared.
- `backupStorage.enabled` is required and must be a boolean. Production fails closed when it is false. An enabled backup declaration must include a bucket and `bindingRef` or `fingerprint`; a disabled declaration must omit backup bucket, endpoint, `bindingRef`, and `fingerprint` fields. The endpoint remains conditional on use of a non-default S3-compatible endpoint. Disabled backup storage is not considered for external-binding uniqueness, while all other applicable external integrations retain their normal checks.
- When a conditionally shareable field is intentionally shared, every participating manifest must mark the same value explicitly with `shared: true` plus the same short, non-empty `sharedRationale` string. Absence or mismatch means the binding is treated as environment-unique by default; `shared: true` cannot override an exclusive class.
- Restore validation tooling may derive shell environment variables such as `EXPECTED_PG_DUMP_BUCKET`, `EXPECTED_ASSET_STORE_BUCKET`, `EXPECTED_ASSET_STORE_ENDPOINT`, `EXPECTED_SMTP_HOST`, and operator-binding fingerprints from this manifest rather than maintaining a second source of truth.
- When validating operator-only credentials, preflight should compare like-for-like against the expected binding form: compare `bindingRef` values when the manifest declares `bindingRef`, and compare fingerprints when the manifest declares `fingerprint`. Implementations should not invent a second canonical representation during validation.
- Preflight should validate player-facing internal state/trust inputs from the same manifest rather than treating them as implicit cluster-local defaults. Cluster-local naming alone is not sufficient proof of environment isolation, and identical cluster-local literals may be valid across separate environment boundaries when the underlying cluster, namespace boundary, and bound Secret/trust resources belong to the target environment.
- Deployment and recovery evidence must reference the same manifest path so auditors can answer “what binding did we expect?” from one record family.

Internal-binding comparison rule:

- For cluster-local internal bindings, preflight validates the declared environment-exclusive ownership class rather than treating a shared declaration as an escape hatch.
- Reusing names such as `postgres.firemud.svc.cluster.local`, `secret://firemud/postgres-credentials`, or `secret://firemud/jwt-signing-keys` is allowed only when those names resolve inside separate environment boundaries and the binding is not declared `shared: true`.
- Raw equality for conditionally shareable external targets such as object-store buckets/endpoints, SMTP targets, and webhook targets requires an explicit identical shared declaration and rationale in every participating manifest; credential principals and operator bindings remain exclusive.
- When a matrix-approved non-sensitive player-facing external target is intentionally shared across environment manifests, declare its bucket, endpoint, SMTP, or webhook value as an object with `value`, `shared: true`, and the same non-empty `sharedRationale` string in every manifest that shares it. Credential and operator identity `bindingRef` and `fingerprint` values remain environment-exclusive and must never use the shared-object form. Matching non-sensitive target values without the explicit shared declaration must fail preflight.

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
- `checkResults[]` with `policyId`, its catalogue-resolved `category`, boolean `required`, `status`, and `message`; a missing, unknown, or mismatched category is invalid
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

Illustrative abbreviated `ci-static` report shape (non-consumable; omitted policy results and fields are represented by the placeholders below):

```json
{
  "environment": "staging",
  "deploymentRef": {
    "overlayCommitSha": "abc123def456"
  },
  "deploymentEventId": "<illustrative-deployment-event-uuid>",
  "trafficOpenEvent": null,
  "checkResults": [
    {
      "policyId": "PREFLIGHT-DIGEST-001",
      "category": "apply-blocking",
      "required": true,
      "status": "pass",
      "message": "all images are digest pinned"
    }
  ],
  "expectedBindingsRef": "design/operations/environments/staging/expected-bindings.yaml",
  "expectedBindingsDigest": "<illustrative-expected-bindings-digest>",
  "policyCatalogVersion": "<illustrative-policy-catalogue-version>",
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
- Rejected pre-event waiver-input audit artifacts are stored separately under:
  - `design/operations/deployments/<environment>/preflight/waiver-input-rejections/<attemptId>.json`
- Each `<attemptId>` is a fresh canonical UUID for one rejected attempt. These artifacts are non-authorizing and are separate from deployment-event reports and waivers; they do not have or substitute for a `deploymentEventId`.
- `deployment-ref` is:
  - `<overlayCommitSha>` for overlay-driven staging/production deployments, or
  - a normalized manifest/chart reference token for hobby/self-hosted deployments.
- Naming rule: `<deployment-ref>` and similar artifact tokens must use lowercase ASCII plus digits and `-`. `deploymentEventId` uses canonical UUID text and changes for every retry or re-apply so immutable event evidence is never overwritten.
- Retention requirement: keep preflight reports, waivers, and rejected waiver-input audit artifacts for at least as long as release/rollback audit history is retained.
- Waiver records must include: authorized approver identity, incident/change ticket, rationale, exact policy IDs and phase, target environment, deployment event identity, issue timestamp, and event-bounded expiration.
- Current implementation status: `preflight.py` rejects `FIREMUD_PREFLIGHT_WAIVER` before generating a deployment event or report, and rejects consumed reports containing `waiverPath`, until a trusted authority can issue and atomically consume each waiver exactly once. Event-ID equality alone is not replay protection. Pre-event waiver rejection is the narrow exception to the event-bound failure-report rule because no `deploymentEventId` exists yet. The target caller records a separate non-authorizing machine-readable `waiver-input-rejected` audit artifact containing a fresh attempt ID, timestamp, target environment and deployment reference when parseable, waiver artifact identity and digest without its sensitive contents, authenticated actor or workload identity when available, and a stable rejection reason. Failure to create that audit record remains fail-closed and grants no deployment authority. The current executable does not yet emit this separate artifact, so waiver input remains unavailable rather than falling back to unaudited or authorizing behavior.

## Failure Handling

- Any failed applicable `apply-blocking` check blocks deployment; any failed applicable `non-waivable-promotion-traffic-open` check blocks promotion or traffic opening.
- Waivers are a target-state break-glass mechanism only and fail closed when malformed, expired, unauthorized, prohibited for the policy category, or mismatched to the event, phase, environment, or policy ID. They are not currently executable.
- Waivers expire after the specific deployment event and must not silently carry forward. A retry or re-apply uses a new `deploymentEventId`, so a prior waiver fails binding validation even when the same `deploymentRef` is reused.
- `PREFLIGHT-BOOTSTRAP-001` remains `apply-blocking` for ordinary bootstrap failures and those failures may use an eligible event-bound waiver. Its custody-selection failure is the narrow exception: for player-facing apply, promotion, or traffic-open, it is non-waivable and requires exactly one authenticated accepted custody proof with the exact `proofId`, `custodyMode`, and `contractVersion` tuple for `PREFLIGHT-JWT-INTERIM-001`/`INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK` or `PREFLIGHT-JWT-002`/`TARGET_NON_EXPORTABLE_SIGNER` (`contractVersion: 1`). A missing, unknown, unsupported, not-yet-implemented, or mismatched selected proof, including fallback to a legacy diagnostic result, cannot be waived.
- `PREFLIGHT-BACKUP-001`, `PREFLIGHT-BACKUP-002`, and `PREFLIGHT-BACKUP-003` are non-waivable readiness gates. A waiver may authorize an isolated drill or salvage action, but not the player-facing promotion/open transition those gates protect.

## Related Documentation

- `design/architecture/system-architecture-deployment-runbook.md`
- `design/architecture/system-architecture-cicd.md`
- `design/architecture/system-architecture-promotion-attestation.md`
- [Validation and Runtime Proof](../developer-workflows/validation-and-runtime-proof.md)
- [Active P0 ADR: phased environment-bound preflight and expected bindings](./decisions/adr-0152-phased-environment-bound-deployment-preflight-and-expected-bindings.md)
