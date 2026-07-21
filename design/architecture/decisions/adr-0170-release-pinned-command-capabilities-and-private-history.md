# ADR 0170: Release-Pinned Command Capabilities and Private History

## Status

Accepted

## Decision Record

- Decision date: 2026-07-21
- Decision key: `CMD-06`
- Disposition: `revised`
- Primary capability: `EA-1.1` player command availability
- Affected capabilities: `AR-2.2`, `EA-1.3`, `AA-2.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of capability expansion, authored/plugin commands, settings authority, help/admission consistency, retained command privacy, and current implementation reality

## Context

FireMUD correctly separates command availability, accepted-command history, and reconnect transcript, but the current capability implementation is a closed platform enum repeated through settings, persistence, protobuf, mapping, resolution, admission, help, and tests. That remains appropriate for a small platform-owned catalog but does not scale to the planned versioned game and plugin command model.

Canonical authored-command prose already requires capability metadata, while current publication and runtime readers ignore it and treat authored commands as mandatory. Command history is bounded only by entry count, can retain low-activity raw input indefinitely, and disabling the capability hides history without deleting or permanently fencing retained rows.

## Decision

Command availability, accepted-command history, and reconnect transcript remain separate contracts.

Platform capabilities remain strongly typed. The platform catalog may mark a capability mandatory, non-disableable, or configurable through the settings-precedence rules. Games and plugins may extend availability through a bounded registry of stable namespaced capability keys declared by their immutable published artifacts.

Every extension declaration includes stable identity, owner, description, default availability, settings eligibility, and the commands or other entry points it gates. The release manifest pins the complete applicable capability registry. Publication rejects duplicate, reserved, malformed, unknown-owner, or unresolved requirements. Runtime admission fails closed for an unknown, stale, or unregistered required key.

Command definitions carry a bounded set of required capability keys. Game Session admission and `HELP` use the same effective set. Any alternate ingress that invokes the same behavior, including Automation or a direct owning-service operation, enforces the same requirement so a disabled command family cannot be bypassed by avoiding the text front door.

Extension keys are not an unrestricted settings map. Overrides exist only for registry-declared keys, follow each key's declared configurable levels, and remain subject to schema bounds and operator caps under ADR 0012. Platform-reserved keys and mandatory invariants cannot be weakened by a game or plugin declaration.

Accepted-command history remains a private convenience feature, not transcript, audit, or replay authority:

- every command is explicitly recordable or non-recordable; authored/plugin commands default to non-recordable;
- a recordable command whose arguments can contain secrets requires a bounded redaction/projection contract rather than raw-line persistence;
- retention is bounded by both entry count and maximum age under platform hard limits;
- disabling command history synchronously stops capture and display, advances the history policy generation, and creates an asynchronous purge obligation for the affected scope;
- re-enabling begins with the new generation and cannot expose pre-disable entries, even if their physical purge is still completing;
- explicit account/privacy erasure remains able to purge history independently of capability configuration.

## Consequences

- Platform safety capabilities retain compile-time types and non-bypassable semantics.
- Games and plugins can add availability families without a platform protobuf and service release for every new key.
- Release publication, settings UI, and every behavior ingress must understand a pinned capability registry.
- History becomes predictably private and time-bounded at the cost of generation-aware cleanup and redaction metadata.
- The current four-boolean override shape, authored-command reader, and count-only history retention require convergence.

## Alternatives Considered

### Keep Every Capability in a Fixed Platform Enum

This maximizes compile-time exhaustiveness but makes each game/plugin availability family a cross-service platform schema change. It is credible only if authored behavior can never introduce independently configurable families.

### Use Arbitrary String Capability Maps

This is easy to extend but permits typos, stale keys, unowned policy, inconsistent defaults, and bypasses. Namespaced keys are accepted only through immutable publication and validation.

### Let Disabled History Remain Retained and Reappear

This makes toggling reversible but gives disablement weak privacy meaning and can unexpectedly resurrect old input. A generation fence plus asynchronous purge keeps the settings operation responsive without resurrection.

### Merge History with Reconnect Transcript

The two surfaces have different content, privacy, retention, and correctness purposes. Combining them would make a convenience setting alter reconnect behavior.

## Implementation and Proof Obligations

Implementation must add registry declarations to published artifacts and manifests, validate requirements, resolve registered settings, and enforce them consistently at help, command admission, Automation, and owning-service entry points. Proof must cover reserved/unknown/stale keys, plugin removal and upgrade, mandatory platform keys, settings precedence, alternate ingress, and pinned-release behavior.

History proof must cover default non-recordability, redaction, count and age expiry, disable/re-enable races, asynchronous purge retry, generation isolation, cross-tenant/game/character isolation, explicit erasure, and independence from reconnect transcript behavior.

## Reversibility and Revisit Triggers

New capability declarations are additive within a release; removing or renaming a key requires versioned migration of every referencing command and setting. Revisit the hybrid registry if measured use shows that only the platform catalog is ever needed, or if capabilities expand into a broader entitlement/policy language that no longer fits boolean availability.
