# ADR 0111: Typed Bounded Gameplay Effect Extension

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `CMD-02`
- Primary capability: `GR-4.1` extensible gameplay rules and actions
- Affected capabilities: `GR-3.1`, `AR-1.1`, `AR-1.5`, `AR-3.3`, `SF-1.1`, `AS-1.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of creator extensibility, targeting and effect authority, hot-path cost, and the value of protecting against unsafe or faulty internal behavior

## Context

FireMUD needs game-authored commands and abilities without allowing authored data or automation to become an unrestricted mutation language inside the gameplay process. A completely general scripting boundary would maximize creator freedom, but it would also move authorization, targeting, atomicity, replay, and performance-critical state changes outside typed domain ownership.

The extension boundary must remain useful without imposing routine signature verification or distributed authorization work on every gameplay action merely to defend against a hypothetical rogue internal service.

## Decision

Gameplay-changing commands declare immutable, release-pinned typed effects. Game Logic resolves bounded target plans and action outcomes from typed policies and owner-provided fact snapshots. Entity Management owns canonical actor identity, actor state, costs, cooldowns, and effect mutation. Game Session owns command admission into the tick system.

The gameplay process does not execute arbitrary code, SQL/DML text, plugin bodies, or unvalidated script payloads. A future bounded declarative plan grammar may compose registered predicates and effect primitives, but every new fact source or mutation primitive requires an explicit platform extension with an owning service, typed schema, validation, authorization, replay, and resource bounds.

Automation and plugin DSL graphs may select or request the same registered domain commands through their sandboxed asynchronous pathway. They do not become remotely invoked arbitrary logic on the hot gameplay path and cannot bypass target resolution or domain mutation checks.

This decision relies on authenticated service transport, scoped execution context, owner-side validation, immutable release identity, and idempotent effect identity. It does not require every ordinary internal gameplay call to carry a separately signed per-action authorization object. Higher-risk account, operator, billing, or real-currency operations may require stronger step-up and authorization workflows at their own owning boundaries; they do not broaden routine gameplay processing.

## Consequences

- Creator-authored commands can compose an expanding catalog of safe behavior without deploying bespoke handlers for every command.
- Core ownership, atomicity, authorization, and replay rules remain enforceable even when an authored declaration or internal caller is faulty.
- Hot gameplay does not incur arbitrary script execution or per-action cryptographic verification.
- Completely novel mechanics require a platform primitive or a bounded future grammar extension before creators can use them.
- The platform must maintain schemas, validators, compatibility rules, and focused proof for each registered effect and predicate kind.

## Alternatives Considered

### Execute Arbitrary Scripts or Plugin Code in the Gameplay Process

Rejected because it would make targeting, resource use, mutation authority, latency, and replay depend on unbounded authored behavior in the hot path.

### Require Independently Signed Authorization for Every Internal Action

Rejected for routine gameplay. Authenticated service identity, scoped execution context, immutable declaration identity, and owner-side validation provide the useful boundary without the processing and operational burden of minting and verifying a signed authorization artifact for every action.

### Keep Every Gameplay Command as Platform-Written Code

This is the strongest simpler alternative for runtime safety, but it makes ordinary creator variation require platform deployments and undercuts game-authored behavior. Typed registered effects preserve the safety boundary while allowing data-driven composition.

## Implementation and Proof Obligations

Contracts must preserve exact release and declaration identity, canonical source actor identity, typed target policies, bounded owner fact snapshots, effect idempotency, cost/cooldown semantics, target-leg outcomes, and fail-closed handling of unknown schemas or facts. Tests must prove that aliases, scripts, plugins, retries, and direct internal calls cannot bypass the same target and mutation validation.

The current implementation proves only the first self-targeted `APPLY_ACTION_STATE` declaration and retains transitional built-in routing. General target plans, multi-effect composition, costs, cooldowns, cross-region outcomes, and the wider primitive catalog remain implementation and proof work.

## Reversibility and Revisit Triggers

New safe predicates and effect kinds can be added incrementally. Revisit the grammar boundary if measured creator requirements repeatedly need compositions that the typed catalog cannot express, but retain owner-side typed mutation and resource bounds. Stronger per-operation authorization remains appropriate for separately classified high-risk account, administrative, billing, or real-currency workflows rather than being imposed on all gameplay.
