# ADR 0153: Measured Online-Backup RPO and Future PITR Trigger

## Status

Accepted

## Implementation Status

This decision is not implemented. Measured verified-point freshness, automated isolated restore proof, and the evidence-based WAL/PITR trigger remain gaps.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `OPS-03`
- Decision date: 2026-07-20
- Decision key: `OPS-03`
- Primary capability: `PO-3.4` Backup, restore, disaster recovery, and self-hosted recovery
- Affected capabilities: `SF-2.1`, `SF-2.2`, `SF-2.3`, `GR-1.4`, `PO-3.1`, `PO-4.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of recoverable data loss, logical-backup scalability, Redis recovery, hobby flexibility, promotion safety, and single-operator production overhead

## Context

[ADR 0015](./adr-0015-online-backup-and-environment-wide-cold-start-recovery.md) establishes online transactionally consistent PostgreSQL backups and environment-wide quarantined cold-start recovery. It deliberately leaves the accepted backup recovery-point objective to a separate decision.

A Cron schedule is not itself a recovery-point objective. A job can start late, overlap another run, fail, upload an incomplete artifact, or produce bytes that have never been proved restorable. Describing a job as running every 15 minutes therefore does not prove that FireMUD can recover to within 15 minutes of the incident.

FireMUD also needs a backup baseline that remains practical for hobby and small self-hosted deployments. Requiring PostgreSQL physical backup and WAL-archive infrastructure everywhere would improve recovery-point granularity but would impose substantial setup and operational cost before database size, dump duration, or runtime impact demonstrates that complexity is needed.

## Decision

### Preserve the Online Cold-Start Recovery Boundary

Routine backups use an online transactionally consistent PostgreSQL snapshot while normal writes continue. They do not pause gameplay or use region maintenance controls as backup evidence.

Any player-facing PostgreSQL rewind remains an environment-wide, quarantined `cold_start_restore` under ADR 0015. Coordination Redis is empty at the restored boundary and is rebuilt only from restored durable authority and new post-restore activity. A surviving or separately captured Redis timeline is never restored or merged beside rewound PostgreSQL.

FireMUD does not initially support tenant-selective or region-selective recovery from the shared whole-database artifact.

### Initial Hosted RPO Objective Is 15 Minutes

The initial hosted-production recovery-point objective is 15 minutes. The objective is measured from the newest verified restorable PostgreSQL point, not from the configured Cron schedule or the existence of an object-store key.

A restore point counts only after the supported verification path establishes that its artifact is complete, readable, bound to the correct environment and database lineage, and usable by the supported recovery tooling. The restore-readability proof includes a successful bounded isolated restore of that exact artifact digest using the supported recovery tooling; an artifact-shaped record, digest-looking value, or tool invocation without a successful restore does not satisfy it. This is a per-artifact readability and restore-tool check, distinct from the separate full environment-wide `cold_start_restore` isolated recovery drill. Backup cadence and verification timing are implementation parameters that must be set tightly enough for the measured newest verified restorable point to remain within the objective.

Every production promotion, including a rollback-compatible promotion, and every production first-live or reopen event must consume a current environment-bound reference and complete digest for the newest verified restorable point and prove that its observed age is within the accepted 15-minute bound. A compact compatible-release result may reference that live freshness proof instead of repeating the full selected-release recovery record; an object-store upload or key-existence check alone never satisfies the objective. This freshness gate is separate from the full isolated drill gate, so a compatible release does not require a new drill solely because it is a new release.

When hosted production has no verified restorable point within 15 minutes:

- monitoring raises the configured backup incident and operators investigate or repair the backup path;
- production promotion is blocked until the objective is restored and evidenced; and
- an otherwise healthy running game is not automatically shut down merely because backup freshness has degraded.

This deliberately preserves current availability while making the increased recoverable-loss exposure visible. It does not allow stale evidence to authorize a new production promotion.

### Logical Backups Remain the Initial Baseline

Online logical PostgreSQL backups remain the initial hosted, hobby, and small-deployment baseline. Their actual completion, verification, storage, and restore-test results determine whether they satisfy the selected environment objective.

Hobby and self-hosted operators may configure a slower cadence than hosted production. Operator-facing backup status and recovery evidence must show the effective recovery-point objective and the age of the newest verified restorable point so a slower policy is an explicit tradeoff rather than a hidden weakening.

### Adopt WAL/PITR When the Measured Baseline Stops Working

Hosted production adopts PostgreSQL physical backup plus WAL archiving and point-in-time recovery when online logical backups cannot reliably maintain the 15-minute measured objective or when their duration, overlap risk, storage behavior, or runtime load materially harms the live platform.

PITR changes the PostgreSQL artifact and point-selection mechanism. It does not change the environment-wide quarantine, empty-Redis cold start, session and epoch invalidation, durable convergence, external reconciliation, hardening, or controlled-reopen requirements.

### Automate Evidence for Single-Operator Production

The hosted baseline must automate scheduled backup execution, artifact verification, isolated restore testing, freshness measurement, and evidence generation. Routine proof must not depend on a single operator manually constructing timestamps or evidence records. The operator remains responsible for responding to incidents and authorizing real recovery and reopen actions, while automation supplies the repeatable backup and restore evidence used by those decisions.

## Consequences

- Hosted production has an explicit initial recoverable-loss objective rather than an implied promise derived from a timer.
- A nominal 15-minute job schedule is insufficient unless verification shows a restorable point no older than 15 minutes; implementations may need a shorter schedule to preserve that margin.
- Backup staleness blocks new promotion and raises an incident without converting a healthy gameplay environment into an automatic outage.
- Logical backups preserve a low-complexity, portable baseline for hobby and small deployments.
- Hobby operators may accept more potential data loss, but the effective policy and current verified restore-point age remain visible.
- WAL/PITR is deferred until measured logical-backup behavior justifies its infrastructure and operational cost.
- PITR can improve PostgreSQL recovery granularity but cannot remove the semantic recovery work required for Redis, durable workflows, external effects, credentials, and sessions.
- Environment-wide recovery means one tenant cannot initially be rewound independently from the shared PostgreSQL artifact.

## Alternatives Considered

### Treat the 15-Minute Cron Schedule as the RPO

This confuses attempted backup frequency with a verified recovery capability. Delayed, failed, unreadable, or unverified artifacts would silently violate the intended loss boundary while the schedule still appeared correct. It is rejected.

### Require WAL/PITR Immediately for Every Environment

This provides finer PostgreSQL recovery points but adds physical-backup lifecycle, WAL archival, retention, monitoring, and restore-operability requirements to hobby and small deployments before evidence shows they need them. It is deferred behind the measured adoption trigger.

### Pause Gameplay Before Every Backup

A gameplay pause does not quiesce every service schema or external workflow in the shared PostgreSQL database and would introduce frequent player-visible disruption and stuck-pause risk. Online snapshots plus restore-time convergence remain canonical.

### Restore PostgreSQL and Coordination Redis Together

A Redis snapshot may represent a different timeline from the selected PostgreSQL restore point. Merging them could resurrect stale sessions, leases, locks, commands, timers, or work ownership. Redis remains transient coordination state and is not restored beside rewound PostgreSQL.

### Provide Tenant-Selective Restore From the Shared Artifact

The logical artifact covers the complete shared database and cross-service workflows are not currently partitioned into a proven tenant-local recovery boundary. Tenant-selective restore would require a separate artifact, authority, reconciliation, and proof design and is not initially supported.

## Implementation and Proof Obligations

Select and report the required checks and evidence under the shared [Validation and Runtime Proof](../../developer-workflows/validation-and-runtime-proof.md) workflow; record execution results in PR/CI evidence or the owning implementation tracker rather than in this decision record.

- Measure backup freshness using the snapshot time of the newest artifact that has passed the supported restore-readability and lineage checks.
- Require every production promotion and production first-live/reopen event to consume a current environment-bound reference and complete digest for that verified point, with observed age within 15 minutes; object existence alone is insufficient.
- Configure hosted backup and verification frequency with enough margin to maintain the 15-minute objective under normal duration and scheduling variance.
- Alert and open the configured operational incident when hosted freshness exceeds the objective, and make production promotion fail closed on stale evidence without automatically closing healthy player traffic.
- Show each hobby or self-hosted environment's configured policy, effective recovery-point objective, and newest verified restorable-point age.
- Schedule automated isolated restore tests and generate immutable evidence covering the artifact, lineage, verification, restore tooling, and result.
- Retain the complete ADR 0015 environment-wide `cold_start_restore` proof boundary for every player-facing rewind, whether the PostgreSQL source is a logical dump or a future PITR-selected point.
- Introduce WAL/PITR through a follow-up implementation decision when logical dumps fail the reliability or runtime-impact trigger.

## Reversibility and Revisit Triggers

Revisit the 15-minute objective if measured player impact, business requirements, compliance obligations, storage cost, or recovery operations justify a different hosted loss boundary. Revisit the logical-backup mechanism when it cannot reliably maintain that objective or materially harms runtime operation. Revisit tenant-selective recovery only when a separately proved tenant-local artifact and reconciliation boundary exists.

## Required Documentation Alignment

- [`design/architecture/system-architecture-backup-recovery.md`](../system-architecture-backup-recovery.md)
- [`design/architecture/system-architecture-backup-recovery-evidence-and-compliance.md`](../system-architecture-backup-recovery-evidence-and-compliance.md)
- [`design/architecture/system-architecture-deployment-runbook.md`](../system-architecture-deployment-runbook.md)
- [`design/architecture/system-architecture-cicd.md`](../system-architecture-cicd.md)
- [`design/architecture/system-architecture-logging-monitoring.md`](../system-architecture-logging-monitoring.md)
- [`design/architecture/infrastructure/schedule.md`](../infrastructure/schedule.md)
