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

## 🔒 Sandboxing & Security

- Script execution occurs in a **sandbox** with restricted APIs and resource limits.
- Components interact with the **Game Logic Service** through validated gRPC calls.
- The service enforces **per-script quotas** (CPU time, memory, tick budget) to contain runaway logic.

## ⚙️ Integration with Game Logic & Tick System

- **Scripts do not execute inside the tick system.** The Automation & Scripting Service evaluates scripts independently—on a schedule, via timers, or in response to events—and injects the resulting commands into command queues.
- These commands are processed during the **next tick cycle** by the Game Logic Service, preserving fairness, determinism, and replay safety.
- Script evaluation never blocks or interferes with tick execution. Scripts can still react to world events, NPC states, or timers provided by the tick system.

## 🔄 Deployment & Versioning

- Script definitions are stored in the **Game Design Service** and versioned alongside other game assets.
- Designers can deploy updated scripts without redeploying code. The Automation & Scripting Service retrieves the current live versions as needed.
- Script-only patches create a `scriptPatchVersion` tied to a `baseVersionId` so new behaviors can be loaded on the fly.
- Previous versions remain available for rollback or auditing.

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
