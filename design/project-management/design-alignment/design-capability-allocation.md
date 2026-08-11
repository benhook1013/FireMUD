# Canonical Design Capability Allocation

Status: Complete and independently coverage-audited.

This ledger maps canonical product and architecture sources to the stable capabilities in the [FireMUD Product Capability Taxonomy](../../product/capability-taxonomy.md). It is an allocation and coverage artifact, not an alternative design authority.

## Validation And Proof References

- Source-set allocation and the declared coverage summary are mechanically checked by [`check-design-capability-allocation.py`](../../../dev-tools/validation/check-design-capability-allocation.py); the complete gate contract is listed in the [design-alignment workstream](./README.md#automated-gates).
- Historical focused validation run on 2026-07-30 is retained as historical evidence: `python3 dev-tools/validation/check-design-capability-allocation.py` returned `design capability allocation passed: 225 sources (222 allocated, 3 explicit exemptions)`.
- Current canonical validator evidence dated 2026-08-11: `design capability allocation passed: 229 sources (226 allocated, 3 explicit exemptions)`.
- Markdown/link validation on 2026-07-31: `linkCheck` checked 3,496 links with 0 errors; `lintMarkdown` checked 407 files with 0 issues.
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
| Architecture decisions | 55 | 54 | 0; 1 registry exemption | 100% classified |
| Product documentation | 7 | 7 | 0 | 100% classified |
| **Total** | **229** | **226** | **0; 3 explicit exemptions** | **100% classified** |

## Allocation Ledger

| Design source | Heading or scope | Primary capability | Secondary handoffs | Source class | Notes or gap |
| --- | --- | --- | --- | --- | --- |
| [Microservice architecture allocation](./design-capability-allocation-microservices.md) | All 76 files under `design/architecture/microservices/**` | Per-source allocation | Per-source handoffs | Service design, contract, runtime/data, configuration, operations, and reference sources | All 76 files are accounted for as 74 allocated sources plus 2 exempt governance/template files: `service-documentation-structure.md` and `service-template.md`; complete path-set coverage |
| [Architecture decision registry](../../architecture/decisions/README.md) | Registry plus 54 ADRs | Per-record allocation | Per-record affected capabilities | Decision record | The registry is an index; accepted, superseded, and withdrawn records remain distinguishable |
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
