# FireMUD Tick System: Execution Flows

This document focuses on **how tick-driven commands execute end-to-end**, including per-command phases, staging/commit, and cross-region flows.

It is intended for developers implementing or reviewing tick-driven commands, Lua scripts, and integration points with other services.

For the canonical, detailed design, see `design/architecture/system-architecture-ticks.md`.

## What This Covers

- Per-command execution phases.
- Tick staging and commit flows.
- Redis integration and Lua patterns.
- Cross-region command execution and result relay.

## Key Sections in the Main Tick Doc

The following sections in `system-architecture-ticks.md` describe execution behavior:

- **Per-Command Execution Phases** – phases a tick-driven command passes through, including an example (Cross-Region Lifesteal).
- **Tick Execution Flow** – how the executor pulls commands and coordinates work for each tick.
- **Tick Staging and Commit Flow** – how state transitions are staged, validated, and committed atomically.
- **Tick Execution and Redis Integration** – how Redis keys and Lua scripts are used to implement the commit pattern.
- **Cross-Region Command Execution and Result Relay** – patterns for sending commands across regions without shared locks.
- **Tick Chaining and Reentrant Effect Control** – how chained effects are controlled to avoid unbounded recursion or reentrancy.

When changing execution behavior, update these sections in the main tick document first, then reconcile any differences here.

