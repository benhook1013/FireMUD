# FireMUD Tick System: Concepts & Invariants

This document summarizes the **core concepts and invariants** of the FireMUD tick system. It is aimed at developers and reviewers who need to understand fairness, region authority, and idempotency without reading the full runtime design in `system-architecture-ticks.md`.

## What This Covers

- Hybrid tick model and player/AI fairness.
- Region authority, leadership, and locking.
- High-level retry, isolation, and idempotency rules.

## Key Sections in the Main Tick Doc

The following sections in `system-architecture-ticks.md` provide the primary conceptual model:

- **Hybrid Tick Model** – overall tick loop structure and fairness guarantees.
- **Tick Events & Heartbeat Stream** – how other services (such as Automation & Scripting) follow the tick timeline via gRPC streams.
- **Region Authority and Tick Executor** – single-writer semantics per region and executor lease behavior.
- **Distributed Locking** – how locks are scoped and enforced to avoid deadlocks.
- **Smart Retry and Conflict Resolution** – canonical patterns for retries under contention.
- **Tick Regions and Parallel Execution** – how work is partitioned to scale horizontally.
- **Region Sizing and Ownership** – guidance for region boundaries and ownership changes.
- **Timeout and Fairness Policy** – how long-running commands are handled without starving others.
- **Isolation and Replay Safety** – invariants that ensure deterministic replays and safe recovery.
- **Timers and Time Scaling** – how tick timers interact with scaled time and the heartbeat stream.

Refer to those sections for the authoritative wording and examples.

## Invariants to Preserve

When designing new tick-driven features, keep these invariants in mind:

- **Single authoritative executor per region** – all tick-side state for a `<tenantId, regionId>` is owned by one executor at a time.
- **One action per entity per tick** – fairness is enforced by limiting how many commands a single entity can execute per tick.
- **No cross-region locks** – cross-region interactions are modeled as messages, not shared locks or multi-region transactions.
- **Idempotent side effects** – tick IDs and effect guards must be used so that replays after failure do not double-apply mutations.

The main tick document contains the detailed rules and Redis key shapes behind each of these points.

