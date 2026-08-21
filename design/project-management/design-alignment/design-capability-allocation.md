# Canonical Design Capability Allocation

Status: Complete and independently coverage-audited.

This ledger maps canonical product and architecture sources to the stable capabilities in the [FireMUD Product Capability Taxonomy](../../product/capability-taxonomy.md). It is an allocation and coverage artifact, not an alternative design authority.

## Validation And Proof References

This file intentionally owns the allocation validation history; do not duplicate allocation validation records in another ledger.

- Source-set allocation and the declared coverage summary are mechanically checked by [`check-design-capability-allocation.py`](../../../dev-tools/validation/check-design-capability-allocation.py); the complete gate contract is listed in the [design-alignment workstream](./README.md#automated-gates).
- Historical focused validation run on 2026-07-30 is retained as historical evidence: `python3 dev-tools/validation/check-design-capability-allocation.py` returned `design capability allocation passed: 225 sources (222 allocated, 3 explicit exemptions)`.
- Previous canonical validator evidence dated 2026-08-13: `design capability allocation passed: 267 sources (264 allocated, 3 explicit exemptions)`.
- Historical canonical validator evidence dated 2026-08-14 Pacific/Auckland (2026-08-13 UTC): `design capability allocation passed: 277 sources (274 allocated, 3 explicit exemptions)`.
- Historical Markdown/link validation on 2026-08-14 Pacific/Auckland (2026-08-13 UTC): `linkCheck` checked 4,574 links (4,536 OK, 0 errors, 38 excluded); `lintMarkdown` checked 454 files with 0 issues. The architecture contract suite and `git diff --check` also passed with no errors.
- Historical canonical proof evidence recorded for calendar date 2026-08-15 Pacific/Auckland; exact command execution timestamp is not retained: `design capability allocation passed: 284 sources (281 allocated, 3 explicit exemptions)`; `linkCheck` checked 4,769 links (4,731 OK, 0 errors, 38 excluded); `lintMarkdown` checked 461 files with 0 issues; the architecture contract suite passed 184 tests; and `git diff --check` passed with no errors.
- Current validation record on 2026-08-21 (Pacific/Auckland): ADR review `123` reviewed / `11` pre-formal; architecture contracts `184` tests; link check `5,470` links (`5,432` OK, `38` excluded, `0` errors); Markdown lint `486` files (`0` issues); `git diff --check` clean. The current allocation validator confirms `309` sources (`306` allocated, `3` explicit exemptions): `python3 dev-tools/validation/check-design-capability-allocation.py` returned `design capability allocation passed: 309 sources (306 allocated, 3 explicit exemptions)`. The six Packet 5 connection/output ADR records are allocated below.
- Runtime proof is not applicable to this documentation-only allocation change.
- Implementation and verification evidence do not belong in this allocation ledger. The initial cross-capability baseline is preserved in the frozen [capability implementation reconciliation snapshot](./capability-implementation-reconciliation.md); live status and focused-proof anchors are maintained in the permanent implementation trackers.

## Allocation Rules

- Every canonical Markdown source under `design/product/**` and `design/architecture/**` receives one primary allocation, normally at file scope. Product sources classify requirements, taxonomy, index, or observable product behavior; architecture sources classify normative design, runbook, reference, index, or generated material. Mixed architecture sources may instead allocate separate normative sections at heading scope; only documented governance, template, or registry/index artifacts are exempt.
- Secondary capabilities identify required handoffs and review scope without duplicating primary ownership.
- References and runbooks map to the capability whose contract or operation they support.
- Ambiguous or missing taxonomy coverage is recorded as a gap rather than forced into the nearest category.

## Coverage Summary

| Source class | Discovered | Allocated | Ambiguous or gap | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Top-level architecture | 83 | 83 | 0 | 100% classified |
| Infrastructure | 6 | 6 | 0 | 100% classified |
| Generated references | 2 | 2 | 0 | 100% classified |
| Microservice architecture | 76 | 74 | 0; 2 explicit governance/template exemptions | 100% classified |
| Architecture decisions | 135 | 134 | 0; 1 registry exemption | 100% classified |
| Product documentation | 7 | 7 | 0 | 100% classified |
| **Total** | **309** | **306** | **0; 3 explicit exemptions** | **100% classified** |

## Allocation Ledger

| Design source | Heading or scope | Primary capability | Secondary handoffs | Source class | Notes or gap |
| --- | --- | --- | --- | --- | --- |
| [Microservice architecture allocation](./design-capability-allocation-microservices.md) | All 76 files under `design/architecture/microservices/**` | Per-source allocation | Per-source handoffs | Service design, contract, runtime/data, configuration, operations, and reference sources | All 76 files are accounted for as 74 allocated sources plus 2 exempt governance/template files: `service-documentation-structure.md` and `service-template.md`; complete path-set coverage |
| [Architecture decision registry](../../architecture/decisions/README.md) | Registry plus 134 ADRs | Per-record allocation | Per-record affected capabilities | Decision record | The registry is an index; accepted, superseded, and withdrawn records remain distinguishable |
| [System architecture allocation](./design-capability-allocation-system.md) | All 83 direct architecture, 6 infrastructure, and 2 generated sources | Per-source allocation | Per-source handoffs | Normative design, runbook, reference, index, and generated sources | Complete path-set coverage |
| [Product documentation](../../product/README.md#canonical-sources) | All 7 files under `design/product/**` | Per-source allocation | Per-source product behavior scope | Requirements, taxonomy, index, and observable product behavior | Complete path-set coverage |

## Architecture Decision Allocation

| Design source | Primary capability | Secondary handoffs | Status or classification |
| --- | --- | --- | --- |
| `design/architecture/decisions/README.md` | Exempt | — | Decision registry/index |
| `design/architecture/decisions/adr-0001-scripting-event-ingress-idempotency-identity.md` | `AS-1` | `SF-1`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0002-automation-handoff-reliability-and-success-semantics.md` | `AS-1` | `GR-1`, `SF-2`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0003-reload-backpressure-and-retry-contract.md` | `AS-1` | `AR-3`, `GR-1`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0004-gameplay-reroute-vs-backend-unavailable.md` | `PO-2` | `AA-2`, `GR-1`, `PO-4` | Superseded by ADR 0007 |
| `design/architecture/decisions/adr-0005-tenant-identifiers-in-gameplay-protocol.md` | `AA-3` | `EA-1`, `SF-1` | Accepted |
| `design/architecture/decisions/adr-0006-gameplay-shard-routing-key-transport.md` | `PO-2` | `AA-3`, `GR-1`, `SF-1` | Withdrawn; superseded by ADR 0007 |
| `design/architecture/decisions/adr-0007-edge-sharding-and-close-taxonomy.md` | `PO-2` | `AA-2`, `GR-1`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0008-multi-cluster-gameplay-sharding-scope.md` | `GR-1` | `PO-2`, `PO-3`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0009-coordination-redis-ownership-boundary.md` | `SF-2` | `AA-2`, `GR-1`, `AS-1` | Accepted |
| `design/architecture/decisions/adr-0010-tcp-proxy-identity-canonicalization.md` | `SF-1` | `PO-2`, `PO-3` | Accepted |
| `design/architecture/decisions/adr-0011-gameplay-session-front-end-and-region-execution.md` | `GR-1` | `AA-2`, `SF-1`, `SF-2`, `PO-2` | Accepted |
| `design/architecture/decisions/adr-0012-settings-value-precedence-and-constraints.md` | `AR-2` | `EA-1`, `GR-1`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0013-bounded-invisible-non-edge-restart-recovery.md` | `GR-1` | `AA-2`, `PO-2`, `PO-4`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0014-phased-jwt-signing-key-rotation-and-readiness.md` | `SF-1` | `AA-1`, `PO-1`, `PO-3`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0015-online-backup-and-environment-wide-cold-start-recovery.md` | `PO-3` | `GR-1`, `PO-1`, `PO-4`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0016-canonical-gameplay-command-status-lifecycle.md` | `GR-1` | `AA-2`, `PO-4`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0017-capability-gated-operational-tracing.md` | `PO-4` | `AA-2`, `GR-1`, `SF-1` | Accepted |
| `design/architecture/decisions/adr-0018-declarative-production-gateway-routes.md` | `PO-2` | `AA-3`, `PO-1`, `PO-3`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0019-separate-active-session-resume-and-transcript-lifetimes.md` | `AA-2` | `AR-2`, `EA-3`, `GR-1`, `SF-1` | Accepted |
| `design/architecture/decisions/adr-0020-scoped-domain-and-operational-identifiers.md` | `SF-1` | `AR-1`, `GR-2`, `GR-3` | Accepted |
| `design/architecture/decisions/adr-0021-staged-player-authentication-and-gameplay-binding.md` | `AA-2` | `EA-3`, `PO-2`, `SF-1` | Accepted |
| `design/architecture/decisions/adr-0022-account-authority-and-gameplay-session-ownership.md` | `AA-1` | `AA-2`, `SF-1`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0023-central-route-authorization-governance.md` | `SF-1` | `AA-1`, `PO-1`, `PO-2`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0024-trusted-gameplay-workload-delegation.md` | `SF-1` | `GR-1`, `PO-3` | Accepted |
| `design/architecture/decisions/adr-0025-explicit-open-enrollment-membership.md` | `AA-1` | `AA-2`, `AA-3`, `EA-3` | Accepted |
| `design/architecture/decisions/adr-0026-global-roles-do-not-grant-gameplay-authority.md` | `AA-1` | `AA-2`, `EA-3`, `PO-1` | Accepted |
| `design/architecture/decisions/adr-0027-single-realm-admission-target.md` | `AA-3` | `AR-3`, `GR-1`, `GR-2` | Accepted |
| `design/architecture/decisions/adr-0028-differentiated-entitlement-freshness.md` | `AA-1` | `AA-2`, `AA-3`, `PO-4`, `SF-1` | Accepted |
| `design/architecture/decisions/adr-0029-single-use-gameplay-connect-token-carriage.md` | `PO-2` | `AA-2`, `SF-1` | Accepted |
| `design/architecture/decisions/adr-0030-risk-based-active-session-revocation.md` | `AA-1` | `AA-2`, `PO-1`, `GR-1` | Accepted |
| `design/architecture/decisions/adr-0031-revocation-safe-session-token-rotation-and-logout.md` | `AA-2` | `AA-1`, `GR-1`, `SF-1` | Accepted |
| `design/architecture/decisions/adr-0032-kubernetes-native-secret-delivery-without-mandatory-vault.md` | `SF-1` | `PO-1`, `PO-2`, `PO-3` | Accepted |
| `design/architecture/decisions/adr-0033-public-player-facing-telnet-requires-tls.md` | `PO-2` | `AA-1`, `AA-2`, `EA-3`, `SF-1` | Accepted |
| `design/architecture/decisions/adr-0034-layered-abuse-controls-without-attacker-triggered-account-locks.md` | `SF-1` | `AA-1`, `AA-2`, `PO-1`, `PO-2` | Accepted |
| `design/architecture/decisions/adr-0035-single-record-issued-token-registry.md` | `SF-1` | `AA-1`, `AA-2`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0036-monotonic-authority-generations-for-bulk-token-revocation.md` | `SF-1` | `AA-1`, `AA-2`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0037-fail-closed-token-authority-outages-with-bounded-active-gameplay.md` | `SF-1` | `AA-2`, `AA-3`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0038-explicit-jwt-profiles-and-mtls-workload-identity.md` | `SF-1` | `AA-1`, `AA-2`, `PO-2` | Accepted |
| `design/architecture/decisions/adr-0039-bounded-redis-operator-maintenance-surface.md` | `PO-1` | `PO-4`, `SF-1`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0040-account-global-control-login-and-explicit-tenant-selection.md` | `AA-1` | `EA-3`, `PO-1`, `SF-1` | Accepted |
| `design/architecture/decisions/adr-0041-shared-tenant-infrastructure-with-full-environment-isolation-gate.md` | `AA-1` | `GR-1`, `PO-3`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0042-global-account-and-tenant-scoped-game-relationships.md` | `AA-1` | `AA-2`, `AA-3`, `SF-2`, `PO-1` | Accepted |
| `design/architecture/decisions/adr-0043-global-account-lifecycle-and-bounded-erasure-workflow.md` | `AA-1` | `PO-1`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0044-account-owned-payment-instruments-with-explicit-subscription-binding.md` | `AA-1` | `PO-1`, `SF-1` | Accepted |
| `design/architecture/decisions/adr-0045-ordinary-login-factors-and-https-sensitive-action-step-up.md` | `AA-1` | `AA-2`, `EA-3`, `PO-1`, `SF-1` | Accepted |
| `design/architecture/decisions/adr-0046-bounded-friend-presence-with-private-by-failure-redaction.md` | `EA-2` | `AA-1`, `AA-2`, `SF-1` | Accepted |
| `design/architecture/decisions/adr-0047-logging-admin-as-external-operator-write-ingress.md` | `PO-1` | `AR-2`, `AR-3`, `GR-1`, `PO-2` | Accepted |
| `design/architecture/decisions/adr-0048-durable-idempotent-operator-write-execution.md` | `PO-1` | `GR-1`, `PO-4`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0049-optional-provider-specific-external-identity-linking.md` | `AA-1` | `EA-3`, `SF-1` | Accepted |
| `design/architecture/decisions/adr-0050-versioned-export-retention-and-erasure-policy.md` | `AA-1` | `AA-2`, `PO-1`, `PO-3`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0051-separate-actor-action-and-effect-lanes.md` | `GR-1` | `GR-4`, `AS-1` | Accepted |
| `design/architecture/decisions/adr-0052-redis-liveness-lease-with-durable-executor-fence.md` | `GR-1` | `SF-2`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0053-command-atomicity-by-invariant-class.md` | `SF-2` | `GR-1`, `GR-2`, `GR-3`, `GR-4` | Accepted |
| `design/architecture/decisions/adr-0054-split-spatial-authority-with-causal-read-composition.md` | `SF-2` | `GR-1`, `GR-2`, `GR-3`, `GR-4`, `SF-1` | Accepted |
| `design/architecture/decisions/adr-0055-durable-cross-region-effects-with-static-live-topology.md` | `GR-2` | `GR-1`, `SF-2`, `AA-3`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0056-one-hot-path-fan-out-owner.md` | `GR-1` | `GR-2`, `GR-3`, `SF-2`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0057-game-session-owned-reconciliation-with-isolated-workers.md` | `GR-1` | `SF-2`, `GR-2`, `GR-3`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0058-class-specific-redis-loss-outcomes.md` | `SF-2` | `PO-3`, `GR-1`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0059-causal-floor-cross-service-presentation-reads.md` | `SF-1` | `SF-2`, `GR-2`, `GR-3` | Accepted |
| `design/architecture/decisions/adr-0060-world-owned-ambient-facts-and-logic-owned-consequences.md` | `GR-2` | `GR-4`, `GR-1`, `SF-2`, `PO-1` | Accepted |
| `design/architecture/decisions/adr-0061-single-owner-spatial-mutations-across-split-authority.md` | `GR-2` | `GR-3`, `GR-4`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0062-layered-gameplay-command-delivery-semantics.md` | `SF-1` | `SF-2`, `GR-1`, `AA-2`, `PO-2` | Accepted |
| `design/architecture/decisions/adr-0063-durable-per-dispatch-script-handoff.md` | `AS-1` | `SF-2`, `GR-1`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0064-stage-qualified-script-outcomes.md` | `AS-1` | `PO-4`, `SF-2`, `SF-1` | Accepted |
| `design/architecture/decisions/adr-0065-deterministic-fair-entity-tick-scheduling.md` | `GR-1` | `SF-2`, `GR-4`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0066-durable-asynchronous-cross-region-result-arbitration.md` | `GR-1` | `SF-2`, `GR-2`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0067-abandon-old-epoch-work-and-reschedule-with-new-lineage.md` | `GR-1` | `SF-2`, `GR-2`, `AS-1`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0068-evidence-derived-bounded-tick-ledger-recovery.md` | `PO-4` | `GR-1`, `SF-2`, `PO-1`, `SF-1` | Accepted |
| `design/architecture/decisions/adr-0069-at-least-once-effect-execution-with-one-logical-terminal-outcome.md` | `GR-1` | `SF-2`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0070-bounded-within-tick-visibility-by-semantic-phase.md` | `GR-1` | `GR-2`, `GR-4`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0071-durable-tick-commit-before-fenced-coordination-cleanup.md` | `GR-1` | `SF-2`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0072-class-specific-timer-durability-and-recovery.md` | `AS-1` | `GR-1`, `GR-2`, `SF-1` | Accepted |
| `design/architecture/decisions/adr-0073-evidence-calibrated-tick-budgets-and-lock-ttls.md` | `SF-1` | `GR-1`, `PO-4`, `AR-2` | Accepted |
| `design/architecture/decisions/adr-0074-one-entity-lock-per-redis-script.md` | `GR-1` | `SF-2`, `GR-4`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0075-depth-cost-and-count-bounds-for-generated-effect-chains.md` | `GR-4` | `GR-1`, `AS-1`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0076-failure-class-specific-durable-tick-retries.md` | `GR-1` | `PO-2`, `PO-4`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0077-durable-global-effect-fanout-and-lightweight-idle-ticks.md` | `GR-1` | `GR-2`, `AS-1`, `SF-1`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0078-digest-bound-workflow-and-step-retry-identities.md` | `SF-2` | `SF-1` | Accepted |
| `design/architecture/decisions/adr-0079-jooq-and-flyway-as-the-single-sql-persistence-stack.md` | `SF-2` | `SF-1`, `PO-3` | Accepted |
| `design/architecture/decisions/adr-0080-service-owned-schemas-with-adopter-local-shared-migrations.md` | `SF-2` | `PO-3` | Accepted |
| `design/architecture/decisions/adr-0081-objective-compatibility-gates-for-database-evolution.md` | `SF-2` | `AR-3`, `PO-3` | Accepted |
| `design/architecture/decisions/adr-0082-semantic-boundary-for-cross-service-identifier-migration.md` | `SF-2` | `AR-1`, `AR-3` | Accepted |
| `design/architecture/decisions/adr-0083-no-general-event-broker-until-measured-adoption-gates.md` | `SF-2` | `SF-1`, `PO-4`, `AS-1`, `GR-1` | Accepted |
| `design/architecture/decisions/adr-0084-evidence-scoped-redis-lua-compatibility.md` | `SF-2` | `SF-1`, `GR-1`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0085-evidence-gated-coordination-replay-and-fenced-reset.md` | `SF-2` | `PO-4`, `PO-3`, `GR-1` | Accepted |
| `design/architecture/decisions/adr-0086-owner-validated-class-a-caches-and-presentation-only-class-b.md` | `SF-2` | `GR-2`, `GR-3`, `EA-1`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0087-isolated-subject-rate-limits-with-explicit-loss-semantics.md` | `SF-2` | `PO-2`, `AA-1`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0088-static-and-incremental-script-output-bounds.md` | `AR-1` | `AS-1`, `GR-4` | Accepted |
| `design/architecture/decisions/adr-0089-durable-script-usage-charges-and-fenced-capacity-leases.md` | `AS-1` | `AR-2`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0090-recorded-script-input-manifests-for-reproducible-evaluation.md` | `AS-1` | `SF-2`, `GR-1`, `SF-1`, `AR-1` | Accepted |
| `design/architecture/decisions/adr-0091-class-specific-script-timer-clocks-and-recovery.md` | `AS-1` | `GR-1`, `SF-2`, `SF-1`, `AR-3` | Accepted |
| `design/architecture/decisions/adr-0092-grpc-status-and-typed-domain-outcome-boundary.md` | `SF-1` | `GR-1`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0093-game-design-coordinated-digest-attested-content-publication.md` | `AR-1` | `AR-3`, `PO-3`, `GR-2`, `AA-3` | Accepted |
| `design/architecture/decisions/adr-0094-explicit-cohesive-runtime-release-tuples.md` | `AR-3` | `AR-1`, `AA-3`, `GR-1`, `PO-3` | Accepted |
| `design/architecture/decisions/adr-0095-content-addressed-published-assets-with-cas-lifecycle-authority.md` | `AR-1` | `AR-3`, `PO-3`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0096-attested-publication-gate-and-quarantined-failed-assets.md` | `AR-3` | `AR-1`, `PO-3`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0097-git-and-ci-validated-single-operator-promotion-evidence.md` | `PO-3` | `PO-4`, `SF-1` | Accepted |
| `design/architecture/decisions/adr-0098-request-bounded-generation-replay-and-explicit-regeneration.md` | `AR-1` | `AR-3`, `GR-2`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0099-bounded-atomic-generation-with-staging-for-large-outputs.md` | `GR-2` | `GR-1`, `SF-2`, `AS-1` | Accepted |
| `design/architecture/decisions/adr-0100-separate-generation-ingress-with-one-world-owned-engine.md` | `AR-1` | `AR-3`, `GR-2`, `AS-1` | Accepted |
| `design/architecture/decisions/adr-0101-explicit-destructive-regeneration-with-previewed-scope.md` | `AR-1` | `AR-2`, `GR-2` | Accepted |
| `design/architecture/decisions/adr-0102-first-class-sparse-and-full-grid-world-topologies.md` | `GR-2` | `AR-1`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0103-single-authority-script-pins-with-exact-version-execution.md` | `AS-1` | `AR-3`, `GR-1` | Accepted |
| `design/architecture/decisions/adr-0106-epoch-fenced-script-rollback-without-routine-gameplay-pause.md` | `AR-3` | `AS-1`, `GR-1`, `SF-2`, `PO-1`, `AA-2` | Accepted |
| `design/architecture/decisions/adr-0107-stage-aware-script-dead-letter-recovery.md` | `AS-1` | `PO-1`, `AR-3`, `SF-2`, `GR-1` | Accepted |
| `design/architecture/decisions/adr-0108-no-degraded-script-admission-without-authoritative-pin.md` | `AR-3` | `AS-1`, `SF-1`, `PO-1` | Accepted |
| `design/architecture/decisions/adr-0109-game-session-owned-script-rollout-history.md` | `GR-1` | `AS-1`, `AR-3`, `SF-1`, `SF-2`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0110-explicit-opt-in-schedule-continuity-across-script-transitions.md` | `AR-3` | `AS-1`, `AR-1`, `GR-1`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md` | `AS-1` | `AR-1`, `GR-4`, `SF-1` | Accepted |
| `design/architecture/decisions/adr-0112-typed-bounded-gameplay-effect-extension.md` | `GR-4` | `GR-3`, `AR-1`, `AR-3`, `SF-1`, `AS-1` | Accepted |
| `design/architecture/decisions/adr-0113-bounded-pull-settings-distribution-with-freshness-classes.md` | `AR-2` | `SF-2`, `GR-1` | Accepted |
| `design/architecture/decisions/adr-0114-command-plan-preview-dry-run-isolation.md` | `AS-1` | `AR-3`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0115-manifest-complete-onload-readiness-without-durable-game-initialization.md` | `AS-1` | `AR-1`, `GR-1` | Accepted |
| `design/architecture/decisions/adr-0116-routine-component-migration-and-explicit-emergency-revocation.md` | `AS-1` | `AR-1`, `PO-1` | Accepted |
| `design/architecture/decisions/adr-0117-producer-owned-event-schemas-with-one-materialized-catalogue.md` | `AS-1` | `AR-1`, `SF-1`, `PO-4`, `GR-1` | Accepted |
| `design/architecture/decisions/adr-0118-preselected-exclusive-handlers-and-durable-fanout-ordering.md` | `AS-1` | `AR-1`, `PO-1` | Accepted |
| `design/architecture/decisions/adr-0119-epoch-fenced-per-instance-plugin-activation.md` | `AS-1` | `AR-3`, `SF-1`, `AR-1`, `GR-1` | Accepted |
| `design/architecture/decisions/adr-0120-owner-read-first-control-plane-notifications.md` | `SF-1` | `AS-1`, `AR-3`, `PO-1`, `PO-4`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0121-historical-broad-dry-run-semantics.md` | `AS-1` | `PO-1`, `PO-2`, `PO-4` | Superseded |
| `design/architecture/decisions/adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md` | `AR-3` | `GR-2`, `GR-3`, `SF-2`, `PO-1` | Accepted |
| `design/architecture/decisions/adr-0123-database-authoritative-temporal-coordinated-world-lifecycle.md` | `AR-3` | `SF-2`, `GR-2`, `PO-1`, `GR-1`, `AS-1` | Accepted |
| `design/architecture/decisions/adr-0124-materialized-starter-profiles-with-conservative-draft-upgrades.md` | `AR-2` | `AR-1`, `AR-3`, `GR-4`, `EA-3` | Accepted |
| `design/architecture/decisions/adr-0125-defer-whole-game-portability-and-external-authoring-formats.md` | `AR-1` | `AR-3`, `PO-3`, `SF-2`, `EA-3` | Accepted; review disposition Deferred |
| `design/architecture/decisions/adr-0126-untrusted-models-and-scoped-authoring-tools.md` | `AR-1` | `AS-1`, `SF-1`, `EA-3`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0127-game-authored-equipment-layouts-with-fail-closed-publication.md` | `GR-3` | `AR-1`, `AR-3`, `GR-1`, `SF-1` | Accepted |
| `design/architecture/decisions/adr-0128-game-design-plugin-trust-provenance.md` | `AR-1` | `AS-1`, `SF-1`, `GR-4` | Accepted |
| `design/architecture/decisions/adr-0129-durable-fenced-multi-owner-draft-commits.md` | `AR-1` | `AR-3`, `SF-2`, `EA-3` | Accepted |
| `design/architecture/decisions/adr-0130-historical-equipment-body-layout-authority.md` | `GR-3` | `AR-1`, `GR-1`, `SF-1` | Superseded |
| `design/architecture/decisions/adr-0131-lifecycle-distinct-gameplay-close-taxonomy.md` | `PO-2` | `AA-2`, `GR-1`, `PO-4` | Accepted |
| `design/architecture/decisions/adr-0132-namespace-scoped-single-character-controller.md` | `AA-2` | `EA-3`, `SF-2`, `AR-3` | Accepted |
| `design/architecture/decisions/adr-0133-fresh-edge-reconnect-without-client-input-replay.md` | `AA-2` | `EA-1`, `PO-2`, `GR-1`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0134-bounded-durable-semantic-reconnect-context.md` | `EA-1` | `AA-2`, `SF-2` | Accepted |
| `design/architecture/decisions/adr-0135-compact-versioned-player-output-and-late-rendering.md` | `EA-1` | `EA-3`, `PO-2`, `SF-1` | Accepted |
| `design/architecture/decisions/adr-0136-future-compatible-localization-boundary.md` | `EA-1` | `EA-3`, `AR-1` | Accepted |

ADR 0121 is superseded by [ADR 0114](../../architecture/decisions/adr-0114-command-plan-preview-dry-run-isolation.md); its historical capability allocation remains recorded above.

## Product Documentation Allocation

Product sources define requirements and observable product behavior. Their allocation chooses the nearest product-facing capability lens without changing the technical authority of linked architecture contracts.

| Path | Primary | Classification | Product scope |
| --- | --- | --- | --- |
| [design/product/README.md](../../product/README.md) | `SF-1` | index | Product documentation navigation and authority boundaries across the platform |
| [design/product/requirements.md](../../product/requirements.md) | `SF-1` | requirements | Canonical platform-wide product requirements and intended outcomes |
| [design/product/capability-taxonomy.md](../../product/capability-taxonomy.md) | `SF-1` | taxonomy | Stable capability identifiers and product-oriented allocation boundaries |
| [design/product/user-journeys/overview.md](../../product/user-journeys/overview.md) | `EA-3` | observable product behavior | Cross-persona journey navigation and product behavior lens |
| [design/product/user-journeys/players.md](../../product/user-journeys/players.md) | `AA-2` | observable product behavior | Player account, admission, gameplay, social, commerce, and data-rights outcomes |
| [design/product/user-journeys/creators.md](../../product/user-journeys/creators.md) | `AR-1` | observable product behavior | Creator authoring, publishing, playtesting, customization, and live-update outcomes |
| [design/product/user-journeys/operators.md](../../product/user-journeys/operators.md) | `PO-1` | observable product behavior | Operator moderation, recovery, deployment, observability, and platform-update outcomes |

## Unallocated Or Ambiguous Sources

No product-capability gap was found in the covered source classes. Three files are deliberately exempt from product allocation:

- The [architecture decision registry](../../architecture/decisions/README.md) is an index rather than a product capability.

- `design/architecture/microservices/service-documentation-structure.md` defines documentation governance.
- `design/architecture/microservices/service-template.md` is a documentation template.

The microservice root README is allocated to `PO-2` because its service map and traffic-surface rules define normative edge and operator-ingress behavior. Creating a product capability for documentation administration would pollute the product taxonomy. All remaining source classes are fully allocated, and no product-capability gap remains.
