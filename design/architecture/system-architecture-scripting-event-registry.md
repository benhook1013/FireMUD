# FireMUD System Architecture: Scripting Event Registry

This document defines the canonical event-registry contract for built-in, custom, and service-specific scripting events. It exists so `eventType` admission, designer-visible binding choices, and producer authorization all read from one source of truth instead of scattered service-local assumptions.

Use this document for event-registry ownership, entry shape, publication/update flow, and read surfaces.
Use `system-architecture-scripting-control-plane-api.md` for event-ingress request and response contracts.
Use `system-architecture-scripting-dsl-reference-and-lifecycle.md` for DSL semantics and author-facing event behavior.

## Implementation Status

The target exact-fence and materialized-catalogue requirements below are not fully implemented. At the live handoff, the current request carries `scriptPatchVersion` but not `scriptPinEpoch`, so same-version work from an older epoch cannot be rejected there today. The current static registry also lacks an accepted materialized catalogue revision/digest and the applicable immutable schema identity/digest evidence required for its mutable `payloadSchemaRef` anchors. Registry-classified reload policy, transient attempt state, producer-specific durable retry, and policy-specific proof under [ADR 0173](./decisions/adr-0173-registry-classified-reload-admission-policy.md) are also unimplemented. See the [runtime execution implementation status](./system-architecture-scripting-runtime-execution.md#current-implementation-status); these gaps do not weaken the target registry contract.

## Purpose

The event registry answers four canonical questions for every scripting event:

- What does this `eventType` mean, and which payload schema/version is valid?
- Which service or principal is allowed to emit it?
- Which event-scope ingress identity fields are required before handler resolution, and which additional full Trigger Identity fields are required after each handler resolves?
- Which binding scopes and runtime behaviors are legal for handlers of that event?

No service may invent a private `eventType` contract outside this registry and still expect Automation & Scripting or Game Design to accept it.

## Ownership Model

- Producer services own event semantics and versioned schemas through service-owned manifests checked into the repo or generated from primary service contracts. For every `(eventType,eventSchemaVersion)`, exactly one producer manifest is the authoritative semantic owner; a manifest may authorize multiple producer principals without making them additional owners.
- Automation & Scripting mechanically validates and materializes those manifests into the one canonical runtime catalogue used for ingress admission, producer authorization, handler-resolution validation, and read APIs. Teams do not manually duplicate producer definitions into a second authoritative registry file.
- Game Design consumes the same registry as a read-only design-time dependency so editor/event-binding UI and publish validation never drift from runtime admission.
- Logging & Admin and other operator tooling read the registry for inspection only; they do not mutate entries directly.

## Registry Entry Contract

Every registry entry is keyed by `(eventType, eventSchemaVersion)`.

`allowedProducerPrincipals` may list multiple authorized emitters, but no second manifest may declare the same key. Duplicate declarations, including byte-equivalent copies, and any conflict in a required registry-entry field or source-level semantic for the same key are materialization errors; the catalogue has no merge or precedence rule for them.

Each entry must define at least:

- `eventType`
- `eventSchemaVersion`
- `ownerService`
- `allowedProducerPrincipals`
- `payloadSchemaRef`
- `requiredTriggerIdentityFields`
- `snapshotAuthority`
- `handlerInputManifestRequirements`
- `consistencyClass`
- `quotaClass`
- `replaySemantics`
- `reloadAdmissionPolicy`
- `allowedBindingScopes`
- `dryRunSupport`
- `deprecationStatus`

Each complete materialized catalogue must also expose an immutable catalogue revision identity, `catalogueDigestProfileVersion`, and `catalogueDigest` over the exact validated producer-manifest inputs and deterministically ordered entries. These catalogue-level fields identify which complete catalogue state supplied an entry; they are not part of the `(eventType, eventSchemaVersion)` key.

`catalogueDigestProfileVersion` identifies the explicitly versioned canonical digest profile under which `catalogueDigest` was computed and must be validated. The profile is owned by this catalogue contract and must define the complete digest input envelope, whether omitted fields are materialized to declared defaults or remain absent, exact UTF-8/canonical-byte serialization, map/object key ordering, set-member ordering, list ordering, hash algorithm, and digest encoding. There is no implicit default profile: a catalogue with a missing, unknown, or mismatched profile version is not accepted as complete, and consumers must not invent local serialization or hash rules. A profile change is a catalogue-contract change and must be versioned and carried with the digest.

Required semantics for those fields:

- `ownerService`
  - The service that owns the semantic contract and approves schema evolution.
- `allowedProducerPrincipals`
  - Exact service principals or internal caller classes allowed to emit the event.
- `payloadSchemaRef`
  - The authoritative schema or proto reference for the payload shape. The reference must resolve to a separately generated or retained immutable schema artifact (or an already content-addressed artifact). For a mutable documentation reference such as a built-in Markdown anchor, catalogue materialization resolves the reference to that artifact and records immutable `resolvedPayloadSchemaRevision` and `resolvedPayloadSchemaDigest` evidence over the artifact's exact canonical bytes under the identified catalogue digest profile; that profile owns the canonical artifact serialization. The selected evidence is retained on the entry and included in the canonical catalogue digest. A mutable document anchor alone is not immutable schema evidence.
- `requiredTriggerIdentityFields`
  - The exact identity fields applicable to this event at each stage: the event-scope ingress subset before handler resolution and the full handler Trigger Identity after each handler resolves, as defined by the [normative identity tables](./system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields).
  - Ordinary producer event-scope ingress must not require, fabricate, or use synthetic values for handler-only fields. In particular, `scriptId`, `pluginId`, `pluginVersionId`, and `bindingId` are unavailable before handler resolution; they become required only when the resolved handler's scope requires them.
  - Tenant-readiness `onLoad` is the deliberate exception for `scriptId`: its event-scope identity targets one script and includes that `scriptId` so Automation can derive the deterministic `scriptEventId` before handler resolution. This does not authorize fabricated runtime-ingress script IDs; `pluginId`, `pluginVersionId`, and `bindingId` remain post-resolution fields.
  - Scheduler/timer due candidates are another deliberate pre-handler exception: because Automation already materialized a durable schedule with its owner identity, the scheduler firing identity and deterministic event-scope `scriptEventId` include the schedule-owned `scriptId` and any schedule-owned `pluginId`, `pluginVersionId`, and `bindingId`. The scheduler must not fabricate a field absent from that schedule. This scheduler-owned exception does not authorize ordinary producer ingress to supply handler-only identity.
  - For every gameplay-originated event, this set must unconditionally include `playableStateNamespaceId` in the applicable gameplay/runtime identity, regardless of whether the realm is shared or isolated. The producer must resolve that namespace authoritatively before admission; a missing, unresolved, stale, or contradictory namespace fails closed. An explicitly declared non-gameplay contract may omit it only when the registry entry is classified outside gameplay/runtime and uses the non-gameplay Trigger Identity branch. `playableStateScope` remains separately persisted, exact-validated policy/routing/authorization/migration-fence evidence; it is excluded from Trigger Identity, event-scope, handoff, schedule, and other uniqueness/deduplication keys. See the [normative identity tables](./system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields).
  - **Target-state exact-fence requirement:** For gameplay/runtime and scheduler events, this set must include the exact Game Session `scriptPatchVersion` plus `scriptPinEpoch`; a version-only event is incomplete and cannot be admitted after a repin.
- `snapshotAuthority`
  - Declares the owner-specific input source used to seed the handler-scoped input manifest. Its values remain:
    - `PRODUCER_SUPPLIED_TOKEN`
    - `AUTOMATION_CAPTURED_AT_ADMISSION`
    - `NON_AUTHORITATIVE_NO_SNAPSHOT`
  - The value does not make one universal `readSnapshotToken` authoritative for every input. If an owner-specific token/selector is required, the entry must name its owner, schema/version, scope, causal-floor fields, and how the bounded value is recorded in the manifest.
- `handlerInputManifestRequirements`
  - A versioned descriptor of the handler-scoped manifest requirements consumed for this entry. Each owner-specific requirement names its owning service, schema/version, scope, selector-or-token kind when applicable, causal-floor fields, and bounded value and capture requirements.
  - Ingress and handler-manifest construction consume this field from the same canonical registry entry; they must not infer a parallel manifest contract from `snapshotAuthority`, payload fields, or local defaults.
  - The exact consistency matrix for this descriptor and `snapshotAuthority` is normative in [ADR 0090: Snapshot authority and handler-input-manifest consistency](./decisions/adr-0090-recorded-script-input-manifests-for-reproducible-evaluation.md#snapshot-authority-and-handler-input-manifest-consistency). Registry schema validation applies that matrix: disagreement between the enum and descriptor makes the registry definition invalid; neither field silently overrides the other.
- `consistencyClass`
  - The required read consistency for authoritative evaluation, such as `AUTHORITATIVE_REGION_TIMELINE`, `AUTHORITATIVE_INSTANCE_SNAPSHOT`, or `BEST_EFFORT`.
- `quotaClass`
  - The canonical budget-policy class used at handler admission and then persisted onto durable work items so execution-time tenant-budget handling reads the same classification instead of re-inferring from `eventType`.
  - Current built-in classes are `STANDARD_RUNTIME` for ordinary live gameplay/scheduler work and `PUBLISH_READINESS` for tenant-readiness `onLoad`.
- `replaySemantics`
  - Whether duplicate ingress is expected to be idempotent, rejected, or coalesced.
- `reloadAdmissionPolicy`
  - Exactly one of `REJECT_VISIBLE`, `DURABLE_RETRY`, or `SKIP_RECONCILE` as defined by [ADR 0173](./decisions/adr-0173-registry-classified-reload-admission-policy.md).
  - `DURABLE_RETRY` also declares the owning producer, maximum elapsed time or expiry, and stable parent-event identity behavior. Timer families retain their separate catch-up and continuity contract.
  - The first event-scope claim persists the selected policy, accepted catalogue revision/digest, owning producer, stable parent-event identity parameters, and, for `DURABLE_RETRY`, the exact maximum-elapsed or expiry boundary. A retry-eligible denial retains those values on the same durable parent claim and attempt history before returning `retryAfterMs`.
  - A `DURABLE_RETRY` reclaim uses only that persisted policy tuple and the same parent-event identity; it must not reread the current registry to extend expiry, change ownership or identity, or upgrade a prior `REJECT_VISIBLE`/`SKIP_RECONCILE` decision. Once admission succeeds, retries and recovery reuse the frozen admitted candidate order, coalescing membership, executable positions, activation fences, and handler manifests rather than resolving either the policy or handlers again. A later registry revision applies only to a new parent event admitted under that revision.
- `allowedBindingScopes`
  - Which target scopes are legal for handlers of this event, such as `ENTITY`, `REGION`, `GLOBAL`, or `COMMAND_ALIAS`.
- `dryRunSupport`
  - Whether the event may be emitted in dry-run/test mode.
- `deprecationStatus`
  - `ACTIVE`, `DEPRECATED`, or `REMOVED`, with removal treated as fail-closed at ingress.

## Publication and Update Flow

Registry changes follow one canonical path:

1. A producer-owning service adds or updates a versioned event-definition manifest in its primary contract surface.
2. Automation & Scripting mechanically discovers the declared source manifests and validates that each is well formed, names exactly one owner and authoritative producer manifest, resolves its schema reference (including a built-in Markdown anchor) to a separately generated or retained immutable schema artifact, records revision/digest evidence over the artifact's exact canonical bytes under the identified catalogue digest profile, and declares one authoritative snapshot and binding-scope contract for the key. The profile owns the artifact serialization; materialization must not hash a whole mutable Markdown source or recursively include the catalogue digest in the artifact evidence. It rejects missing manifests, duplicate declarations even when byte-equivalent, unresolved or mutable-without-digest schema references, and any conflict in a required registry-entry field or source-level semantic for the same `(eventType,eventSchemaVersion)`; it does not merge declarations or choose a winner.
3. Automation deterministically materializes the complete validated source set, assigns the immutable catalogue revision and `(catalogueDigestProfileVersion, catalogueDigest)`, validates the digest under exactly that identified profile, and atomically accepts that complete catalogue for ingress and reads. Failed or partial materialization leaves the prior complete accepted catalogue authoritative.
4. Game Design refreshes its read model from that same canonical catalogue before exposing the event in authoring UI or publish validation.

Before a producer emits any `(eventType,eventSchemaVersion)`, the exact producer manifest and payload schema for that key must be present in the same complete catalogue revision and `catalogueDigest` that is currently accepted for ingress. Producer authorization is evaluated against that accepted entry; a manifest or schema that exists only in an unaccepted, failed, partial, or newer materialization cannot authorize emission. If materialization fails, the prior complete catalogue remains active only for the keys it already accepted and cannot authorize a new key or schema version. Emission of a new or not-yet-accepted key/version therefore fails closed until its complete source set is accepted as one catalogue.

The producer-side activation gate is a control-plane/read gate, not an event-wire assertion. Before enabling a release or producer process to emit a new key/version, the producer's activation path must read the exact entry from the canonical registry read API (or an Automation-owned or authorized equivalent read of the same accepted catalogue) and verify its `eventType`, `eventSchemaVersion`, `ownerService`, authorized producer principal, `payloadSchemaRef`, applicable immutable schema identity/digest evidence, immutable catalogue revision, `catalogueDigestProfileVersion`, and `catalogueDigest`. Only a successful exact read against the currently accepted complete catalogue may mark that key/version enabled for the producer; an unaccepted, failed, partial, newer, or mismatched read leaves it disabled. This gate is evaluated at producer activation/release time and does not add catalogue fields to event identity or require producers to assert them on every event. Automation independently repeats the exact active-entry, principal, immutable-schema, and payload validation at ingress and records the accepted catalogue revision/digest, applicable immutable schema identity/digest evidence, and resolved owner/principal in the existing ingress audit, so a stale or incorrectly activated producer cannot bypass the enforcement authority; ingress rejection is the safety fence, not the producer activation mechanism.

Rules:

- Breaking payload, identity, producer-authorization, snapshot-authority, consistency, replay, reload-admission, quota, or binding-scope changes require a new `eventSchemaVersion`; compatible additive changes must pass declared mechanical compatibility validation.
- Removing an event requires a deprecation phase in the catalogue first; Game Design must stop offering new bindings before runtime ingress rejects it as `REMOVED`.
- Source manifests and catalogue entries required by supported patches remain retained for validation, runtime admission, rollback, and operator explanation. Removal or compaction is allowed only after the authoritative patch-support lifecycle proves that no supported patch still references the event schema version.

## Read Surfaces

The registry must expose one canonical read API family:

- `GetScriptEventDefinition(eventType, eventSchemaVersion)`
- `ListScriptEventDefinitions(filter...)`
- `StreamScriptEventRegistryChanges` or an equivalent replayable change feed

Minimum read payload:

- catalogue revision identity, `catalogueDigestProfileVersion`, and canonical `catalogueDigest`
- identity fields for the entry
- owner service
- payload schema reference plus the applicable content-addressed identity or resolved schema revision/digest
- allowed producers
- required trigger identity fields
- snapshot authority and consistency class
- versioned handler input manifest requirements, including owner, schema/version, scope, selector-or-token, causal-floor, and bounded-value requirements
- quota class
- replay semantics
- reload admission policy
- allowed binding scopes
- deprecation status
- last changed timestamp

Game Design, Logging & Admin, and documentation generators should all consume these reads rather than duplicating registry tables locally.

## Built-In Payload References

The built-in registry entries currently use the following canonical payload-schema references. These Markdown anchors are source documentation references for the built-in payload contracts; they are not by themselves immutable schema evidence. During catalogue materialization, each built-in entry must resolve its anchor to a separately generated or retained immutable schema artifact and record the artifact revision/digest over its exact canonical bytes under the identified catalogue digest profile. The artifact evidence is included in the catalogue digest envelope, but neither the artifact digest nor the catalogue digest hashes the whole mutable Markdown source or recursively hashes itself. This is registry/materialization evidence, not a new wire-payload or event-identity field.

### `onLoad` payload `v1`

Anchor target for `payloadSchemaRef`:
`design/architecture/system-architecture-scripting-event-registry.md#onload-payload-v1`

Minimum payload contract:

- `tenantId`
- `scriptPatchVersion`
- `scriptEventId`
- `isDryRun`

The tenant-readiness event-scope identity also requires the targeted `scriptId`; Automation uses it with the other applicable readiness fields to derive the deterministic `scriptEventId`. This readiness exception does not make `scriptId` a required or fabricated field for ordinary runtime ingress, and plugin/binding identity remains post-resolution.

No authoritative gameplay snapshot token is required. This payload exists for readiness-only initialization and must not imply mutable shared runtime ownership.

Registry policy:

- `quotaClass=PUBLISH_READINESS`
- This event must bypass ordinary live per-script quota and tenant runtime budget charging because it belongs to publish/readiness capacity rather than steady-state gameplay automation.

### `onCommand` payload `v1`

Anchor target for `payloadSchemaRef`:
`design/architecture/system-architecture-scripting-event-registry.md#oncommand-payload-v1`

Minimum payload contract:

- `commandId`
- `commandName`

Current optional producer enrichment:

- `commandAlias` when the producer can preserve the normalized built-in alias that triggered the command
- `actionCategory`
- `actionTags[]`

Current live binding-scope consumers on `onCommand` still support the baseline binding scopes (`GLOBAL`, `ENTITY`, and `REGION`) and additionally may resolve handlers from:

- `COMMAND_ALIAS`
- `ACTION_CATEGORY`
- `ACTION_TAG`

When a resolved handler is plugin-owned, Automation ingress must only admit that handler when the same plugin version is currently active for the runtime scope identified by the event trigger (`gameInstanceId`, `regionId`, `regionEpoch`). Plugin ownership on resolved handler audit/work-item rows must come from that script owner truth rather than from optional producer-supplied plugin identity on the ingress request.

Required Trigger Identity fields remain defined by the registry entry and the [normative identity tables](./system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields). Handler input-manifest requirements (owner versions, bounded values, causal floor, and any owner-specific `readSnapshotToken`) are separate from Trigger Identity. Payload contents are intentionally narrower than both contracts.

### `onSpawn` payload `v1`

Anchor target for `payloadSchemaRef`:
`design/architecture/system-architecture-scripting-event-registry.md#onspawn-payload-v1`

Minimum payload contract:

- `spawnReason`
- optional producer-owned spawn metadata needed by the owning service contract

The producer service owns any additional spawn-specific fields, but they must remain versioned under this payload reference.

### `onEnterRegion` payload `v1`

Anchor target for `payloadSchemaRef`:
`design/architecture/system-architecture-scripting-event-registry.md#onenterregion-payload-v1`

Minimum payload contract:

- `fromRegionId` when known
- `toRegionId`
- optional producer-owned movement metadata needed by the owning service contract

### `onLeaveRegion` payload `v1`

Anchor target for `payloadSchemaRef`:
`design/architecture/system-architecture-scripting-event-registry.md#onleaveregion-payload-v1`

Minimum payload contract:

- `fromRegionId`
- `toRegionId` when known
- optional producer-owned movement metadata needed by the owning service contract, such as `exitReason` for canonical gameplay disconnect teardown

### `onTimerExpire` payload `v1`

Anchor target for `payloadSchemaRef`:
`design/architecture/system-architecture-scripting-event-registry.md#ontimerexpire-payload-v1`

Minimum payload contract:

- `scheduleId`
- `dueTickId` or equivalent due-point identity
- optional timer payload fields owned by the scheduler contract

### `onInterval` payload `v1`

Anchor target for `payloadSchemaRef`:
`design/architecture/system-architecture-scripting-event-registry.md#oninterval-payload-v1`

Minimum payload contract:

- `scheduleId`
- `dueTickId` or equivalent cadence boundary identity
- optional scheduler-owned interval metadata needed by the runtime contract

If a future built-in payload changes incompatibly, it must publish a new `eventSchemaVersion` and a new payload reference target rather than rewriting these `v1` anchors in place.

## Ingress Contract Requirements

Automation & Scripting ingress must enforce the registry before handler resolution:

- reject unknown `(eventType, eventSchemaVersion)`
- reject unauthorized producer identity
- reject missing or forbidden registry-required owner input selectors/manifest seed fields according to the [ADR 0090 consistency matrix](./decisions/adr-0090-recorded-script-input-manifests-for-reproducible-evaluation.md#snapshot-authority-and-handler-input-manifest-consistency)
- reject bindings that target scopes the registry does not allow
- reject dry-run requests for events that do not support dry-run mode

The ingress validator and handler-manifest builder must consume the same normalized rule from the canonical registry entry. Each rejects missing or forbidden seed material at the earliest applicable stage and supplies no local default; a handler manifest that does not satisfy the selected matrix row cannot proceed to evaluation.

These are event-scope admission decisions, not handler-scope outcomes.

## Designer and Publish-Time Use

Game Design must use the registry for:

- showing which event types are available in the editor
- validating that a binding target scope is legal for the selected event
- validating required payload/schema version references for authored test fixtures
- hiding or warning on deprecated events

Publish validation must fail closed if it cannot read the canonical registry for the event types used by the artifact being published.

## Audit and Observability

Registry-driven admission must be observable:

- The current bounded `script_event_ingress_audit` fallback records the `eventType`, `eventSchemaVersion`, and producing service identity for every admitted or rejected custom/service-specific event before handler resolution
- **Target state:** For registry-governed events, that audit also records the accepted catalogue revision, `catalogueDigestProfileVersion`, `catalogueDigest`, applicable immutable schema identity/digest evidence, resolved `ownerService`, and authenticated producer principal used for the decision; these fields explain the exact registry state without becoming event identity
- ingress rejection metrics must tag bounded reasons such as `unknown_event_type`, `unauthorized_producer`, `missing_owner_input`, or `illegal_binding_scope`; an owner-specific missing token may use a more specific bounded reason when that owner contract requires one
- registry change events must be replayable so operator read models can explain why an event became valid, deprecated, or rejected

## Related Documents

- [Scripting & Automation Framework](./system-architecture-scripting.md)
- [Scripting Control Plane API](./system-architecture-scripting-control-plane-api.md)
- [Scripting DSL Reference & Event Lifecycle](./system-architecture-scripting-dsl-reference-and-lifecycle.md)
- [Automation & Scripting Service API Contracts](./microservices/automation-scripting-service/api-contracts.md)
