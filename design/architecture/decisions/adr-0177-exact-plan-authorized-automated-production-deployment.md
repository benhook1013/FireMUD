# ADR 0177: Exact-Plan-Authorized Automated Production Deployment

## Status

Accepted

## Implementation Status

This decision is not implemented. Exact production-plan authorization, protected unattended execution, bounded observation and restoration, short-lived credentials, and retained proof remain gaps.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-21
- Human review disposition: Revised
- Review source: `OPS-05`
- Decision date: 2026-07-21
- Decision key: `OPS-05`
- Primary capability: `PO-3.1` deployment and promotion authority
- Affected capabilities: `PO-3.2`, `PO-4.4`, `PO-1.3`, `SF-1.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of one-person production operation, authorization versus execution, deployment credentials, unattended observation, rollback authority, GitOps, and current implementation reality

## Context

FireMUD describes production apply as operator-controlled, but current runbooks interpret that as an operator manually running `kubectl`, observing rollout, executing smoke checks, transcribing evidence, and deciding rollback. ADR 0156 already expects bounded automated observation and compatibility-approved restoration. Manual supervision makes the sole operator's workstation, command accuracy, and continued availability part of the production deployment critical path.

The system does not need continuous unapproved deployment or a permanently privileged GitOps controller to remove that weakness. It needs to distinguish deliberate human authorization from reliable mechanical execution.

## Decision

PRs validate changes, `develop` supplies staging candidates, and reviewed `main` releases and release tags supply production candidates. Production remains operator-controlled: a human authorizes one immutable production deployment plan, after which protected automation may execute that exact plan without continued human presence.

Approval binds all of the following:

- target environment and current environment generation;
- exact production overlay commit and rendered-manifest digest;
- exact service image digest set;
- migration, configuration, mounted-resource, secret-contract, and external-binding versions;
- preflight, staging, promotion, recovery, and compliance evidence references;
- rollback classification;
- exact predeclared known-good rollback digest set and policy, when applicable;
- protected workflow identity and version that will execute the plan.

After approval, the executor re-resolves and hashes every input. A changed commit, manifest, digest, workflow, evidence record, environment generation, or live-state precondition invalidates approval and stops before mutation. Approval never means "deploy current main" or authorizes a mutable input.

The executor may run preflight, apply, bounded rollout progression, public-path smoke and SLO observation, live-state verification, and authoritative evidence recording unattended. It uses a concurrency lock and environment-generation compare-and-set so approved plans cannot race. Ambiguous results leave the deployment paused in a fail-closed state, alert the operator, and permit no continuation until the ambiguity is resolved.

Automatic restoration is pre-authorized only when the plan names the exact known-good digest set, the release is still `rollback-compatible`, current schema/config/secret/binding evidence remains compatible, and the failure fits the declared rollout policy. A `roll-forward-only` release, migration ambiguity, trust or binding drift, stale evidence, or unknown live state stops for explicit forward remediation. Automation does not improvise another target.

Production execution uses short-lived environment-specific credentials where the infrastructure supports them, obtained only after the protected approval gate. Credentials and RBAC are limited to the declared deployment and observation operations, unavailable to pull-request jobs, and unable to modify workflow or approval policy. Protected workflows and reusable actions are reviewed and immutable for the approved run.

Manual `kubectl` remains supported for environment bootstrap and audited break-glass recovery, not as the normal steady-state deployment mechanism. A permanently privileged GitOps controller remains deferred until multiple clusters, frequent drift, or a demonstrated continuous-reconciliation need justifies its operational surface.

## Consequences

- A one-person operator can deliberately authorize a release without remaining awake or connected throughout a long rollout.
- Mechanical preflight, observation, evidence, and compatible restoration become reproducible rather than workstation-dependent.
- The deployment runner becomes a sensitive production actor and requires exact-plan binding, protected workflow identity, short-lived least privilege, concurrency control, and break-glass proof.
- Human approval and automated execution remain in one administrative trust domain; this improves reliability but is not independent separation of duties.
- Current manual-only staging/production application and incomplete preflight/evidence tooling do not prove the target.

## Alternatives Considered

### Require Manual `kubectl` for Every Production Change

This has the smallest automation footprint and keeps routine credentials off CI, but it makes operator presence and correct command/evidence handling deployment dependencies. It remains appropriate for bootstrap and break-glass use.

### Deploy Automatically on Every Eligible Merge

This removes approval latency but makes repository merge authority production mutation authority. One-person availability does not justify removing deliberate release authorization.

### Adopt GitOps Now

GitOps provides continuous desired-state reconciliation and drift repair, but adds a permanently privileged in-cluster controller, suspension and recovery semantics, and still needs rollout, migration, evidence, and compatibility-bounded rollback policy. It is not currently required.

## Implementation and Proof Obligations

Implement a canonical plan generator and protected executor covering exact input binding, live precondition checks, concurrency, preflight, apply, rollout observation, public smoke, evidence writing, and compatibility-bounded restoration. Prove changed inputs after approval, workflow drift, stale environment generation, concurrent plans, credential isolation, executor interruption/retry, ambiguous apply result, rollback-compatible restoration, roll-forward-only stop, evidence failure, and break-glass reconciliation.

## Reversibility and Revisit Triggers

The executor and credential provider may change without changing the authority model. Revisit GitOps when multiple clusters or measured drift make continuous reconciliation valuable. Independent approval/signing becomes necessary when FireMUD introduces multiple administrative trust domains or formal separation of duties.
