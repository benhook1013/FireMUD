# FireMUD System Architecture: Scripting Event Registry

This document defines the canonical event-registry contract for built-in, custom, and service-specific scripting events. It exists so `eventType` admission, designer-visible binding choices, and producer authorization all read from one source of truth instead of scattered service-local assumptions.

Use this document for event-registry ownership, entry shape, publication/update flow, and read surfaces.
Use `system-architecture-scripting-control-plane-api.md` for event-ingress request and response contracts.
Use `system-architecture-scripting-dsl-reference-and-lifecycle.md` for DSL semantics and author-facing event behavior.

## Purpose

The event registry answers four canonical questions for every scripting event:

- What does this `eventType` mean, and which payload schema/version is valid?
- Which service or principal is allowed to emit it?
- What trigger identity and snapshot fields are required at ingress?
- Which binding scopes and runtime behaviors are legal for handlers of that event?

No service may invent a private `eventType` contract outside this registry and still expect Automation & Scripting or Game Design to accept it.

## Ownership Model

- Automation & Scripting owns the canonical runtime registry used for ingress admission, producer authorization, and handler-resolution validation.
- Source definitions are service-owned, versioned manifests checked into the repo or generated from primary service contracts; producer services own the correctness of their event schema and semantics.
- Game Design consumes the same registry as a read-only design-time dependency so editor/event-binding UI and publish validation never drift from runtime admission.
- Logging & Admin and other operator tooling read the registry for inspection only; they do not mutate entries directly.

This means source ownership is distributed, but canonical runtime truth is centralized.

## Registry Entry Contract

Every registry entry is keyed by `(eventType, eventSchemaVersion)`.

Each entry must define at least:

- `eventType`
- `eventSchemaVersion`
- `ownerService`
- `allowedProducerPrincipals`
- `payloadSchemaRef`
- `requiredTriggerIdentityFields`
- `snapshotAuthority`
- `consistencyClass`
- `quotaClass`
- `replaySemantics`
- `allowedBindingScopes`
- `dryRunSupport`
- `deprecationStatus`

Required semantics for those fields:

- `ownerService`
  - The service that owns the semantic contract and approves schema evolution.
- `allowedProducerPrincipals`
  - Exact service principals or internal caller classes allowed to emit the event.
- `payloadSchemaRef`
  - The authoritative schema or proto reference for the payload shape.
- `requiredTriggerIdentityFields`
  - The exact Trigger Identity fields that must be present, such as `tenantId`, `gameInstanceId`, `regionId`, `regionEpoch`, `entityId`, or `scriptEventId`.
  - For gameplay-originated events whose producer already resolved shared-versus-isolated realm state, this set must also include `playableStateScope` so durable ingress/work-item identity, timer follow-up work, and operator read models do not collapse distinct playable-state namespaces that happen to share the same tenant and instance identifiers.
- `snapshotAuthority`
  - One of:
    - `PRODUCER_SUPPLIED_TOKEN`
    - `AUTOMATION_CAPTURED_AT_ADMISSION`
    - `NON_AUTHORITATIVE_NO_SNAPSHOT`
  - If a token is required, the entry must name the required token fields and timeline.
- `consistencyClass`
  - The required read consistency for authoritative evaluation, such as `AUTHORITATIVE_REGION_TIMELINE`, `AUTHORITATIVE_INSTANCE_SNAPSHOT`, or `BEST_EFFORT`.
- `quotaClass`
  - The event-scope quota policy used before handler resolution.
- `replaySemantics`
  - Whether duplicate ingress is expected to be idempotent, rejected, or coalesced.
- `allowedBindingScopes`
  - Which target scopes are legal for handlers of this event, such as `ENTITY`, `REGION`, `GLOBAL`, or `COMMAND_ALIAS`.
- `dryRunSupport`
  - Whether the event may be emitted in dry-run/test mode.
- `deprecationStatus`
  - `ACTIVE`, `DEPRECATED`, or `REMOVED`, with removal treated as fail-closed at ingress.

## Publication and Update Flow

Registry changes follow one canonical path:

1. A producer-owning service adds or updates a versioned event-definition manifest in its primary contract surface.
2. Automation & Scripting validates that the manifest is well-formed, names one owner, and does not redefine an existing `(eventType, eventSchemaVersion)` incompatibly.
3. The validated definition is materialized into the canonical event registry used by ingress admission.
4. Game Design refreshes its read model from that same canonical registry before exposing the event in authoring UI or publish validation.

Rules:

- Breaking payload or identity changes require a new `eventSchemaVersion`.
- Narrowing allowed producers, changing snapshot authority, or changing allowed binding scopes is a breaking change unless explicitly proven compatible.
- Removing an event requires a deprecation phase in the registry first; Game Design must stop offering new bindings before runtime ingress rejects it as `REMOVED`.

## Read Surfaces

The registry must expose one canonical read API family:

- `GetScriptEventDefinition(eventType, eventSchemaVersion)`
- `ListScriptEventDefinitions(filter...)`
- `StreamScriptEventRegistryChanges` or an equivalent replayable change feed

Minimum read payload:

- identity fields for the entry
- owner service
- payload schema reference
- allowed producers
- required trigger identity fields
- snapshot authority and consistency class
- quota class
- replay semantics
- allowed binding scopes
- deprecation status
- last changed timestamp

Game Design, Logging & Admin, and documentation generators should all consume these reads rather than duplicating registry tables locally.

## Built-In Payload References

The built-in registry entries currently use the following canonical payload-schema references:

### `onLoad` payload `v1`

Anchor target for `payloadSchemaRef`:
`design/architecture/system-architecture-scripting-event-registry.md#onload-payload-v1`

Minimum payload contract:

- `tenantId`
- `scriptPatchVersion`
- `scriptEventId`
- `isDryRun`

No authoritative gameplay snapshot token is required. This payload exists for readiness-only initialization and must not imply mutable shared runtime ownership.

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

Current live binding-scope consumers on `onCommand` may resolve handlers from:

- `COMMAND_ALIAS`
- `ACTION_CATEGORY`
- `ACTION_TAG`

When a resolved handler is plugin-owned, Automation ingress must only admit that handler when the same plugin version is currently active for the runtime scope identified by the event trigger (`gameInstanceId`, `regionId`, `regionEpoch`). Plugin ownership on resolved handler audit/work-item rows must come from that script owner truth rather than from optional producer-supplied plugin identity on the ingress request.

Required Trigger Identity and snapshot fields remain defined by the registry entry itself (`tenantId`, `gameInstanceId`, `regionId`, `regionEpoch`, `entityId`, `scriptPatchVersion`, `scriptEventId`, and `readSnapshotToken` where required). Payload contents are intentionally narrower than full Trigger Identity.

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
- reject missing or malformed registry-required snapshot fields
- reject bindings that target scopes the registry does not allow
- reject dry-run requests for events that do not support dry-run mode

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

- `script_event_ingress_audit` records the `eventType`, `eventSchemaVersion`, and producing service identity for every admitted or rejected custom/service-specific event before handler resolution
- ingress rejection metrics must tag bounded reasons such as `unknown_event_type`, `unauthorized_producer`, `missing_snapshot_token`, or `illegal_binding_scope`
- registry change events must be replayable so operator read models can explain why an event became valid, deprecated, or rejected

## Related Documents

- [Scripting & Automation Framework](./system-architecture-scripting.md)
- [Scripting Control Plane API](./system-architecture-scripting-control-plane-api.md)
- [Scripting DSL Reference & Event Lifecycle](./system-architecture-scripting-dsl-reference-and-lifecycle.md)
- [Automation & Scripting Service API Contracts](./microservices/automation-scripting-service/api-contracts.md)
