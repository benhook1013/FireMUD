# ADR 0112: Typed Bounded Gameplay Effect Extension

## Status

Accepted

## Implementation Status

The current implementation proves only the first self-targeted `APPLY_ACTION_STATE` declaration and retains transitional built-in routing. General target plans, multi-effect composition, costs, cooldowns, cross-region outcomes, and the wider primitive catalog remain implementation and proof work.

## Decision Record

- Decision date: 2026-07-20
- Decision key: `CMD-02`
- Primary capability: `GR-4.1` extensible gameplay rules and actions
- Affected capabilities: `GR-3.1`, `AR-1.1`, `AR-1.5`, `AR-3.3`, `SF-1.1`, `AS-1.2`
- Decision owner: FireMUD human product and architecture owner
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Accepted
- Review source: `CMD-02`

## Context

FireMUD needs game-authored commands and abilities without turning authored data or automation into an unrestricted mutation language inside the gameplay process. A general scripting boundary would move authorization, targeting, atomicity, replay, and performance-sensitive state changes outside typed domain ownership.

## Decision

Gameplay-changing commands declare immutable, release-pinned typed effects. Game Logic resolves bounded target plans and action outcomes from typed policies and owner-provided fact snapshots. Entity Management owns canonical actor identity, actor state, costs, cooldowns, and effect mutation. Game Session owns command admission into the tick system.

The gameplay process does not execute arbitrary code, SQL/DML text, plugin bodies, or unvalidated script payloads. A future bounded declarative plan grammar may compose registered predicates and effect primitives, but every new fact source or mutation primitive requires an explicit platform extension with an owning service, typed schema, validation, authorization, replay, and resource bounds.

Automation and plugin DSL graphs may request the same registered domain commands through their sandboxed asynchronous pathway. They do not become remotely invoked arbitrary logic on the hot gameplay path and cannot bypass target resolution or domain mutation checks.

Routine gameplay relies on authenticated service transport, scoped execution context, immutable release identity, and idempotent effect identity. It does not require a separately signed authorization artifact for every ordinary internal action. Higher-risk account, operator, billing, or real-currency operations may require stronger authorization at their own owning boundaries.

## Consequences

- Creator-authored commands can compose an expanding catalog of safe behavior without bespoke handlers for every command.
- Core ownership, atomicity, authorization, and replay rules remain enforceable when authored declarations or internal callers are faulty.
- Completely novel mechanics require a platform primitive or bounded grammar extension before creators can use them.
- The platform must maintain schemas, validators, compatibility rules, and focused proof for each registered effect and predicate kind.

## Implementation and Proof Obligations

Contracts must preserve exact release and declaration identity, canonical source actor identity, typed target policies, bounded owner fact snapshots, effect idempotency, cost/cooldown semantics, target-leg outcomes, and fail-closed handling of unknown schemas or facts. Tests must prove aliases, scripts, plugins, retries, and direct internal calls cannot bypass the same target and mutation validation.

## Related Contracts

- [Player Command Model](../system-architecture-player-command-model.md)
- [Game Logic API contracts](../microservices/game-logic-service/api-contracts.md)
- [Entity Management API contracts](../microservices/entity-management-service/api-contracts.md)
- [Game Session API contracts](../microservices/game-session-service/api-contracts.md)
- [Game Design ability/action tools](../microservices/game-design-service/ability-action-tools.md)
