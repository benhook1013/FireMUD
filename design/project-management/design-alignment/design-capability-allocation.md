# Canonical Design Capability Allocation

Status: Complete and independently coverage-audited.

This ledger maps canonical design sources to the stable capabilities in the [FireMUD Product Capability Taxonomy](../../architecture/product-capability-taxonomy.md). It is an allocation and coverage artifact, not an alternative design authority.

## Allocation Rules

- Every canonical Markdown source under `design/architecture/**` receives one file-level primary capability unless it is an index, generated reference, template, diagram companion, or genuinely mixed canonical source.
- Mixed sources use heading-level rows for separately normative capability sections.
- Secondary capabilities identify required handoffs and review scope without duplicating primary ownership.
- References and runbooks map to the capability whose contract or operation they support.
- Ambiguous or missing taxonomy coverage is recorded as a gap rather than forced into the nearest category.

## Coverage Summary

| Source class | Discovered | Allocated | Ambiguous or gap | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Top-level architecture | 89 | 89 | 0 | 100% classified |
| Infrastructure | 6 | 6 | 0 | 100% classified |
| Generated references | 1 | 1 | 0 | 100% classified |
| Microservice architecture | 76 | 74 | 0; 2 explicit governance/template exemptions | 100% classified |
| Architecture decisions | 12 | 11 | 0; 1 registry exemption | 100% classified |
| **Total** | **184** | **181** | **0; 3 explicit exemptions** | **100% classified** |

## Allocation Ledger

| Design source | Heading or scope | Primary capability | Secondary handoffs | Source class | Notes or gap |
| --- | --- | --- | --- | --- | --- |
| [Microservice architecture allocation](./design-capability-allocation-microservices.md) | All 76 files under `design/architecture/microservices/**` | Per-source allocation | Per-source handoffs | Service design, contract, runtime/data, configuration, operations, and reference sources | Complete path-set coverage |
| [Architecture decision registry](../../architecture/decisions/README.md) | Registry plus 11 ADRs | Per-record allocation | Per-record affected capabilities | Decision record | The registry is an index; accepted, superseded, and withdrawn records remain distinguishable |
| [System architecture allocation](./design-capability-allocation-system.md) | All 89 direct architecture, 6 infrastructure, and 1 generated source | Per-source allocation | Per-source handoffs | Normative design, runbook, reference, index, and generated sources | Complete path-set coverage |

## Architecture Decision Allocation

| Design source | Primary capability | Secondary handoffs | Status or classification |
| --- | --- | --- | --- |
| `design/architecture/decisions/README.md` | Exempt | All capabilities represented by indexed ADRs | Decision registry/index |
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

## Unallocated Or Ambiguous Sources

No product-capability gap was found in the 76-file microservice corpus. Two files are deliberately exempt from product allocation:

- `design/architecture/microservices/service-documentation-structure.md` defines documentation governance.
- `design/architecture/microservices/service-template.md` is a documentation template.

The microservice root README is allocated to `PO-2` because its service map and traffic-surface rules define normative edge and operator-ingress behavior. Creating a product capability for documentation administration would pollute the product taxonomy. All remaining source classes are fully allocated, and no product-capability gap remains.
