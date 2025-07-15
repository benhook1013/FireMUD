# 🔧 FireMUD System Architecture: Scripting & Automation Framework

This document outlines how FireMUD executes custom in-game behavior through a sandboxed scripting framework. It complements the [Automation & Scripting Service](./microservices/automation-scripting-service/README.md) and expands on the extensibility goals in the [core requirements](../project-management/core-requirements.md).

---

## 🎯 Goals

- Enable **event-driven scripting** and **NPC automation** so worlds feel alive even without active players.
- Keep the system **extensible** while preventing malicious or abusive scripts.
- Support **persistence** and versioned updates so game creators can iterate safely.

## 🧩 Component‑Based Scripting DSL

- Scripts are authored in a **visual editor** where designers assemble **predefined components** (conditions, actions, timers, etc.).
- Each component maps to a safe, well-defined operation in the Automation & Scripting Service.
- The editor exports structured data—**not raw Lua or general-purpose code**—which the service compiles into execution units.
- This approach prevents arbitrary behavior and limits scripts to the capabilities exposed by the platform.

## Supported Script Events

Scripts may register handlers for a set of standard lifecycle events. The Automation & Scripting Service emits these events and queues them as commands so they run during the normal tick flow.

- `onLoad` – when the script is first loaded or hot reloaded
- `onSpawn` – when the associated entity enters the world
- `onDeath` – when the entity dies
- `onDestroy` – when the entity is permanently removed
- `onEnterRegion` – when the entity moves into a new region
- `onLeaveRegion` – when the entity leaves a region
- `onTimerExpire` – when a scheduled timer finishes
- `onCommand` – when a player targets the entity with a command
- `onInterval` – periodic execution at a configured rate

## 🔒 Sandboxing & Security

- Script execution occurs in a **sandbox** with restricted APIs and resource limits.
- Components interact with the **Game Logic Service** through validated gRPC calls.
- The service enforces **per-script quotas** (CPU time, memory, tick budget) to contain runaway logic.

## ⚙️ Integration with Game Logic & Tick System

- **Scripts do not execute inside the tick system.** The Automation & Scripting Service evaluates scripts independently—on a schedule, via timers, or in response to events—and enqueues the resulting commands into each entity's command queue.
- These queued commands run during the **next tick cycle** via the normal Game Session and Game Logic flow, ensuring deterministic, replayable behavior that follows the tick system's fairness and retry rules.
- Script evaluation never blocks or interferes with tick execution. Scripts can still react to world events, NPC states, or timers provided by the tick system.
- Script-generated commands—like any gameplay command—may fail due to lock contention or target remote regions. These cases are automatically handled by the Game Session Service via standard tick rescheduling and cross-region routing logic.
- The Automation & Scripting Service only determines which commands to inject. It may query world state via gRPC but never mutates entity or world data directly—every action passes through the Game Session Service so tick regions remain consistent.

## 🔄 Deployment & Versioning

- Script definitions are stored in the **Game Design Service** and versioned alongside other game assets.
- Designers can deploy updated scripts without redeploying code. The Automation & Scripting Service retrieves the current live versions as needed.
- Script-only patches create a `scriptPatchVersion` tied to a `baseVersionId` so new behaviors can be loaded on the fly.
- The Game Session Service tracks the active script version for each running game and notifies the Automation & Scripting Service when a new version should be loaded.
- Timer events and scheduled evaluations always reference the version pinned by the Game Session Service at the moment they run.
- Older versions remain in the database for auditing or rollback, but only the pinned version is executed.

## 🛡️ Fairness & Abuse Prevention

The Automation & Scripting Service now enforces several safeguards to prevent runaway
scripts and ensure fair resource usage:

- `ScriptQuotaService` limits how often a script may execute within a configurable
  window. **Quota checks happen before commands are enqueued**, so abusive scripts never reach
  the tick queues. When the quota is exceeded the event is ignored and metrics are emitted
  for monitoring.
- The tick system only processes these queued commands—it never runs script logic itself.
- Metrics track script execution and help detect logic that attempts to monopolize
  CPU time or grief other players.
- Administrators may disable or throttle problematic scripts via the Game Design
  Service, which updates definitions and triggers hot reloads in the Automation &
  Scripting Service.

---

By constraining scripts to curated components and enforcing strict quotas, FireMUD delivers powerful automation tools while maintaining security and fair resource usage across all hosted games.

## 🛠️ Developer Tools

Several helper scripts live under `dev-tools/` to streamline common tasks:

- `firemud-cli.sh` – command-line utility for starting and stopping the local stack.
- `generate-erd.sh` – produces Entity Relationship Diagrams for each service.
- `generate-grpc-docs.sh` – generates HTML documentation from protobuf definitions.

These scripts complement the web-based editor and allow creators to automate routine actions.
