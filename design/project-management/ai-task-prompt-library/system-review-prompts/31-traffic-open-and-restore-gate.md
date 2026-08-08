# Traffic-Open And Restore Gate

Use this prompt for a specific deployment, first traffic opening, post-incident reopening, or restore event. It consumes event-bound evidence and does not replace human operational authorization.

Apply the [shared review contract](./00-shared-review-contract.md).

## Required Invocation

The caller provides:

- the exact environment and event type;
- the deployed revision and immutable artifact identity;
- the applicable operations evidence location;
- the expected bindings and environment policy;
- the exact command or live-check allowlist, if the reviewer is expected to collect rather than only inspect evidence; and
- the human owner of the traffic-opening or restore decision.

Without a defined environment, event, evidence source, and human decision owner, stop with `blocked`.

## Starting Sources

- `design/architecture/infrastructure/deployment-environments.md`
- `design/architecture/infrastructure/environment-and-secrets-catalog.md`
- `design/architecture/system-architecture-deployment-runbook.md`
- `design/architecture/system-architecture-backup-recovery.md` and its evidence contracts
- `design/architecture/system-architecture-post-restore-hardening.md`
- `design/operations/README.md`
- the matching environment, deployment, recovery, traffic-open, backup-readiness, release-manifest, and secret-compliance material under `design/operations/`
- supplied controller, cluster, certificate, secret, backup, restore, observability, provider, and player-path evidence

## Gate

For the declared environment and event, check:

- immutable artifact and configuration lineage;
- expected bindings, isolation, secrets, certificates, keys, routes, migrations, and readiness;
- backup freshness and scope, restore completion, quarantine, post-restore hardening, credential rebinding, and a new backup baseline where applicable;
- alerts, dashboards, logs, traces, external monitoring, and operator access;
- canonical player-path and control-plane probes;
- rollback or re-quarantine ability;
- unresolved environment-specific security, privacy, recovery, or data-integrity findings; and
- evidence timestamps, scope, authority, and applicability to this exact event.

Checked-in examples, projections, manifests, or evidence from another environment cannot substitute for required event-bound proof.

## Output

Provide:

1. the assessed environment, revision, and event;
2. an event-evidence coverage table;
3. missing, stale, mismatched, or failing evidence;
4. a recommendation: `ready-to-open`, `do-not-open`, or `incomplete`; and
5. the review state required by the shared contract.

The recommendation does not authorize deployment, traffic opening, restore, rollback, or any external state change.
