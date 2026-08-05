# Consequential Design Decision Inventory

Status: Inventory and human-led review are complete and independently coverage/fidelity-audited. On `develop`, 40 of 182 distinct active decisions have imported, merged provenance; 142 reviewed decisions remain pending selective import. The historical navigation alias is excluded from both counts. ADR numbering records how applied outcomes were materialized but does not define the application boundary.

## Implementation Status

`Complete` applies to inventory coverage and the human-owned review run, not to decision import or implementation. The checked provenance below records only outcomes already applied to `develop`; unchecked rows are reviewed in the source archive but remain pending selective import. Canonical design and accepted ADRs define merged target state, while the domain trackers define implementation and proof status.

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
- [Microservice decisions](./decision-inventory-microservices.md) contains 23 service-only rows = 22 distinct decisions plus the navigation-only `MS-AA-TOKEN-REVOCATION` alias, and stronger evidence for 40 cross-cutting decisions from all 76 microservice architecture files.
- [Specialized runtime decisions](./decision-inventory-specialized-runtime.md) contains 54 decisions and stronger evidence for 20 cross-cutting decisions from 39 Redis, scripting, tick, identity, token, migration, shared-library, spatial, authorization, and tracing documents.
- [Product and operations decisions](./decision-inventory-product-operations.md) contains 38 decisions and stronger evidence for 11 existing keys from the remaining 35 product, frontend, authoring, protocol, infrastructure, deployment, recovery, observability, and generated-settings sources.

The canonical design allocation covers all 225 discovered product and architecture sources: 222 receive a primary capability allocation and 3 are explicit governance, template, or registry exemptions. This inventory inspects the 223 potentially decision-bearing sources: 172 allocated non-ADR product and architecture documents, 50 ADR records, and the architecture decision registry/index. The two exempt microservice governance/template documents are covered by allocation but excluded from decision evidence. A source may provide evidence without producing a distinct decision row. The source-scoped ledgers contain 182 distinct active decisions with no duplicates across ledgers. The 23 microservice service-only rows comprise 22 active decisions plus the superseded historical service-scan alias `MS-AA-TOKEN-REVOCATION`, so the source-scoped ledgers have 183 key/navigation rows while the distinct decision count remains 182. The completed human-review archive likewise has one row for each of the 182 active decision keys plus the historical service-scan alias. The nine legacy ADR labels in [Legacy ADR Alias Navigation](#legacy-adr-alias-navigation) map to nine of those active keys; they substitute as historical navigation labels and do not add archive or provenance rows. The historical service-scan alias is the only extra navigation row and is excluded from applied-review disposition. Collectively, the inventories reference all 79 leaf capabilities in the taxonomy.

| Capability | Sources reviewed | Decisions inventoried | Human-review candidates | Coverage state |
| --- | ---: | ---: | ---: | --- |
| Existing ADR set | 50 ADR records (48 accepted; 2 historical/superseded) plus the architecture decision registry/index | 9 original aliases within the 68 cross-cutting decisions; later accepted records are allocated directly | Complete in the source archive | Complete and allocated through ADR 0050 |
| Cross-cutting system architecture | 22 canonical sources plus ADRs | 68 | Complete in the source archive | Complete and independently audited |
| Microservice architecture | 76 sources | 23 rows = 22 active decisions + superseded historical `MS-AA-TOKEN-REVOCATION` alias; stronger evidence for 40 existing keys | Complete in the source archive | Complete and independently audited |
| Specialized runtime architecture | 39 sources | 54 new; stronger evidence for 20 existing keys | Complete in the source archive | Complete and independently audited |
| Product and operations architecture | 35 sources | 38 new; stronger evidence for 11 existing keys | Complete in the source archive | Complete and independently audited |
| **Total active decisions / navigation rows** | **All 225 design sources: 223 decision-bearing source rows (172 allocated non-ADR documents, 50 ADR records, and the exempt registry/index) plus 2 excluded governance/template exemptions; 222 allocated and 3 total exemptions** | **182 active decisions; 183 navigation/key rows including one historical alias** | **All 183 queue/navigation rows reviewed in the source archive** | **40 distinct decisions have merged provenance through ADR 0050; 142 remain pending import** |

## Legacy ADR Alias Navigation

These nine pre-inventory keys are navigation aliases for current decision keys. Their original decision prose and alternatives remain in the source-scoped ledger; this control document does not restate those contracts.

| Legacy key | Current decision key | Human review outcome | Merged canonical source |
| --- | --- | --- | --- |
| `AS-INGRESS-IDEMPOTENCY` | `AUTO-01` | `revised`; reviewed result pending import | [ADR 0001](../../architecture/decisions/adr-0001-scripting-event-ingress-idempotency-identity.md) and the [trigger identity table](../../architecture/system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields) |
| `AS-HANDOFF-SUCCESS` | `AUTO-02` | `accepted`; reviewed clarification pending import | [ADR 0002](../../architecture/decisions/adr-0002-automation-handoff-reliability-and-success-semantics.md) |
| `AS-RELOAD-BACKPRESSURE` | `AUTO-03` | `revised`; reviewed result pending import | [ADR 0003](../../architecture/decisions/adr-0003-reload-backpressure-and-retry-contract.md) |
| `AA-WORLD-SELECTOR-IDENTITY` | `AUTH-01` | `revised`; reviewed result pending import | [ADR 0005](../../architecture/decisions/adr-0005-tenant-identifiers-in-gameplay-protocol.md) |
| `PO-EDGE-SHARDING` | `EDGE-01` | `accepted`; no replacement target | [ADR 0007](../../architecture/decisions/adr-0007-edge-sharding-and-close-taxonomy.md) |
| `GR-GAMEPLAY-CLUSTER-SCOPE` | `EDGE-02` | `accepted`; reviewed clarification pending import | [ADR 0008](../../architecture/decisions/adr-0008-multi-cluster-gameplay-sharding-scope.md) |
| `SF-COORDINATION-REDIS-OWNERSHIP` | `REDIS-02` | `accepted`; no replacement target | [ADR 0009](../../architecture/decisions/adr-0009-coordination-redis-ownership-boundary.md) |
| `SF-TCP-PROXY-IDENTITY` | `EDGE-03` | `revised`; reviewed result pending import | [ADR 0010](../../architecture/decisions/adr-0010-tcp-proxy-identity-canonicalization.md) and [ADR 0038](../../architecture/decisions/adr-0038-explicit-jwt-profiles-and-mtls-workload-identity.md) |
| `GR-SESSION-FRONTEND-EXECUTION` | `SESSION-01` | `revised`; reviewed result pending import | [ADR 0011](../../architecture/decisions/adr-0011-gameplay-session-front-end-and-region-execution.md) |

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

The remotely backed `design/adversarial-decision-review` archive records the completed human-led review of all `183` queue/navigation rows. This section is the authoritative checked provenance only for reviewed outcomes already imported and merged to `develop`: `applied` means the reviewed result is present in merged canonical design, not merely recorded in the archive. A checked row must link that merged ADR or canonical design. An unchecked row here means `reviewed, pending import`, not `unreviewed`.

Future imports preserve the archive's exact disposition and review date, materialize the reviewed result, and check the corresponding row only in the same change that provides its merged provenance. An `accepted` or `revised` result updates canonical design and, when the resulting decision is consequential, materializes a corresponding accepted ADR; `deferred` records the accepted deferral and its revisit boundary without presenting the deferred capability as current target state; `superseded` links the replacement and removes the superseded target as current guidance; and `withdrawn` removes or declines the target and records the withdrawal rationale. Closely coupled keys may share an ADR, but every key retains its own outcome.

### Progress Summary

| Packet | Scope | Human-reviewed rows in archive | Applied distinct decisions on `develop` | Application state |
| --- | --- | ---: | ---: | --- |
| 1 | Known conflicts and drift | 9 | 9 | `applied` |
| 2 | Identity, authority, and security | 32 | 31 | `applied`; one historical alias is navigation-only |
| 3 | Execution correctness and durability | 43 | 0 | `pending-import` |
| 4 | Publishing, settings, and authored behavior | 36 | 0 | `pending-import` |
| 5 | Gameplay and player experience | 21 | 0 | `pending-import` |
| 6 | Operations and delivery | 25 | 0 | `pending-import` |
| 7 | Existing ADR-backed and lower-risk remainder | 17 | 0 | `pending-import` |
| **Total** | | **183** | **40** | `review-complete`; `import-in-progress` |

The source-archive total counts 183 navigation rows. Packet 2 contains 31 distinct decision keys plus the `MS-AA-TOKEN-REVOCATION` historical alias, producing 32 archive rows but only 31 applicable decisions. The merged applied-provenance checklist excludes that alias and therefore has 182 rows: 40 checked decisions already merged to `develop` and 142 unchecked decisions pending import. The 40 applied decisions are Packet 1's nine decisions plus Packet 2's 31 distinct decisions. The nine legacy ADR labels above are mappings to active checklist keys rather than additional rows.

### Priority Overrides

No implementation-blocking import override is active. Record an override here with the decision keys, blocked capability, blocking question, and requesting branch or PR. An override changes only import order; it does not remove or duplicate reviewed keys.

### Packet 1: Known Conflicts And Drift

#### Packet 1 P0

- [x] `SET-01` — `revised` on 2026-07-18; [ADR 0012](../../architecture/decisions/adr-0012-settings-value-precedence-and-constraints.md); [canonical settings model](../../architecture/system-architecture-settings-model.md)
- [x] `SESSION-04` — `revised` on 2026-07-18; [ADR 0013](../../architecture/decisions/adr-0013-bounded-invisible-non-edge-restart-recovery.md); [canonical reconnection contract](../../architecture/system-architecture-reconnection.md)
- [x] `SEC-02` — `revised` on 2026-07-18; [ADR 0014](../../architecture/decisions/adr-0014-phased-jwt-signing-key-rotation-and-readiness.md); [canonical JWT rotation workflow](../../architecture/system-architecture-security.md#jwt-key--jwks-rotation-workflow)
- [x] `OPS-04` — `revised` on 2026-07-18; [ADR 0015](../../architecture/decisions/adr-0015-online-backup-and-environment-wide-cold-start-recovery.md); [canonical backup and recovery contract](../../architecture/system-architecture-backup-recovery.md)
- [x] `CMD-STATUS-01` — `revised` on 2026-07-18; [ADR 0016](../../architecture/decisions/adr-0016-canonical-gameplay-command-status-lifecycle.md); [canonical command lifecycle](../../architecture/system-architecture-tick-execution-flows.md#command-ingress-acknowledgement-contract-required)
- [x] `TRACE-01` — `revised` on 2026-07-18; [ADR 0017](../../architecture/decisions/adr-0017-capability-gated-operational-tracing.md); [capability-gated tracing contract](../../architecture/system-architecture-tracing.md#implementation-notes)
- [x] `EDGE-06` — `revised` on 2026-07-18; [ADR 0018](../../architecture/decisions/adr-0018-declarative-production-gateway-routes.md); [canonical route authority](../../architecture/system-architecture-gateway.md#dynamic-route-override-lifecycle)
- [x] `MS-GW-DYNAMIC-ROUTES` — `revised` on 2026-07-18; [ADR 0018](../../architecture/decisions/adr-0018-declarative-production-gateway-routes.md); [Gateway API boundary](../../architecture/microservices/spring-cloud-gateway/api-contracts.md#dynamic-route-management)

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

- [ ] `TICK-01`
- [ ] `TICK-02`
- [ ] `TICK-03`
- [ ] `TICK-04`
- [ ] `TICK-06`
- [ ] `HOTPATH-01`
- [ ] `RECON-01`
- [ ] `REDIS-01`
- [ ] `ID-02`
- [ ] `MS-GR-AMBIENT-STATE-AUTHORITY`
- [ ] `SPATIAL-01`
- [ ] `SESSION-06`
- [ ] `SCRIPT-01`
- [ ] `SCRIPT-04`
- [ ] `TICK-09`
- [ ] `TICK-13`
- [ ] `TICK-14`
- [ ] `TICK-15`
- [ ] `TICK-16`
- [ ] `TICK-17`
- [ ] `TICK-19`

#### Packet 3 P1

- [ ] `TICK-05`
- [ ] `TICK-07`
- [ ] `TICK-08`
- [ ] `TICK-10`
- [ ] `TICK-11`
- [ ] `TICK-12`
- [ ] `TICK-18`
- [ ] `ID-03`
- [ ] `DB-01`
- [ ] `DB-02`
- [ ] `DB-03`
- [ ] `DB-04`
- [ ] `REDIS-03`
- [ ] `REDIS-04`
- [ ] `REDIS-05`
- [ ] `CACHE-01`
- [ ] `CACHE-02`
- [ ] `SCRIPT-02`
- [ ] `SCRIPT-03`
- [ ] `SCRIPT-12`
- [ ] `TIMER-01`
- [ ] `GRPC-01`

### Packet 4: Publishing, Settings, And Authored Behavior

#### Packet 4 P0

- [ ] `CONTENT-01`
- [ ] `CONTENT-02`
- [ ] `ASSET-01`
- [ ] `ASSET-02`
- [ ] `PROMO-01`
- [ ] `PROC-02`
- [ ] `PROC-04`
- [ ] `MS-AS-PATCH-READINESS-PIN`
- [ ] `MS-GR-REPLACEMENT-STATE`
- [ ] `MS-GR-WORLD-LIFECYCLE`
- [ ] `SCRIPT-06`
- [ ] `SCRIPT-07`
- [ ] `SCRIPT-08`
- [ ] `SCRIPT-16`
- [ ] `TIMER-02`

#### Packet 4 P1

- [ ] `CONTENT-03`
- [ ] `CONTENT-04`
- [ ] `CONTENT-05`
- [ ] `CMD-02`
- [ ] `SET-02`
- [ ] `LLM-01`
- [ ] `PROC-01`
- [ ] `PROC-03`
- [ ] `PROC-05`
- [ ] `EQUIP-01`
- [ ] `MS-AS-PLUGIN-TRUST`
- [ ] `MS-AS-DRY-RUN-ISOLATION`
- [ ] `MS-AR-DRAFT-CONCURRENCY`
- [ ] `MS-GR-EQUIPMENT-BODY-LAYOUT`
- [ ] `SCRIPT-05`
- [ ] `SCRIPT-09`
- [ ] `SCRIPT-10`
- [ ] `SCRIPT-11`
- [ ] `SCRIPT-13`
- [ ] `PLUGIN-01`
- [ ] `CP-01`

### Packet 5: Gameplay And Player Experience

#### Packet 5 P0

- [ ] `TENANT-03`
- [ ] `EDGE-05`
- [ ] `SESSION-02`
- [ ] `SESSION-03`
- [ ] `CMD-04`

#### Packet 5 P1

- [ ] `CMD-03`
- [ ] `CMD-05`
- [ ] `MOD-01`
- [ ] `MS-GR-COMMUNICATION-ORCHESTRATION`
- [ ] `MS-SOCIAL-RELATIONSHIP-AUTHORITY`
- [ ] `MS-SOCIAL-HISTORY-DURABILITY`
- [ ] `MS-SOCIAL-OBSERVER-SHOUT-POLICY`
- [ ] `PLAYTEST-01`
- [ ] `LIFE-01`
- [ ] `PLAYER-01`
- [ ] `SAFETY-01`
- [ ] `COMMERCE-01`
- [ ] `SOCIAL-01`
- [ ] `MS-PO-MODERATION-APPEALS`

#### Packet 5 P2 And P3

- [ ] `FRONT-01`
- [ ] `MCP-01`

### Packet 6: Operations And Delivery

#### Packet 6 P0

- [ ] `COMPLIANCE-01`
- [ ] `PREFLIGHT-02`
- [ ] `PREFLIGHT-01`
- [ ] `OPS-03`
- [ ] `RECOVERY-01`
- [ ] `RECOVERY-02`
- [ ] `OPS-02`
- [ ] `HEALTH-01`
- [ ] `OBS-04`
- [ ] `OBS-05`

#### Packet 6 P1

- [ ] `OBS-01`
- [ ] `OBS-02`
- [ ] `OBS-03`
- [ ] `OBS-06`
- [ ] `CAPACITY-01`
- [ ] `CAPACITY-02`
- [ ] `TEST-01`
- [ ] `TEST-02`
- [ ] `TEST-03`
- [ ] `MS-OPS-AVAILABILITY-PARTITION`
- [ ] `MS-PO-OWNER-REMEDIATION`
- [ ] `SCRIPT-14`
- [ ] `SCRIPT-15`
- [ ] `TRACE-02`

#### Packet 6 P2 And P3

- [ ] `TRACE-03`

### Packet 7: Existing ADR-Backed And Lower-Risk Remainder

#### Packet 7 P2 And P3

- [ ] `AUTH-01`
- [ ] `EDGE-01`
- [ ] `EDGE-02`
- [ ] `EDGE-03`
- [ ] `SESSION-01`
- [ ] `SESSION-05`
- [ ] `REDIS-02`
- [ ] `AUTO-01`
- [ ] `AUTO-02`
- [ ] `AUTO-03`
- [ ] `GRPC-02`
- [ ] `CMD-01`
- [ ] `CMD-06`
- [ ] `LIB-01`
- [ ] `OPS-01`
- [ ] `OPS-05`
- [ ] `OPS-06`

### Allocation Notes

- `EDGE-06` and `MS-GW-DYNAMIC-ROUTES` describe the same dynamic-route boundary but remain separate authoritative keys and must be reviewed together.
- `SESSION-08` remained in Packet 1 because the control inventory recorded target/current drift requiring human verification; that review is now complete.
- Publication authority precedes deployment gates, so `CONTENT-01`, `ASSET-01`, `ASSET-02`, and `PROMO-01` remain in Packet 4 rather than Packet 6.
- Version admission and rollback convergence precede execution, so the patch, plugin, and timer-reload decisions remain in Packet 4 rather than Packet 3.
- Account authority, identity scope, and erasure govern their player-facing consequences, so `DATA-01` and `ACCOUNT-01` remain in Packet 2 rather than Packet 5.
