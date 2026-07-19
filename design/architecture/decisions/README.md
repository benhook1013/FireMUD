# Architecture Decision Records

Architecture decision records explain why consequential FireMUD product and architecture choices were accepted, superseded, withdrawn, or rejected. They supplement canonical architecture but do not replace it: current target-state behavior remains defined by the linked canonical design documents.

## Status Rules

- `Accepted` records explain current consequential choices and must remain aligned with canonical design.
- `Superseded` and `Withdrawn` records are historical context only and must identify the replacing decision.
- `Proposed` records are not current target state until explicitly accepted and reflected in canonical design.
- A new ADR is warranted for a cross-cutting, authority-setting, security-sensitive, expensive-to-reverse, or genuinely contested decision. Routine local implementation choices belong in code and the owning design document.
- Changing an accepted decision requires explicit human design review, a new or superseding ADR, and updates to every affected canonical design source.

## Registry

| ADR | Status | Primary capability | Secondary capabilities | Decision |
| --- | --- | --- | --- | --- |
| [ADR 0001](./adr-0001-scripting-event-ingress-idempotency-identity.md) | Accepted | `AS-1` | `SF-1`, `SF-2` | Canonical scripting trigger identity and retry deduplication boundary |
| [ADR 0002](./adr-0002-automation-handoff-reliability-and-success-semantics.md) | Accepted | `AS-1` | `GR-1`, `SF-2`, `PO-4` | Durable automation-to-tick handoff and success semantics |
| [ADR 0003](./adr-0003-reload-backpressure-and-retry-contract.md) | Accepted | `AS-1` | `AR-3`, `GR-1`, `PO-4` | Reload backpressure, bounded retry, and timer behavior |
| [ADR 0004](./adr-0004-gameplay-reroute-vs-backend-unavailable.md) | Superseded by ADR 0007 | `PO-2` | `AA-2`, `GR-1`, `PO-4` | Historical distinct reroute close taxonomy |
| [ADR 0005](./adr-0005-tenant-identifiers-in-gameplay-protocol.md) | Accepted | `AA-3` | `EA-1`, `SF-1` | Internal tenant identity and player-facing world selector boundary |
| [ADR 0006](./adr-0006-gameplay-shard-routing-key-transport.md) | Withdrawn; superseded by ADR 0007 | `PO-2` | `AA-3`, `GR-1`, `SF-1` | Historical client-carried gameplay shard routing proposal |
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
| [ADR 0017](./adr-0017-capability-gated-operational-tracing.md) | Accepted | `PO-4` | `AA-2`, `GR-1`, `SF-1` | Proof-gated workflow tracing and service or tenant/region incident sampling capabilities |
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
| [ADR 0029](./adr-0029-single-use-gameplay-connect-token-carriage.md) | Accepted | `PO-2` | `AA-2`, `PO-2`, `SF-1` | Unambiguous browser/non-browser connect-token carriage with shared atomic single-use enforcement |
| [ADR 0030](./adr-0030-risk-based-active-session-revocation.md) | Accepted | `AA-1` | `AA-2`, `GR-1`, `PO-1` | Risk-based role, authority, security, and billing changes with bounded active-session revocation |
| [ADR 0031](./adr-0031-revocation-safe-session-token-rotation-and-logout.md) | Accepted | `AA-2` | `AA-1`, `GR-1`, `SF-1` | Generation-bound private Service JWT rotation with distinct per-token and account-wide logout semantics |
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
| [ADR 0042](./adr-0042-global-account-and-tenant-scoped-game-relationships.md) | Accepted | `AA-1` | `SF-2`, `PO-1` | One global account with explicit tenant-scoped game relationships |
| [ADR 0043](./adr-0043-global-account-lifecycle-and-bounded-erasure-workflow.md) | Accepted | `AA-1` | `SF-2`, `PO-1` | Global account lifecycle with pending deletion and bounded cross-service erasure |
| [ADR 0044](./adr-0044-account-owned-payment-instruments-with-explicit-subscription-binding.md) | Accepted | `AA-1` | `PO-1`, `SF-1` | Account-owned payment instruments with explicit per-subscription binding |
| [ADR 0045](./adr-0045-ordinary-login-factors-and-https-sensitive-action-step-up.md) | Accepted | `AA-1` | `AA-2`, `EA-3`, `SF-1` | Ordinary login factors with HTTPS-only sensitive-action step-up and gameplay handoff |
| [ADR 0046](./adr-0046-bounded-friend-presence-with-private-by-failure-redaction.md) | Accepted | `EA-2` | `AA-1`, `AA-2`, `SF-1` | Bounded friend presence with complete private-by-failure redaction |
| [ADR 0047](./adr-0047-logging-admin-as-external-operator-write-ingress.md) | Accepted | `PO-1` | `PO-2`, `GR-1`, `AR-3` | Logging and Admin as external operator-write ingress with domain-owned mutation authority |

Capability identifiers are defined in the [FireMUD Product Capability Taxonomy](../product-capability-taxonomy.md).

## Record Shape For New Decisions

New ADRs should include:

- status and decision dates;
- primary and affected capabilities;
- decision owner and consulted decision makers;
- context, constraints, assumptions, and decision drivers;
- the accepted decision stated without historical alternatives mixed into it;
- strongest credible alternatives, including doing nothing where meaningful;
- positive and negative consequences;
- reversibility, lock-in, exit strategy, and revisit triggers;
- security, operations, cost, and implementation/proof obligations;
- canonical design links; and
- structured supersession relationships when applicable.

Alternatives and rejected hypotheses are non-normative. The accepted decision and canonical architecture must remain the only current target-state contract.
