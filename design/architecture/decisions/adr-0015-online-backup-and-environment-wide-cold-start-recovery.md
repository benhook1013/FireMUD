# ADR 0015: Online Backup and Environment-Wide Cold-Start Recovery

## Status

Accepted

## Implementation Status

The decision is accepted; implementation and proof remain partial. The scheduled job performs an online PostgreSQL dump without pausing gameplay, but restore quarantine, empty-Redis and epoch/fence reset, participant convergence, external-effect reconciliation, controlled reopen, and the complete backup-under-write recovery proof are not implemented or proved. Acceptance records the target decision, not completion; the obligations below define the remaining proof.

## Decision Record

- Decision date: 2026-07-18
- Primary capability: `PO-3.4` Backup, restore, disaster recovery, and self-hosted recovery
- Affected capabilities: `GR-1.3`, `PO-1.3`, `PO-3.1`, `PO-4.4`, `SF-2.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `OPS-04`

## Context

FireMUD stores the authoritative state of all tenants and service-owned schemas in one PostgreSQL database while Coordination Redis holds transient sessions, queues, timers, locks, and lease state. Restoring PostgreSQL therefore rewinds the whole environment while any surviving Redis or external systems may contain newer state.

The previous recovery target required automated backups to pause gameplay at canonical `{tenantId, regionId}` scope before every logical dump. That did not match the artifact boundary: `pg_dump` captures the shared database rather than one gameplay region, and pausing one region does not quiesce Account, Social, authoring, outbox, external-side-effect, or other service transactions. Repeating a gameplay pause every 15 minutes would also add a recurring player-visible and stuck-pause failure mode.

The current implementation is not player-facing restore-ready under either model. The scheduled PostgreSQL CronJob performs an online dump without coordinating gameplay. The separate pause helper uses a process-local global flag, lacks a bounded wait and guaranteed cleanup, and does not use the target maintenance authority. Region scope is present in the proto but rejected by Game Session. Normal command intake is not fully blocked by pause. The restore helper restores Velero resources and immediately restarts workloads instead of enforcing quarantine, and preflight can accept evidence-shaped timestamps and scope strings without validating a canonical recovery record or real recovery run.

`verify-backups.sh` currently checks that Velero backups exist and that optional pg-dump object storage is reachable. It does not prove immutable artifact lineage, artifact readability, restore-tool compatibility, or player-facing readiness.

## Decision

FireMUD uses online transactionally consistent PostgreSQL backups and an environment-wide, quarantined cold-start recovery model as its initial player-facing restore contract. Routine backups do not pause gameplay. Player-facing readiness depends on proving that a backup taken under active writes can be restored with empty Coordination Redis and that all durable and external state converges safely before traffic reopens.

### Backup Boundary

- A backup artifact covers the complete shared PostgreSQL database and therefore every tenant and service schema in the environment.
- Routine backup creation uses one transactionally consistent PostgreSQL snapshot while normal writes continue. It does not invoke Game Session pause/resume and does not claim tenant- or region-local coverage.
- Every artifact records its environment binding, database identity, snapshot time, schema/migration lineage, deployed service digests, backup-tool digest, object-storage identity, and immutable `artifactErasureHighWater`.
- Readiness verification proves that the artifact is readable, complete for the declared database, bound to the expected environment, and usable by the supported restore tooling. The existence/reachability result from `verify-backups.sh` is only an input to that evidence; it is not the readiness proof.
- Region pause/status remains a valid maintenance, reset, migration, and future scoped-recovery control. It is not a prerequisite for routine online backup and is not evidence that a whole-database artifact is safe.

### Backup Confidentiality Invariant (Normative)

Every backup artifact, transfer, temporary restore copy, and sensitive recovery evidence must remain confidential to its environment boundary:

- backup and restore transfers use authenticated encryption in transit, and dumps, object-store copies, temporary restore media, and retained recovery artifacts use encryption at rest with environment-scoped key material;
- access is least-privilege and environment-scoped for backup, restore, recovery, and key-management identities, with object, key, and administrative access audited;
- retention follows the environment policy, and expired, aborted, and temporary copies are securely deleted with deletion evidence; and
- production-origin data used in a non-production drill remains in an isolated quarantine boundary, is sanitized and validated before any workload or traffic exposure, and is deleted from drill storage after the permitted evidence-retention period. Production credentials and integrations are never reused in that drill.

Checked-in recovery projections contain only redacted metadata and immutable references; they never contain backup contents, credentials, private keys, or bearer tokens.

### Initial Player-Facing Restore Mode

The only initially supported player-facing database-rewind mode is environment-wide `cold_start_restore`:

- Restore PostgreSQL into an enforced quarantined environment boundary.
- Start with an empty Coordination Redis for that environment. Surviving Redis must be replaced or cleared; it must not be merged with the older database.
- Treat the restore as affecting all tenants. Tenant-local or region-local rewind is not supported by a whole-database artifact.
- Invalidate gameplay and Account sessions by default and require fresh authentication and gameplay admission after reopen.
- Advance or recreate every gameplay region epoch and fence so no pre-restore owner, lease, lock, command, or worker can act on the restored timeline.
- Capture an immutable `restoreHighWater` from the authoritative erasure ledger, require it to be greater than or equal to `artifactErasureHighWater`, then replay the complete interval `(artifactErasureHighWater, restoreHighWater]` into the restored environment without gaps before reopen. Initial catch-up may extend the interval while erasures continue, but `ready_to_reopen` requires a bounded authoritative final cutover that serializes sequence assignment, captures and replays the final interval, and atomically hands the resulting cursor to normal online erasure processing. An expired or ambiguous cutover remains fail-closed in `collecting` and must be retried.
- Rebuild Coordination Redis only from restored durable authority plus post-restore activity after the offline convergence gate passes.

Player-facing `scoped_reset_restore` with surviving Coordination Redis is deferred. It may become supported only after a separate decision and proof package establishes complete region ownership, scope inventory, stale-state rejection, session policy, and end-to-end reset/reconciliation behavior. Quarantined experiments with that mode do not count as readiness.

### Restore-Safe Quarantine

Quarantine is a technical execution state, not an operator convention:

- Gateway, TCP Proxy, normal Game Session workers, tick executors, automation, schedulers, outbound processors, asset publication, and other side-effecting workloads cannot accept or create normal work.
- Restored manifests or helper scripts must not automatically restart normal workloads.
- Only narrowly authorized recovery, validation, and hardening jobs may run before the recovery controller opens each later phase.
- First-live and reopen each require one explicit operator-authorized `continueRecovery(operationId, expectedPhase, evidenceRef)` call against the durable actual-recovery controller in `ready_to_reopen`. The finalized drill projections referenced by `restoreRecoveryRecordRef` and `baselineRecoveryRecordRef` remain mandatory preflight evidence before promotion or traffic opening, but they do not authorize the live boundary. The actual-recovery controller's durable state and linked immutable evidence are the sole pre-release authority; checked-in actual-recovery or traffic-open JSON is not required for, or consulted to authorize, that same release. The controller idempotently reconciles `ready_to_reopen -> releasing -> finalized`: it applies the quarantine release, observes that the release is active, advances to `finalized`, and only then permits player traffic. A failed or ambiguous apply remains fail-closed. After `finalized`, the workflow exports checked-in actual-recovery and traffic-open evidence as immutable projections including the later release timestamp; repository evidence is not part of the release transaction.

### Recovery Continuation Contract

The public recovery-control surface has one continuation verb: `continueRecovery(operationId, expectedPhase, evidenceRef)`. It is idempotent and phase-checked: the durable controller loads `operationId`, compares the current phase with `expectedPhase`, validates the immutable evidence identified by `evidenceRef`, and advances or returns the already-applied result. The internal durable `pause/lock` phase protects the quarantined operation; `pause`, `resume`, `lock`, and `release-lock` are not standalone public recovery verbs.

### Offline Convergence and External Reconciliation

Before normal startup, recovery must classify and converge every durable workflow family that can straddle the snapshot boundary, including:

- gameplay commands, tick batches and effects, remote follow-ups, and execution ledgers;
- sagas, outboxes, retries, timers, and automation dispatches;
- object-store publication and immutable release references;
- external side effects such as communications, payments, webhooks, and provider-owned operations; and
- account/data-erasure events and their authoritative sequence;
- restored sessions, credentials, certificates, bindings, epochs, and fences.

Each declared and enabled family records a deterministic safe disposition such as replayed/converged, reconciled against external authority, terminalized, invalidated, or durably fenced and disabled with its backlog retained. Unknown, unsafe, missing, or unproved outcomes keep the environment quarantined. Recovery cannot infer safety merely because PostgreSQL restored successfully, but it also need not execute every long-lived retry before reopen when the owning participant proves a safe fenced disposition.

### Proof and Release Gates

Player-facing restore readiness requires a production-equivalent drill that:

1. Takes the actual backup artifact while representative writes and cross-service workflows are active.
2. Restores the PostgreSQL artifact into an isolated environment with empty Coordination Redis and normal workloads held closed.
3. Runs the offline convergence, session invalidation, epoch/fence reset, JWT and credential hardening, external-binding validation, and secret-compliance refresh paths.
4. Starts workloads under quarantine and proves representative tenant, gameplay-region, command, external-effect, and login invariants.
5. Establishes one durable recovery-controller state linked to immutable backup, restore-tool, recovery-tool, service-digest, schema-lineage, erasure high-water, confidentiality, and smoke evidence.
6. Replays the complete erasure interval through the immutable `restoreHighWater`, completes the bounded final erasure cutover and online-consumer handoff, and proves both are gap-free before the controller may reach `ready_to_reopen`.
7. Proves the backup confidentiality invariant, including encrypted transport/storage, environment-scoped least-privilege access and audit, retention/deletion evidence, and quarantine, sanitization, and deletion evidence for any production-origin non-production drill.
8. Calls `continueRecovery(operationId, expectedPhase, evidenceRef)` for the gated continuation and reopens only through the same controller transition production uses, with the durable controller as pre-release authority.
9. Exports one canonical immutable recovery projection after the controller reaches `finalized`; the projection is not a prerequisite for that release.

Until this proof and its executable validators exist:

- production first-live is blocked;
- player-facing reopen after any PostgreSQL rewind is blocked;
- production `roll-forward-only` promotion is blocked;
- restore artifacts may be used for isolated drills or last-resort salvage, but they are not an approved rollback strategy or readiness evidence; and
- an evidence-shaped JSON record or operator waiver cannot declare the missing capability complete.

A rollback-compatible release to an already-running environment may still use known-good binary rollback when it does not change the backup or recovery boundary. This exception does not establish restore readiness or authorize first-live/reopen.

### Tiered Proof Cadence

Full restore drills are not required for every ordinary release:

- Run a full production-equivalent baseline drill at least every 30 days.
- A rollback-compatible release may reuse current drill evidence when an automated compatibility result proves the backup/restore tool digests, database and migration lineage, durable workflow/reconciliation contract, Coordination Redis recovery contract, secret/binding contract, and complete enabled recovery-participant inventory remain restore-compatible.
- A compatibility result records the baseline drill and exported recovery-projection references, baseline and candidate recovery-contract fingerprints, changed dimensions, evaluator/tool version, status, rationale, and whether a new drill is required. Ordinary promotion/deployment evidence carries this small result or its immutable reference rather than duplicating the full recovery projection.
- Changes invalidate prior evidence only when they alter restore compatibility, a recovery semantic, a participant contract or inventory, a trust/binding contract, or the backup/restore/hardening/reconciliation path. A restore-compatible additive migration or routine credential value rotation does not force a full drill when its contract and hardening workflow are unchanged.
- A production `roll-forward-only` release requires a release-candidate recovery drill. The drill takes its source artifact from the current production database lineage under representative writes, restores it with the candidate recovery tooling, applies the exact candidate service digests and migration path, and proves the candidate config/binding lineage through controlled reopen.
- First-live and reopen-after-restore require environment-specific evidence for the actual boundary being opened; cadence evidence alone is insufficient.

Cheap per-release checks compare fingerprints, digests, participant inventories, backup freshness, and compatibility declarations. They do not replace the full drill when an invalidating change occurs. The full backup-readiness artifact remains reserved for `roll-forward-only`, first-live, reopen, and invalidation-triggered drills.

## Consequences

- Routine backups no longer create recurring gameplay stalls or stuck-pause incidents.
- Recovery scope is honest: rewinding the shared database is an environment-wide event with full outage and forced reauthentication, not a one-region repair.
- The backup path is simpler, but the restore path requires strong durable idempotency, reconciliation, and external-effect proof across every participating service.
- Backup confidentiality is a release invariant rather than an operator convention; missing encryption, boundary-scoped access/audit, retention/deletion, or non-production sanitization evidence keeps recovery quarantined.
- FireMUD may lose up to the accepted backup RPO during a restore, and Redis-only transient work is not preserved. The separate backup-policy decision owns the actual cadence and RPO.
- Player-facing RTO includes offline convergence, hardening, smoke validation, and explicit reopen approval.
- Tiered evidence limits routine release overhead while automatically invalidating stale proof when the recovery contract changes.
- Region-scoped maintenance remains useful, but scoped recovery and tenant-local restore are not initial product promises.

## Alternatives Considered

### Canonical Region Pause Before Every Dump

Pause each gameplay region, wait for quiescence, take the database dump, then resume. This reduces gameplay work in flight but does not freeze other service workflows and does not match a whole-environment database artifact unless every active scope is inventoried and held behind one barrier. At a 15-minute cadence it also creates recurring player interruption and a new P0 stuck-pause failure mode. It remains useful for maintenance and future scoped recovery, not routine backup.

### Stop Every Writer During Backup

Enter a full maintenance window, stop all side-effecting workloads, dump PostgreSQL, and restart. This is the simplest snapshot to reason about and the strongest fallback if online crash-consistent recovery cannot be proved. Frequent use would create unacceptable downtime or require weakening the backup RPO. It remains a credible bounded interim for rare recovery points, not the default recurring workflow.

### PostgreSQL WAL/PITR

Use physical backups and write-ahead-log archiving for finer recovery points. This can improve RPO and avoid periodic gameplay pauses, but it adds operational infrastructure and still requires environment-wide Redis reset, durable workflow convergence, and external-effect reconciliation. The backup-policy decision may adopt PITR later without changing the cold-start recovery boundary in this record.

### Accept Live Dumps Without Recovery Proof

Treat any readable `pg_dump` as a valid recovery point. A database snapshot can be transactionally valid while durable workflows, Redis state, external side effects, and restored trust material are semantically inconsistent. This alternative creates false safety and is rejected.

## Implementation and Proof Obligations

- Make the scheduled backup job record immutable environment, schema, service-digest, tool-digest, and snapshot lineage and prove artifact restoration, not only existence.
- Enforce the backup confidentiality invariant and retain machine-checkable proof for encrypted transport/storage, environment-scoped least-privilege access and audit, retention/secure deletion, and production-origin non-production drill quarantine, sanitization, and deletion.
- Enforce restore quarantine before any restored normal workload can start; remove immediate rollout restart from the player-facing restore path.
- Implement the environment-wide cold-start controller, empty-Redis proof, epoch/fence reset, session invalidation, durable participant inventory, convergence steps, and controlled reopen transition.
- Persist `artifactErasureHighWater`, capture immutable `restoreHighWater`, and prove gap-free erasure replay before reopen.
- Implement the `continueRecovery(operationId, expectedPhase, evidenceRef)` controller contract; keep pause/lock as internal phase state rather than a public recovery command.
- Define external-effect reconciliation contracts for payments, communications, webhooks, and object-store publication.
- Make release evidence carry and validate the cheap recovery-compatibility result; make preflight use the durable recovery-controller state and its linked artifact digests, participant safe dispositions, recovery-contract fingerprints, confidentiality proof, and environment-wide scope as the pre-release authority rather than trusting timestamps or caller-supplied scope strings. Export checked-in recovery JSON only after controller finalization and treat it as an immutable projection for later audit and evidence reuse.
- Implement tiered evidence invalidation and require exact release-candidate drills for `roll-forward-only` promotion.
- Keep the capability implementation state partial until a real backup-under-write, restore, convergence, hardening, smoke, and reopen sequence is proved.

## Reversibility and Revisit Triggers

The environment-wide cold-start model is a conservative initial contract. FireMUD can later add PITR, scoped reset, or tenant-level logical recovery without invalidating cold-start recovery, provided each has a separate authority, artifact, reconciliation, and proof design.

Revisit the backup method if the accepted RPO cannot be met, logical dump size or duration becomes operationally expensive, or compliance requires physical/PITR recovery. Revisit scope if customers require tenant-local recovery, if full-environment RTO becomes unacceptable, or when complete region-partitioned ownership and reconciliation make scoped restore credible.

## Required Documentation Alignment

The following sources must remain aligned with this decision:

- `design/architecture/system-architecture-backup-recovery.md`
- `design/architecture/system-architecture-backup-recovery-evidence-and-compliance.md`
- `design/architecture/system-architecture-post-restore-hardening.md`
- `design/architecture/system-architecture-deploy-preflight-policy.md`
- `design/architecture/system-architecture-deployment-runbook.md`
- `design/architecture/system-architecture-cicd.md`
- `design/architecture/system-architecture-redis-operations.md`
- `design/architecture/system-architecture-logging-monitoring.md`
- `design/architecture/system-architecture-observability-incident-runbook.md`
- `design/architecture/system-architecture-testing.md`
- `design/architecture/system-architecture-tracing.md`
- `design/architecture/microservices/game-session-service/api-contracts.md`
