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
| [ADR 0048](./adr-0048-durable-idempotent-operator-write-execution.md) | Accepted | `PO-1` | `SF-2`, `GR-1`, `PO-4` | Durable idempotent operator-write execution with owner-local commit and fenced forwarding |
| [ADR 0049](./adr-0049-optional-provider-specific-external-identity-linking.md) | Accepted | `AA-1` | `EA-3`, `SF-1` | Optional provider-specific external identity linking with Account-owned recovery |
| [ADR 0050](./adr-0050-versioned-export-retention-and-erasure-policy.md) | Accepted | `AA-1` | `PO-1`, `PO-3`, `SF-2` | Versioned cross-service export and finite category-specific retention and erasure policy |
| [ADR 0051](./adr-0051-separate-actor-action-and-effect-lanes.md) | Accepted | `GR-1` | `GR-4`, `AS-1` | Separate deterministic actor-action and passive/inbound-effect tick lanes |
| [ADR 0052](./adr-0052-redis-liveness-lease-with-durable-executor-fence.md) | Accepted | `GR-1` | `SF-2`, `PO-4` | Redis region liveness lease installed and revalidated against a durable executor fence |
| [ADR 0053](./adr-0053-command-atomicity-by-invariant-class.md) | Accepted | `SF-2` | `GR-1`, `GR-2`, `GR-3`, `GR-4` | Local idempotent gameplay effects with stronger atomicity selected by invariant class |
| [ADR 0054](./adr-0054-split-spatial-authority-with-causal-read-composition.md) | Accepted | `SF-2` | `GR-1`, `GR-2`, `GR-3`, `GR-4` | Split spatial authority with operation-bound effects and causal presentation reads |
| [ADR 0055](./adr-0055-durable-cross-region-effects-with-static-live-topology.md) | Accepted | `GR-2` | `GR-1`, `SF-2`, `PO-4` | Durable asynchronous cross-region effects with static live topology and maintenance cutover |
| [ADR 0056](./adr-0056-one-hot-path-fan-out-owner.md) | Accepted | `GR-1` | `GR-2`, `GR-3`, `SF-2`, `PO-4` | One hot-path fan-out owner with at most two authoritative participants |
| [ADR 0057](./adr-0057-game-session-owned-reconciliation-with-isolated-workers.md) | Accepted | `GR-1` | `SF-2`, `GR-2`, `GR-3`, `PO-4` | Game Session-owned effect reconciliation with independently scalable isolated workers |
| [ADR 0058](./adr-0058-class-specific-redis-loss-outcomes.md) | Accepted | `SF-2` | `GR-1`, `PO-3`, `PO-4` | PostgreSQL-backed durable intent with class-specific outcomes after Redis loss |
| [ADR 0059](./adr-0059-causal-floor-cross-service-presentation-reads.md) | Accepted | `SF-1` | `SF-2`, `GR-2`, `GR-3` | Common causal floor with distinct component versions for presentation reads |
| [ADR 0060](./adr-0060-world-owned-ambient-facts-and-logic-owned-consequences.md) | Accepted | `GR-2` | `GR-1`, `GR-4`, `SF-2`, `PO-1` | World-owned ambient facts with Logic-owned consequences and durable effect admission |
| [ADR 0061](./adr-0061-single-owner-spatial-mutations-across-split-authority.md) | Accepted | `GR-2` | `GR-3`, `GR-4`, `SF-2` | Single-owner spatial mutations across split World and Entity authority |
| [ADR 0062](./adr-0062-layered-gameplay-command-delivery-semantics.md) | Accepted | `SF-1` | `SF-2`, `GR-1`, `AA-2`, `PO-2` | Layered edge, accepted-command, outbound, and internal-event delivery semantics |
| [ADR 0063](./adr-0063-durable-per-dispatch-script-handoff.md) | Accepted | `AS-1` | `SF-2`, `GR-1`, `PO-4` | PostgreSQL-authoritative script work items with durable per-command dispatch children |
| [ADR 0064](./adr-0064-stage-qualified-script-outcomes.md) | Accepted | `AS-1` | `PO-4`, `SF-2`, `SF-1` | Stage-qualified scripting outcomes with authoritative per-command gameplay results |
| [ADR 0065](./adr-0065-deterministic-fair-entity-tick-scheduling.md) | Accepted | `GR-1` | `SF-2`, `GR-4`, `PO-4` | Deterministic fair per-entity scheduling with one in-flight tick through cleanup |
| [ADR 0066](./adr-0066-durable-asynchronous-cross-region-result-arbitration.md) | Accepted | `GR-1` | `SF-2`, `GR-2`, `PO-4` | Durable asynchronous cross-region result arbitration with immutable terminal outcomes |
| [ADR 0067](./adr-0067-abandon-old-epoch-work-and-reschedule-with-new-lineage.md) | Accepted | `GR-1` | `SF-2`, `GR-2`, `AS-1`, `PO-4` | Old-epoch work abandoned immutably and reconstructed only with a new lineage-linked identity |
| [ADR 0068](./adr-0068-evidence-derived-bounded-tick-ledger-recovery.md) | Accepted | `PO-4` | `GR-1`, `SF-2`, `PO-1`, `SF-1` | Evidence-derived convergence SLO with bounded fair Game Session-owned ledger recovery |
| [ADR 0069](./adr-0069-at-least-once-effect-execution-with-one-logical-terminal-outcome.md) | Accepted | `GR-1` | `SF-2`, `PO-4` | At-least-once physical effect attempts with one guarded logical mutation and terminal outcome |
| [ADR 0070](./adr-0070-bounded-within-tick-visibility-by-semantic-phase.md) | Accepted | `GR-1` | `GR-2`, `GR-4`, `SF-2` | Bounded within-tick visibility across passive, root-actor, and parent-generated phases |
| [ADR 0071](./adr-0071-durable-tick-commit-before-fenced-coordination-cleanup.md) | Accepted | `GR-1` | `SF-2`, `PO-4` | Durable tick commit before exact fenced Redis coordination cleanup |
| [ADR 0072](./adr-0072-class-specific-timer-durability-and-recovery.md) | Accepted | `AS-1` | `GR-1`, `GR-2`, `SF-1` | Class-specific timer durability, missed-occurrence policy, and bounded recovery |
| [ADR 0073](./adr-0073-evidence-calibrated-tick-budgets-and-lock-ttls.md) | Accepted | `SF-1` | `GR-1`, `PO-4`, `AR-2` | Evidence-calibrated tick budgets and lock TTLs with shared bounded bootstrap defaults |
| [ADR 0074](./adr-0074-one-entity-lock-per-redis-script.md) | Accepted | `GR-1` | `SF-2`, `GR-4`, `PO-4` | Hard initial one-entity-lock boundary for Redis tick scripts |
| [ADR 0075](./adr-0075-depth-cost-and-count-bounds-for-generated-effect-chains.md) | Accepted | `GR-4` | `GR-1`, `AS-1`, `PO-4` | Deterministic depth, count, cost, and per-target bounds for generated effect chains |
| [ADR 0076](./adr-0076-failure-class-specific-durable-tick-retries.md) | Accepted | `GR-1` | `PO-2`, `PO-4`, `SF-2` | Failure-class-specific durable retries through the deterministic fair tick scheduler |
| [ADR 0077](./adr-0077-durable-global-effect-fanout-and-lightweight-idle-ticks.md) | Accepted | `GR-1` | `GR-2`, `AS-1`, `SF-1`, `PO-4` | Durable bounded global-effect fan-out with lightweight physical idle ticks |
| [ADR 0078](./adr-0078-digest-bound-workflow-and-step-retry-identities.md) | Accepted | `SF-2` | `SF-2`, `SF-1` | Digest-bound stable workflow and logical-step identities across retries and run replacement |
| [ADR 0079](./adr-0079-jooq-and-flyway-as-the-single-sql-persistence-stack.md) | Accepted | `SF-2` | `SF-1`, `PO-3` | Flyway-owned schema evolution with generated jOOQ persistence by default and a bounded SQL escape hatch |
| [ADR 0080](./adr-0080-service-owned-schemas-with-adopter-local-shared-migrations.md) | Accepted | `SF-2` | `SF-2`, `PO-3` | Service-owned schemas and Flyway histories with adopter-local reusable shared migrations |
| [ADR 0081](./adr-0081-objective-compatibility-gates-for-database-evolution.md) | Accepted | `SF-2` | `AR-3`, `PO-3` | Objective binary-overlap and retained-data gates for direct replacement or expand/migrate/contract |
| [ADR 0082](./adr-0082-semantic-boundary-for-cross-service-identifier-migration.md) | Accepted | `SF-2` | `SF-2`, `AR-1`, `AR-3` | Preserve IDs across representation changes and map new identities for semantic replacement, scope change, split, or merge |
| [ADR 0083](./adr-0083-no-general-event-broker-until-measured-adoption-gates.md) | Accepted | `SF-2` | `SF-1`, `PO-4`, `AS-1`, `GR-1` | PostgreSQL outbox delivery without a general broker until measured adoption gates are crossed |
| [ADR 0084](./adr-0084-evidence-scoped-redis-lua-compatibility.md) | Accepted | `SF-2` | `SF-1`, `GR-1`, `PO-4` | Redis Lua compatibility scoped to evidenced caller and payload coexistence |
| [ADR 0085](./adr-0085-evidence-gated-coordination-replay-and-fenced-reset.md) | Accepted | `SF-2` | `PO-4`, `PO-3`, `GR-1` | Evidence-gated coherent replay with externally fenced smallest-complete-scope reset |
| [ADR 0086](./adr-0086-owner-validated-class-a-caches-and-presentation-only-class-b.md) | Accepted | `SF-2` | `GR-2`, `GR-3`, `EA-1`, `PO-4` | Owner-validated Class A read acceleration with presentation-only disposable Class B caches |
| [ADR 0087](./adr-0087-isolated-subject-rate-limits-with-explicit-loss-semantics.md) | Accepted | `SF-2` | `PO-2`, `AA-1`, `PO-4` | One-to-one subject rate-limit buckets with bounded cardinality and explicit loss semantics |
| [ADR 0088](./adr-0088-static-and-incremental-script-output-bounds.md) | Accepted | `AR-1` | `AS-1`, `GR-4` | Versioned static output-cost analysis plus incremental metering and atomic handler output persistence |
| [ADR 0089](./adr-0089-durable-script-usage-charges-and-fenced-capacity-leases.md) | Accepted | `AS-1` | `AR-2`, `PO-4` | Durable Trigger-keyed usage charges separated from fenced reclaimable sandbox-capacity leases |
| [ADR 0090](./adr-0090-recorded-script-input-manifests-for-reproducible-evaluation.md) | Accepted | `AS-1` | `SF-2`, `GR-1`, `SF-1`, `AR-1` | Durable owner-versioned input manifests for reproducible script evaluation and retry |
| [ADR 0091](./adr-0091-class-specific-script-timer-clocks-and-recovery.md) | Accepted | `AS-1` | `GR-1`, `SF-2`, `SF-1`, `AR-3` | Class-specific script timer clocks, bounded recurring recovery, and durable correctness one-shots |
| [ADR 0092](./adr-0092-grpc-status-and-typed-domain-outcome-boundary.md) | Accepted | `SF-1` | `SF-1`, `GR-1`, `PO-4` | Canonical gRPC status for request/infrastructure failure with typed successful domain outcomes |
| [ADR 0093](./adr-0093-game-design-coordinated-digest-attested-content-publication.md) | Accepted | `AR-1` | `AR-1`, `AR-3`, `PO-3`, `GR-2`, `AA-3` | Game Design-coordinated publication with domain-owned templates and actual-content digest attestation |
| [ADR 0094](./adr-0094-explicit-cohesive-runtime-release-tuples.md) | Accepted | `AR-3` | `AR-1`, `AR-3`, `AA-3`, `GR-1`, `PO-3` | Explicit immutable base-release, patch, and plugin tuples with one-time channel resolution |
| [ADR 0095](./adr-0095-content-addressed-published-assets-with-cas-lifecycle-authority.md) | Accepted | `AR-1` | `AR-1`, `AR-3`, `PO-3`, `SF-2` | Content-addressed immutable published assets with mandatory byte digests and CAS lifecycle authority |
| [ADR 0096](./adr-0096-attested-publication-gate-and-quarantined-failed-assets.md) | Accepted | `AR-3` | `AR-1`, `PO-3`, `SF-2` | Only fully attested Published assets are launchable; failed candidates remain privately quarantined with bounded retention |
| [ADR 0097](./adr-0097-git-and-ci-validated-single-operator-promotion-evidence.md) | Accepted | `PO-3` | `PO-4`, `SF-1` | Machine-generated, Git-reviewed, CI-validated single-operator promotion evidence with explicit upgrade triggers |
| [ADR 0098](./adr-0098-request-bounded-generation-replay-and-explicit-regeneration.md) | Accepted | `AR-1` | `AR-3`, `GR-2`, `SF-2` | Request-bounded generator compatibility with committed-topology authority and explicit newest-policy regeneration |
| [ADR 0099](./adr-0099-bounded-atomic-generation-with-staging-for-large-outputs.md) | Accepted | `GR-2` | `GR-1`, `SF-2`, `AS-1` | Atomic generation visibility through bounded local transactions or digest-checked staging for large output |
| [ADR 0100](./adr-0100-single-authority-script-pins-with-exact-version-execution.md) | Accepted | `AS-1` | `AS-1`, `AR-3`, `GR-1` | Game Session-authoritative script pins with exact-version execution, epoch fencing, and explicit rollback |
| [ADR 0101](./adr-0101-stable-playable-state-namespaces-for-runtime-replacement.md) | Accepted | `AR-3` | `GR-2`, `GR-3`, `AR-3`, `SF-2` | Stable playable-state namespaces with explicit preservation, owner-applied mappings, and fail-closed replacement |
| [ADR 0102](./adr-0102-database-authoritative-temporal-coordinated-world-lifecycle.md) | Accepted | `AR-3` | `SF-2`, `GR-2`, `PO-1` | Database-authoritative fenced world lifecycle with Temporal coordination and all-owner cleanup convergence |
| [ADR 0103](./adr-0103-epoch-fenced-script-rollback-without-routine-gameplay-pause.md) | Accepted | `AR-3` | `AS-1`, `GR-1`, `SF-2`, `PO-1`, `AA-2` | Script-epoch-fenced rollback with scoped Automation pause and uninterrupted ordinary gameplay |
| [ADR 0104](./adr-0104-stage-aware-script-dead-letter-recovery.md) | Accepted | `AS-1` | `PO-1`, `AR-3`, `SF-2`, `GR-1` | Stage-aware dead-letter recovery with frozen-input retry and stored-dispatch continuation |
| [ADR 0105](./adr-0105-no-degraded-script-admission-without-authoritative-pin.md) | Accepted | `AR-3` | `AS-1`, `SF-1`, `PO-1` | Fail-closed Automation admission without a stale-pin operator override |
| [ADR 0106](./adr-0106-game-session-owned-script-rollout-history.md) | Accepted | `AS-1` | `AR-3`, `SF-1`, `SF-2`, `PO-4` | Game Session-owned exact pin and append-only rollout history with Automation-only convergence projection |
| [ADR 0107](./adr-0107-explicit-opt-in-schedule-continuity-across-script-transitions.md) | Accepted | `AR-3` | `AS-1`, `AR-1`, `GR-1`, `SF-2` | Default-reset interval transitions with explicit stable-owner schedule continuity |

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
