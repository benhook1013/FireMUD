# FireMUD System Architecture: Scripting DSL & Event Lifecycle

This document is a **short hub** for the scripting DSL and event lifecycle. It explains, at a high level, what the scripting DSL is and points different audiences to the detailed documents they should read.

It is a companion to:

- `design/architecture/system-architecture-scripting.md` – high-level hub for scripting and automation.
- `design/architecture/system-architecture-scripting-examples-and-patterns.md` – worked examples and common behaviors.
- `design/architecture/system-architecture-scripting-quotas-and-operations.md` – sandboxing, quotas, and operational guidance.

If you are new to scripting and automation overall, start with:

- `design/architecture/system-architecture-scripting.md` – especially **Who Should Read What** and **Where to Find Details**, which describe how this DSL-focused set of docs fits into the wider scripting & automation framework.

For service-level implementation details, also see:

- Automation & Scripting Service README: `design/architecture/microservices/automation-scripting-service/README.md`
- Tick System and Runtime Design: `design/architecture/system-architecture-ticks.md`
- Versioning & Runtime Configuration: `design/architecture/system-architecture-versioning-runtime.md`

## Table of Contents

- [Overview](#overview)
- [Docs for Game Designers](#docs-for-game-designers)
- [Docs for Implementers & Backend Developers](#docs-for-implementers--backend-developers)

---

## Overview

FireMUD’s scripting DSL lets you define behavior as **visual graphs of components** instead of raw code. Scripts are authored in the Game Design Service’s visual editor, compiled into a safe, sandboxed representation by the Automation & Scripting Service, and executed in response to game events and timers.

From a systems perspective:

- Game events and timers create **triggers** for specific entities or regions.
- The Automation & Scripting Service runs the corresponding script graphs once per trigger, applying validation, loop safety, quotas, and determinism rules.
- The script run produces **commands** that are enqueued into the same tick-based queues used by player commands.
- The **tick system** then applies those commands during its normal ticks, following the idempotency and fairness rules described in `design/architecture/system-architecture-ticks.md` and `design/architecture/system-architecture-transactions.md`.

The detailed semantics of this pipeline now live in focused documents for different audiences.

---

## Docs for Game Designers

If you are a **game designer or content author**, start with:

- `design/architecture/system-architecture-scripting-dsl-for-designers.md`

That document explains:

- What the scripting DSL is and how it fits into the overall scripting architecture.
- Core concepts in designer terms: events, triggers, timers, counters, predicates, conditions, and actions.
- How to build behaviors in the Game Design Service’s visual editor.
- How validation, loop safety, and common runtime outcomes show up in tooling, and where to look when debugging.

You should also pair it with:

- `design/architecture/system-architecture-scripting-examples-and-patterns.md`
- `design/architecture/system-architecture-scripting-quotas-and-operations.md`

and the Game Design Service UX docs:

- `design/architecture/microservices/game-design-service/web-visual-interface.md`
- `design/architecture/microservices/game-design-service/world-editing-tools.md`

These documents together give a complete, designer-focused view of how to author, validate, publish, and debug scripts.

---

## Docs for Implementers & Backend Developers

If you are an **implementer or backend developer**, use:

- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`

as the canonical **spec** for:

- Terminology and glossary (game tick, automation/script tick, automation queue, tick heartbeat, etc.).
- The full script execution lifecycle (triggers, DSL runs, script work items, tick commands).
- `scriptEventId` generation, uniqueness scope, lifecycle, and deduplication rules.
- Supported script events and how custom/service-specific events integrate.
- DSL semantics: graph model, predicates, node types, type safety, loop safety analysis, and runtime budgets.
- Determinism and allowed non-determinism, including seeded RNG and tick-based time constraints.
- Integration with the Tick System and Game Logic, including command queues, ordering, and Redis key patterns.
- Scheduler leadership and coordination, script timers vs tick timers, and hot reload and resume behavior.
- Failure modes, error handling, and idempotent integration with downstream services.

When implementing or changing behavior in the Automation & Scripting Service, Tick System, or domain services that interact with scripts, **treat that reference document as the source of truth** for scripting semantics and contracts.
