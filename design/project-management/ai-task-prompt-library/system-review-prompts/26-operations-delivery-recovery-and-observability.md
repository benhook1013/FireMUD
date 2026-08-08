# Operations, Delivery, Recovery, And Observability Review

Use this prompt to review whether FireMUD's checked-in deployment and operations design is coherent, executable, diagnosable, and recoverable. It reviews repository contracts and artifacts, not the live state of an environment.

Apply the [shared review contract](./00-shared-review-contract.md).

## Starting Sources

- `design/architecture/infrastructure/README.md`
- the environment, secrets, and schedule documents under `design/architecture/infrastructure/`
- `design/architecture/system-architecture-cicd.md`
- `design/architecture/system-architecture-deployment-runbook.md`
- `design/architecture/system-architecture-backup-recovery.md` and its linked evidence contracts
- `design/architecture/system-architecture-logging-monitoring.md`
- `design/architecture/system-architecture-tracing.md`
- `design/operations/README.md`
- deployment, environment, release, recovery, traffic-open, and secret-compliance material under `design/operations/`
- relevant manifests, charts, infrastructure code, scripts, dashboards, alerts, trackers, and focused proof

## Review

Check:

- each documented environment class, isolation boundary, expected binding, secret and certificate source, and supported traffic plane;
- build artifact lineage, supply-chain controls, deployment, migration, readiness, promotion, rollback, and configuration validation;
- backup coverage, freshness, retention, restore, quarantine, post-restore hardening, traffic reopening, and evidence ownership;
- failure detection, logs, metrics, traces, SLOs, alerts, dashboards, runbook linkage, cardinality, correlation, and player-impact signals;
- operator access, audit evidence, maintenance, scaling, incident handling, and degraded operation;
- agreement among design, manifests, scripts, configuration, runbooks, and the platform-operations tracker; and
- which claims require controller-, cluster-, provider-, deployment-, or event-bound evidence unavailable to static review.

Do not weaken a player-facing requirement because a development or hobby environment cannot currently prove it. Record the applicable environment scope and evidence limitation.

## Output

Provide:

1. an environment and operational-contract coverage table;
2. deployment, secret, readiness, observability, runbook, backup, restore, and rollback findings;
3. mismatches among checked-in design and operational artifacts;
4. live evidence that remains unavailable and therefore cannot be claimed; and
5. the review state required by the shared contract.
