# Release Readiness Gate

Use this prompt only for a declared release scope. It consumes completed review and proof evidence; it does not rerun an unconstrained whole-system audit and does not replace human release authorization.

Apply the [shared review contract](./00-shared-review-contract.md).

## Required Invocation

The caller provides:

- the release type and intended audience;
- the exact capabilities, journeys, services, public surfaces, and artifacts claimed by the release;
- applicable completed review findings and their dispositions;
- the implementation trackers and focused proof supporting those claims;
- explicitly deferred or excluded behavior; and
- any release-specific evidence available for inspection.

If the release scope or audience is not defined, stop with `blocked` and request that human decision.

## Starting Sources

- `design/product/requirements.md`
- `design/product/capability-taxonomy.md`
- the applicable product journeys and canonical architecture contracts
- `design/project-management/implementation-tracking/README.md` and owning trackers
- `design/architecture/system-architecture-cicd.md`
- `design/architecture/system-architecture-database-migrations.md`
- `design/architecture/system-architecture-security.md`
- `design/operations/release-process.md`
- release manifests, schemas, API contracts, tests, validation evidence, and accepted review dispositions supplied by the caller

## Gate

Check that:

- every claimed capability and journey has a canonical target, supported implementation status, and focused proof appropriate to the release claim;
- target-only, deferred, partial, or unavailable behavior is not represented as shipped;
- public routes, protocols, schemas, migrations, versions, artifacts, licenses, notices, and compatibility expectations are explicit;
- unresolved design, security, privacy, data-rights, corruption, or user-blocking findings have an accepted human disposition;
- rollback and recovery expectations are defined for the release type;
- repository validation evidence is current for the release revision; and
- the status of live-environment or provider evidence is explicit: unavailable evidence required for the release claim makes the recommendation `incomplete`, while evidence that is not required leaves the result `static-only` and not a release-readiness claim.

## Output

Provide:

1. the assessed release scope;
2. a gate table for claimed capabilities, journeys, public contracts, proof, security prerequisites, artifacts, rollback, and known gaps;
3. release blockers and evidence limitations;
4. a recommendation: `ready`, `not-ready`, `incomplete`, or `static-only`; and
5. the review state required by the shared contract.

`ready` is a recommendation to the human release owner, not authorization to publish, deploy, merge, or open traffic. A `static-only` result is not a release-readiness claim.
