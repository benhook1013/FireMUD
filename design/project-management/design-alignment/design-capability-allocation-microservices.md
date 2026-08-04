# Microservice Design Capability Allocation

Status: Complete inventory for `design/architecture/microservices/**`.

This ledger maps every Markdown source under [`design/architecture/microservices`](../../architecture/microservices/README.md) to the stable capabilities in the [FireMUD Product Capability Taxonomy](../../product/capability-taxonomy.md). It is a non-normative allocation and coverage artifact; the linked architecture sources remain authoritative for runtime behavior and ownership.

## Allocation Rules

- Every capability-bearing source has exactly one file-level primary capability.
- Materially mixed files retain one file-level primary and list heading-level overrides for sections whose normative center belongs elsewhere.
- Secondary handoffs identify required review scope without creating duplicate ownership.
- Service-local persistence and cache mechanics remain secondary `SF-2` handoffs unless the source primarily defines a shared persistence or workflow contract.
- Environment, secret, certificate, and service-discovery delivery documents allocate to `PO-3`; substantive game/runtime settings sections allocate to `AR-2`.
- Service operations documents allocate to `PO-4`, with operator-control or domain-runtime sections recorded as handoffs.
- `service-documentation-structure.md` and `service-template.md` are explicit governance/template exemptions. The microservice root README is capability-bearing because its traffic-surface rules are normative.

## Coverage Summary

| Measure | Count |
| --- | ---: |
| Markdown sources discovered | 76 |
| Capability-allocated sources | 74 |
| Explicitly exempt artifacts | 2 |
| Unallocated sources | 0 |
| Taxonomy gaps | 0 |
| Coverage | 100% |

| Source classification | Count |
| --- | ---: |
| Exempt governance/template artifacts | 2 |
| Service overviews | 12 |
| API contracts | 11 |
| Configuration contracts | 11 |
| Operations contracts | 11 |
| Runtime/data contracts | 9 |
| Service-specific design, UX, protocol, and workflow appendices | 20 |
| **Total** | **76** |

## Microservice-Root Artifacts

| Design source | Primary capability | Secondary handoffs | Heading or scope | Source classification | Ambiguity notes |
| --- | --- | --- | --- | --- | --- |
| `design/architecture/microservices/README.md` | `PO-2` | `PO-1`, `SF-1` | **Service Map** and **Traffic-Surface Rules** define public-route curation, internal-only defaults, and operator mutation ingress; **Shared Modules Are Not Services** hands off to `SF-1`. | Service overview | The navigation sections are index material, but the traffic-surface rules are normative edge and operator-ingress contracts. |
| `design/architecture/microservices/service-documentation-structure.md` | Exempt | `SF-1`, `SF-2`, `PO-3`, `PO-4` as documentation-shape references only | Whole file: documentation structure and refactor governance | Governance guide | Explicit governance exemption; it governs documentation organization rather than product behavior. |
| `design/architecture/microservices/service-template.md` | Exempt | `SF-1`, `PO-3`, `PO-4` as template placeholders only | Whole file: generic service-documentation template | Template | Explicit template exemption; placeholders do not establish capability ownership. |

## Account Service

| Design source | Primary capability | Secondary handoffs | Heading or scope | Source classification | Ambiguity notes |
| --- | --- | --- | --- | --- | --- |
| `design/architecture/microservices/account-service/README.md` | `AA-1` | `AA-2`, `AA-3`, `SF-1` | Whole file. Bootstrap, connect-token, and gameplay-session dependency passages hand off to `AA-2` and `AA-3`. | Service overview | Account owns identity and entitlement authority; gameplay and realm admission consume that authority. |
| `design/architecture/microservices/account-service/api-contracts.md` | `AA-1` | `AA-2`, `AA-3`, `EA-2`, `SF-1`, `PO-1` | Overrides: **Endpoint Authentication Classes**, **Subject-Binding Rules (Normative)**, **Login Modes**, and **Login Error Codes** -> `SF-1`; bootstrap discovery and connect-token portions of **gRPC APIs** and **REST APIs** plus **Runtime Membership and Entitlement Response Shapes** -> `AA-2`/`AA-3`; presence-visibility APIs -> `EA-2`. | API contract | The mixed API catalog is still primarily Account authority; admission and presence sections are explicit consumer contracts. |
| `design/architecture/microservices/account-service/configuration.md` | `PO-3` | `AA-1`, `SF-1` | Whole file. TLS and proto-source details hand off to `SF-1`. | Configuration contract | No unresolved ambiguity. |
| `design/architecture/microservices/account-service/operations.md` | `PO-4` | `AA-1`, `SF-2`, `PO-1` | Overrides: **Saga Participation** -> `SF-2`; audit aspects of **Operational Notes** -> `PO-1`. | Operations contract | No unresolved ambiguity. |
| `design/architecture/microservices/account-service/runtime-and-data.md` | `AA-1` | `AA-2`, `AA-3`, `SF-1`, `SF-2`, `PO-1` | Overrides: **Redis Role and Prefixes** -> `SF-2` with auth trust in `SF-1`; **Session and Token Model** -> `SF-1`/`AA-2`; **Membership and Entitlement Authority** runtime-consumer portions -> `AA-2`/`AA-3`. | Runtime/data contract | Account lifecycle and commerce remain primary; runtime admission is deliberately downstream. |
| `design/architecture/microservices/account-service/stripe-integration.md` | `AA-1` | `SF-1`, `SF-2`, `PO-1`, `PO-4` | Overrides: **Multi-Tenancy and Security** -> `SF-1`; webhook/reconciliation portions of **Payment Flows** and **Operational Concerns** -> `SF-2`/`PO-4`; audit controls -> `PO-1`. | Commerce design | No unresolved ambiguity. |
| `design/architecture/microservices/account-service/subscription-management.md` | `AA-1` | `AA-2`, `AA-3`, `AR-3`, `SF-1`, `SF-2`, `PO-1`, `PO-4` | Overrides: **Authorization Roles for Billing and Subscriptions** -> `SF-1`; runtime enforcement portions of **Tenant Availability and Quota Enforcement** and **Runtime Entitlement Contract** -> `AA-2`/`AA-3`/`AR-3`; **Event Delivery Semantics (Required)** -> `SF-2`; failure/reconciliation notes -> `PO-4`. | Entitlement design | Billing authority and gameplay enforcement are intentionally separate. |

## Automation and Scripting Service

| Design source | Primary capability | Secondary handoffs | Heading or scope | Source classification | Ambiguity notes |
| --- | --- | --- | --- | --- | --- |
| `design/architecture/microservices/automation-scripting-service/README.md` | `AS-1` | `GR-1`, `GR-2`, `GR-4`, `AR-3`, `SF-2`, `PO-4` | Whole file. Tick, world-state, rule-resolution, and patch-readiness passages are handoffs. | Service overview | No unresolved ambiguity. |
| `design/architecture/microservices/automation-scripting-service/api-contracts.md` | `AS-1` | `AR-1`, `AR-3`, `GR-1`, `SF-1`, `SF-2`, `PO-1`, `PO-4` | Overrides: **Reload Backpressure Contract**, **Script Patch and Plugin Visibility APIs**, and **Pinned Version Visibility Consistency** -> `AR-3`/`GR-1`/`PO-1`; **Digest Contract** -> `AR-1`/`SF-1`; idempotency and durable ingress mechanics -> `SF-2`. | API contract | Design publication, runtime readiness, and instance activation are distinct lifecycles. |
| `design/architecture/microservices/automation-scripting-service/configuration.md` | `PO-3` | `AS-1`, `AR-2`, `GR-1`, `SF-1` | Whole file. Automation budget and quota variables hand off to `AR-2`; tick timing variables hand off to `GR-1`. | Configuration contract | No unresolved ambiguity. |
| `design/architecture/microservices/automation-scripting-service/operations.md` | `PO-4` | `AS-1`, `AR-2`, `AR-3`, `SF-2`, `PO-1` | Overrides: **Fairness Quotas and Budgets** -> `AS-1`/`AR-2`; **Patch Rollout, Rollback, and Convergence** -> `AR-3`; audit and operator-control portions -> `PO-1`. | Operations contract | No unresolved ambiguity. |
| `design/architecture/microservices/automation-scripting-service/runtime-and-data.md` | `AS-1` | `AR-3`, `GR-1`, `GR-3`, `GR-4`, `SF-1`, `SF-2`, `PO-4` | Overrides: **Workflow Participation**, **Durable Script Work-Item Outbox**, and **Redis Roles and Prefixes** -> `SF-2`; **Redis Cluster Slotting Rules** -> `SF-1`/`SF-2`; **PvE, Faction, and Reputation Behavior** -> `GR-4` with entity-state handoff to `GR-3`. | Runtime/data contract | Persistent NPC memory is automation-owned behavior with bounded entity-state handoff. |
| `design/architecture/microservices/automation-scripting-service/sandbox-runtime-design.md` | `AS-1` | `AR-2`, `GR-1`, `SF-1`, `SF-2`, `PO-1`, `PO-3`, `PO-4` | Overrides: **Interaction with Quotas, Scheduling, and Multi-Tenancy** -> `AR-2`/`GR-1`; **Configuration Knobs** -> `AR-2`/`PO-3`; **Operator Guidance** and observable-failure subsections -> `PO-1`/`PO-4`; container isolation -> `SF-1`/`PO-3`. | Sandbox/runtime design | Target-state implementation status does not affect the allocation. |

## Entity Management Service

| Design source | Primary capability | Secondary handoffs | Heading or scope | Source classification | Ambiguity notes |
| --- | --- | --- | --- | --- | --- |
| `design/architecture/microservices/entity-management-service/README.md` | `GR-3` | `AA-3`, `AR-1`, `EA-1`, `GR-1`, `GR-2`, `SF-1`, `SF-2` | Whole file. Character discovery scope -> `AA-3`; template authoring -> `AR-1`; authoritative location exclusion -> `GR-2`; LOOK presentation handoff -> `EA-1`. | Service overview | World owns location and occupancy; Entity owns actor/item state and containment. |
| `design/architecture/microservices/entity-management-service/api-contracts.md` | `GR-3` | `AR-1`, `EA-1`, `GR-2`, `GR-4`, `SF-1`, `SF-2`, `PO-4` | Overrides: **Design-Time APIs** and **Digest Input Manifest** -> `AR-1`; **LOOK Entity Listing Contract** -> `EA-1`/`GR-2`; **Actor State Query** -> `GR-4`; **Implementation Status (LOOK Slice)** -> `PO-4`. | API contract | LOOK returns structured entity facts; it does not own transcript rendering. |
| `design/architecture/microservices/entity-management-service/configuration.md` | `PO-3` | `GR-3`, `SF-1`, `SF-2` | Whole file. Cache variables -> `SF-2`; TLS/proto details -> `SF-1`. | Configuration contract | No unresolved ambiguity. |
| `design/architecture/microservices/entity-management-service/operations.md` | `PO-4` | `GR-1`, `GR-3`, `SF-2` | Overrides: **Tick Locking** and **Tick Idempotency** -> `GR-1`/`SF-2`. | Operations contract | No unresolved ambiguity. |
| `design/architecture/microservices/entity-management-service/runtime-and-data.md` | `GR-3` | `AR-1`, `AR-3`, `GR-1`, `GR-2`, `GR-4`, `SF-1`, `SF-2`, `PO-1` | Overrides: design/version portions of **Data Model and Versioning** -> `AR-1`; **Replacement-Instance State Classification** -> `AR-3`; **Inventory Transfer Audit** -> `PO-1`; **Instance Termination Cleanup Contract**, **Workflow Participation**, **Redis Role and Prefixes**, and **Version Sources for Entity Caches** -> `SF-2`; location/spatial boundaries -> `GR-2`. | Runtime/data contract | Character location remains explicitly World-owned. |

## Game Design Service

| Design source | Primary capability | Secondary handoffs | Heading or scope | Source classification | Ambiguity notes |
| --- | --- | --- | --- | --- | --- |
| `design/architecture/microservices/game-design-service/README.md` | `AR-1` | `AR-2`, `AR-3`, `AS-1`, `EA-3`, `PO-1`, `PO-3`, `SF-1`, `SF-2` | Overrides: **Script Patch Lifecycle and Runtime Coordination** -> `AR-3`/`AS-1`; creator-application portions of **Key Features** and **Design Workflow** -> `EA-3`; asset delivery infrastructure -> `PO-3`. | Service overview | Game Design owns authoring history and release construction, not runtime execution. |
| `design/architecture/microservices/game-design-service/ability-action-tools.md` | `AR-1` | `AR-3`, `AS-1`, `EA-3`, `GR-3`, `GR-4`, `SF-1` | Overrides: runtime semantics in actor-state, targeting, cost, feedback, effect, and condition headings -> `GR-4` with `GR-3` mutation handoff; **Integration with the Scripting DSL** -> `AS-1`; **Version Pinning with Scripts and Plugins** -> `AR-3`; creator-tool portions -> `EA-3`. | Authoring design | Authored declarations belong to `AR-1`; reusable runtime interpretation belongs to `GR-4`. |
| `design/architecture/microservices/game-design-service/api-contracts.md` | `AR-1` | `AR-3`, `AS-1`, `SF-1`, `SF-2`, `PO-1` | Whole file. Authentication, workflow, and runtime-readiness calls are secondary handoffs. | API contract | No unresolved ambiguity. |
| `design/architecture/microservices/game-design-service/asset-storage.md` | `AR-1` | `AR-3`, `PO-1`, `PO-3`, `SF-1`, `SF-2` | Overrides: **External Delivery Classification** -> `PO-3`; persistence mechanics in **Table Structure** -> `SF-2`; purge/operator controls in **API** and **Asset Upload Guardrails** -> `PO-1`/`SF-1`; **Interaction with Script-Only Patches** and activation-facing parts of **Asset Lifecycle and Publish Workflow** -> `AR-3`. | Release-asset design | Published assets/manifests are `AR-1`; object-store/CDN infrastructure is `PO-3`. |
| `design/architecture/microservices/game-design-service/configuration.md` | `PO-3` | `AR-1`, `AR-2`, `SF-1`, `SF-2` | Overrides: **Redis Role and Prefixes** -> `SF-2`; **Asset Store** -> `AR-1`/`PO-3`; runtime setting variables -> `AR-2`. | Configuration contract | No unresolved ambiguity. |
| `design/architecture/microservices/game-design-service/feature-flags.md` | `AR-2` | `AR-1`, `AR-3`, `PO-1`, `PO-4`, `SF-2` | Overrides: **Design-Time Definitions** publication aspects -> `AR-1`; runtime activation portions of **Runtime Toggling and Persistence** -> `AR-3`; operator mutation/audit -> `PO-1`/`PO-4`. | Runtime-policy design | Game Design stores definitions; Game Session owns live runtime truth. |
| `design/architecture/microservices/game-design-service/game-templates.md` | `AR-1` | `AA-3`, `AR-2`, `AR-3`, `EA-3`, `GR-1`, `SF-1`, `SF-2` | Overrides: **Backfill, Validation, and Runtime Usage** -> `SF-2`/`AR-3`; **Runtime Defaults** -> `AR-2`; **Resolved Launch Descriptor**, **Launch Orchestration Ownership**, and activation portions of **Interaction with Version Lifecycle** -> `AR-3`; creator-facing **Creating Templates** -> `EA-3`; routing target fields -> `AA-3`. | Template/launch design | Template authoring and deterministic runtime activation are separate authorities. |
| `design/architecture/microservices/game-design-service/item-equipment-balancing.md` | `AR-1` | `AR-3`, `EA-3`, `GR-3`, `GR-4` | Overrides: editor/visualization portions of **Features** and **Workflow** -> `EA-3`; replacement-instance deployment in **Workflow** -> `AR-3`; runtime item/equipment/rule semantics -> `GR-3`/`GR-4`. | Authoring design | Creator-facing editor remains partially target-state. |
| `design/architecture/microservices/game-design-service/modding-framework.md` | `AR-1` | `AR-2`, `AR-3`, `AS-1`, `PO-1`, `PO-3`, `PO-4`, `SF-1`, `SF-2` | Overrides: **Trust Model & Roles** and **Signing and Key Lifecycle (Required)** -> `SF-1`; **Sandbox Capabilities & Quotas**, **Timer & Event Guarantees**, and **Validation Rules for Plugins** runtime portions -> `AS-1`; **Plugin Activation Failure Matrix**, **Plugin Lifecycle & Rollback**, and runtime binding portions of **Canonical Binding Model** -> `AR-3`; **Plugin Component Policy Management** and **Policy Rollout & Rollback** -> `AR-2`; **Monitoring & Debugging** -> `PO-4`/`PO-1`; object-store distribution -> `PO-3`. | Plugin/mod design | Bundle publication, runtime readiness, and instance activation are intentionally separate. |
| `design/architecture/microservices/game-design-service/operations.md` | `PO-4` | `AR-1`, `SF-2` | Whole file. Publish/saga operational notes hand off to `AR-1`/`SF-2`. | Operations contract | No unresolved ambiguity. |
| `design/architecture/microservices/game-design-service/version-control.md` | `AR-1` | `AR-3`, `AS-1`, `PO-4`, `SF-1`, `SF-2` | Overrides: **Design-Time Synchronization** and **Digest Schema Migration** -> `SF-2`/`SF-1`; **Digest Participants by Publish Type** -> `SF-1`/`PO-4`; activation entries in **Change Vehicle Selection Matrix** and **Script Patch Versions and Runtime Behavior** -> `AR-3`/`AS-1`. | Version/publish design | Digest participation is a cross-service publication contract, not duplicate content ownership. |
| `design/architecture/microservices/game-design-service/web-visual-interface.md` | `EA-3` | `AR-1`, `AS-1`, `SF-1` | Whole file. Authoring operations -> `AR-1`; script-editor integration -> `AS-1`; authentication -> `SF-1`. | First-party creator UX | The implementation outline is brief, but capability ownership is clear. |
| `design/architecture/microservices/game-design-service/world-editing-tools.md` | `AR-1` | `AR-3`, `GR-2`, `GR-3`, `PO-4`, `SF-1`, `SF-2` | Overrides: **Draft Write Concurrency** -> `SF-2`; digest/publish portions of **Workflow** and **Implementation Checklist** -> `AR-3`/`PO-4`; **Draft Reference Validation** and **Cross-Service Reference Invariants** -> `SF-1` with domain handoffs to `GR-2`/`GR-3`. | Authoring workflow | Game Design owns revision history; World and Entity own draft template state. |

## Game Logic Service

| Design source | Primary capability | Secondary handoffs | Heading or scope | Source classification | Ambiguity notes |
| --- | --- | --- | --- | --- | --- |
| `design/architecture/microservices/game-logic-service/README.md` | `GR-4` | `AS-1`, `EA-1`, `EA-2`, `GR-1`, `GR-2`, `GR-3`, `PO-4`, `SF-1` | Whole file. Command parsing/presentation -> `EA-1`; communication -> `EA-2`; tick scheduling -> `GR-1`; automation -> `AS-1`; world/entity fact reads -> `GR-2`/`GR-3`. | Service overview | Game Session owns queueing and final delivery. |
| `design/architecture/microservices/game-logic-service/api-contracts.md` | `GR-4` | `AS-1`, `EA-1`, `EA-2`, `GR-1`, `GR-2`, `GR-3`, `PO-4`, `SF-1` | Overrides: **Exposure Class** -> `SF-1`; presentation-event portions of **Gameplay Action Outcomes** plus **LOOK Aggregation and Formatting** -> `EA-1`; **Communication Flow** and **Current scope versus future communication semantics** -> `EA-2`; **Implementation Status** -> `PO-4`; world/entity fact ownership -> `GR-2`/`GR-3`. | API contract | Game Logic owns structured rule outcomes, not rendered transcript or social delivery. |
| `design/architecture/microservices/game-logic-service/configuration.md` | `AR-2` | `EA-1`, `EA-2`, `PO-3`, `SF-1` | Overrides: **Core Configuration**, **Dependent-Service Variables**, and **Proto Files** -> `PO-3`/`SF-1`; communication and command-capability portions of **FireMUD Settings Domains** -> `EA-1`/`EA-2`. | Runtime-policy/configuration contract | Substantive settings authority makes `AR-2` primary despite environment-delivery sections. |
| `design/architecture/microservices/game-logic-service/operations.md` | `PO-4` | `GR-1`, `GR-2`, `GR-3`, `GR-4`, `PO-3`, `SF-2` | Whole file. Deployment mechanics -> `PO-3`; dependency-shaped readiness -> domain handoffs; integration-test orchestration -> `SF-2`. | Operations contract | No unresolved ambiguity. |
| `design/architecture/microservices/game-logic-service/runtime-and-data.md` | `GR-4` | `AR-1`, `AS-1`, `EA-1`, `EA-2`, `GR-1`, `GR-2`, `GR-3`, `PO-4`, `SF-1`, `SF-2` | Overrides: **Workflow Participation** -> `SF-2`/`AR-1`; **Draft Digest Contract** -> `AR-1`/`SF-1`; **Redis Role and Prefixes** -> `SF-2`; queue/execution portions of **Data and Command Flow** -> `GR-1`/`EA-1`. | Runtime/data contract | Stateless replaceability is a reliability invariant, not local state ownership. |

## Game Session Service

| Design source | Primary capability | Secondary handoffs | Heading or scope | Source classification | Ambiguity notes |
| --- | --- | --- | --- | --- | --- |
| `design/architecture/microservices/game-session-service/README.md` | `AA-2` | `AA-3`, `AR-3`, `EA-1`, `EA-2`, `GR-1`, `PO-2`, `PO-4`, `SF-1`, `SF-2` | Overrides: realm/runtime-target portions of **Terminology** and **Responsibilities** -> `AA-3`; tick/execution portions of **Responsibilities** and **Architecture Summary** -> `GR-1`; live presence -> `EA-2`; pinned runtime/script state -> `AR-3`; protocol/presentation -> `EA-1`. | Service overview | The service spans admission and execution; player session continuity remains the file primary. |
| `design/architecture/microservices/game-session-service/api-contracts.md` | `AA-2` | `AA-3`, `AR-3`, `AS-1`, `EA-1`, `GR-1`, `PO-1`, `PO-2`, `PO-4`, `SF-1`, `SF-2` | Overrides: **Session Front-End and Lease-Owner Routing** and **Forwarding contract** -> `GR-1`; launch/cutover RPCs in **gRPC APIs** -> `AR-3`; automation handoff RPCs -> `AS-1`; durable command/status RPCs -> `EA-1`/`SF-2`; operator control/status RPCs -> `PO-1`/`PO-4`; **External HTTP route classification** -> `PO-2`; **Command Front Door Ownership** -> `EA-1`. | API contract | Player admission, runtime execution, and operator control are distinct sections of one service contract. |
| `design/architecture/microservices/game-session-service/configuration.md` | `PO-3` | `AA-2`, `AR-2`, `GR-1`, `SF-1` | Override: **FireMUD Settings Domains** -> `AR-2`; session/tick-specific environment values hand off to `AA-2`/`GR-1`. | Configuration contract | No unresolved ambiguity. |
| `design/architecture/microservices/game-session-service/operations.md` | `PO-4` | `AA-2`, `AR-3`, `EA-1`, `EA-2`, `GR-1`, `SF-2` | Overrides: **Scaling and Region Rebalancing** -> `GR-1`; runtime-slice status under **Current Runtime Status** -> `AA-2`/`EA-1`/`EA-2`; activation/recovery notes -> `AR-3`/`SF-2`. | Operations contract | Live versus target-state status is explicit and does not change capability ownership. |
| `design/architecture/microservices/game-session-service/protocols.md` | `EA-1` | `AA-2`, `AA-3`, `EA-2`, `GR-1`, `PO-2`, `PO-4`, `SF-1` | Overrides: **Login and Play Flow** and **Stage-aware command handling** -> `AA-2`/`AA-3`; **Plaintext Telnet pre-login warning** -> `PO-2`/`SF-1`; communication portions of **LOOK and SAY Behavior** plus **Communication request flow** -> `EA-2`; execution portions of request flows -> `GR-1`; metrics subsection -> `PO-4`. | Gameplay protocol contract | Command/presentation ownership is separated from gameplay-rule and social-delivery ownership. |
| `design/architecture/microservices/game-session-service/runtime-and-data.md` | `GR-1` | `AA-2`, `AR-2`, `AR-3`, `AS-1`, `GR-4`, `PO-4`, `SF-1`, `SF-2` | Overrides: session keys and **Reconnection and Disconnect Handling** -> `AA-2`; **Runtime Feature Flags** -> `AR-2`; **Script Patch Version Pinning and Rollback** -> `AR-3`/`AS-1`; persistence mechanics in Redis, command/effect execution, and saga sections -> `SF-2`; gameplay effects -> `GR-4`. | Runtime/data contract | No unresolved ambiguity. |

## Logging and Admin Service

| Design source | Primary capability | Secondary handoffs | Heading or scope | Source classification | Ambiguity notes |
| --- | --- | --- | --- | --- | --- |
| `design/architecture/microservices/logging-admin-service/README.md` | `PO-1` | `AA-1`, `AR-2`, `EA-2`, `EA-3`, `GR-1`, `PO-4`, `SF-2` | Whole file. Dashboard/logging portions -> `PO-4`; admin-application portions -> `EA-3`; tick remediation -> `GR-1`; feature flags -> `AR-2`; account/social enforcement -> `AA-1`/`EA-2`. | Service overview | Operator policy and audit do not transfer runtime-state ownership. |
| `design/architecture/microservices/logging-admin-service/admin-ui.md` | `EA-3` | `AR-2`, `GR-1`, `PO-1`, `PO-4`, `SF-1`, `SF-2` | Whole file. Moderation/control workflows -> `PO-1`; dashboards -> `PO-4`; feature flags -> `AR-2`; tick remediation -> `GR-1`; saga inspection -> `SF-2`; authentication -> `SF-1`. | First-party operator UX | The application experience is primary; underlying control authorities remain secondary handoffs. |
| `design/architecture/microservices/logging-admin-service/analytics-dashboards.md` | `PO-4` | `EA-3`, `PO-1` | Whole file. Embedded operator experience -> `EA-3`; moderation/report panels -> `PO-1`. | Observability design | No unresolved ambiguity. |
| `design/architecture/microservices/logging-admin-service/api-contracts.md` | `PO-1` | `AR-2`, `AR-3`, `GR-1`, `PO-4`, `SF-1`, `SF-2` | Overrides: log-query and observability availability portions -> `PO-4`; feature-flag APIs -> `AR-2`; admission-pointer/cutover APIs -> `AR-3`; tick-remediation APIs -> `GR-1`; **Endpoint Authentication Classes** -> `SF-1`; saga APIs -> `SF-2`. | API contract | The service forwards mutations to owning runtime services. |
| `design/architecture/microservices/logging-admin-service/configuration.md` | `PO-3` | `PO-1`, `PO-4`, `SF-1` | Whole file. JWT/TLS configuration -> `SF-1`; observability endpoint configuration -> `PO-4`. | Configuration contract | No unresolved ambiguity. |
| `design/architecture/microservices/logging-admin-service/moderation-policies.md` | `PO-1` | `AA-1`, `AA-2`, `EA-2`, `SF-1` | Overrides: **Profanity Filters** -> `EA-2`; Account and gameplay enforcement steps in **Enforcement Workflow** and **Appeals** -> `AA-1`/`AA-2`; authorization boundary -> `SF-1`. | Moderation-policy design | Recording and policy evaluation are separate from Account, Game Session, and Social enforcement. |
| `design/architecture/microservices/logging-admin-service/operations.md` | `PO-4` | `PO-1`, `SF-2` | Overrides: control-plane parts of **Availability and Degradation Expectations** and **Operator Workflows** -> `PO-1`; saga inspection -> `SF-2`. | Operations contract | Core operator control remains available during observability degradation. |
| `design/architecture/microservices/logging-admin-service/runtime-and-data.md` | `PO-1` | `AR-2`, `AR-3`, `GR-1`, `PO-4`, `SF-1`, `SF-2` | Overrides: **Availability Partitioning** -> `PO-4`; **Script Patch and Plugin Control-Plane Coordination** -> `AR-3`; feature-flag handoff in **Data Model** -> `AR-2`; **Saga Dashboard** -> `SF-2`; tick coordination -> `GR-1`. | Runtime/data contract | Operator control and observability are separate availability partitions. |

## Social and Groups Service

| Design source | Primary capability | Secondary handoffs | Heading or scope | Source classification | Ambiguity notes |
| --- | --- | --- | --- | --- | --- |
| `design/architecture/microservices/social-groups-service/README.md` | `EA-2` | `AA-1`, `EA-3`, `PO-1`, `SF-2` | Whole file. Application presentation -> `EA-3`; moderation/audit -> `PO-1`; identity -> `AA-1`; persistence -> `SF-2`. | Service overview | Presence remains instance-bounded unless a separate social surface broadens it. |
| `design/architecture/microservices/social-groups-service/api-contracts.md` | `EA-2` | `EA-3`, `PO-1`, `PO-2`, `SF-1`, `SF-2` | Whole file. Voice gateway transport -> `PO-2`; moderation/history -> `PO-1`/`SF-2`; client UX -> `EA-3`; authentication -> `SF-1`. | API contract | Gameplay communication intent enters through Game Logic before social delivery. |
| `design/architecture/microservices/social-groups-service/configuration.md` | `PO-3` | `EA-2`, `SF-1`, `SF-2` | Whole file. Chat-cache variables -> `SF-2`; voice/chat policy variables -> `EA-2`; TLS/proto -> `SF-1`. | Configuration contract | No unresolved ambiguity. |
| `design/architecture/microservices/social-groups-service/operations.md` | `PO-4` | `EA-2`, `SF-2` | Overrides: **Saga Participation** -> `SF-2`; **Chat Slice Status** -> `EA-2`. | Operations contract | No unresolved ambiguity. |
| `design/architecture/microservices/social-groups-service/runtime-and-data.md` | `EA-2` | `PO-1`, `PO-2`, `SF-1`, `SF-2` | Overrides: **Redis Role and Prefixes** -> `SF-2`; moderation portions -> `PO-1`; voice transport portions of **Chat and Voice Delivery** -> `PO-2`; trust propagation -> `SF-1`. | Runtime/data contract | Voice media relay is an edge handoff, not ownership of network transport. |

## Spring Cloud Gateway

| Design source | Primary capability | Secondary handoffs | Heading or scope | Source classification | Ambiguity notes |
| --- | --- | --- | --- | --- | --- |
| `design/architecture/microservices/spring-cloud-gateway/README.md` | `PO-2` | `AA-2`, `AA-3`, `AR-3`, `PO-3`, `PO-4`, `SF-1` | Overrides: readiness sections -> `PO-4`/`AA-2`; dynamic-route lifecycle status -> `AR-3`; environment topology -> `PO-3`; trust -> `SF-1`. | Service overview | Gateway routes edge traffic but does not own gameplay shard selection. |
| `design/architecture/microservices/spring-cloud-gateway/api-contracts.md` | `PO-2` | `AR-3`, `PO-1`, `PO-3`, `SF-1`, `SF-2` | Overrides: persistence/convergence portions of **Dynamic Route Management** -> `AR-3`/`SF-2`; **Management Plane Security** -> `SF-1`; control-plane portions of **Data Plane vs Control Plane** -> `PO-1`/`PO-3`. | API contract | Dynamic mutation remains dev/test until persistence, convergence, and audit controls exist. |
| `design/architecture/microservices/spring-cloud-gateway/client-behavior.md` | `PO-2` | `AA-2`, `AA-3`, `EA-1`, `PO-4`, `SF-1` | Overrides: gameplay admission portions -> `AA-2`/`AA-3`; handshake/close observability -> `PO-4`; header and bridge trust -> `SF-1`; text-client interaction references -> `EA-1`. | Edge client-behavior contract | Gateway is the edge failure boundary; backend restart continuity belongs downstream. |
| `design/architecture/microservices/spring-cloud-gateway/configuration.md` | `PO-3` | `AR-2`, `PO-2`, `SF-1`, `SF-2` | Overrides: route and header-trust policy -> `PO-2`/`AR-2`; **Redis Role and Prefixes** -> `SF-2`; **Runtime and TLS Invariants** trust portions -> `SF-1`. | Configuration contract | No unresolved ambiguity. |
| `design/architecture/microservices/spring-cloud-gateway/operations.md` | `PO-4` | `PO-2`, `PO-3` | Overrides: route mutation and edge behavior in **Dynamic Route Operational Guardrails** -> `PO-2`; deployment/scaling -> `PO-3`. | Operations contract | No unresolved ambiguity. |

## TCP Proxy Service

| Design source | Primary capability | Secondary handoffs | Heading or scope | Source classification | Ambiguity notes |
| --- | --- | --- | --- | --- | --- |
| `design/architecture/microservices/tcp-proxy-service/README.md` | `PO-2` | `AA-2`, `PO-3`, `PO-4`, `SF-1` | Overrides: readiness -> `AA-2`/`PO-4`; TLS/trust -> `SF-1`; deployment status -> `PO-3`. | Service overview | Raw Telnet is an accepted, hardened edge surface. |
| `design/architecture/microservices/tcp-proxy-service/api-contracts.md` | `PO-2` | `AA-2`, `PO-4`, `SF-1`, `SF-2` | Overrides: session-continuity portions of **Service Interactions** and **Failure Modes and Expectations** -> `AA-2`; retry/idempotency -> `SF-2`; **NotifyDisconnect Error Codes** and trust/correlation -> `SF-1`; **Logging and Correlation** -> `PO-4`. | API contract | `NotifyDisconnect` is advisory lifecycle signaling, not gameplay replay authority. |
| `design/architecture/microservices/tcp-proxy-service/configuration.md` | `PO-3` | `AR-2`, `PO-2`, `PO-4`, `SF-1`, `SF-2` | Overrides: **TLS and Trust Surfaces** and WebSocket mTLS -> `SF-1`/`PO-2`; **Redis Role Guidance** -> `SF-2`; connection limits -> `PO-2`/`AR-2`; tuning/metrics -> `PO-4`. | Configuration contract | No unresolved ambiguity. |
| `design/architecture/microservices/tcp-proxy-service/operations.md` | `PO-4` | `AA-2`, `PO-2`, `PO-3`, `SF-1` | Overrides: endpoint/Telnet proof -> `PO-2`/`AA-2`; deployment/local-development mechanics -> `PO-3`; mTLS verification -> `SF-1`. | Operations contract | No unresolved ambiguity. |
| `design/architecture/microservices/tcp-proxy-service/protocols.md` | `PO-2` | `AA-2`, `EA-1`, `PO-4`, `SF-1` | Overrides: login/resume portions of **Recommended Telnet Client Flows** and **Advanced Multi-Connection Scenarios** -> `AA-2`; player-facing disconnect and command framing -> `EA-1`; trust portions of **Hidden Attach Metadata** -> `SF-1`; metrics and abuse evidence -> `PO-4`. | Edge protocol contract | Telnet negotiation is edge protocol; gameplay command semantics remain `EA-1`. |
| `design/architecture/microservices/tcp-proxy-service/runtime-and-data.md` | `PO-2` | `AA-2`, `PO-4`, `SF-1`, `SF-2` | Overrides: **Redis Role and Prefixes** and optional cache mechanics -> `SF-2`; **Reconnection Behaviour at the Proxy Layer** -> `AA-2`; **Trust Surfaces Summary** -> `SF-1`; lifecycle observability -> `PO-4`. | Runtime/data contract | Proxy is stateless; Game Session owns resumable gameplay state. |

## World Management Service

| Design source | Primary capability | Secondary handoffs | Heading or scope | Source classification | Ambiguity notes |
| --- | --- | --- | --- | --- | --- |
| `design/architecture/microservices/world-management-service/README.md` | `GR-2` | `AA-2`, `AR-1`, `AR-3`, `GR-1`, `GR-3`, `PO-4`, `SF-2` | Whole file. Design-template ownership -> `AR-1`; lifecycle -> `AR-3`/`SF-2`; item/inventory exclusion -> `GR-3`; session/tick handoffs -> `AA-2`/`GR-1`. | Service overview | World owns topology, location, occupancy, and ambient state, not containment. |
| `design/architecture/microservices/world-management-service/api-contracts.md` | `GR-2` | `AA-2`, `AR-1`, `AR-2`, `AR-3`, `EA-1`, `GR-1`, `GR-3`, `PO-4`, `SF-1`, `SF-2` | Overrides: design APIs and digest portions -> `AR-1`/`SF-1`; generation runtime-default REST endpoints -> `AR-2`; upgrade/lifecycle/termination APIs -> `AR-3`/`SF-2`; **LOOK Snapshot Contract** and **LOOK Consumer Notes** presentation portions -> `EA-1`; entity joins -> `GR-3`; admission closure -> `AA-2`. | API contract | Spatial reads, design-time writes, and activation APIs remain separate seams. |
| `design/architecture/microservices/world-management-service/configuration.md` | `PO-3` | `AR-2`, `GR-2`, `SF-1` | Whole file. Runtime generation settings -> `AR-2`; world-specific values -> `GR-2`; secret/trust delivery -> `SF-1`. | Configuration contract | No unresolved ambiguity. |
| `design/architecture/microservices/world-management-service/operations.md` | `PO-4` | `GR-1`, `GR-2`, `SF-2` | Overrides: **Instance Cleanup and Expiry** and **Temporal Participation** -> `SF-2`; runtime world state -> `GR-2`; session/tick coordination -> `GR-1`. | Operations contract | No unresolved ambiguity. |
| `design/architecture/microservices/world-management-service/procedural-generation-control.md` | `AR-1` | `AR-2`, `AR-3`, `PO-1`, `PO-3`, `PO-4`, `SF-1`, `SF-2` | Overrides: runtime-default portions of **Procedural Generation Control APIs** -> `AR-2`; activation-facing artifact requirements -> `AR-3`; object-store delivery -> `PO-3`; audit in **Audit and Publish-Gating Notes** -> `PO-1`/`PO-4`; digest contract -> `SF-1`/`SF-2`. | Procedural-authoring design | Procedural generation is split between release-owned inputs and runtime-only policy; no taxonomy gap remains. |
| `design/architecture/microservices/world-management-service/runtime-and-data.md` | `GR-2` | `AR-1`, `AR-3`, `GR-1`, `GR-3`, `GR-4`, `PO-4`, `SF-1`, `SF-2` | Overrides: template portions of **Template and Runtime Ownership** and **Template Identifier Invariants** plus **Digest Input Manifest** -> `AR-1`/`SF-1`; **Replacement-Instance State Classification** -> `AR-3`; **Redis Role and Cache Usage** and **Instance-Scoped Population Schedule Contract** -> `SF-2`; containment legs of **Spatial Effects Contract** -> `GR-3`; gameplay effect semantics -> `GR-4`; scheduling/coordination -> `GR-1`. | Runtime/data contract | World location/ambient authority and Entity containment authority are explicitly joined by effect contracts. |
| `design/architecture/microservices/world-management-service/world-creation-workflow.md` | `AR-3` | `AA-2`, `AR-1`, `GR-1`, `GR-2`, `GR-3`, `PO-1`, `PO-4`, `SF-2` | Overrides: topology materialization in **Steps** -> `GR-2`; **Workflow Activity Idempotency** and **Instance Termination and Cleanup** workflow mechanics -> `SF-2`; entity cleanup -> `GR-3`; admission fencing -> `AA-2`/`GR-1`; operator status/audit -> `PO-1`/`PO-4`; published inputs -> `AR-1`. | Activation workflow | Some generation stages remain implementation gaps, but the capability allocation is unambiguous. |

## Coverage Proof

| Source scope | Discovered | Capability allocated | Exempt | Unallocated |
| --- | ---: | ---: | ---: | ---: |
| Microservices root | 3 | 1 | 2 | 0 |
| Account Service | 7 | 7 | 0 | 0 |
| Automation and Scripting Service | 6 | 6 | 0 | 0 |
| Entity Management Service | 5 | 5 | 0 | 0 |
| Game Design Service | 13 | 13 | 0 | 0 |
| Game Logic Service | 5 | 5 | 0 | 0 |
| Game Session Service | 6 | 6 | 0 | 0 |
| Logging and Admin Service | 8 | 8 | 0 | 0 |
| Social and Groups Service | 5 | 5 | 0 | 0 |
| Spring Cloud Gateway | 5 | 5 | 0 | 0 |
| TCP Proxy Service | 6 | 6 | 0 | 0 |
| World Management Service | 7 | 7 | 0 | 0 |
| **Total** | **76** | **74** | **2** | **0** |

The inventory covers every path returned by `rg --files design/architecture/microservices -g '*.md'`. Two microservice-root artifacts are explicit governance/template exemptions, all 74 capability-bearing files have one primary allocation, and no source remains unallocated or requires a taxonomy-gap classification.
