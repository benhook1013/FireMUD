# Consequential Design Decision Inventory

Status: Inventory and human-led review are complete and independently coverage/fidelity-audited. All 182 historical archive decision keys and the three post-archive direct human decisions now have applied review provenance, for 185 current decision keys. Packet 6 is complete at 25 applied outcomes through ADRs 0151-0167. Packet 7 is complete at 17 applied outcomes through ADRs 0168-0178 plus clarifications to existing authority. The direct decisions `COMMERCE-02`, `HOSTED-TERMS-01`, and `HOSTED-TERMS-02` are applied through ADRs 0179-0181 without changing historical Packet 1-7 totals. The separately tracked service-scan `MS-AA-TOKEN-REVOCATION` row remains the only excluded navigation alias. ADR numbering records how outcomes were materialized but does not define the application boundary; the planned whole-corpus authority review follows this completed decision integration.

## Implementation Status

`Complete` applies to inventory coverage, the human-owned review run, selective import of all `182` historical archive decision keys, and integration of the three direct post-archive decisions; it does not claim runtime implementation or proof. The checked provenance below records reviewed outcomes present in this repository state. Canonical design owns the target state; accepted ADRs provide consequential rationale within that boundary, while the domain trackers define implementation and proof status. Decision integration is complete; remaining work is the planned whole-corpus authority review.

This inventory identifies important explicit and implicit product and architecture decisions in canonical FireMUD design. It preserves the evidence and merged provenance for the completed human-led review. Automated work on this inventory must not accept, reject, supersede, or resolve a future decision; accepted target state remains in canonical design and consequential rationale belongs in an ADR.

## Decision Threshold

Inventory a decision when at least one of these applies:

- it establishes authority or ownership across services or product domains;
- it is expensive or disruptive to reverse;
- it materially affects security, tenant isolation, durability, consistency, operations, cost, or player/creator experience;
- it constrains future extensibility or the soft-configured game model;
- it has a credible competing target state;
- current design asserts it without sufficient rationale or explicit human consultation; or
- different canonical sources imply competing choices.

Routine implementation mechanics that do not affect the target-state contract are excluded.

## Status Vocabulary

| Status | Meaning |
| --- | --- |
| `accepted-explicit` | Canonical design, an accepted ADR, or both explicitly establish the choice. This records repository state, not proof of prior human consultation. |
| `accepted-implicit` | Canonical design establishes the choice but no adequate rationale/ADR was found. |
| `proposed` | Design presents a target that still requires explicit acceptance. |
| `deferred` | The choice is intentionally postponed behind an adoption or implementation gate. |
| `conflicting` | Canonical sources imply incompatible target states. |
| `needs-human-review` | Credible alternatives or product consequences require explicit discussion. |

## Review Priority

| Priority | Meaning |
| --- | --- |
| `P0` | Foundational conflict or unsafe ambiguity blocking reliable downstream design. |
| `P1` | High-impact, cross-domain, difficult-to-reverse, or likely under-consulted decision. |
| `P2` | Material bounded decision that should be challenged during its domain review. |
| `P3` | Low-risk rationale/documentation completion. |

## Coverage Summary

The inventory is split into this control ledger and exhaustive source-scoped ledgers:

- [Cross-cutting architecture decisions](./decision-inventory-cross-cutting.md) contains 68 decisions from the ADR set and 22 high-authority system documents.
- [Microservice decisions](./decision-inventory-microservices.md) contains 23 service-source rows = 22 distinct service-source decisions (21 active plus the formal superseded `MS-GR-EQUIPMENT-BODY-LAYOUT`) plus the navigation-only `MS-AA-TOKEN-REVOCATION` alias, and stronger evidence for 41 cross-cutting decisions from 76 covered microservice architecture paths (74 allocated evidence paths and 2 explicit governance/template exemptions). `MS-PO-OWNER-REMEDIATION` remains historical review evidence in this control ledger, not a service-source row.
- [Specialized runtime decisions](./decision-inventory-specialized-runtime.md) contains 54 decisions and stronger evidence for 20 cross-cutting decisions from 38 Redis, scripting, tick, identity, token, migration, shared-library, spatial, authorization, and tracing documents.
- [Product and operations decisions](./decision-inventory-product-operations.md) contains 41 decisions and stronger evidence for 11 existing keys from the remaining 38 product, frontend, authoring, protocol, infrastructure, deployment, recovery, observability, and generated-settings sources.

The merged Packet 6 P1-P3 baseline covered `340` discovered sources (`337` allocated, `3` explicit exemptions), including `165` allocated ADR records through ADR 0167. Packet 7 added 11 ADR records through ADR 0178, and the three post-archive direct decisions add ADRs 0179-0181. The canonical allocation therefore covers all 354 discovered product and architecture sources: 351 allocated decision-bearing sources and 3 explicit exemptions (the ADR registry/index plus two microservice governance/template documents). The four disjoint source-scoped partitions account for all 174 non-ADR paths (`22 + 76 + 38 + 38`); 172 are allocated (`22 + 74 + 38 + 38`) and two microservice governance/template paths are explicit exemptions. The 180-path ADR partition contributes 179 allocated ADR records plus the exempt ADR registry/index, yielding `174 + 180 = 354` discovered, `172 + 179 = 351` allocated, and 3 exemptions. The decision evidence set is 352 sources: the 351 allocated sources plus the exempt ADR registry/index; the two exempt microservice governance/template documents are discovered but excluded from decision evidence. A source may provide evidence without producing a distinct decision row. The source-scoped ledgers preserve unique primary ownership for the 185 current decision keys; cross-cutting evidence/reference rows may repeat existing keys across ledgers and do not add distinct keys. The microservice service-source ledger has 23 rows: 22 distinct non-alias service-source decisions (21 active plus the formal superseded `MS-GR-EQUIPMENT-BODY-LAYOUT`) plus the navigation-only historical service-scan alias `MS-AA-TOKEN-REVOCATION`. The removed `MS-PO-OWNER-REMEDIATION` key remains historical review evidence below and is not a service-source row. The completed historical human-review archive still has one row for each of its 182 decision keys plus the historical alias; the three direct decisions increase the current universe to 185 distinct keys and 186 key/navigation rows without changing that archive. Collectively, the inventories reference all 79 leaf capabilities in the taxonomy.

| Capability | Sources reviewed | Decisions inventoried | Human-review candidates | Coverage state |
| --- | ---: | ---: | ---: | --- |
| Existing ADR set | 179 ADR records (171 with completed review metadata; 11 pre-formal records, with overlap where accepted legacy records now carry exact review provenance) plus the architecture decision registry/index | 9 original aliases within the 68 cross-cutting decisions; later reviewed records are allocated directly | Complete in the historical source archive; ADRs 0179-0181 are direct post-archive decisions | Complete and allocated through ADR 0181 |
| Cross-cutting system architecture | 22 canonical sources plus ADRs | 68 | Complete in the source archive | Complete and independently audited |
| Microservice architecture | 76 covered paths (74 allocated evidence paths; 2 explicit governance/template exemptions) | 23 service-source rows = 22 distinct decisions (21 active + formal superseded `MS-GR-EQUIPMENT-BODY-LAYOUT`) + excluded historical `MS-AA-TOKEN-REVOCATION` alias; `MS-PO-OWNER-REMEDIATION` remains historical review evidence; stronger evidence for 41 existing keys | Complete in the historical source archive plus direct `HOSTED-TERMS-01` provenance | Complete and independently audited |
| Specialized runtime architecture | 38 sources | 54 new; stronger evidence for 20 existing keys | Complete in the source archive | Complete and independently audited |
| Product and operations architecture | 38 sources | 41 new; stronger evidence for 11 existing keys | Complete in the historical source archive plus three direct post-archive decisions | Complete and independently audited |
| **Decision keys / navigation rows** | **All 354 design sources: 351 allocated decision-bearing sources (172 allocated non-ADR documents and 179 ADR records), 1 exempt architecture decision registry/index, and 2 excluded governance/template exemptions; 351 allocated and 3 total exemptions** | **185 current decision keys; 186 navigation/key rows including one historical service-scan alias** | **All 183 historical queue/navigation rows reviewed in the source archive plus three direct human decisions** | **All 185 current decision keys have reviewed outcomes applied; Packet 1-7 remains complete through ADR 0178 and direct post-archive decisions are applied through ADR 0181** |

## Legacy ADR Alias Navigation

These nine pre-inventory keys are navigation aliases for current decision keys. Their original decision prose and alternatives remain in the source-scoped ledger; this control document does not restate those contracts.

| Legacy key | Current decision key | Human review outcome | Merged canonical source |
| --- | --- | --- | --- |
| `AS-INGRESS-IDEMPOTENCY` | `AUTO-01` | `revised`; applied | [replacement ADR 0172](../../architecture/decisions/adr-0172-parent-event-and-frozen-handler-execution-identity.md) |
| `AS-HANDOFF-SUCCESS` | `AUTO-02` | `accepted`; reviewed clarification applied | [ADR 0002](../../architecture/decisions/adr-0002-automation-handoff-reliability-and-success-semantics.md) |
| `AS-RELOAD-BACKPRESSURE` | `AUTO-03` | `revised`; applied | [replacement ADR 0173](../../architecture/decisions/adr-0173-registry-classified-reload-admission-policy.md) |
| `AA-WORLD-SELECTOR-IDENTITY` | `AUTH-01` | `revised`; applied | [replacement ADR 0168](../../architecture/decisions/adr-0168-snapshot-bound-lobby-selectors-and-stable-realm-identity.md) |
| `PO-EDGE-SHARDING` | `EDGE-01` | `accepted`; applied without replacement | [ADR 0007](../../architecture/decisions/adr-0007-edge-sharding-and-close-taxonomy.md) |
| `GR-GAMEPLAY-CLUSTER-SCOPE` | `EDGE-02` | `accepted`; reviewed clarification applied | [ADR 0008](../../architecture/decisions/adr-0008-multi-cluster-gameplay-sharding-scope.md) |
| `SF-COORDINATION-REDIS-OWNERSHIP` | `REDIS-02` | `accepted`; applied extension | [ADR 0171](../../architecture/decisions/adr-0171-separated-redis-role-processes-and-owned-keyspaces.md), extending [ADR 0009](../../architecture/decisions/adr-0009-coordination-redis-ownership-boundary.md) |
| `SF-TCP-PROXY-IDENTITY` | `EDGE-03` | `revised`; applied | [replacement ADR 0169](../../architecture/decisions/adr-0169-exclusive-environment-bound-tcp-proxy-trust.md) |
| `GR-SESSION-FRONTEND-EXECUTION` | `SESSION-01` | `revised`; applied | [replacement ADR 0170](../../architecture/decisions/adr-0170-fenced-command-forwarding-and-authoritative-region-transition.md) |

## Conflicts And Missing Decisions

The detailed ledgers preserve all conflicts, target/current gaps, weak rationale, and consultation questions. The highest-impact reconciliation items are:

| Decision key | Conflict or missing decision |
| --- | --- |
| `AUTO-01` | Resolved baseline: ADR 0001 now delegates the endpoint-specific identity matrix to the canonical scripting contract table and includes runtime scope plus dry-run separation. |
| `SET-01` | Resolved target: values follow defaults, preset, bootstrap, supported runtime-default, tenant, and game-instance precedence while hard bounds and operator caps apply separately as constraints. The current effective-settings implementation does not yet resolve the complete accepted model. |
| `CONTENT-05` | Resolved baseline: first-party authoring uses Game Design-owned revision and domain APIs; bulk JSON import/export remains deferred until it has a validated package contract. |
| `SESSION-04` | Resolved target: ordinary non-edge failures use bounded invisible recovery when the edge socket, healthy replacement capacity, and shared authority remain available; the ordinary target is 10 seconds and the hard cutoff is 30 seconds before `1013/backend_unavailable`. Complete real-Game-Session continuity proof remains implementation debt. |
| `SESSION-08` | Resolved target: healthy uninterrupted play is independent of private player-delegation token lifetime; immutable continuity expiry limits reuse of an old binding after transport loss, disconnected resume uses the stricter continuity and configured windows, and transcript retention or Redis presence never grants authority. Current `PLAY` admission does not yet enforce these deadlines. |
| `SEC-02` | Resolved target: planned Account JWT rotation prepublishes and converges the new public key before signer promotion, retains old verification through token expiry, then prunes; compromise/restore uses an environment-wide hard cutover. Current shared-HMAC issuance and validation fail the accepted player-facing readiness gate. |
| `OPS-04` | Resolved target: routine backups are online environment-wide PostgreSQL snapshots without gameplay pause; player-facing rewind uses only environment-wide `cold_start_restore` with empty Redis, safe participant dispositions, quarantine, and controlled reopen. The durable recovery controller is the runtime authority for the gated reopen transition; checked-in recovery evidence is a finalized projection. Current implementation and proof do not satisfy the accepted gates. |
| `CMD-STATUS-01` | Resolved target: evolve `GetGameplayCommandStatus` in place as the single authoritative durable API, with stable pre-retry identity and separate acknowledgement, ingress, execution-outcome, and gameplay-result dimensions. Current fields, persistence, recovery, and proof remain incomplete. |
| `TRACE-01` | Resolved target: metrics and structured logs are the dependable baseline; named workflow tracing, service sampling, and tenant/game-instance/region sampling are progressively advertised only after end-to-end environment proof. Current tracing remains a narrower best-effort surface. |
| `EDGE-06` / `MS-GW-DYNAMIC-ROUTES` | Resolved target: the version-controlled release catalog is the sole player-facing route authority; bounded ephemeral mutation is dev/test-only, and any production runtime control plane requires a separate future decision. Current endpoint gating, isolation, validation, and proof remain incomplete. |

These rows preserve the original conflict inventory and now summarize its human-reviewed resolutions. The linked ADRs and canonical design are authoritative for the accepted target state; implementation gaps remain tracked separately.

## Focused Revalidation Evidence

- `MS-AA-LIFECYCLE-ERASURE`: [ADR 0043](../../architecture/decisions/adr-0043-global-account-lifecycle-and-bounded-erasure-workflow.md), the Account runtime model, and the player journey require ownership transfer or terminal subscription termination plus complete durable/provider billing reconciliation before terminal `deleted`; a durably scheduled step remains intermediate pending evidence.
- `DATA-01`: [ADR 0050](../../architecture/decisions/adr-0050-versioned-export-retention-and-erasure-policy.md), the Account runtime model, and the player journey keep durable asynchronous full export separate from deletion, require every owning-service contribution to fan in, and permit manifest retrieval only after that fan-in. Proof must show deletion remains blocked or intermediate while nonterminal subscriptions and tenant-data/retention blockers remain, and advances to terminal deletion only after those blockers and related billing/provider work clear.
- `TENANT-02` and `MS-AA-PAYMENT-INSTRUMENT`: [ADR 0041](../../architecture/decisions/adr-0041-shared-tenant-infrastructure-with-full-environment-isolation-gate.md), [ADR 0044](../../architecture/decisions/adr-0044-account-owned-payment-instruments-with-explicit-subscription-binding.md), and [Multi-Tenancy](../../architecture/system-architecture-multi-tenancy.md) distinguish tenant-scoped hosting/game entitlements from explicit account-scoped purchases, grants, and donations; no fabricated tenant is permitted.
- `MS-SOCIAL-PRESENCE-PRIVACY`: [ADR 0046](../../architecture/decisions/adr-0046-bounded-friend-presence-with-private-by-failure-redaction.md) already specifies immutable-snapshot scanning through consumed omitted/redacted candidates until page fill or exhaustion, accumulated entries plus a continuation at scan cap, and zero-entry success only when no subject was emitted.
- Focused evidence covers a missing policy entry and its complete `PRIVATE` redaction in [`FriendServiceImplTest`](../../../services/social-groups-service/src/test/java/unit/net/firedevops/firemud/socialgroups/service/impl/FriendServiceImplTest.java) (`listFriendPresenceFailsClosedWhenAccountPolicyBatchIsUnavailable`), while [`AccountClientTest`](../../../services/social-groups-service/src/test/java/unit/net/firedevops/firemud/socialgroups/client/AccountClientTest.java) (`recordsApplicationErrorsFromPresenceVisibilityPolicyReads`) proves Account application-error handling and the empty client result. The implementation has a fail-closed path for unknown or malformed policy values, but this inventory has no focused test proving those cases at the service boundary; unknown/malformed-policy coverage therefore remains partial rather than proof complete.

## Applied Review Provenance

The remotely backed `design/adversarial-decision-review` archive records the completed human-led review of all `183` queue/navigation rows. This section is the authoritative checked provenance for the `182` applicable outcomes already applied and merged to `develop`: `applied` means the reviewed result is present in merged canonical design, not merely recorded in the archive. Every applicable row is checked and must link that merged ADR or canonical design; the one excluded navigation alias remains historical archive evidence only.

The completed imports preserve each archive disposition and review date, materialize the reviewed result, and check the corresponding row in the same change that provides its merged provenance. An `accepted` or `revised` result updates canonical design and, when the resulting decision is consequential, materializes a corresponding accepted ADR; `deferred` records the accepted deferral and its revisit boundary without presenting the deferred capability as current target state; `superseded` links the replacement and removes the superseded target as current guidance; and `withdrawn` removes or declines the target and records the withdrawal rationale. Closely coupled keys may share an ADR, but every key retains its own outcome. Future work is the planned whole-corpus authority review.

### Progress Summary

| Packet | Scope | Human-reviewed rows in archive | Applied distinct decisions on `develop` | Application state |
| --- | --- | ---: | ---: | --- |
| 1 | Known conflicts and drift | 9 | 9 | `applied` |
| 2 | Identity, authority, and security | 32 | 31 | `applied`; one historical alias is navigation-only |
| 3 | Execution correctness and durability | 43 | 43 | `applied` |
| 4 | Publishing, settings, and authored behavior | 36 | 36 | `applied` |
| 5 | Gameplay and player experience | 21 | 21 | `applied` |
| 6 | Operations and delivery | 25 | 25 | `applied` |
| 7 | Existing ADR-backed and lower-risk remainder | 17 | 17 | `applied` |
| **Total** | | **183** | **182** | `review-complete`; `applied` |

The source-archive total counts 183 navigation rows. Packet 2 contains 31 distinct decision keys plus the `MS-AA-TOKEN-REVOCATION` historical alias, producing 32 archive rows but only 31 applicable decisions. The applied-provenance checklist excludes that service-scan alias and therefore has 182 rows, all now applied. Packet 6 is complete at 25 applied outcomes through ADRs 0151-0167. Packet 7 adds all 17 reviewed outcomes through ADRs 0168-0178 plus clarifications to existing ADR and architecture authority. The combined Packet 6 P1-P3 and Packet 7 imports create 19 active ADR records, with the 11 Packet 7 records added in this parcel; the legacy ADR labels above are mappings to decision keys rather than additional rows. The later whole-corpus authority review remains required before declaring post-ADR alignment complete.

### Priority Overrides

No implementation-blocking import override is active. Record an override here with the decision keys, blocked capability, blocking question, and requesting branch or PR. An override changes only import order; it does not remove or duplicate reviewed keys.

### Packet 1: Known Conflicts And Drift

#### Packet 1 P0

- [x] `SET-01` — `revised` on 2026-07-18; [ADR 0012](../../architecture/decisions/adr-0012-settings-value-precedence-and-constraints.md); [canonical settings model](../../architecture/system-architecture-settings-model.md)
- [x] `SESSION-04` — `revised` on 2026-07-18; [ADR 0013](../../architecture/decisions/adr-0013-bounded-invisible-non-edge-restart-recovery.md); [canonical reconnection contract](../../architecture/system-architecture-reconnection.md)
- [x] `SEC-02` — `revised` on 2026-07-18; [ADR 0014](../../architecture/decisions/adr-0014-phased-jwt-signing-key-rotation-and-readiness.md); [canonical JWT rotation workflow](../../architecture/system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative)
- [x] `OPS-04` — `revised` on 2026-07-18; [ADR 0015](../../architecture/decisions/adr-0015-online-backup-and-environment-wide-cold-start-recovery.md); [canonical backup and recovery contract](../../architecture/system-architecture-backup-recovery.md)
- [x] `CMD-STATUS-01` — `revised` on 2026-07-18; [ADR 0016](../../architecture/decisions/adr-0016-canonical-gameplay-command-status-lifecycle.md); [canonical command lifecycle](../../architecture/system-architecture-tick-execution-flows.md#command-ingress-acknowledgement-contract-required)
- [x] `TRACE-01` — `revised` on 2026-07-18; [ADR 0017](../../architecture/decisions/adr-0017-capability-gated-operational-tracing.md); [capability-gated tracing contract](../../architecture/system-architecture-tracing.md#implementation-notes)
- [x] `EDGE-06` — `revised` on 2026-07-18; [ADR 0018](../../architecture/decisions/adr-0018-declarative-production-gateway-routes.md); [canonical route authority](../../architecture/system-architecture-gateway.md#dynamic-route-override-lifecycle)
- [x] `MS-GW-DYNAMIC-ROUTES` — `revised` on 2026-07-18; [ADR 0018](../../architecture/decisions/adr-0018-declarative-production-gateway-routes.md); [Gateway API boundary](../../architecture/microservices/spring-cloud-gateway/api-contracts.md#dynamic-route-management-target-state)

#### Packet 1 P1

- [x] `SESSION-08` — `revised` on 2026-07-18; [ADR 0019](../../architecture/decisions/adr-0019-separate-active-session-resume-and-transcript-lifetimes.md); [canonical session lifetime contract](../../architecture/system-architecture-session-behavior.md#session-types-and-lifetimes)

### Packet 2: Identity, Authority, And Security

Packet 2 checklist dispositions record the human review outcome, not an inventory status. For every checked Packet 2 entry, `accepted` maps to the canonical inventory status `accepted-explicit`; `revised` maps to the same status after the revised target was accepted; `superseded` records that the reviewed key was replaced by the linked decision(s), so the linked replacement row rather than the checklist label determines the canonical inventory status. Unchecked entries have no Packet 2 disposition; their canonical inventory status remains whatever the inventory records and is not inferred from this checklist. The `AUTH-02` and `AUTH-06` targets retain explicit `JOIN`/`Join & Play`, but `POST /auth/bootstrap/join` is target-state and not implemented; current text `PLAY` and connect-token issuance require existing caller-bound membership and return canonical `JOIN_REQUIRED` for eligible missing or `INACTIVE` public-production membership, while the obsolete implicit membership-writer RPC/client/service seam has been removed. The target admission contract retains `JOIN_REQUIRED` for policy-permitted missing or `INACTIVE` membership. `WORLD_ACCESS_DENIED` is reserved for another reachable authoritative world/tenant denial and is mutually exclusive with `JOIN_REQUIRED`. A realm grant never substitutes for membership, and the connect-token membership-version plus membership-authority-generation reread remains a gap. The accepted tenant-role boundary and tenantless control-login/tenant-switching decision are target choices, not proof claims: the owning [Player Access and Session implementation tracker](../implementation-tracking/player-access-and-session.md#capability-status) records the missing tenant-scoped roles, switching controls, and implementation state, while its [Validation and Proof](../implementation-tracking/player-access-and-session.md#validation-and-proof) section records focused evidence and remaining proof gaps.

Packet 2 count reconciliation: the 31 reviewed decisions are covered by the checked dispositions for ADRs 0020-0050, excluding the historical service-scan alias. The Packet 2 disposition contains exactly those 31 reviewed decisions; the alias is navigation-only and is not a checked disposition row.

Packet 2 historical-alias rule: `MS-AA-TOKEN-REVOCATION` is a superseded service-scan alias, not an additional unresolved decision or an independent target. Its canonical mapping is the accepted/revised set `AUTH-03`, `JWT-01`, `JWT-02`, `JWT-03`, and `JWT-04`; new evidence or review outcomes must be recorded against those owning keys. The alias may remain in historical scan/navigation material, but it is excluded from the Packet 2 disposition and must not be counted as a separate decision or reopened as an alternative target.

#### Packet 2 P0

- [x] `ID-01` — `revised` on 2026-07-18; [ADR 0020](../../architecture/decisions/adr-0020-scoped-domain-and-operational-identifiers.md); [canonical identifier contract](../../architecture/system-architecture-identifier-glossary.md)
- [x] `AUTH-02` — `revised` on 2026-07-18; [ADR 0021](../../architecture/decisions/adr-0021-staged-player-authentication-and-gameplay-binding.md)
- [x] `AUTH-03` — `accepted` on 2026-07-18; [ADR 0022](../../architecture/decisions/adr-0022-account-authority-and-gameplay-session-ownership.md)
- [x] `AUTH-04` — `revised` on 2026-07-18; [ADR 0023](../../architecture/decisions/adr-0023-central-route-authorization-governance.md)
- [x] `AUTH-05` — `revised` on 2026-07-19; [ADR 0024](../../architecture/decisions/adr-0024-trusted-gameplay-workload-delegation.md)
- [x] `AUTH-06` — `revised` on 2026-07-19; [ADR 0025](../../architecture/decisions/adr-0025-explicit-open-enrollment-membership.md)
- [x] `AUTH-07` — `revised` on 2026-07-19; [ADR 0026](../../architecture/decisions/adr-0026-global-roles-do-not-grant-gameplay-authority.md)
- [x] `TENANT-01` — `revised` on 2026-07-19; [ADR 0027](../../architecture/decisions/adr-0027-single-realm-admission-target.md)
- [x] `ADMIT-01` — `revised` on 2026-07-19; [ADR 0028](../../architecture/decisions/adr-0028-differentiated-entitlement-freshness.md)
- [x] `EDGE-04` — `revised` on 2026-07-19; [ADR 0029](../../architecture/decisions/adr-0029-single-use-gameplay-connect-token-carriage.md)
- [x] `SESSION-07` — `revised` on 2026-07-19; [ADR 0030](../../architecture/decisions/adr-0030-risk-based-active-session-revocation.md)
- [x] `SESSION-09` — `revised` on 2026-07-19; [ADR 0031](../../architecture/decisions/adr-0031-revocation-safe-session-token-rotation-and-logout.md)
- [x] `SEC-01` — `revised` on 2026-07-19; [ADR 0032](../../architecture/decisions/adr-0032-kubernetes-native-secret-delivery-without-mandatory-vault.md)
- [x] `SEC-03` — `revised` on 2026-07-19; [ADR 0033](../../architecture/decisions/adr-0033-public-player-facing-telnet-requires-tls.md)
- [x] `SEC-05` — `revised` on 2026-07-19; [ADR 0034](../../architecture/decisions/adr-0034-layered-abuse-controls-without-attacker-triggered-account-locks.md)
- [x] `JWT-01` — `revised` on 2026-07-19; [ADR 0035](../../architecture/decisions/adr-0035-single-record-issued-token-registry.md)
- [x] `JWT-02` — `revised` on 2026-07-19; [ADR 0036](../../architecture/decisions/adr-0036-monotonic-authority-generations-for-bulk-token-revocation.md)
- [x] `JWT-03` — `revised` on 2026-07-19; [ADR 0037](../../architecture/decisions/adr-0037-fail-closed-token-authority-outages-with-bounded-active-gameplay.md)
- [x] `JWT-04` — `revised` on 2026-07-19; [ADR 0038](../../architecture/decisions/adr-0038-explicit-jwt-profiles-and-mtls-workload-identity.md)
- [x] `REDIS-06` — `revised` on 2026-07-19; [ADR 0039](../../architecture/decisions/adr-0039-bounded-redis-operator-maintenance-surface.md)
- [x] `MS-AA-CONTROL-LOGIN-SCOPE` — `revised` on 2026-07-19; [ADR 0040](../../architecture/decisions/adr-0040-account-global-control-login-and-explicit-tenant-selection.md)

#### Packet 2 P1

- [x] `TENANT-02` — `revised` on 2026-07-19; [ADR 0041](../../architecture/decisions/adr-0041-shared-tenant-infrastructure-with-full-environment-isolation-gate.md)
- [x] `MS-AA-GLOBAL-TENANT-BOUNDARY` — `revised` on 2026-07-19; [ADR 0042](../../architecture/decisions/adr-0042-global-account-and-tenant-scoped-game-relationships.md)
- [x] `MS-AA-LIFECYCLE-ERASURE` — `revised` on 2026-07-19; [ADR 0043](../../architecture/decisions/adr-0043-global-account-lifecycle-and-bounded-erasure-workflow.md)
- [x] `MS-AA-PAYMENT-INSTRUMENT` — `revised` on 2026-07-19; [ADR 0044](../../architecture/decisions/adr-0044-account-owned-payment-instruments-with-explicit-subscription-binding.md)
- [x] `MS-AA-LOGIN-FACTORS` — `revised` on 2026-07-19; [ADR 0045](../../architecture/decisions/adr-0045-ordinary-login-factors-and-https-sensitive-action-step-up.md)
- [x] `MS-SOCIAL-PRESENCE-PRIVACY` — `revised` on 2026-07-19; [ADR 0046](../../architecture/decisions/adr-0046-bounded-friend-presence-with-private-by-failure-redaction.md)
- [x] `SEC-04` — `revised` on 2026-07-19; [ADR 0047](../../architecture/decisions/adr-0047-logging-admin-as-external-operator-write-ingress.md)
- [x] `ADMIN-01` — `revised` on 2026-07-19; [ADR 0048](../../architecture/decisions/adr-0048-durable-idempotent-operator-write-execution.md)
- [x] `ACCOUNT-01` — `revised` on 2026-07-19; [ADR 0049](../../architecture/decisions/adr-0049-optional-provider-specific-external-identity-linking.md)
- [x] `DATA-01` — `revised` on 2026-07-19; [ADR 0050](../../architecture/decisions/adr-0050-versioned-export-retention-and-erasure-policy.md)

### Packet 3: Execution Correctness And Durability

#### Packet 3 P0

- [x] `TICK-01` — `revised` on 2026-07-19; [ADR 0051](../../architecture/decisions/adr-0051-separate-actor-action-and-effect-lanes.md)
- [x] `TICK-02` — `revised` on 2026-07-19; [ADR 0052](../../architecture/decisions/adr-0052-redis-liveness-lease-with-durable-executor-fence.md)
- [x] `TICK-03` — `revised` on 2026-07-19; [ADR 0053](../../architecture/decisions/adr-0053-command-atomicity-by-invariant-class.md)
- [x] `TICK-04` — `revised` on 2026-07-19; [ADR 0054](../../architecture/decisions/adr-0054-split-spatial-authority-with-causal-read-composition.md)
- [x] `TICK-06` — `revised` on 2026-07-19; [ADR 0055](../../architecture/decisions/adr-0055-durable-cross-region-effects-with-static-live-topology.md)
- [x] `HOTPATH-01` — `revised` on 2026-07-19; [ADR 0056](../../architecture/decisions/adr-0056-one-hot-path-fan-out-owner.md)
- [x] `RECON-01` — `revised` on 2026-07-19; [ADR 0057](../../architecture/decisions/adr-0057-game-session-owned-reconciliation-with-isolated-workers.md)
- [x] `REDIS-01` — `revised` on 2026-07-19; [ADR 0058](../../architecture/decisions/adr-0058-class-specific-redis-loss-outcomes.md)
- [x] `ID-02` — `revised` on 2026-07-19; [ADR 0059](../../architecture/decisions/adr-0059-causal-floor-cross-service-presentation-reads.md)
- [x] `MS-GR-AMBIENT-STATE-AUTHORITY` — `revised` on 2026-07-19; [ADR 0060](../../architecture/decisions/adr-0060-world-owned-ambient-facts-and-logic-owned-consequences.md)
- [x] `SPATIAL-01` — `revised` on 2026-07-19; [ADR 0061](../../architecture/decisions/adr-0061-single-owner-spatial-mutations-across-split-authority.md)
- [x] `SESSION-06` — `revised` on 2026-07-19; [ADR 0062](../../architecture/decisions/adr-0062-layered-gameplay-command-delivery-semantics.md)
- [x] `SCRIPT-01` — `revised` on 2026-07-19; [ADR 0063](../../architecture/decisions/adr-0063-durable-per-dispatch-script-handoff.md)
- [x] `SCRIPT-04` — `revised` on 2026-07-19; [ADR 0064](../../architecture/decisions/adr-0064-stage-qualified-script-outcomes.md)
- [x] `TICK-09` — `revised` on 2026-07-19; [ADR 0065](../../architecture/decisions/adr-0065-deterministic-fair-entity-tick-scheduling.md)
- [x] `TICK-13` — `revised` on 2026-07-19; [ADR 0066](../../architecture/decisions/adr-0066-durable-asynchronous-cross-region-result-arbitration.md)
- [x] `TICK-14` — `revised` on 2026-07-19; [ADR 0067](../../architecture/decisions/adr-0067-abandon-old-epoch-work-and-reschedule-with-new-lineage.md)
- [x] `TICK-15` — `revised` on 2026-07-19; [ADR 0068](../../architecture/decisions/adr-0068-evidence-derived-bounded-tick-ledger-recovery.md)
- [x] `TICK-16` — `revised` on 2026-07-19; [ADR 0069](../../architecture/decisions/adr-0069-at-least-once-effect-execution-with-one-logical-terminal-outcome.md)
- [x] `TICK-17` — `revised` on 2026-07-19; [ADR 0070](../../architecture/decisions/adr-0070-bounded-within-tick-visibility-by-semantic-phase.md)
- [x] `TICK-19` — `revised` on 2026-07-19; [ADR 0071](../../architecture/decisions/adr-0071-durable-tick-commit-before-fenced-coordination-cleanup.md)

#### Packet 3 P1

- [x] `TICK-05` — `accepted` on 2026-07-19; [canonical contract](../../architecture/system-architecture-transactions.md#saga-vs-temporal-boundary); no ADR required
- [x] `TICK-07` — `revised` on 2026-07-19; [ADR 0072](../../architecture/decisions/adr-0072-class-specific-timer-durability-and-recovery.md)
- [x] `TICK-08` — `revised` on 2026-07-19; [ADR 0073](../../architecture/decisions/adr-0073-evidence-calibrated-tick-budgets-and-lock-ttls.md)
- [x] `TICK-10` — `revised` on 2026-07-19; [ADR 0074](../../architecture/decisions/adr-0074-one-entity-lock-per-redis-script.md)
- [x] `TICK-11` — `revised` on 2026-07-20; [ADR 0075](../../architecture/decisions/adr-0075-depth-cost-and-count-bounds-for-generated-effect-chains.md)
- [x] `TICK-12` — `revised` on 2026-07-20; [ADR 0076](../../architecture/decisions/adr-0076-failure-class-specific-durable-tick-retries.md)
- [x] `TICK-18` — `revised` on 2026-07-20; [ADR 0077](../../architecture/decisions/adr-0077-durable-global-effect-fanout-and-lightweight-idle-ticks.md)
- [x] `ID-03` — `revised` on 2026-07-20; [ADR 0078](../../architecture/decisions/adr-0078-digest-bound-workflow-and-step-retry-identities.md)
- [x] `DB-01` — `accepted` on 2026-07-20; [ADR 0079](../../architecture/decisions/adr-0079-jooq-and-flyway-as-the-single-sql-persistence-stack.md)
- [x] `DB-02` — `accepted` on 2026-07-20; [ADR 0080](../../architecture/decisions/adr-0080-service-owned-schemas-with-adopter-local-shared-migrations.md)
- [x] `DB-03` — `revised` on 2026-07-20; [ADR 0081](../../architecture/decisions/adr-0081-objective-compatibility-gates-for-database-evolution.md)
- [x] `DB-04` — `revised` on 2026-07-20; [ADR 0082](../../architecture/decisions/adr-0082-semantic-boundary-for-cross-service-identifier-migration.md)
- [x] `REDIS-03` — `revised` on 2026-07-20; [ADR 0083](../../architecture/decisions/adr-0083-no-general-event-broker-until-measured-adoption-gates.md)
- [x] `REDIS-04` — `revised` on 2026-07-20; [ADR 0084](../../architecture/decisions/adr-0084-evidence-scoped-redis-lua-compatibility.md)
- [x] `REDIS-05` — `revised` on 2026-07-20; [ADR 0085](../../architecture/decisions/adr-0085-evidence-gated-coordination-replay-and-fenced-reset.md)
- [x] `CACHE-01` — `revised` on 2026-07-20; [ADR 0086](../../architecture/decisions/adr-0086-owner-validated-class-a-caches-and-presentation-only-class-b.md)
- [x] `CACHE-02` — `revised` on 2026-07-20; [ADR 0087](../../architecture/decisions/adr-0087-isolated-subject-rate-limits-with-explicit-loss-semantics.md)
- [x] `SCRIPT-02` — `revised` on 2026-07-20; [ADR 0088](../../architecture/decisions/adr-0088-static-and-incremental-script-output-bounds.md)
- [x] `SCRIPT-03` — `revised` on 2026-07-20; [ADR 0089](../../architecture/decisions/adr-0089-durable-script-usage-charges-and-fenced-capacity-leases.md)
- [x] `SCRIPT-12` — `revised` on 2026-07-20; [ADR 0090](../../architecture/decisions/adr-0090-recorded-script-input-manifests-for-reproducible-evaluation.md)
- [x] `TIMER-01` — `revised` on 2026-07-20; [ADR 0091](../../architecture/decisions/adr-0091-class-specific-script-timer-clocks-and-recovery.md)
- [x] `GRPC-01` — `revised` on 2026-07-20; [ADR 0092](../../architecture/decisions/adr-0092-grpc-status-and-typed-domain-outcome-boundary.md)

### Packet 4: Publishing, Settings, And Authored Behavior

#### Packet 4 P0

- [x] `CONTENT-01` — `revised` on 2026-07-20; [ADR 0093](../../architecture/decisions/adr-0093-game-design-coordinated-digest-attested-content-publication.md)
- [x] `CONTENT-02` — `revised` on 2026-07-20; [ADR 0094](../../architecture/decisions/adr-0094-explicit-cohesive-runtime-release-tuples.md)
- [x] `ASSET-01` — `revised` on 2026-07-20; [ADR 0095](../../architecture/decisions/adr-0095-content-addressed-published-assets-with-cas-lifecycle-authority.md)
- [x] `ASSET-02` — `revised` on 2026-07-20; [ADR 0096](../../architecture/decisions/adr-0096-attested-publication-gate-and-quarantined-failed-assets.md)
- [x] `PROMO-01` — `revised` on 2026-07-20; [ADR 0097](../../architecture/decisions/adr-0097-git-and-ci-validated-single-operator-promotion-evidence.md)
- [x] `PROC-02` — `revised` on 2026-07-20; [ADR 0098](../../architecture/decisions/adr-0098-request-bounded-generation-replay-and-explicit-regeneration.md)
- [x] `PROC-04` — `revised` on 2026-07-20; [ADR 0099](../../architecture/decisions/adr-0099-bounded-atomic-generation-with-staging-for-large-outputs.md)
- [x] `MS-AS-PATCH-READINESS-PIN` — `revised` on 2026-07-20; [ADR 0103](../../architecture/decisions/adr-0103-single-authority-script-pins-with-exact-version-execution.md)
- [x] `MS-GR-REPLACEMENT-STATE` — `revised` on 2026-07-20; [ADR 0122](../../architecture/decisions/adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md)
- [x] `MS-GR-WORLD-LIFECYCLE` — `revised` on 2026-07-20; [ADR 0123](../../architecture/decisions/adr-0123-database-authoritative-temporal-coordinated-world-lifecycle.md)
- [x] `SCRIPT-06` — `revised` on 2026-07-20; [ADR 0106](../../architecture/decisions/adr-0106-epoch-fenced-script-rollback-without-routine-gameplay-pause.md)
- [x] `SCRIPT-07` — `revised` on 2026-07-20; [ADR 0107](../../architecture/decisions/adr-0107-stage-aware-script-dead-letter-recovery.md)
- [x] `SCRIPT-08` — `revised` on 2026-07-20; [ADR 0108](../../architecture/decisions/adr-0108-no-degraded-script-admission-without-authoritative-pin.md)
- [x] `SCRIPT-16` — `revised` on 2026-07-20; [ADR 0109](../../architecture/decisions/adr-0109-game-session-owned-script-rollout-history.md)
- [x] `TIMER-02` — `revised` on 2026-07-20; [ADR 0110](../../architecture/decisions/adr-0110-explicit-opt-in-schedule-continuity-across-script-transitions.md)

#### Packet 4 P1

- [x] `PROC-01` — `revised` on 2026-07-20; [ADR 0100](../../architecture/decisions/adr-0100-separate-generation-ingress-with-one-world-owned-engine.md)
- [x] `PROC-03` — `revised` on 2026-07-20; [ADR 0101](../../architecture/decisions/adr-0101-explicit-destructive-regeneration-with-previewed-scope.md)
- [x] `PROC-05` — `revised` on 2026-07-20; [ADR 0102](../../architecture/decisions/adr-0102-first-class-sparse-and-full-grid-world-topologies.md)
- [x] `CONTENT-03` — `revised` on 2026-07-20; [ADR 0111](../../architecture/decisions/adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md)
- [x] `CONTENT-04` — `revised` on 2026-07-20; [ADR 0124](../../architecture/decisions/adr-0124-materialized-starter-profiles-with-conservative-draft-upgrades.md)
- [x] `CONTENT-05` — `deferred` on 2026-07-20; [ADR 0125](../../architecture/decisions/adr-0125-defer-whole-game-portability-and-external-authoring-formats.md)
- [x] `CMD-02` — `accepted` on 2026-07-20; [ADR 0112](../../architecture/decisions/adr-0112-typed-bounded-gameplay-effect-extension.md)
- [x] `SET-02` — `revised` on 2026-07-20; [ADR 0113](../../architecture/decisions/adr-0113-bounded-pull-settings-distribution-with-freshness-classes.md)
- [x] `LLM-01` — `revised` on 2026-07-20; [ADR 0126](../../architecture/decisions/adr-0126-untrusted-models-and-scoped-authoring-tools.md)
- [x] `EQUIP-01` — `revised` on 2026-07-20; [ADR 0127](../../architecture/decisions/adr-0127-game-authored-equipment-layouts-with-fail-closed-publication.md)
- [x] `MS-AS-PLUGIN-TRUST` — `accepted` on 2026-07-20; [ADR 0128](../../architecture/decisions/adr-0128-game-design-plugin-trust-provenance.md)
- [x] `MS-AS-DRY-RUN-ISOLATION` — `revised` on 2026-07-20; [ADR 0114](../../architecture/decisions/adr-0114-command-plan-preview-dry-run-isolation.md)
- [x] `MS-AR-DRAFT-CONCURRENCY` — `revised` on 2026-07-20; [ADR 0129](../../architecture/decisions/adr-0129-durable-fenced-multi-owner-draft-commits.md)
- [x] `MS-GR-EQUIPMENT-BODY-LAYOUT` — `superseded` on 2026-07-20 by `EQUIP-01`; [ADR 0130](../../architecture/decisions/adr-0130-historical-equipment-body-layout-authority.md); [replacement ADR 0127](../../architecture/decisions/adr-0127-game-authored-equipment-layouts-with-fail-closed-publication.md)
- [x] `SCRIPT-05` — `revised` on 2026-07-20; [ADR 0115](../../architecture/decisions/adr-0115-manifest-complete-onload-readiness-without-durable-game-initialization.md)
- [x] `SCRIPT-09` — `superseded` on 2026-07-20 by `MS-AS-DRY-RUN-ISOLATION`; [ADR 0121](../../architecture/decisions/adr-0121-historical-broad-dry-run-semantics.md); [replacement ADR 0114](../../architecture/decisions/adr-0114-command-plan-preview-dry-run-isolation.md)
- [x] `SCRIPT-10` — `revised` on 2026-07-20; [ADR 0116](../../architecture/decisions/adr-0116-routine-component-migration-and-explicit-emergency-revocation.md)
- [x] `SCRIPT-11` — `accepted` on 2026-07-20; [ADR 0117](../../architecture/decisions/adr-0117-producer-owned-event-schemas-with-one-materialized-catalogue.md)
- [x] `SCRIPT-13` — `revised` on 2026-07-20; [ADR 0118](../../architecture/decisions/adr-0118-preselected-exclusive-handlers-and-durable-fanout-ordering.md)
- [x] `PLUGIN-01` — `revised` on 2026-07-20; [ADR 0119](../../architecture/decisions/adr-0119-epoch-fenced-per-instance-plugin-activation.md)
- [x] `CP-01` — `revised` on 2026-07-20; [ADR 0120](../../architecture/decisions/adr-0120-owner-read-first-control-plane-notifications.md)

### Packet 5: Gameplay And Player Experience

#### Packet 5 P0

- [x] `TENANT-03` — `revised` on 2026-07-20; [ADR 0137](../../architecture/decisions/adr-0137-isolated-playtest-state-modes-and-reset.md)
- [x] `EDGE-05` — `revised` on 2026-07-20; [ADR 0131](../../architecture/decisions/adr-0131-lifecycle-distinct-gameplay-close-taxonomy.md)
- [x] `SESSION-02` — `revised` on 2026-07-20; [ADR 0132](../../architecture/decisions/adr-0132-namespace-scoped-single-character-controller.md)
- [x] `SESSION-03` — `revised` on 2026-07-20; [ADR 0133](../../architecture/decisions/adr-0133-fresh-edge-reconnect-without-client-input-replay.md)
- [x] `CMD-04` — `revised` on 2026-07-20; [ADR 0134](../../architecture/decisions/adr-0134-bounded-durable-semantic-reconnect-context.md)

#### Packet 5 P1

- [x] `CMD-03` — `revised` on 2026-07-20; [ADR 0135](../../architecture/decisions/adr-0135-compact-versioned-player-output-and-late-rendering.md)
- [x] `CMD-05` — `revised` on 2026-07-20; [ADR 0136](../../architecture/decisions/adr-0136-future-compatible-localization-boundary.md)
- [x] `MOD-01` — `revised` on 2026-07-20; [ADR 0146](../../architecture/decisions/adr-0146-owner-local-moderation-enforcement.md)
- [x] `MS-GR-COMMUNICATION-ORCHESTRATION` — `revised` on 2026-07-20; [ADR 0147](../../architecture/decisions/adr-0147-explicit-communication-classes-and-owner-delivery.md)
- [x] `MS-SOCIAL-RELATIONSHIP-AUTHORITY` — `revised` on 2026-07-20; [ADR 0148](../../architecture/decisions/adr-0148-social-relationship-authority-and-entity-owned-value.md)
- [x] `MS-SOCIAL-HISTORY-DURABILITY` — `revised` on 2026-07-20; [ADR 0149](../../architecture/decisions/adr-0149-communication-type-specific-history-and-retention.md)
- [x] `MS-SOCIAL-OBSERVER-SHOUT-POLICY` — `revised` on 2026-07-20; [ADR 0150](../../architecture/decisions/adr-0150-closed-observer-views-and-profile-scoped-shout.md)
- [x] `PLAYTEST-01` — `revised` on 2026-07-20; [ADR 0138](../../architecture/decisions/adr-0138-expiring-playtest-grants-with-bounded-active-revocation.md)
- [x] `LIFE-01` — `revised` on 2026-07-20; [ADR 0139](../../architecture/decisions/adr-0139-tenant-owned-runtime-lifecycle-with-audited-break-glass.md)
- [x] `PLAYER-01` — `revised` on 2026-07-20; [ADR 0140](../../architecture/decisions/adr-0140-realm-authored-controllable-actor-entry.md)
- [x] `SAFETY-01` — `revised` on 2026-07-20; [ADR 0141](../../architecture/decisions/adr-0141-fixed-safety-restriction-categories-and-independent-lifecycles.md)
- [x] `COMMERCE-01` — `revised` on 2026-07-20; [ADR 0143](../../architecture/decisions/adr-0143-stripe-v1-hosting-billing-and-deferred-creator-monetization.md)
- [x] `SOCIAL-01` — `superseded` on 2026-07-20 by `MS-GR-COMMUNICATION-ORCHESTRATION`, `MS-SOCIAL-HISTORY-DURABILITY`, and `MS-SOCIAL-OBSERVER-SHOUT-POLICY`; [replacement ADR 0147](../../architecture/decisions/adr-0147-explicit-communication-classes-and-owner-delivery.md); [replacement ADR 0149](../../architecture/decisions/adr-0149-communication-type-specific-history-and-retention.md); [replacement ADR 0150](../../architecture/decisions/adr-0150-closed-observer-views-and-profile-scoped-shout.md); no ADR required
- [x] `MS-PO-MODERATION-APPEALS` — `revised` on 2026-07-20; [ADR 0142](../../architecture/decisions/adr-0142-bounded-moderation-appeal-cases.md)

#### Packet 5 P2 And P3

- [x] `FRONT-01` — `revised` on 2026-07-20; [ADR 0144](../../architecture/decisions/adr-0144-stateless-first-party-frontend-application-boundary.md)
- [x] `MCP-01` — `revised` on 2026-07-20; [ADR 0145](../../architecture/decisions/adr-0145-plain-text-gameplay-and-deferred-classic-client-extensions.md)

### Packet 6: Operations And Delivery

#### Packet 6 P0

- [x] `COMPLIANCE-01` — `revised` on 2026-07-20; [ADR 0151](../../architecture/decisions/adr-0151-event-scoped-automated-tier-a-credential-compliance.md)
- [x] `PREFLIGHT-02` — `revised` on 2026-07-20; [ADR 0152](../../architecture/decisions/adr-0152-phased-environment-bound-deployment-preflight-and-expected-bindings.md)
- [x] `PREFLIGHT-01` — `revised` on 2026-07-20; [ADR 0152](../../architecture/decisions/adr-0152-phased-environment-bound-deployment-preflight-and-expected-bindings.md)
- [x] `OPS-03` — `revised` on 2026-07-20; [ADR 0153](../../architecture/decisions/adr-0153-measured-online-backup-rpo-and-future-pitr-trigger.md)
- [x] `RECOVERY-01` — `revised` on 2026-07-20; [ADR 0154](../../architecture/decisions/adr-0154-automated-recovery-proof-and-differentiated-traffic-open-gates.md)
- [x] `RECOVERY-02` — `revised` on 2026-07-20; [ADR 0155](../../architecture/decisions/adr-0155-automated-event-classified-post-restore-trust-reset.md)
- [x] `OPS-02` — `revised` on 2026-07-20; [ADR 0156](../../architecture/decisions/adr-0156-risk-tiered-progressive-rollout-with-compatibility-bounded-rollback.md)
- [x] `HEALTH-01` — `revised` on 2026-07-20; [ADR 0157](../../architecture/decisions/adr-0157-dependency-classified-liveness-readiness-and-route-admission.md)
- [x] `OBS-04` — `revised` on 2026-07-20; [ADR 0158](../../architecture/decisions/adr-0158-simplified-observability-degradation-without-fallback-alert-authority.md)
- [x] `OBS-05` — `revised` on 2026-07-20; [ADR 0159](../../architecture/decisions/adr-0159-profile-dependent-independent-deadman-and-public-path-monitoring.md)

#### Packet 6 P1

- [x] `OBS-01` — `accepted` on 2026-07-20; [canonical contract](../../architecture/system-architecture-logging-monitoring.md#cardinality-guardrails-for-metrics); no ADR required
- [x] `OBS-02` — `revised` on 2026-07-20; [ADR 0160](../../architecture/decisions/adr-0160-staged-profile-aware-player-experience-slo-contract.md)
- [x] `OBS-03` — `revised` on 2026-07-20; [ADR 0161](../../architecture/decisions/adr-0161-profile-aware-isolated-synthetic-player-flow-canaries.md)
- [x] `OBS-06` — `revised` on 2026-07-20; [ADR 0162](../../architecture/decisions/adr-0162-profile-aware-asynchronous-end-to-end-log-queryability-evidence.md)
- [x] `CAPACITY-01` — `accepted` on 2026-07-20; [canonical contract](../../architecture/system-architecture-scaling-runbook.md#starting-guardrails-baseline-sizing); no ADR required
- [x] `CAPACITY-02` — `revised` on 2026-07-20; [ADR 0163](../../architecture/decisions/adr-0163-service-owned-retention-classes-with-cross-service-safety.md)
- [x] `TEST-01` — `revised` on 2026-07-20; [canonical contract](../../architecture/system-architecture-testing.md#redis-in-tests); no ADR required
- [x] `TEST-02` — `revised` on 2026-07-20; [ADR 0164](../../architecture/decisions/adr-0164-three-boundary-profile-aware-verification-evidence.md)
- [x] `TEST-03` — `accepted` on 2026-07-20; [canonical contract](../../architecture/system-architecture-testing.md#high-concurrency-load-testing); no ADR required
- [x] `MS-OPS-AVAILABILITY-PARTITION` — `revised` on 2026-07-20; [ADR 0165](../../architecture/decisions/adr-0165-authoritative-control-actions-during-observability-loss.md)
- [x] `MS-PO-OWNER-REMEDIATION` — `revised` on 2026-07-20; [canonical contract](../../architecture/microservices/logging-admin-service/api-contracts.md#rest); no ADR required
- [x] `SCRIPT-14` — `revised` on 2026-07-20; [ADR 0166](../../architecture/decisions/adr-0166-attributable-script-breakers-and-tenant-first-fairness.md)
- [x] `SCRIPT-15` — `revised` on 2026-07-20; [canonical contract](../../architecture/system-architecture-scripting-normative-contract-tables.md#table-4-metrics-label-matrix); no ADR required
- [x] `TRACE-02` — `revised` on 2026-07-20; [ADR 0167](../../architecture/decisions/adr-0167-allowlisted-sensitive-trace-attributes.md)

#### Packet 6 P2 And P3

- [x] `TRACE-03` — `revised` on 2026-07-20; [canonical contract](../../architecture/system-architecture-tracing.md#sampling-and-sensitive-attributes); no ADR required

### Packet 7: Existing ADR-Backed And Lower-Risk Remainder

#### Packet 7 P2 And P3

- [x] `AUTH-01` — `revised` on 2026-07-21; [ADR 0168](../../architecture/decisions/adr-0168-snapshot-bound-lobby-selectors-and-stable-realm-identity.md)
- [x] `EDGE-01` — `accepted` on 2026-07-21; [ADR 0007](../../architecture/decisions/adr-0007-edge-sharding-and-close-taxonomy.md)
- [x] `EDGE-02` — `accepted` on 2026-07-21; [ADR 0008](../../architecture/decisions/adr-0008-multi-cluster-gameplay-sharding-scope.md)
- [x] `EDGE-03` — `revised` on 2026-07-21; [ADR 0169](../../architecture/decisions/adr-0169-exclusive-environment-bound-tcp-proxy-trust.md)
- [x] `SESSION-01` — `revised` on 2026-07-21; [ADR 0170](../../architecture/decisions/adr-0170-fenced-command-forwarding-and-authoritative-region-transition.md)
- [x] `SESSION-05` — `revised` on 2026-07-21; [canonical contract](../../architecture/system-architecture-reconnection.md); no ADR required
- [x] `REDIS-02` — `accepted` on 2026-07-21; [ADR 0171](../../architecture/decisions/adr-0171-separated-redis-role-processes-and-owned-keyspaces.md)
- [x] `AUTO-01` — `revised` on 2026-07-21; [ADR 0172](../../architecture/decisions/adr-0172-parent-event-and-frozen-handler-execution-identity.md)
- [x] `AUTO-02` — `accepted` on 2026-07-21; clarified in [ADR 0002](../../architecture/decisions/adr-0002-automation-handoff-reliability-and-success-semantics.md)
- [x] `AUTO-03` — `revised` on 2026-07-21; [ADR 0173](../../architecture/decisions/adr-0173-registry-classified-reload-admission-policy.md)
- [x] `GRPC-02` — `revised` on 2026-07-21; [ADR 0174](../../architecture/decisions/adr-0174-maturity-scoped-protobuf-compatibility.md)
- [x] `CMD-01` — `accepted` on 2026-07-21; [canonical contract](../../architecture/system-architecture-player-command-model.md); no ADR required
- [x] `CMD-06` — `revised` on 2026-07-21; [ADR 0175](../../architecture/decisions/adr-0175-release-pinned-command-capabilities-and-private-history.md)
- [x] `LIB-01` — `revised` on 2026-07-21; [ADR 0176](../../architecture/decisions/adr-0176-owner-local-redis-execution-with-aggregated-contracts.md)
- [x] `OPS-01` — `accepted` on 2026-07-21; [canonical contract](../../architecture/system-architecture-cicd.md); no ADR required
- [x] `OPS-05` — `revised` on 2026-07-21; [ADR 0177](../../architecture/decisions/adr-0177-exact-plan-authorized-automated-production-deployment.md)
- [x] `OPS-06` — `revised` on 2026-07-21; [ADR 0178](../../architecture/decisions/adr-0178-disposable-transport-complete-pr-preview-proof.md)

### Post-Archive Direct Human Decisions

- [x] `COMMERCE-02` — `accepted` on 2026-08-25; [ADR 0179](../../architecture/decisions/adr-0179-firemud-managed-creator-commerce-boundary.md)
- [x] `HOSTED-TERMS-01` — `accepted` on 2026-08-25; [ADR 0180](../../architecture/decisions/adr-0180-account-owned-hosted-terms-acceptance-gate.md)
- [x] `HOSTED-TERMS-02` — `accepted` on 2026-08-25; [ADR 0181](../../architecture/decisions/adr-0181-changed-hosted-terms-decline-and-existing-content-continuity.md)

### Allocation Notes

- `EDGE-06` and `MS-GW-DYNAMIC-ROUTES` describe the same dynamic-route boundary but remain separate authoritative keys and must be reviewed together.
- `SESSION-08` remained in Packet 1 because the control inventory recorded target/current drift requiring human verification; that review is now complete.
- Publication authority precedes deployment gates, so `CONTENT-01`, `ASSET-01`, `ASSET-02`, and `PROMO-01` remain in Packet 4 rather than Packet 6.
- Version admission and rollback convergence precede execution, so the patch, plugin, and timer-reload decisions remain in Packet 4 rather than Packet 3.
- Account authority, identity scope, and erasure govern their player-facing consequences, so `DATA-01` and `ACCOUNT-01` remain in Packet 2 rather than Packet 5.
