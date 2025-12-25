# FireMUD Tick System: Failures & Operations

This document focuses on **failure modes, recovery flows, and operational guidance** for the tick system.

It is aimed at both developers and operators who need to understand what happens when executors crash, Redis has issues, or ticks must be replayed.

For the canonical, detailed design, see `design/architecture/system-architecture-ticks.md`.

## What This Covers

- Crash recovery and replay behavior.
- Idempotency rules tied to `tickId`.
- Handling stuck or partial tick entries.
- Design checklist for new tick-driven commands.

## Key Sections in the Main Tick Doc

The following sections in `system-architecture-ticks.md` contain the main failure-handling and operational rules:

- **Crash Recovery and Replay** – how executors recover from failure and resume processing safely.
- **Domain Idempotency Rules (TickId in PostgreSQL)** – how tick IDs enforce idempotent domain mutations.
- **Design Checklist for New Tick-Driven Commands** – review checklist for new commands to ensure they follow tick invariants.
- **Tick Execution and Redis Integration** – failure scenarios and invariants around the canonical commit pattern.
- **Cross-Region Command Execution and Result Relay** – constraints for cross-region retries and replay.

When implementing new failure-handling flows or adding operational procedures, ensure the detailed behavior is captured in `system-architecture-ticks.md` and reflected in the appropriate runbooks (for example, Redis incident runbooks).

