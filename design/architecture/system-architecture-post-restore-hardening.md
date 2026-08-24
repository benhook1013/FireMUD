# FireMUD Post-Restore Hardening

This document defines the mandatory hardening and validation steps that must complete after a restore and before a player-facing environment may reopen to traffic.

The owner contract is [ADR 0155: automated event-classified post-restore trust reset](./decisions/adr-0155-automated-event-classified-post-restore-trust-reset.md), with event-scoped evidence from [ADR 0151](./decisions/adr-0151-event-scoped-automated-tier-a-credential-compliance.md) and recovery/reopen ownership from [ADR 0154](./decisions/adr-0154-automated-recovery-proof-and-differentiated-traffic-open-gates.md). This document owns hardening procedure and evidence handoff; it does not replace the durable recovery controller as release authority. Use [Validation and Runtime Proof](../developer-workflows/validation-and-runtime-proof.md) to select the required checks and proof for changes to this workflow.

## Implementation Status

This document is target-state architecture, not evidence that the full restore workflow is live.

- **Live behavior:** the repository has restore-safe guidance, preflight validation, and evidence contracts, but does not yet provide a proven end-to-end player-facing restore-hardening controller or automation path.
- **Target-only behavior:** the durable recovery controller, delegated hardening Jobs, `continueRecovery(... expectedPhase=ready_to_reopen ...)`, public `resume(... expectedPhase=awaiting_resume ...)`, and the internal guarded release through `finalized` remain required target-state behavior. They must not be inferred from the documentation or from partial tooling.

## Restore Quarantine

Restore isolation is mandatory. Before restoring manifests or PostgreSQL into a player-facing environment, operators must place the environment in quarantine so snapshot-era trust material cannot serve real traffic during recovery.

Required quarantine actions:

- scale Gateway and TCP Proxy workloads to zero, or detach their external `LoadBalancer` / Ingress exposure
- prevent DNS or traffic-manager cutover to the restored environment
- disable or scale down normal background processors, automation workers, schedulers, notification senders, webhooks, asset publishing workers, and any other workload that can create external or player-visible side effects
- keep Game Session tick executors and command intake stopped or under an enforced restore-safe startup gate until the coordination recovery mode is proven
- keep quarantine in place until post-restore hardening, external credential validation, required sanitization evidence, smoke checks, and the durable recovery-controller `ready_to_reopen` gate all succeed

It is not sufficient to rely on “operators will not send traffic yet” as a procedural control. The restored environment must be technically unable to accept player-facing traffic until the hardening sequence is complete.

Restore quarantine is a full restore-safe mode, not only an ingress block. Restored workloads must not be able to process queued work, emit outbound communications, publish assets, run gameplay automation, or create new Coordination Redis state with snapshot-era credentials before the hardening and coordination gates complete. Maintenance Jobs required for recovery may run, but they must use narrowly scoped service accounts and submit their results to the durable recovery controller.

## Post-Restore Secret Hardening

Post-restore hardening is performed by a dedicated resumable Kubernetes Job or equivalent playbook such as `post-restore-secret-hardening` that coordinates multiple flows. Its coordination and observations are retry-safe, while destructive credential mutations use durable operation identity and ambiguous-outcome validation rather than assuming idempotency. It classifies the recovery event before acting and records one disposition for every applicable credential class: `rotated`, `reissued`, `rebound`, or `verified_not_restored`. It is one recovery control group, not one all-powerful workload: JWT rotation is a delegated Account-owned sub-operation, while database credential rotation, certificate reissuance, external-credential rebinding, evidence collection, and the recovery-controller gate remain separate control flows.

Every PostgreSQL rewind still requires quarantine, old-authority fencing, empty Coordination Redis, session invalidation, epoch/fence advancement, durable and external reconciliation, binding validation, smoke, and controlled reopen. Credential treatment is event-specific:

- A same-boundary PostgreSQL-only rewind may use `verified_not_restored` when automation proves the credential authority remained current outside the restored artifact and is still correctly bound.
- A fresh cluster or namespace, restored Secret material, or an unprovable trust lineage requires fresh provisioning, rebinding, rotation, or reissuance as appropriate.
- Known or suspected compromise uses the complete compromise-response hard cutover for every affected authority.

No credential may be retained silently; its disposition and evidence are part of the canonical recovery record.

The workload and authority relationship is explicit:

- `post-restore-secret-hardening` is the recovery orchestrator. Its separately provisioned service account may invoke the declared recovery Jobs, read their bounded evidence, update the durable recovery-controller workflow, and write only the evidence needed for that workflow. It must not read JWT private material, write `jwt-signing-keys` or `jwt-jwks`, patch Account deployments, or impersonate Account authority.
- `jwt-rotation` is a separate Job/CronJob workload using the dedicated `sa-jwt-rotation` service account. The same artifact and phased protocol may be invoked as the JWT control group of post-restore hardening, but it remains a delegated workload with its own narrow RBAC and audit identity; it is not an inline privilege of the parent Job and is not a second independent restore cutover.
- `sa-jwt-rotation` only observes the persisted Account-owned restore-cutover request/status, including Account's phase/generation/publication status, runs validator-convergence probes/readbacks, and records validator and Game Session convergence evidence; it does not reconcile or mutate the Account request/state machine, patch or restart validators, or mutate Game Session. It has no write access to `jwt-jwks`, no read or update access to `jwt-signing-keys`, and no `patch` authority on the Account Service Deployment. The JWT contract defines the Account authority, custody, and publication semantics; this workflow records the resulting owner evidence. Game Session APIs separately invalidate gameplay bindings and gameplay session state from the durable cutoff event.
- Both workload identities and the Account request correlation must appear in immutable audit/evidence records keyed to the stable `restoreCutoverOperationId` and request digest. In target state only, the recovery controller records the parent orchestration and the JWT evidence; `jwt-rotation-status` records JWT automation evidence, not a competing rotation state machine. Any missing RBAC/audit/evidence boundary keeps quarantine closed.

The JWT restore-hardening actor and RBAC boundary is the existing `jwt-rotation` Job/CronJob and `sa-jwt-rotation` service-account contract described in the [JWT signing-key rotation contract](./system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative):

- For each persisted Account-owned restore-cutover request identified by `restoreCutoverOperationId`, `sa-jwt-rotation` only observes the request/status, runs validator-convergence probes/readbacks, and records validator and Game Session convergence evidence under narrow read/probe RBAC. It does not reconcile or mutate the Account request/state machine, patch or restart validators, or mutate Game Session, and it may not create or request a transition. It has no write access to `jwt-jwks`, no read or update access to `jwt-signing-keys`, and no `patch` authority on the Account Service Deployment.
- The JWT contract defines restore-cutover and custody authority. Locally, the interim materialization controller is an execution component only: every private-slot `generate`, `materialize`, `resourceVersion`-CAS, and `prune` action requires a persisted Account-authorized operation. Account remains authoritative for desired key-ring state, slot choice, and operation authorization; the controller cannot choose slots or mutate desired key-ring state independently, and its authenticated result is execution evidence rather than authority. In target non-exportable-signer mode, no application private-key Secret, mount, or materialization-controller path exists; recovery observes Account's signer and public-JWKS evidence instead. Game Session owns gameplay-binding/session invalidation and returns separate convergence evidence.
- Validator refresh or restart uses the validator's inventory-declared refresh/deployment mechanism under operator or owning-controller authority; `sa-jwt-rotation` only probes/readbacks the resulting convergence under narrow read/probe RBAC and records the evidence. It must not request that mutation or patch the Account Service Deployment. Validators receive public JWKS only.
- DB rotation automation may read/update only the PostgreSQL credential Secrets and optionally restart the Deployments or StatefulSets that consume them.
- Certificate reissuance automation may read/update only the specific certificate resources or Secrets required for workload, bridge, and operator leaf identities.

JWT post-restore rotation follows the Account-owned cutover and custody authority in [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative). The recovery orchestration must submit the one persisted `StartRestoreCutover` request, or idempotently replay that already persisted request using the same operation identity and request digest, before any hardening observer reads or records cutover evidence. After submit/replay, the orchestration only observes Account-owned phase, generation, publication, and result transitions and records handoff evidence. Account alone advances and resolves that operation; `sa-jwt-rotation` and validators remain read/probe-only for this workflow. No participant may create a second request, advance or resolve Account state, or perform Account/JWT authority mutations. Game Session separately performs gameplay-binding/session invalidation and returns its evidence. The JWT contract remains canonical for JWT cleanup, custody, and private-material semantics; Account owns the persisted restore-cutover operation state.

Issued-token projection evidence uses one canonical `issued-token-projection/v1` digest per environment-wide restore-cutover operation and issuer-authority generation. The unhashed evidence object contains exactly `schemaVersion`, `restoreCutoverOperationId`, `environmentId`, `issuerAuthorityGeneration`, `scope`, `entryCount`, and `entries`; `scope` is exactly `environment-wide`. Each entry contains exactly the non-secret tuple `tokenHash`, `recordSchemaVersion`, `accountId`, `tokenProfile`, `audience`, `jti`, `iat`, `exp`, `tokenGeneration`, and `state`. `tokenHash` is SHA-256 over the exact ASCII bytes of the complete compact JWT serialization as issued (`base64url(header).base64url(payload).base64url(signature)`), without trimming, decoding, re-encoding, Unicode normalization, or claim reserialization, encoded as 64 lowercase hexadecimal characters without a prefix. The raw JWT may exist only transiently in the Account-owned issuance or validation path that computes this value; it is never serialized into projection evidence. Producers sort entries by `tokenHash`, reject duplicates, encode identifiers and enum strings without case folding, encode integers as JSON integers without leading zeroes, normalize strings to Unicode NFC, and serialize the exact object as UTF-8 RFC 8785 JSON Canonicalization Scheme bytes. The projection digest is SHA-256 over those bytes and is encoded as `sha256:` followed by 64 lowercase hexadecimal characters. Producers and validators use the same field set, normalization, entry order, and canonical bytes; a missing or extra field, duplicate or reordered entry, non-canonical encoding, count mismatch, unsupported schema, or digest mismatch fails closed before the evidence can satisfy smoke, continuation, or reopen. Raw JWTs and private material are never fields in the projection.

The JWT contract defines the JWT cleanup, custody, and private-material semantics; Account owns the persisted `StartRestoreCutover` operation state and its idempotent replay, advancement, and resolution. This hardening flow requires durable submission or replay before observation and evidence recording.

### 1. JWT signing key and JWKS rotation

- For `verified_not_restored` on a same-boundary PostgreSQL-only rewind, prove that the current Account signing authority and validator JWKS remained outside the restored artifact, exact-match the target binding, current issuer generation, and keyset integrity, and have every validator accept that unchanged current set. Account authority/delegation invalidation and Game Session gameplay-binding/session invalidation remain mandatory universal rewind controls. This branch does not require a replacement `kid`, rejection of the still-current `kid`, or an issuer-generation advance solely as credential-rotation evidence.
- For ordinary `rotated`, `reissued`, or applicable `rebound` dispositions, run the replacement path: remove restored keys from active trust material, publish the replacement keyset, and prove every validator rejects every restored or old `kid` and accepts the replacement `kid`. The aggregate evidence marks this operation `compromiseClassified=false`; it must not carry compromise-only identities.
- Known or suspected compromise always uses this full hard cutover for the affected authority and marks the aggregate JWT evidence `compromiseClassified=true`. That explicit classification requires the exact compromised/candidate `kid` and public-key-fingerprint pairings in the aggregate and validator-convergence evidence; compromise proof remains fail-closed.
- keep JWT issuance and JWT-protected admission/control-plane traffic quarantined until the applicable disposition, universal invalidation, and validator-convergence evidence is complete
- observe and record Account's completed authority/delegation invalidation and Game Session's separate gameplay-binding/session invalidation from the single Account-owned restore-cutover cutoff under the recovery controller's stable `restoreCutoverOperationId`; Account and Game Session APIs perform those effects, lost-response observation reuses that identity, and `cold_start_restore` must not issue a second cutover or become an invalidation writer
- the owning validator deployment/refresh mechanism refreshes or restarts every validator in the authoritative, complete validator inventory, and `sa-jwt-rotation` probes/readbacks each validator for the disposition-specific acceptance/rejection result above; missing, unknown, unreachable, or non-converged validators fail closed
- verify Account Service health, immutable restore-cutover evidence, and validator convergence before traffic reopen

`validatorConvergenceEvidence` must contain one immutable record per inventoried validator. Each record identifies the validator workload and environment, its applicable JWT profiles/audiences, the observed refresh or restart when applicable (or a bounded readback of the current set for `verified_not_restored`), the JWKS generation and `kid` set observed, the disposition-specific unchanged-set acceptance or replacement/old-`kid` rejection and replacement acceptance, rejection of inapplicable audiences, the reserved-probe authorization/side-effect denial result, observation time, and an immutable evidence reference. A missing per-validator record, an unexpected acceptance or rejection, or a probe that authorizes an operation fails the recovery gate. The aggregate `jwtHardening` evidence records the disposition, rotation Job reference when applicable, stable `restoreCutoverOperationId`, immutable restore-cutover request digest, and either current keyset/binding/generation-integrity evidence for `verified_not_restored` or replacement key IDs, issuer-generation/rejection evidence for a replacement path; an issuer-generation advance is not required solely to evidence the `verified_not_restored` branch. No record contains private key material.

For ordinary replacement hardening, aggregate `jwtHardening.replacementEvidence` must contain one record per old or restored key that the replacement path removes. Each record contains exactly `oldKid`, `candidateKid`, `oldKidRejected`, `candidateKidAccepted`, and `validatorEvidenceRef`; the IDs are distinct and non-empty, both booleans are `true`, each reference exactly matches the corresponding aggregate `validatorConvergenceEvidence`, the candidate is present in `resultingKeyIds`, and every recorded old ID is absent. The set must cover every old/restored key in the operation; a missing record or an unlisted old key fails closed. This per-key proof applies to `rotated`, `reissued`, and applicable `rebound` dispositions only. It is prohibited for compromise-classified and `verified_not_restored` branches. For a compromise-classified hard cutover, both `validatorConvergenceEvidence` and aggregate `jwtHardening` evidence must instead carry the exact `compromisedKid`, `candidateKid`, `compromisedPublicKeyFingerprint`, and `candidatePublicKeyFingerprint` pairings from the Account-owned operation; fingerprints are lowercase `sha256:<64 hex>`. Each validator must verify the exact `kid`-to-public-key-fingerprint mapping: every token using the compromised `kid` or compromised fingerprint is rejected, and the distinct candidate `kid` is accepted only with the exact candidate fingerprint. A swapped, duplicate, missing, or mismatched `kid`/fingerprint pair, acceptance of any compromised identity, or rejection of the exact candidate pair fails closed; `verified_not_restored` records the unchanged current key set instead and must not fabricate a replacement pair.

### 2. Database credential rotation

- rotate database credentials when the credential, its owning Secret, or the database authority was restored or cannot be proved current; restored or unprovable password material is unsafe trust material and cannot be marked `rebound`, while a same-boundary, proved-current case may be `verified_not_restored`
- run `db-credential-rotation` using a least-privilege service account and persist its durable operation identity and current phase before any password mutation
- before `ALTER ROLE ... PASSWORD ...`, prove a fresh target-bound PostgreSQL admin authority. When the restored cluster, `postgres-admin-credentials` Secret, admin role, or authority lineage is restored or cannot be proved current, the rotation flow must obtain a fresh/reissued admin credential from the trusted target-cluster bootstrap/control-plane authority; it must not reuse snapshot-era admin material or an app credential as an implicit admin
- the trusted admin authority is a dedicated target-bound rotation controller or proxy. Each durable rotation operation carries an explicit allowlist of non-superuser application-role identities, and the constrained job may invoke only the controller/proxy's operation-bound password-rotation action for a role on that list; it cannot execute arbitrary `ALTER ROLE`, hold generic `CREATEROLE`, or widen the allowlist. The privileged implementation may hold only the PostgreSQL role capability required to perform that validated action and has no `SUPERUSER`, `CREATEDB`, `REPLICATION`, `BYPASSRLS`, or application-data access. The authority evidence records the target cluster/database identity, explicit target-role allowlist, controller/proxy and privileged-role identities and attributes, issuer/binding identity, authority generation, credential Secret reference, and immutable issuance evidence without recording a password
- for a mutation disposition, persist `adminAuthorityProved` and its exact operation-bound authority evidence before reading the current app DB credential lineage or attempting `ALTER ROLE`; if the target cluster/database, role attributes, Secret binding, authority generation, or issuance result is missing, conflicting, stale, unreachable, or ambiguous, fail closed before mutation
- for a candidate `verified_not_restored` disposition, persist a distinct read-only target-bound lineage proof that the current application credential material, Secret, database role, and consumer bindings all remained outside the restored artifact and still match the target. Transition directly to terminal `verified_not_restored` without obtaining mutation authority, issuing `ALTER ROLE`, publishing a replacement Secret, or restarting consumers
- for a mutation disposition, read the current app DB credential lineage from `postgres-credentials`, then use only the proved target-bound controller/proxy action to rotate the exact allowlisted role under that same operation identity
- persist the outcome of the database mutation before publishing the replacement application Secret, and persist the Secret outcome before restarting or reloading consumers
- on a crash or ambiguous result, inspect the database role, Secret, and every consuming workload before continuing or rolling back the staged cutover; leave quarantine closed until the database, Secret, and consumer state reach one known consistent state
- trigger rollout restarts or reloads for every Deployment and StatefulSet in the authoritative consumer inventory, and prove every consumer is healthy and using the expected new credential lineage before recording `rotated` or `rebound`
- fail closed with the durable phase and actionable diagnostic when any step remains unresolved; an incomplete or partially converged cutover cannot qualify as a disposition

The durable rotation phases are monotonic, with explicit branch paths. A `verified_not_restored` operation follows `operation_initialized` -> `disposition_classified` -> `lineage_proof_pending` -> `lineage_proved` -> terminal `verified_not_restored`; this branch performs only the distinct read-only target-bound lineage proof. A mutation operation follows `operation_initialized` -> `disposition_classified` -> `admin_authority_pending` -> `admin_authority_proved` -> `lineage_proof_pending` -> `lineage_proved` -> `database_password_mutation` -> `database_mutation_recorded` -> `application_secret_published` -> `consumers_restarted` -> `consumer_lineage_verified` -> terminal mutation disposition. A mutation operation must persist `adminAuthorityProved` before entering lineage proof or reading current application-credential lineage. `ALTER ROLE` is forbidden outside the operation-bound controller/proxy action and in every phase before `admin_authority_proved`; a lost response or any ambiguous authority/mutation outcome remains in quarantine for readback and reconciliation under the same operation identity. Reissued admin authority is a control-plane trust input, not evidence that the restored database or restored Secret was trustworthy.

### 3. Certificate reissuance

- reissue workload, bridge, and operator leaf certificates when their Secrets or issuing boundary were restored, replaced, or cannot be proved current
- allow `verified_not_restored` for a same-boundary PostgreSQL-only rewind only after proving the current certificate resources and issuer binding remained outside the restored artifact
- default restore hardening rotates leaf certificates only; CA/root rotation is a separate compromise-response workflow
- require peer/validator convergence evidence before traffic reopen

### 4. External credential validation

Restoring a namespace also restores third-party credentials stored as Secrets. Post-restore hardening must rotate or re-issue those restored credentials; a `rebound` disposition is allowed only when the provider trust boundary is safely re-established without reusing unsafe restored material and the binding and material lineage are proved before any external traffic is allowed.

Canonical external validation matrix, limited to integrations enabled for the target environment:

- `backup-storage`
- `asset-storage`
- `outbound-comms`
- `operator-credentials`

Each class must produce:

- one machine-checkable validation result
- one explicit environment-isolation assertion
- one immutable evidence reference in the durable recovery-controller state

The durable recovery-controller state must also include:

- `validationMethod`
- `validatedAt`
- `validatedBy`
- `observedValue` or fingerprint sufficient to prove what was checked without disclosing the secret

`observedValue` must be redacted by design. Never store secret values, passwords, private keys, bearer tokens, or full connection strings containing credentials.

Minimum execution contract:

- backup-storage validation must confirm bucket and endpoint binding plus non-production isolation constraints
- asset-storage validation must confirm publishing target isolation
- outbound-comms validation must confirm SMTP or webhook targets and enforce “staging cannot send production messages”
- operator-credentials validation must confirm the expected operator certificate or credential binding

Before enabling external traffic, run:

- `dev-tools/restores/validate-external-credentials.sh <hobby-self-hosted|staging|production>`

Expected inputs include environment-specific values such as:

- `EXPECTED_PG_DUMP_BUCKET`
- `EXPECTED_ASSET_STORE_BUCKET`
- `EXPECTED_ASSET_STORE_ENDPOINT`
- `EXPECTED_SMTP_HOST`
- `EXTERNAL_CREDENTIAL_EVIDENCE_REF`
- optionally `PRODUCTION_PG_DUMP_BUCKET` and `PRODUCTION_ASSET_STORE_BUCKET` when validating staging isolation

For staging restores sourced from production-origin snapshots, `SANITIZATION_EVIDENCE_REF` must point at an immutable `<recovery-ref>.sanitization.json` pre-release artifact whose operation, deployment event, and backup-artifact digest match the durable controller. The finalized recovery projection references that same artifact after the controller reaches `finalized`; it is not used circularly as the pre-release input.

### 5. Secret-compliance evidence refresh

Post-restore hardening records the event-classified disposition for each Tier A credential. Before release, every provisioning, rotation, or rebinding operation that establishes or changes trust lineage triggers one operation-bound `secretComplianceRefresh` handoff with content-addressed supporting evidence. The checked-in `design/operations/secret-compliance/<environment>.yaml` projection is refreshed only after controller finalization. `verified_not_restored` is the sole disposition allowed to reuse existing compliance evidence, and only when current binding and material lineage prove that the trust material remained outside the restored artifact; it does not invent a new freshness timestamp. Any checked-in recovery projection is likewise exported after controller finalization.

The refresh must include the credential classes affected by restore hardening:

- `jwt-signing-keys-jwks`
- `postgres-application-credentials`
- `backup-storage`
- `asset-storage` when external asset storage is enabled
- `operator-credentials`

Each `secretComplianceRefresh.freshness` entry is keyed exactly by its refreshed `credentialClasses` and carries `lineage`, `field`, `value`, `previousField`, and `previousValue`. A new lineage/first issuance uses `lineage=new`, either `rotated` or `reissued`, `field=lastProvisionedAt`, and no previous field/value. An existing-lineage `rotated` or `reissued` replacement uses `lineage=existing`, `field=lastRotationAt`, and an advanced timestamp over the recorded previous value. A binding-only `rebound` or `verified_not_restored` refresh uses `lineage=existing` and preserves the exact previous field/value; its separate operation identity and evidence do not reset credential age. Every selected and previous freshness timestamp must be no later than the recovery record's `finalizedAt`. Missing class entries, an incompatible disposition/lineage/field combination, a future freshness timestamp, or a changed preserved timestamp fails closed. The referenced evidence payload must include an immutable artifact identifier, exact target-environment binding evidence, and material-lineage evidence, and must be linked from the durable recovery-controller state under `secretComplianceRefresh`; the checked-in projection mirrors this after finalization.

The secret-compliance publisher is the environment/secrets owner’s authenticated provisioning, rotation, or rebinding controller, using a dedicated publisher identity authorized only for the target environment’s compliance path and its content-addressed evidence objects. A Git author, branch, commit timestamp, file path, or successful local process is not a trusted publisher and cannot authorize a recovery or release. Before release, the hardening workflow hands the durable recovery controller one complete immutable `secretComplianceRefresh` handoff containing the stable restore operation identity, credential event kind and operation identity, target environment, exact credential-class keys, per-class freshness lineage/field/value and previous field/value, evidence references and digests, and canonical parent-record digest. A `rebound` or `verified_not_restored` handoff also binds the retained material-lineage identity and exact preserved freshness pair; a rebound additionally binds the exact new target binding. The owner validates the handoff and publishes the evidence artifact before the handoff is accepted; the handoff is committed by one compare-and-set/atomic operation, and a retry may reuse it only when every operation, environment, key, and digest field exact-matches. A partial artifact, missing digest, conflicting retry, or ambiguous publisher result keeps quarantine closed.

The durable recovery controller consumes that operation-bound handoff and its dereferenced evidence as pre-release input. The publisher may update the versioned Git record at `design/operations/secret-compliance/<environment>.yaml` only as a post-finalization projection, together with the immutable supporting evidence reference; that projection is not read back as authority for the same release and cannot be used circularly to authorize the controller transition that caused it. After finalization, the publisher exports one immutable Git projection for the finalized operation and may retry only with the exact same operation/digest tuple; it must not mutate a prior event’s projection or refresh its timestamp without a new owner operation.

The target pre-release validator checks the owner handoff and dereferenced content-addressed evidence against the live recovery operation, target environment, credential keys, operation identity, and canonical digest. The existing Git-backed secret-compliance validator is a post-publication retention check used on later promotion, first-live/reopen, and disaster-recovery checks: it validates the retained projection and immutable evidence age/reference shape, while richer owner-handoff and digest binding remain target-state validation. It is never current-recovery authority. A stale, missing, or tampered Git projection therefore cannot make the current recovery pass, and a fresh Git commit cannot substitute for the owner’s pre-release handoff.

## Post-Restore Coordination Recovery Gate

The restore mode, reopen lifecycle, and fixed erasure-replay boundary are canonical in [Backup & Disaster Recovery](./system-architecture-backup-recovery.md#restore-mode-selection), [Recovery Controller Continuation](./system-architecture-backup-recovery.md#recovery-controller-continuation), and [Artifact Erasure Replay Boundary](./system-architecture-backup-recovery.md#artifact-erasure-replay-boundary). Post-restore hardening retains the local gate that must be evidenced before that owner-defined boundary can pass:

- Coordination Redis must be empty for the restored environment boundary, with fresh target-environment credentials and rejection of snapshot-era credentials; Cache Redis is not evidence for this gate.
- The hardening workflow observes one Account-owned restore-cutover operation and records separate evidence for Account authority/delegation invalidation and Game Session gameplay-binding/session invalidation. For that `restoreCutoverOperationId`, `sa-jwt-rotation` only probes/readbacks validator and Game Session convergence under narrow read/probe RBAC and records evidence; the owning validator deployment/refresh mechanism and Game Session APIs perform those mutations, while the recovery orchestrator observes and records the resulting evidence.
- JWT, issuer/account/tenant/membership projection, validator-convergence, replay-domain, participant, external-effect, confidentiality, and smoke evidence must be complete and non-secret before quarantine can be lifted. Missing, stale, malformed, unknown, unreachable, or unsafe evidence keeps quarantine closed.
- Evidence fields retain the owner-defined artifact and erasure high-water references, tool digests, recovery-contract fingerprint, participant inventory, convergence results, and controlled-reopen references as defined in [Backup Recovery Evidence and Compliance](./system-architecture-backup-recovery-evidence-and-compliance.md#canonical-recovery-record); checked-in projections are emitted only after owner-defined finalization.

### Deferred `scoped_reset_restore` (quarantined only)

Player-facing `scoped_reset_restore` with surviving Coordination Redis is deferred. Quarantined experiments cannot lift quarantine or satisfy player-facing recovery readiness until a separate accepted design and proof package authorizes the mode.

Recovery automation must fail closed when it cannot prove the owner-defined `cold_start_restore` contract. A successful PostgreSQL restore or empty Redis check alone is insufficient.

## Reopen Sequence

The restore controller's reopen sequence is canonical in [Backup & Disaster Recovery](./system-architecture-backup-recovery.md#recovery-controller-continuation). Runbooks must still treat `post-restore-secret-hardening` as mandatory and retain these local hardening consequences:

1. Enter restore-safe quarantine and hold normal workloads, ingress, background processors, outbound integrations, automation, and Game Session tick executors behind the restore-safe gate.
2. Submit the one persisted Account-owned `StartRestoreCutover` request, or idempotently replay an already-persisted request using the same operation identity and request digest, before any observer reads or records cutover evidence. After that submit/replay, run the Account-owned read-only JWT cutover observer, database credential rotation or rebind, certificate reissuance or binding proof, enabled external-credential validation, and secret-compliance refresh in the target environment. Require one proved event-classified disposition for every applicable credential; the JWT Job observes the Account operation and does not perform Account or Game Session invalidation.
3. Require complete validator, participant, external-effect, confidentiality, sanitization, and restore-safe smoke evidence. Any missing, stale, partial, ambiguous, or unsafe result keeps quarantine closed.
4. Submit the hardening evidence, using the evidence fields and handoff defined in [Backup Recovery Evidence and Compliance](./system-architecture-backup-recovery-evidence-and-compliance.md#canonical-recovery-record), to the durable recovery controller. This document's handoff ends at the owner-defined continuation/reopen boundary; hardening never invokes release directly. Permit traffic only after the owning lifecycle has finalized through idempotent, crash-recoverable monotonic steps; uncertainty remains closed and retries converge. Checked-in recovery and traffic-open projections are post-finalization evidence, not release authority.

Hobby/self-hosted operators must run equivalent one-shot hardening automation with distinct authenticated principals equivalent to `post-restore-secret-hardening` and `sa-jwt-rotation`, preserving the same least-privilege control groups, immutable audit identities/correlation, private-material restrictions, and prohibited mutation/access boundaries above. One-shot execution must not collapse those principals or grant Account, JWKS, validator, or Game Session mutation authority when the Kubernetes Job template is unavailable.

## Planned DB Credential Rotation

In addition to post-restore hardening, operators may periodically rotate application database credentials as routine security hygiene. Planned DB credential rotation reuses the same `db-credential-rotation` job and Secrets described above but runs outside of a disaster recovery event.

Recommended flow:

1. Ensure recent PostgreSQL backups exist and are healthy.
2. Schedule a low-traffic window and notify stakeholders.
3. Run the rotation job so it generates a new password, updates the Secret, and restarts workloads.
4. Monitor application health checks, logs, and database connections for errors.
5. If issues appear, reconcile the database role, Secret, and every consumer under the same durable operation before continuing. Reverting to a previous known-good password still requires a fresh target-bound rotation authority and an explicit database mutation followed by Secret and consumer convergence; never restore an old Secret value alone or attempt rollback while the mutation outcome is ambiguous.
