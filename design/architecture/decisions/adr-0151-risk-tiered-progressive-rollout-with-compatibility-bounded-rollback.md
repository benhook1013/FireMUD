# ADR 0151: Risk-Tiered Progressive Rollout With Compatibility-Bounded Rollback

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `OPS-02`
- Primary capability: `PO-3.1` packaging, CI/CD, deployment, promotion, and infrastructure topology
- Affected capabilities: `AR-3.3`, `PO-1.4`, `PO-4.2`, `PO-4.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of rollout blast radius, readiness limits, progressive delivery, rollback compatibility, automated failure response, and single-operator burden

## Context

FireMUD currently targets ordinary Kubernetes `RollingUpdate` deployment with explicit operator reapplication of a known-good digest set when rollback is needed. This is operationally simple, but a rolling update eventually replaces every healthy replica when a candidate remains technically ready while exhibiting a semantic, authorization, data, latency, or feature-specific regression. Post-deployment smoke and synthetic player-flow checks can detect some such failures, but they do not currently govern rollout progression.

Universal canary infrastructure and automatic rollback would create a different risk. A canary is meaningful only when an environment has enough replicas and representative traffic to limit and compare exposure. Automatic restoration of old binaries is unsafe when database schema, secret or configuration shape, mounted-file contracts, external bindings, or durable state make the previous release incompatible. FireMUD already distinguishes `rollback-compatible` from `roll-forward-only` releases; rollout behavior must preserve that boundary.

The normal operating model has one operator. Rollout evidence collection, smoke execution, SLO observation, pausing, and preparation of a known-good target should therefore be automated without requiring a permanent progressive-delivery control plane or transferring unsafe compatibility judgment to automation.

## Decision

### Explicit Conservative Rolling Update Is the Baseline

Multi-replica staging and production workloads use an explicit conservative `RollingUpdate` strategy rather than relying on Kubernetes defaults. The baseline preserves current ready capacity while introducing one candidate replica at a time, using `maxUnavailable: 0` and `maxSurge: 1` unless a separately reviewed workload constraint requires an equally safe explicit strategy.

Readiness prevents an unready candidate from receiving ordinary Service traffic, but readiness alone is not release acceptance. The deployment playbook also observes rollout status, runs the canonical smoke checks, evaluates the applicable player-path and service SLO signals, and records the observed candidate digest and outcome.

### Ordinary Rollback-Compatible Releases Use Automated Observation and Pause

An ordinary release classified and evidenced as `rollback-compatible` uses the conservative rolling baseline with automated smoke and SLO observation. A hard candidate failure pauses or aborts further rollout progression and retains evidence of the failing candidate rather than allowing every known-good replica to be replaced.

Automation may restore the predeclared known-good digest set only when all of the following are true:

- the release evidence classifies the candidate as `rollback-compatible`;
- the prior digest set was selected before apply and remains the known-good target;
- the compatibility evidence remains valid for the current database schema, secret and configuration contract, mounted-file contract, external bindings, and recovery state; and
- the failure matches the rollout policy's hard-failure conditions.

The automatic action and its evidence remain explicit and auditable. Operators may instead require approval before restoration for an environment or release class, but automation cannot infer rollback compatibility from readiness failure alone.

### Roll-Forward-Only Releases Never Automatically Reapply Old Binaries

A release classified as `roll-forward-only` may use automated observation and automatic pause, but it never automatically rolls back to the prior digest set. On hard failure, rollout stops and the operator executes the documented forward-remediation or restore-point recovery path.

Absence of a safe binary rollback is not permission to continue replacing healthy replicas. Promotion and deployment evidence must identify the forward path before apply, and automation must preserve enough known-good capacity or quarantine to support that path.

### Bounded Canaries Are Risk- and Scale-Triggered

High-risk production changes use a bounded canary phase when the deployment has enough replicas and representative traffic for a smaller cohort to provide meaningful isolation and comparison. The canary runs the same candidate digest and exact release contract intended for the remaining rollout, receives bounded exposure, and must pass the defined smoke and SLO observation before broader progression.

A one-replica or very small deployment is not required to install nominal canary machinery that cannot materially reduce blast radius or yield a representative comparison. Hobby/self-hosted and other small deployments retain a simple explicit rolling or recreate strategy, health verification, smoke checks, a predeclared recovery target, and the same compatibility restriction on rollback.

Canary use is a rollout technique, not a separate artifact lineage. It does not permit rebuilding, retagging different bytes, bypassing preflight, weakening health semantics, or promoting a digest other than the exact attested candidate.

### No Mandatory Progressive-Delivery Controller Yet

FireMUD does not require Argo Rollouts, Flagger, or another permanent progressive-delivery controller at the current scale. The canonical deployment playbook may implement conservative progression, observation, pause, and compatibility-bounded restoration with Kubernetes and repository-owned tooling.

Adopt a dedicated controller only when replica count, deployment frequency, traffic volume, rollout duration, or policy complexity makes the bounded playbook inadequate. Introducing one must preserve the same digest lineage, compatibility classification, evidence, pause, and roll-forward-only boundaries.

### The Playbook Carries Routine One-Operator Work

The canonical playbook resolves the candidate and predeclared known-good digests, verifies rollout classification, applies the selected strategy, watches readiness and rollout status, runs smoke checks, observes the configured SLO window, pauses on hard failure, performs only permitted restoration, and writes the deployment outcome. The operator reviews the candidate, risk and compatibility classification, and any exceptional recovery decision rather than manually polling every service or reconstructing commands during failure.

Production deployments remain intentional operator-controlled events. Automation reduces reaction time and transcription burden; it does not turn a deployment into an unattended production mutation or auto-authorize an unsafe rollback.

## Consequences

- Ordinary rollouts retain simple Kubernetes behavior while making surge, unavailability, observation, and failure response explicit.
- A candidate that passes readiness but fails smoke or SLO checks can be stopped before replacing every known-good replica.
- Safe known-good restoration can be fast for rollback-compatible releases without pretending every change is reversible.
- Roll-forward-only releases fail safely into explicit remediation rather than automatically starting incompatible old binaries.
- Meaningful canaries reduce blast radius for sufficiently scaled, high-risk changes without imposing nominal canary infrastructure on hobby or tiny deployments.
- One operator receives automated observation, pause, evidence, and permitted restoration instead of a long manual polling and command sequence.
- Repository-owned playbooks require implementation and proof, and a dedicated progressive-delivery controller may become worthwhile at greater scale.

## Alternatives Considered

### RollingUpdate With Manual Observation and Rollback Only

Rejected as the complete target because readiness can remain green during semantic or performance regressions, allowing the candidate to replace every known-good replica before the operator reacts. Manual control remains available, but the routine observation and pause path is automated.

### Automatic Rollback for Every Failed Release

Rejected because an old binary may be incompatible with the current database, credentials, configuration, mounted resources, external bindings, or durable state. Automatic restoration is bounded by prior rollback-compatibility evidence.

### Mandatory Canary and Progressive-Delivery Controller for Every Deployment

Rejected because small and low-traffic environments cannot create a meaningful representative cohort, while controller installation and operation add cost, policy, availability, and troubleshooting burden. Canary use is triggered by risk and meaningful scale.

### Never Use Canaries

Rejected as a permanent rule because sufficiently scaled, high-risk production changes benefit from limiting initial exposure and observing the exact candidate under representative traffic before broader rollout.

### Treat Readiness as Rollout Acceptance

Rejected because readiness answers whether a pod may accept its current traffic contract. It does not prove the release's complete behavior, player experience, performance, or absence of data and authorization regressions.

## Implementation and Proof Obligations

Make the multi-replica rollout strategy explicit in canonical staging and production manifests. Provide a canonical deployment playbook that consumes the exact candidate and known-good digest sets, rollback classification and compatibility evidence, risk classification, smoke contract, SLO observation inputs, and target environment.

The playbook must distinguish pause, abort, compatible restoration, forward remediation, and successful progression as separate recorded outcomes. It must refuse automatic known-good restoration for a `roll-forward-only` release or for missing, stale, or mismatched compatibility evidence. Any restoration must reapply the exact predeclared digest set and record the trigger, observations, action, actor or automation identity, and resulting health.

Define the bounded high-risk canary path without changing artifact lineage. Proof must show that the canary receives limited exposure, failure stops broader rollout, success alone does not bypass other promotion or deployment gates, and a too-small environment follows the documented simple strategy rather than claiming ineffective canary proof.

Proof must cover an unready candidate; a ready candidate that fails smoke; a latency or error-budget hard failure; a successful ordinary rollout; a compatible automatic restoration; refusal to restore when compatibility evidence is missing or stale; a `roll-forward-only` failure that pauses for forward remediation; operator-selected manual handling; high-risk canary failure and success; one-replica hobby deployment; and preservation of exact digest and deployment-evidence lineage throughout.

Current implementation is incomplete. Canonical workload manifests rely on implicit `RollingUpdate` defaults and do not declare the conservative strategy. Synthetic player-flow canaries, alerts, readiness probes, smoke runners, and manual deployment guidance exist, but no production rollout playbook currently composes them into automatic observation, pause, risk-triggered canary progression, or compatibility-bounded restoration. No focused production-equivalent rollout and rollback proof establishes this decision yet.

## Reversibility and Revisit Triggers

Observation windows, hard-failure policies, canary cohort sizing, and playbook mechanics may evolve with measured deployment behavior while retaining exact artifact lineage and compatibility-bounded rollback. Revisit the need for a dedicated progressive-delivery controller when production replica count, traffic, deployment frequency, rollout duration, policy complexity, or one-operator workload makes the repository-owned playbook unreliable or disproportionately expensive.

## Required Documentation Alignment

- `design/architecture/system-architecture-cicd.md`
- `design/architecture/system-architecture-deployment-runbook.md`
- `design/architecture/system-architecture-logging-monitoring.md`
- `design/architecture/infrastructure/deployment-environments.md`
- `k8s/base/`
- `k8s/overlays/stage/`
- `k8s/overlays/prod/`
