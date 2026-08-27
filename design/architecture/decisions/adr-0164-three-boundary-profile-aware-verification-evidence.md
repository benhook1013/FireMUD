# ADR 0164: Three-Boundary Profile-Aware Verification Evidence

## Status

Accepted

## Implementation Status

This decision is not implemented. Profile-aware three-boundary verification evidence, exact recovery-event binding, expiry, integrity, and promotion enforcement remain gaps.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `TEST-02`
- Decision date: 2026-07-20
- Decision key: `TEST-02`
- Primary capability: `PO-4.4` smoke, canary, incident evidence, and architecture conformance proof
- Affected capabilities: `PO-4.3`, `PO-3.4`, `PO-3.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of CI speed, environment assurance, automated recovery, hobby deployment burden, evidence integrity, and profile-dependent claims

## Context

Fast repository checks cannot prove live bindings, external failure-domain separation, routed alerts, indexed queries, restore behavior, or resource isolation. Conversely, requiring every hosted production check and external service in every pull request or hobby deployment would be slow, secret-bearing, flaky, and disproportionate.

Recovery evidence is also different from a generic staging smoke result: reopening after a real restore or rewind must be bound to that exact event and current recovered state.

## Decision

FireMUD has three verification boundaries:

1. **Deterministic change verification.** Pull-request and main CI run static, unit, integration, contract, schema, rendered-configuration, and evidence-shape checks that are safe for untrusted change execution.
2. **Environment assurance.** Scheduled, release-bound, or staging/prod-like automation proves the applicable deployed capabilities, including live smoke, canaries, alert delivery, independent monitoring, queryability, and selected fault injection.
3. **Event-bound recovery or traffic-open proof.** Restore, rewind, quarantine, reconciliation, and reopen validation is tied to the exact recovery event and cannot be satisfied by a prior generic environment smoke result.

A green earlier boundary never substitutes for a later one. Evidence identifies the exact artifact, environment, live cluster or equivalent deployment identity, event and phase, expected-binding digest, tool version, timestamps, freshness/expiry, selected assurance profile, and content-addressed underlying tool output.

Requirements are capability- and profile-aware. A hobby or small deployment runs an unattended local evidence playbook for the capabilities it claims. It may explicitly omit independently hosted monitoring or indexed-log search where their owning decisions allow a reduced posture, and must expose the resulting weaker assurance. It may not omit mandatory validation after an actual destructive restore or rewind.

Failed or expired evidence blocks only the promotion, traffic-open transition, or assurance claim that requires it. It does not automatically stop unrelated healthy gameplay unless a separate accepted runtime safety contract says so.

## Consequences

- Pull-request feedback remains fast and does not expose live credentials to untrusted changes.
- Hosted claims are supported by evidence from the environment and failure domain they concern.
- Small deployments avoid mandatory enterprise observability infrastructure while accurately declaring omissions.
- Automated evidence production, sanitization, integrity, freshness, retention, and profile matrices add operational work.
- Recovery events retain a stricter boundary than routine releases.

## Alternatives Considered

### Put Every Check in Pull-Request CI

This would increase latency, cost, flakiness, and secret exposure while still failing to reproduce the exact target or recovery event.

### Treat Static Reports as Live Proof

Shape validation cannot establish bindings, backend delivery, failure-domain separation, restore correctness, or external page delivery.

### One Generic Hobby Evidence Exception

This obscures which capabilities are omitted and could incorrectly weaken non-optional post-recovery gates. Capability-specific posture is required instead.

## Implementation and Proof Obligations

Select and report the required checks and evidence under the shared [Validation and Runtime Proof](../../developer-workflows/validation-and-runtime-proof.md) workflow; record execution results in PR/CI evidence or the owning implementation tracker rather than in this decision record.

Current static and preview/dev-demo evidence is substantial, but scheduled hosted assurance, independent external-path proof, end-to-end log queries, and automated recovery drills are partial or absent. Existing evidence directories contain mostly examples or placeholders.

Implementation must provide unattended playbooks, profile applicability and omission declarations, event/artifact/environment binding, content-addressed raw results, sanitization, access controls, freshness and expiry, and explicit blocking semantics. Fault proof must include the Logging & Admin availability partition, independent monitoring where claimed, queryability where claimed, and mandatory post-rewind quarantine/reopen checks.

## Reversibility and Revisit Triggers

Checks may move between boundaries as they become faster, safer, or more reliable. Revisit profile requirements when a deployment changes its assurance claims, and revisit blocking scope when a new capacity, SLO, compliance, or recovery contract is accepted.

## Required Documentation Alignment

- [design/architecture/system-architecture-testing.md](../system-architecture-testing.md)
- [design/architecture/system-architecture-deploy-preflight-policy.md](../system-architecture-deploy-preflight-policy.md)
- [design/architecture/infrastructure/deployment-environments.md](../infrastructure/deployment-environments.md)
- [design/operations/deployments/](../../operations/deployments/)
