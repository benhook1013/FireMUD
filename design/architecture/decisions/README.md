# Architecture Decision Records

Architecture decision records explain why consequential FireMUD product and architecture choices were accepted, superseded, or withdrawn. They supplement canonical architecture but do not replace it: current target-state behavior remains defined by the linked canonical design documents.

## Status Rules

- `Accepted` records explain current consequential choices and must remain aligned with canonical design.
- `Superseded` and `Withdrawn` records are historical context only. Both status values are valid on their own; replacement detail is never appended to the `Status` value. A formal `Superseded` record requires exactly one strict `Supersession` entry. A `Withdrawn` record may omit that section but must state its rationale for withdrawal; if replacement detail is recorded, it uses the same strict section defined below.
- `Proposed` records are not current target state until explicitly accepted and reflected in canonical design. An AI-authored ADR awaiting human review must use the exact pending metadata shape defined below: `Proposed - Pending Human Review`, `Human review status: Pending`, `Human review date: Not yet reviewed`, `Human review disposition: Pending`, and a `Review source` value of `AI-AUTHORED-PENDING`.
- An agent may set an ADR to `Accepted`, `Superseded`, or `Withdrawn` only when the checked review queue in the [consequential decision inventory](../../project-management/design-alignment/consequential-decision-inventory.md) names that ADR as provenance, except for pre-formal ADRs `0001` through `0011`, which predate the checked queue. Every ADR linked as provenance by such a row must record all four matching fields: review status, date, disposition, and backtick-delimited decision keys. An agent must never infer those values.
- Reversible work may continue while an AI-authored ADR awaits review only when existing canonical design already supports that work and the implementation does not depend on treating the proposal as accepted. Work that changes an accepted decision, selects between competing target states, or creates a consequential commitment waits for human review.
- A new ADR is warranted for a cross-cutting, authority-setting, security-sensitive, expensive-to-reverse, or genuinely contested decision. Routine local implementation choices belong in code and the owning design document.
- Changing an accepted decision requires explicit human design review, a new or superseding ADR, and updates to every affected canonical design source.

### Machine-Readable Status Grammar

The value immediately following `## Status` is exactly one of these strings:

```text
Status ::= "Proposed - Pending Human Review"
         | "Accepted"
         | "Superseded"
         | "Withdrawn"
```

Status values do not contain replacement links, parentheticals, or other prose. When replacement detail is needed, the ADR may contain this separate machine-readable section with exactly one line:

```text
## Supersession

- Replacement ADR: [ADR NNNN](./adr-NNNN-example.md)
```

The replacement link must use the exact `ADR NNNN` label and an `adr-NNNN-*.md` target whose number matches the label in the canonical ADR directory. A formal `Superseded` ADR must contain exactly one strict `Supersession` entry with one valid replacement entry. A `Withdrawn` ADR may omit this section but must state its rationale for withdrawal; when present, it uses the same exact one-entry grammar. The pre-formal ADRs `0001` through `0011` retain their existing historical replacement prose as a validation-only legacy exception; that prose is not part of the grammar and is not permitted for reviewed ADRs.

### Status-to-Review Mapping

Every formal ADR must obey the status-to-review mapping below. Terminal statuses require checked queue evidence and completed review metadata; `Proposed - Pending Human Review` is the sole no-queue exception and must retain the exact pending metadata shape. Pre-formal ADRs `0001` through `0011` remain the separate historical exception:

| ADR Status | Human review status | Allowed human review disposition |
| --- | --- | --- |
| `Proposed - Pending Human Review` | `Pending` | `Pending`, with no checked provenance row |
| `Accepted` | `Completed` | `Accepted` or `Revised` |
| `Superseded` | `Completed` | `Superseded` |
| `Withdrawn` | `Completed` | `Withdrawn` |

`Deferred` has no ADR status mapping. A checked `deferred` queue row may record a canonical-design outcome, but it must not use an exact `[ADR NNNN]` provenance link until a status and mapping are added deliberately. A pending ADR cannot carry completed queue evidence.

## Review Metadata Contract

The `Decision Record` section of a reviewed ADR is machine-readable. A completed record must contain exactly one line for each of these fields:

- `Human review status: Completed`
- `Human review date: YYYY-MM-DD`
- `Human review disposition: Accepted`, `Revised`, `Superseded`, or `Withdrawn` for an ADR provenance record
- `Review source:` followed by one or more backtick-delimited checked-queue decision keys separated by commas

For a formal `Withdrawn` ADR that omits `## Supersession`, the same `Decision Record` section must also contain exactly one non-empty `Withdrawal rationale:` field. Its value is normalized as single-spaced text by validation and must explain why the proposal was withdrawn.

Completed review metadata is distinct from the pending proposal shape: for `Accepted`, `Superseded`, or `Withdrawn` records, `Review source` contains only one or more checked-queue decision keys and must never be `AI-AUTHORED-PENDING`. `AI-AUTHORED-PENDING` is metadata reserved exclusively for an ADR whose status is `Proposed - Pending Human Review` and whose review metadata has the exact pending shape below. It is not a completed review source, checked provenance, or human review evidence.

The authoritative provenance is the checked review queue in the [consequential decision inventory](../../project-management/design-alignment/consequential-decision-inventory.md), not the ADR metadata alone. A checked queue row has this exact shape:

```text
- [x] `DECISION-KEY` — `accepted|revised|deferred|superseded|withdrawn` on YYYY-MM-DD; OUTCOME
- [x] `DECISION-KEY` — `accepted` on YYYY-MM-DD by [ADR NNNN](../../architecture/decisions/adr-NNNN-example.md)
```

`OUTCOME` must be non-empty, use either the semicolon or `by` form shown in the queue, and contain one or more Markdown links. Provenance is label-based, not disposition-based: an ADR provenance reference is specifically a link labeled `[ADR NNNN]` whose target filename is `adr-NNNN-*.md`, and every such ADR link in any checked row receives that row's source key, date, and disposition. Every checked non-alias row with an `accepted`, `revised`, `superseded`, or `withdrawn` disposition must contain at least one exact `[ADR NNNN]` provenance link. A superseded scan alias is a historical replacement mapping, not a separate ADR decision: every Markdown link in its outcome must be a replacement decision with either a non-provenance `[replacement ADR NNNN]` label targeting that exact canonical ADR, or a decision-key label such as `[JWT-01]` targeting an existing Markdown decision document. Exact `[ADR NNNN]` labels, arbitrary labels, external targets, and non-Markdown replacement references are invalid. Alias links do not contribute provenance to the historical row, and a replacement ADR receives provenance only from its own checked row. Other outcome prose may follow. Distinct coupled queue rows may reference the same ADR only when their date and disposition agree. Duplicate source keys, conflicting duplicate ADR provenance, duplicate ADR links in one row, malformed checked rows, and checked rows without an outcome link are invalid. Unchecked rows are not parsed as completed review evidence.

For any ADR linked by an exact `[ADR NNNN]` provenance label in a checked queue row, all four completed review fields are mandatory and must match the aggregate queue evidence exactly:

- `Human review status: Completed`
- `Human review date: YYYY-MM-DD`
- `Human review disposition: Accepted`, `Revised`, `Superseded`, or `Withdrawn` for an ADR provenance record
- `Review source:` followed by one or more backtick-delimited queue keys, for example `` `DECISION-KEY` `` or `` `DECISION-KEY`, `OTHER-KEY` ``

The pre-formal records `0001` through `0011` are the explicit exception only when no checked queue row links them; those historical records may omit review metadata. An AI-authored pending record is not completed review evidence and must use this exact shape instead; `AI-AUTHORED-PENDING` is valid only in this pending shape:

```text
## Status

Proposed - Pending Human Review

## Decision Record

- Human review status: Pending
- Human review date: Not yet reviewed
- Human review disposition: Pending
- Review source: `AI-AUTHORED-PENDING`
```

Validation precedence is fixed: first parse every checked queue row and validate its ADR references; then aggregate the checked rows for each ADR and require the ADR's completed metadata to match that aggregate exactly; finally validate the ADR `Status` independently. A terminal status requires checked queue evidence except for pre-formal ADR records `0001` through `0011`, while a pending proposal must retain the exact pending metadata shape above. The validator never infers human review from a terminal ADR status or from an unchecked queue row.

## Registry

| ADR | Status | Primary capability | Secondary capabilities | Decision |
| --- | --- | --- | --- | --- |
| [ADR 0001](./adr-0001-scripting-event-ingress-idempotency-identity.md) | Accepted | `AS-1` | `SF-1`, `SF-2` | Canonical scripting trigger identity and retry deduplication boundary |
| [ADR 0002](./adr-0002-automation-handoff-reliability-and-success-semantics.md) | Accepted | `AS-1` | `GR-1`, `SF-2`, `PO-4` | Durable automation-to-tick handoff and success semantics |
| [ADR 0003](./adr-0003-reload-backpressure-and-retry-contract.md) | Accepted | `AS-1` | `AR-3`, `GR-1`, `PO-4` | Reload backpressure, bounded retry, and timer behavior |
| [ADR 0004](./adr-0004-gameplay-reroute-vs-backend-unavailable.md) | Superseded | `PO-2` | `AA-2`, `GR-1`, `PO-4` | Historical distinct reroute close taxonomy |
| [ADR 0005](./adr-0005-tenant-identifiers-in-gameplay-protocol.md) | Accepted | `AA-3` | `EA-1`, `SF-1` | Internal tenant identity and player-facing world selector boundary |
| [ADR 0006](./adr-0006-gameplay-shard-routing-key-transport.md) | Withdrawn | `PO-2` | `AA-3`, `GR-1`, `SF-1` | Historical client-carried gameplay shard routing proposal |
| [ADR 0007](./adr-0007-edge-sharding-and-close-taxonomy.md) | Accepted | `PO-2` | `AA-2`, `GR-1`, `PO-4` | Shard-unaware edge and unified client-visible close taxonomy |
| [ADR 0008](./adr-0008-multi-cluster-gameplay-sharding-scope.md) | Accepted | `GR-1` | `PO-2`, `PO-3`, `SF-2` | Single-cluster gameplay execution and multi-cluster adoption gate |
| [ADR 0009](./adr-0009-coordination-redis-ownership-boundary.md) | Accepted | `SF-2` | `AA-2`, `GR-1`, `AS-1` | Coordination Redis ownership and participation boundaries |
| [ADR 0010](./adr-0010-tcp-proxy-identity-canonicalization.md) | Accepted | `SF-1` | `PO-2`, `PO-3` | TCP Proxy URI SAN identity and constrained fallback modes |
| [ADR 0011](./adr-0011-gameplay-session-front-end-and-region-execution.md) | Accepted | `GR-1` | `AA-2`, `SF-1`, `SF-2`, `PO-2` | Session front-end and fenced lease-owner execution model |
| [ADR 0012](./adr-0012-settings-value-precedence-and-constraints.md) | Accepted | `AR-2` | `EA-1`, `GR-1`, `SF-2` | Settings value precedence, source eligibility, and separately enforced constraints |
| [ADR 0013](./adr-0013-bounded-invisible-non-edge-restart-recovery.md) | Accepted | `GR-1` | `AA-2`, `PO-2`, `PO-4`, `SF-2` | Bounded invisible non-edge restart recovery and explicit fallback |
| [ADR 0014](./adr-0014-phased-jwt-signing-key-rotation-and-readiness.md) | Accepted | `SF-1` | `AA-1`, `PO-1`, `PO-3`, `PO-4` | Phased planned JWT rotation, compromise hard cutover, validator convergence, and player-facing readiness |
| [ADR 0015](./adr-0015-online-backup-and-environment-wide-cold-start-recovery.md) | Accepted | `PO-3` | `GR-1`, `PO-1`, `PO-4`, `SF-2` | Online environment-wide PostgreSQL backup and cold-start player-facing recovery |
| [ADR 0016](./adr-0016-canonical-gameplay-command-status-lifecycle.md) | Accepted | `GR-1` | `AA-2`, `PO-4`, `SF-2` | Existing gameplay-command status API evolved into one orthogonal acknowledgement, progress, and outcome lifecycle |
| [ADR 0017](./adr-0017-capability-gated-operational-tracing.md) | Accepted | `PO-4` | `AA-2`, `GR-1`, `SF-1` | Proof-gated workflow tracing and service or tenant/game-instance/region incident sampling capabilities |
| [ADR 0018](./adr-0018-declarative-production-gateway-routes.md) | Accepted | `PO-2` | `AA-3`, `PO-1`, `PO-3`, `SF-2` | Declarative player-facing route authority with isolated ephemeral dev/test overrides |
| [ADR 0019](./adr-0019-separate-active-session-resume-and-transcript-lifetimes.md) | Accepted | `AA-2` | `AR-2`, `EA-3`, `GR-1`, `SF-1` | Independent active-session, continuity, disconnected-resume, storage, and transcript lifetimes |
| [ADR 0020](./adr-0020-scoped-domain-and-operational-identifiers.md) | Accepted | `SF-1` | `AR-1`, `GR-2`, `GR-3` | Opaque UUID identities for durable resources, scoped numeric runtime IDs, and separate operational identities |
| [ADR 0021](./adr-0021-staged-player-authentication-and-gameplay-binding.md) | Accepted | `AA-2` | `EA-3`, `PO-2`, `SF-1` | Staged browser and text login with explicit Game Session authentication and gameplay binding |
| [ADR 0022](./adr-0022-account-authority-and-gameplay-session-ownership.md) | Accepted | `AA-1` | `AA-2`, `SF-1`, `SF-2` | Account-owned durable security authority, Game Session-owned gameplay bindings, and a bounded Gateway role |
| [ADR 0023](./adr-0023-central-route-authorization-governance.md) | Accepted | `SF-1` | `AA-1`, `PO-1`, `PO-2`, `PO-4` | One machine-readable route policy with generated completeness checks and runtime default denial |
| [ADR 0024](./adr-0024-trusted-gameplay-workload-delegation.md) | Accepted | `SF-1` | `GR-1`, `PO-3` | Concrete mTLS workload identity, method allowlists, and unsigned typed player execution context for gameplay delegation |
| [ADR 0025](./adr-0025-explicit-open-enrollment-membership.md) | Accepted | `AA-1` | `AA-2`, `AA-3`, `EA-3` | Explicit one-step public-game join creates the durable tenant membership used for return discovery |
| [ADR 0026](./adr-0026-global-roles-do-not-grant-gameplay-authority.md) | Accepted | `AA-1` | `AA-2`, `PO-1`, `EA-3` | Global control-plane roles never grant or elevate gameplay authority, impersonation, or observation |
| [ADR 0027](./adr-0027-single-realm-admission-target.md) | Accepted | `AA-3` | `AR-3`, `GR-1`, `GR-2` | Each realm has zero or one Game Session-owned admission target with atomic routing and bounded source drain |
| [ADR 0028](./adr-0028-differentiated-entitlement-freshness.md) | Accepted | `AA-1` | `AA-2`, `AA-3`, `PO-4`, `SF-1` | Strict fresh entitlements for new commitments and bounded last-known-good continuity for existing authority |
| [ADR 0029](./adr-0029-single-use-gameplay-connect-token-carriage.md) | Accepted | `PO-2` | `AA-2`, `SF-1` | Unambiguous browser/non-browser connect-token carriage with shared atomic single-use enforcement |
| [ADR 0030](./adr-0030-risk-based-active-session-revocation.md) | Accepted | `AA-1` | `AA-2`, `GR-1`, `PO-1` | Risk-based role, authority, security, and billing changes with bounded active-session revocation |
| [ADR 0031](./adr-0031-revocation-safe-session-token-rotation-and-logout.md) | Accepted | `AA-2` | `AA-1`, `GR-1`, `SF-1` | Generation-bound `game-session-account-delegation` rotation with distinct per-token and account-wide logout semantics |
| [ADR 0032](./adr-0032-kubernetes-native-secret-delivery-without-mandatory-vault.md) | Accepted | `SF-1` | `PO-1`, `PO-2`, `PO-3` | One Kubernetes-native mounted-secret contract without mandatory or bundled Vault |
| [ADR 0033](./adr-0033-public-player-facing-telnet-requires-tls.md) | Accepted | `PO-2` | `AA-1`, `AA-2`, `EA-3`, `SF-1` | TLS required for public player-facing Telnet without a transport-specific TOTP gate |
| [ADR 0034](./adr-0034-layered-abuse-controls-without-attacker-triggered-account-locks.md) | Accepted | `SF-1` | `AA-1`, `AA-2`, `PO-1`, `PO-2` | Layered abuse ownership without attacker-triggered account locks or per-command Redis limiting |
| [ADR 0035](./adr-0035-single-record-issued-token-registry.md) | Accepted | `SF-1` | `AA-1`, `AA-2`, `SF-2` | One default-deny Account-owned registry record per revocable JWT |
| [ADR 0036](./adr-0036-monotonic-authority-generations-for-bulk-token-revocation.md) | Accepted | `SF-1` | `AA-1`, `AA-2`, `SF-2` | Monotonic issuer, account, tenant, and membership generations for bulk token revocation |
| [ADR 0037](./adr-0037-fail-closed-token-authority-outages-with-bounded-active-gameplay.md) | Accepted | `SF-1` | `AA-2`, `AA-3`, `SF-2` | Fail-closed token authority with retryable outage semantics and bounded already-admitted gameplay |
| [ADR 0038](./adr-0038-explicit-jwt-profiles-and-mtls-workload-identity.md) | Accepted | `SF-1` | `AA-1`, `AA-2`, `PO-2` | Exact JWT profiles with mTLS—not a generic Service JWT—as workload identity |
| [ADR 0039](./adr-0039-bounded-redis-operator-maintenance-surface.md) | Accepted | `PO-1` | `SF-1`, `SF-2`, `PO-4` | Read-only operator Redis access with a bounded high-level maintenance surface |
| [ADR 0040](./adr-0040-account-global-control-login-and-explicit-tenant-selection.md) | Accepted | `AA-1` | `EA-3`, `PO-1`, `SF-1` | Account-global control login with explicit per-request tenant authorization |
| [ADR 0041](./adr-0041-shared-tenant-infrastructure-with-full-environment-isolation-gate.md) | Accepted | `AA-1` | `PO-3`, `SF-2`, `GR-1` | Shared tenant infrastructure with a separately reviewed full-environment isolation gate |
| [ADR 0042](./adr-0042-global-account-and-tenant-scoped-game-relationships.md) | Accepted | `AA-1` | `AA-2`, `AA-3`, `SF-2`, `PO-1` | Global account identity with explicit tenant-scoped game relationships and membership |
| [ADR 0043](./adr-0043-global-account-lifecycle-and-bounded-erasure-workflow.md) | Accepted | `AA-1` | `SF-2`, `PO-1` | Global account lifecycle with pending deletion and bounded cross-service erasure |
| [ADR 0044](./adr-0044-account-owned-payment-instruments-with-explicit-subscription-binding.md) | Accepted | `AA-1` | `PO-1`, `SF-1` | Account-owned payment instruments with explicit per-subscription binding |
| [ADR 0045](./adr-0045-ordinary-login-factors-and-https-sensitive-action-step-up.md) | Accepted | `AA-1` | `AA-2`, `EA-3`, `PO-1`, `SF-1` | Ordinary login factors with HTTPS-only sensitive-action step-up and gameplay handoff |
| [ADR 0046](./adr-0046-bounded-friend-presence-with-private-by-failure-redaction.md) | Accepted | `EA-2` | `AA-1`, `AA-2`, `SF-1` | Bounded friend presence with complete private-by-failure redaction |
| [ADR 0047](./adr-0047-logging-admin-as-external-operator-write-ingress.md) | Accepted | `PO-1` | `AR-2`, `AR-3`, `GR-1`, `PO-2` | Logging and Admin as external operator-write ingress with domain-owned mutation authority |
| [ADR 0048](./adr-0048-durable-idempotent-operator-write-execution.md) | Accepted | `PO-1` | `SF-2`, `GR-1`, `PO-4` | Durable idempotent operator-write execution with owner-local commit and fenced forwarding |
| [ADR 0049](./adr-0049-optional-provider-specific-external-identity-linking.md) | Accepted | `AA-1` | `EA-3`, `SF-1` | Optional provider-specific external identity linking with Account-owned recovery |
| [ADR 0050](./adr-0050-versioned-export-retention-and-erasure-policy.md) | Accepted | `AA-1` | `AA-2`, `PO-1`, `PO-3`, `SF-2` | Versioned cross-service export and finite category-specific retention and erasure policy |
| [ADR 0051](./adr-0051-separate-actor-action-and-effect-lanes.md) | Accepted | `GR-1` | `GR-4`, `AS-1` | Separate deterministic actor-action and passive/inbound-effect tick lanes |
| [ADR 0052](./adr-0052-redis-liveness-lease-with-durable-executor-fence.md) | Accepted | `GR-1` | `SF-2`, `PO-4` | Redis region liveness lease installed and revalidated against a durable executor fence |
| [ADR 0053](./adr-0053-command-atomicity-by-invariant-class.md) | Accepted | `SF-2` | `GR-1`, `GR-2`, `GR-3`, `GR-4` | Local idempotent gameplay effects with stronger atomicity selected by invariant class |
| [ADR 0054](./adr-0054-split-spatial-authority-with-causal-read-composition.md) | Accepted | `SF-2` | `GR-1`, `GR-2`, `GR-3`, `GR-4`, `SF-1` | Split spatial authority with operation-bound effects and causal presentation reads |
| [ADR 0055](./adr-0055-durable-cross-region-effects-with-static-live-topology.md) | Accepted | `GR-2.1` | `GR-1.3`, `GR-1.4`, `SF-2.3`, `AA-3.3`, `PO-4.2` | Durable cross-region effects with static live topology and maintenance cutover |
| [ADR 0056](./adr-0056-one-hot-path-fan-out-owner.md) | Accepted | `GR-1.2` | `GR-2.2`, `GR-3.2`, `SF-2.3`, `PO-4.2` | One hot-path fan-out owner with a transitive two-participant ceiling |
| [ADR 0057](./adr-0057-game-session-owned-reconciliation-with-isolated-workers.md) | Accepted | `GR-1.4` | `SF-2.3`, `GR-2.3`, `GR-3.2`, `PO-4.2` | Game Session-owned reconciliation with isolated workers and durable dispositions |
| [ADR 0058](./adr-0058-class-specific-redis-loss-outcomes.md) | Accepted | `SF-2.2` | `SF-2.1`, `SF-2.3`, `PO-3.4`, `GR-1.4`, `PO-4.2` | Class-specific Redis-loss outcomes with durable-intent authority |
| [ADR 0059](./adr-0059-causal-floor-cross-service-presentation-reads.md) | Accepted | `SF-1.2` | `SF-2.3`, `GR-2.1`, `GR-3.2` | Causal-floor cross-service presentation reads and component-version identity |
| [ADR 0060](./adr-0060-world-owned-ambient-facts-and-logic-owned-consequences.md) | Accepted | `GR-2.3` | `GR-2.2`, `GR-4.1`, `GR-1.4`, `SF-2.3`, `PO-1.4` | World-owned ambient facts and Logic-owned gameplay consequences |
| [ADR 0061](./adr-0061-single-owner-spatial-mutations-across-split-authority.md) | Accepted | `GR-2.3` | `GR-2.2`, `GR-3.2`, `GR-4.1`, `SF-2.3` | Single-owner spatial mutations across split World and Entity authority |
| [ADR 0062](./adr-0062-layered-gameplay-command-delivery-semantics.md) | Accepted | `SF-1.1` | `SF-2.3`, `GR-1.2`, `AA-2.2`, `PO-2.4` | Layered gameplay command and internal-event delivery semantics |
| [ADR 0063](./adr-0063-durable-per-dispatch-script-handoff.md) | Accepted | `AS-1.5` | `SF-2.3`, `GR-1.1`, `SF-2.2`, `PO-4.1` | Durable per-dispatch script handoff and deterministic child dispatches |
| [ADR 0064](./adr-0064-stage-qualified-script-outcomes.md) | Accepted | `AS-1.5` | `PO-4.1`, `SF-2.3`, `SF-1.1` | Stage-qualified script outcomes and command-level authority links |
| [ADR 0065](./adr-0065-deterministic-fair-entity-tick-scheduling.md) | Accepted | `GR-1.2` | `SF-2.2`, `SF-2.3`, `GR-4.1`, `PO-4.2` | Deterministic fair entity tick scheduling and persisted selected manifests |
| [ADR 0066](./adr-0066-durable-asynchronous-cross-region-result-arbitration.md) | Accepted | `GR-1.4` | `SF-2.3`, `GR-2.1`, `GR-2.2`, `GR-1.2`, `PO-4.2` | Durable asynchronous cross-region result arbitration |
| [ADR 0067](./adr-0067-abandon-old-epoch-work-and-reschedule-with-new-lineage.md) | Accepted | `GR-1.4` | `SF-2.3`, `GR-2.1`, `AS-1.4`, `PO-4.4` | Evidence-qualified old-epoch reconciliation and new-lineage re-drive |
| [ADR 0068](./adr-0068-evidence-derived-bounded-tick-ledger-recovery.md) | Accepted | `PO-4.2` | `GR-1.4`, `SF-2.3`, `PO-1.4`, `SF-1.4` | Evidence-derived bounded tick-ledger recovery |
| [ADR 0069](./adr-0069-at-least-once-effect-execution-with-one-logical-terminal-outcome.md) | Accepted | `GR-1.2` | `GR-1.4`, `SF-2.3`, `PO-4.2` | At-least-once effect execution with one logical terminal outcome |
| [ADR 0070](./adr-0070-bounded-within-tick-visibility-by-semantic-phase.md) | Accepted | `GR-1.2` | `GR-2.1`, `GR-4.1`, `SF-2.3` | Bounded within-tick visibility by semantic phase |
| [ADR 0071](./adr-0071-durable-tick-commit-before-fenced-coordination-cleanup.md) | Accepted | `GR-1.4` | `SF-2.3`, `GR-1.3`, `PO-4.2` | Durable tick commit before fenced coordination cleanup |
| [ADR 0072](./adr-0072-class-specific-timer-durability-and-recovery.md) | Accepted | `AS-1.4` | `GR-1.2`, `GR-2.3`, `AS-1.5`, `SF-1.4` | Class-specific timer durability and recovery |
| [ADR 0073](./adr-0073-evidence-calibrated-tick-budgets-and-lock-ttls.md) | Accepted | `SF-1.4` | `GR-1.2`, `GR-1.3`, `PO-4.2`, `AR-2.3` | Evidence-calibrated tick budgets and lock TTLs |
| [ADR 0074](./adr-0074-one-entity-lock-per-redis-script.md) | Accepted | `GR-1.3` | `SF-2.2`, `GR-4.1`, `PO-4.4` | One entity lock per Redis script |
| [ADR 0075](./adr-0075-depth-cost-and-count-bounds-for-generated-effect-chains.md) | Accepted | `GR-4.1` | `GR-1.2`, `AS-1.2`, `PO-4.2` | Depth, cost, and count bounds for generated effect chains |
| [ADR 0076](./adr-0076-failure-class-specific-durable-tick-retries.md) | Accepted | `GR-1.2` | `PO-2.4`, `PO-4.2`, `SF-2.3` | Failure-class-specific durable tick retries |
| [ADR 0077](./adr-0077-durable-global-effect-fanout-and-lightweight-idle-ticks.md) | Accepted | `GR-1.2` | `GR-2.1`, `AS-1.4`, `SF-1.4`, `PO-4.2` | Durable global-effect fan-out and lightweight idle ticks |
| [ADR 0078](./adr-0078-digest-bound-workflow-and-step-retry-identities.md) | Accepted | `SF-2.3` | `SF-2.4`, `SF-1.2` | Digest-bound workflow and step retry identities |
| [ADR 0079](./adr-0079-jooq-and-flyway-as-the-single-sql-persistence-stack.md) | Accepted | `SF-2.1` | `SF-1.5`, `PO-3.1` | jOOQ and Flyway as the single SQL persistence stack |
| [ADR 0080](./adr-0080-service-owned-schemas-with-adopter-local-shared-migrations.md) | Accepted | `SF-2.1` | `SF-2.4`, `PO-3.1` | Service-owned schemas with adopter-local shared migrations |
| [ADR 0081](./adr-0081-objective-compatibility-gates-for-database-evolution.md) | Accepted | `SF-2.1` | `AR-3.2`, `PO-3.1` | Objective compatibility gates for database evolution |
| [ADR 0082](./adr-0082-semantic-boundary-for-cross-service-identifier-migration.md) | Accepted | `SF-2.3` | `SF-2.1`, `AR-1.5`, `AR-3.2` | Semantic boundary for cross-service identifier migration |
| [ADR 0083](./adr-0083-no-general-event-broker-until-measured-adoption-gates.md) | Accepted | `SF-2.2` | `SF-1.1`, `PO-4.1`, `AS-1.5`, `GR-1.4` | No general event broker until measured adoption gates |
| [ADR 0084](./adr-0084-evidence-scoped-redis-lua-compatibility.md) | Accepted | `SF-2.2` | `SF-1.5`, `GR-1.3`, `PO-4.4` | Evidence-scoped Redis Lua compatibility |
| [ADR 0085](./adr-0085-evidence-gated-coordination-replay-and-fenced-reset.md) | Accepted | `SF-2.2` | `PO-4.4`, `PO-3.4`, `GR-1.4` | Evidence-gated coordination replay and fenced reset |
| [ADR 0086](./adr-0086-owner-validated-class-a-caches-and-presentation-only-class-b.md) | Accepted | `SF-2.2` | `GR-2.3`, `GR-3.2`, `EA-1.2`, `PO-4.1` | Owner-validated Class A caches and presentation-only Class B |
| [ADR 0087](./adr-0087-isolated-subject-rate-limits-with-explicit-loss-semantics.md) | Accepted | `SF-2.2` | `PO-2.4`, `AA-1.5`, `PO-4.2` | Isolated-subject rate limits with explicit loss semantics |

Capability identifiers are defined in the [FireMUD Product Capability Taxonomy](../../product/capability-taxonomy.md).

### Supersession Index

This is a hand-maintained index, not an independent authority. The ADR review-status validator checks that each row's status and replacement agree with the corresponding ADR's validated `## Status` and `## Supersession` sections; those ADR sections remain normative. The pre-formal ADRs `0001` through `0011` retain hand-maintained legacy entries permitted only by their historical replacement prose and remain validation-only exceptions.

| ADR | Status | Replacement ADR |
| --- | --- | --- |
| [ADR 0004](./adr-0004-gameplay-reroute-vs-backend-unavailable.md) | Superseded | [ADR 0007](./adr-0007-edge-sharding-and-close-taxonomy.md) |
| [ADR 0006](./adr-0006-gameplay-shard-routing-key-transport.md) | Withdrawn | [ADR 0007](./adr-0007-edge-sharding-and-close-taxonomy.md) |

## Record Shape For New Decisions

New ADRs should include:

- status and decision dates;
- primary and affected capabilities;
- decision owner and consulted decision makers;
- machine-readable review metadata: completed ADRs require human-review status, date, disposition, and checked-queue review-source decision keys, while `Proposed - Pending Human Review` records use the exact pending `AI-AUTHORED-PENDING` shape above and must not fabricate completed-review keys;
- context, constraints, assumptions, and decision drivers;
- the accepted decision stated without historical alternatives mixed into it;
- strongest credible alternatives, including doing nothing where meaningful;
- positive and negative consequences;
- reversibility, lock-in, exit strategy, and revisit triggers;
- security, operations, cost, and implementation/proof obligations;
- canonical design links; and
- structured supersession relationships when applicable.

Alternatives and rejected hypotheses are non-normative. The accepted decision and canonical architecture must remain the only current target-state contract.
