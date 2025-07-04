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

- During each tick, the Automation & Scripting Service evaluates scheduled scripts and queues resulting commands.
- Commands are processed by the **Game Logic Service** in deterministic order, ensuring consistent outcomes.
- Scripts can react to world events, NPC states, or timers provided by the tick system.

## 🔄 Deployment & Versioning

- Script definitions are stored in the **Game Design Service** and versioned alongside other game assets.
- Designers can deploy updated scripts without redeploying code. The Automation & Scripting Service retrieves the current live versions as needed.
- Previous versions remain available for rollback or auditing.

## 🛡️ Fairness & Abuse Prevention (Planned)

The following mechanics are **future additions** to the platform and are not yet implemented:

- Limits on how often a script may run and how many resources it consumes per tick.
- Tracking fairness metrics and detecting scripts that attempt to monopolize CPU or grief other players.
- Tools for administrators to disable or throttle problematic scripts via the Game Design Service.

---

By constraining scripts to curated components and enforcing strict quotas, FireMUD delivers powerful automation tools while maintaining security and fair resource usage across all hosted games.
