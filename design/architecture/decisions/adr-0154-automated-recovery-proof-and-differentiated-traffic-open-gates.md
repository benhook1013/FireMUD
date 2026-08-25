# ADR 0154: Automated Recovery Proof and Differentiated Traffic-Open Gates

## Status

Accepted

## Implementation Status

This decision is not implemented. Strict hosted recovery gates, automated drills and evidence, actual recovery state handling, and differentiated hobby proof remain gaps.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `RECOVERY-01`
- Decision date: 2026-07-20
- Decision key: `RECOVERY-01`
- Primary capability: `PO-3.4` Backup, restore, disaster recovery, and self-hosted recovery
- Affected capabilities: `PO-3.1`, `PO-4.4`, `GR-1.4`, `SF-2.1`, `SF-2.2`, `SF-2.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of hosted and hobby traffic-open gates, recovery automation, destructive authority, unattended operation, proof cadence, and reopen failure handling

## Context

[ADR 0015](./adr-0015-online-backup-and-environment-wide-cold-start-recovery.md) establishes online PostgreSQL backups and environment-wide quarantined `cold_start_restore` as the initial player-facing database-rewind contract. It requires real recovery proof instead of treating an evidence-shaped record as proof that recovery works.

That safety boundary does not require the same proof and traffic-open policy for hosted production, ordinary restart, ordinary compatible release, and hobby first-live. Nor should it require one operator to perform every restore step or manually construct routine evidence. FireMUD needs automation that can execute and diagnose recovery safely even when a one-person operator is absent, while retaining human authority over deliberate data loss unless a future automatic-disaster-recovery policy explicitly pre-authorizes it.

The controlled-reopen boundary also cannot be implemented as a literal atomic transaction across PostgreSQL, Kubernetes traffic controls, workloads, and retained evidence. It requires a crash-recoverable state machine whose retry-safe workflow transitions and durable states make partial progress safe and diagnosable. The destructive PostgreSQL restore itself is not assumed to be idempotent.

## Decision

### Hosted Production Uses Strict Evidence Gates

Hosted production must complete a full production-equivalent `cold_start_restore` proof before first-live player traffic opens. That proof exercises the actual quarantine, empty-Redis recovery, authority fencing, durable and external reconciliation, hardening, validation, smoke, and controlled-reopen path against a representative isolated boundary.

After an actual PostgreSQL rewind, hosted player traffic never reopens until the actual recovery has completed the strict ADR 0015 quarantine and proof boundary. A recent isolated drill cannot replace evidence from the actual recovery event.

A `roll-forward-only` production candidate requires an exact candidate drill using the candidate recovery tooling, service versions, migration path, configuration, and bindings. An ordinary compatible release may reuse a recent successful baseline through a compact compatibility result. It does not repeat the full drill when the recovery contract remains compatible.

### Target: Scheduled Drills and Evidence Are Automated

The target state automatically schedules isolated restore drills and refreshes the retained recovery evidence when they succeed. The automation performs the restore rehearsal, convergence and validation path, records its exact inputs and results, and exposes actionable failure diagnostics. A human operator does not manually manufacture timestamps or proof records for routine drill success. This automation and evidence path is not implemented by this decision's current baseline.

Compatibility evaluation for an ordinary production release consumes that recent baseline and produces the compact compatibility result. A changed or incompatible recovery boundary requires a new drill rather than allowing stale proof to pass.

### Restart and Rewind Are Different Operations

Routine service, pod, node, and environment restarts that do not rewind PostgreSQL remain automatic availability operations. They do not require destructive recovery-point authorization and do not enter the database-rewind workflow merely because processes restarted.

An actual rewind workflow automatically:

1. establishes and durably records restore quarantine;
2. prevents old owners, sessions, leases, workers, and side-effecting paths from acting on the restored timeline;
3. verifies the selected recovery candidate and its environment, artifact, tool, schema, and binding lineage;
4. restores PostgreSQL with empty Coordination Redis using one durable restore-attempt identity bound to the exact target-boundary fingerprint and selected recovery-point ID;
5. runs durable convergence, external-effect reconciliation, session invalidation, epoch and fence reset, hardening, validation, and smoke checks;
6. records complete results and actionable diagnostics; and
7. prepares the environment for the separately authorized controlled-reopen transition.

The recovery workflow is resumable, and its observations, evidence writes, and non-destructive phase transitions are retry-safe. A destructive PostgreSQL restore is not treated as idempotent: the controller persists the restore attempt ID, exact target-boundary fingerprint, and recovery-point ID before applying it. If the restore returns an ambiguous outcome, the controller keeps quarantine closed and validates the durable attempt and target state against those bindings before any retry. It must reconcile a proven result or retain an actionable ambiguous state; it must not blindly repeat the destructive restore. A crash, retry, or temporarily absent operator therefore resumes from durable recovery state without losing the reason for a failure or silently opening traffic.

### Data-Loss Acceptance Is Operator-Authorized by Default

Selecting and applying a recovery point can discard database changes after that point. The destructive recovery-point choice and its resulting data-loss acceptance require explicit operator authorization by default. Automation may discover candidates, calculate and display the effective loss window, validate them, and prepare a recovery plan, but it does not silently choose and apply a rewind merely because a health check failed.

A future explicitly configured automatic-disaster-recovery mode may pre-authorize a maximum acceptable data-loss window. Such a mode must also define strict old-authority fencing and candidate-validation requirements before automation can select and apply a point without waiting for an operator. It is not enabled by this decision and requires its own configured policy and proof.

### Reopen Is a Crash-Recoverable State Machine

Controlled reopen is not a claim of literal cross-system atomicity. Recovery uses durable, monotonic states that distinguish at least quarantined work, proof-complete and ready-to-reopen state, traffic-release progress, and finalized reopen state.

The reopen transition is retry-safe and crash recoverable. It fences stale executors, verifies that the same proved recovery remains current, releases traffic through controlled steps, and finalizes evidence without permitting an ambiguous partial transition to authorize gameplay. Retrying the transition converges on the same result, while a failed or uncertain transition remains closed and reports the exact incomplete step.

### Hobby Uses a Differentiated First-Live Policy

The supported hobby and self-hosted default includes an automated local restore rehearsal that exercises the environment-wide cold-start path and records whether the deployment currently has verified recovery status.

A hobby operator may explicitly open first-live traffic without a fresh successful automated local restore rehearsal, but only under a precisely recorded `recovery-unverified` waiver. The waiver omits only rehearsal-only restore, credential-disposition, and rehearsal cutover evidence; it still requires the immutable backup-compliance tuple `{backupComplianceRef, backupComplianceRecordVersion, backupComplianceRecordDigest}`, an exact `recoveryContractFingerprint`, `PREFLIGHT-BACKUP-003=pass`, and the durable actual-recovery controller at `phase=ready_to_reopen` for the pre-release gate. The controller must still complete its normal continuation and controlled release, reaching `phase=finalized` with `status=SUCCEEDED` and observed quarantine-release postconditions before exposure; those post-release states are validated in the retained projection rather than used as pre-release waiver input. The consumed controller/evidence must exact-match the current event and player-facing target tuple required by the canonical [Hobby Traffic-Open Evidence](../system-architecture-backup-recovery-evidence-and-compliance.md#hobby-traffic-open-evidence) schema: `operationId`, `eventType`, `deploymentEventId`, `preflightReportPath`, `backupComplianceRef`, `backupComplianceRecordVersion`, `backupComplianceRecordDigest`, `actualRecoveryRecordRef`, `playerFacingTargetBoundary`, and `trafficExposure`. Here `actualRecoveryRecordRef` identifies the current first-live controller operation; it is not a prior restore rehearsal or successful-recovery projection and must never be fabricated. The record must not include or claim `baselineRecoveryRecordRef` or `lastSuccessfulRecoveryRecordRef`, which are required only for verified posture. The tooling must present a clear warning and make no verified-recovery promise for that deployment. This exception applies only to hobby first-live; it does not redefine a failed or absent drill as success and cannot authorize a post-restore reopen or reopen after a rewind.

After a hobby PostgreSQL restore or rewind, player traffic never bypasses quarantine, reconciliation, validation, and controlled reopen. The first-live exception cannot authorize an unproved post-restore reopen.

Periodic hobby restore drills are advisory for deployments that do not claim verified recovery. A hobby deployment that retains or advertises verified recovery status must keep its required drill evidence current; stale evidence removes that status rather than silently preserving the promise.

## Consequences

- Hosted first-live and every actual post-rewind reopen retain strict recovery proof.
- Full drills are not added to every compatible ordinary release; compact compatibility evidence reuses a recent automated baseline.
- `roll-forward-only` candidates carry higher release cost because their exact recovery path must be exercised.
- Routine restart automation remains fast and independent from destructive database recovery.
- Recovery automation can continue, stop safely, and explain its state without requiring one operator to supervise every step.
- Human authorization remains the default boundary for knowingly discarding newer database state.
- A future automatic DR policy can trade waiting time for a pre-authorized bounded loss window, but only with explicit configuration, candidate checks, and strict fencing.
- Hobby deployments remain easy to start while accurately distinguishing recovery-unverified operation from a verified recovery promise.
- No hobby or hosted deployment may use the easier first-live policy to bypass quarantine after a real restore.
- Reopen safety depends on durable idempotent state transitions rather than an impossible cross-system atomic transaction.

## Alternatives Considered

### Require a Full Restore Drill for Every Release

This maximizes repeated proof but adds substantial time and infrastructure cost even when a compatible release has not changed backup or recovery behavior. Recent automated baseline proof plus a compact compatibility result is sufficient for ordinary compatible releases; exact drills remain mandatory for `roll-forward-only` candidates.

### Rely on a Manual Recovery Runbook and Manually Written Evidence

This concentrates too much failure risk and routine overhead in the operator, especially for a one-person production environment. Recovery execution, retries, diagnostics, scheduled drills, and evidence generation are automated while destructive choice and reopen authority remain explicit.

### Automatically Rewind Whenever Availability Checks Fail

An availability failure does not prove that database rewind is necessary, and automatic point selection may discard valid player state. Routine restart remains automatic, but destructive rewind remains operator-authorized unless a future bounded automatic-DR policy explicitly pre-authorizes it.

### Apply the Hosted First-Live Gate to Every Hobby Deployment

This would make the strongest recovery posture easy to describe but would raise the minimum self-hosting burden substantially. Hobby first-live may explicitly proceed as recovery-unverified, while the supported default still supplies an automated rehearsal and post-restore recovery remains strict.

### Permit Hobby Reopen With a Warning After Restore

Once PostgreSQL has been rewound, stale Redis state, sessions, authority, workflows, and external effects can conflict with the restored timeline. A warning cannot make that state safe. Post-restore quarantine and controlled reopen therefore have no hobby bypass.

### Treat Reopen as One Atomic Cross-System Operation

PostgreSQL evidence, workload state, fencing, and traffic routing do not share one transactional authority. Claiming literal atomicity would hide crash and retry behavior. Durable monotonic state, idempotent transitions, strict fencing, and fail-closed resumption provide the required safety boundary.

## Implementation and Proof Obligations

- Implement automated scheduled isolated restore drills and immutable recovery-evidence generation for hosted production.
- Implement the compact compatibility evaluator and require an exact candidate drill for every `roll-forward-only` production candidate.
- Keep routine non-rewind restart automation separate from the destructive restore controller.
- Implement a resumable actual-rewind workflow covering quarantine, old-authority fencing, candidate verification, restore, empty Redis, convergence, hardening, validation, smoke, and reopen preparation. Bind each destructive PostgreSQL restore attempt to its durable attempt ID, target-boundary fingerprint, and recovery-point ID, and validate an ambiguous target before retrying.
- Require operator authorization for the selected recovery point and displayed data-loss window unless a separately configured and proved automatic-DR policy pre-authorizes that window.
- Implement controlled reopen as a durable crash-recoverable state machine with fail-closed uncertainty and actionable step-level diagnostics.
- Provide the supported automated local restore rehearsal for hobby deployments and visibly record `recovery-unverified` first-live operation when explicitly chosen.
- Remove verified hobby recovery status when required periodic evidence becomes stale, without shutting down a healthy non-verified hobby game.
- Prove that neither hosted nor hobby post-restore traffic can bypass the actual-recovery quarantine and controlled-reopen path.

## Reversibility and Revisit Triggers

Revisit hosted proof cadence or compatibility reuse when measured drill cost, recovery-contract change frequency, or incidents show that the current balance is too weak or too expensive. Revisit hobby first-live policy if the warning and status distinction causes unsafe expectations. Consider automatic DR only when an environment owner can explicitly define its maximum acceptable loss, fencing behavior, candidate-selection rules, and failure proof.

## Required Documentation Alignment

- [`design/architecture/system-architecture-backup-recovery.md`](../system-architecture-backup-recovery.md)
- [`design/architecture/system-architecture-backup-recovery-evidence-and-compliance.md`](../system-architecture-backup-recovery-evidence-and-compliance.md)
- [`design/architecture/system-architecture-post-restore-hardening.md`](../system-architecture-post-restore-hardening.md)
- [`design/architecture/system-architecture-deploy-preflight-policy.md`](../system-architecture-deploy-preflight-policy.md)
- [`design/architecture/system-architecture-deployment-runbook.md`](../system-architecture-deployment-runbook.md)
- [`design/architecture/system-architecture-cicd.md`](../system-architecture-cicd.md)
- [`design/architecture/infrastructure/schedule.md`](../infrastructure/schedule.md)
