# In-Game Modding and Plugin Framework

This document outlines the modding system that lets administrators extend a published game without republishing a full version.

Plugins use the same **component-based DSL** and sandbox as the Automation & Scripting Service so custom logic can be hot reloaded safely.
Management APIs for enabling or disabling plugins reside in the Logging & Admin Service.

Plugin bundles are uploaded through the Game Design Service and stored in the same asset repository used for other design files.

## Goals

- Enable runtime loading of approved plugins written in the same **component‑based** scripting DSL used for automation.
- Provide a secure sandbox so plugins cannot access unauthorized data or system resources.
- Allow plugins to hook into game events exposed by the Game Logic and World Management services.
- Isolate plugin data per `tenantId` so multiple games can run on the same infrastructure.
- Forward plugin execution metrics and error logs to the Logging & Admin Service for auditing.

## Trust Model & Roles

Plugins run with the same core execution model as other scripts but are subject to additional trust and governance requirements:

- Only appropriately privileged principals (for example, tenant administrators or platform operators, depending on environment configuration) may upload or enable plugins for a given `tenantId`.
- Plugin bundles must be **signed** by a trusted key before the Game Design Service accepts them; key management and allowed signers are environment-specific and controlled by platform operators.
- Different environments (development, staging, production) may use distinct signing keys and role policies so that development plugins cannot be promoted directly into production without review.

The Logging & Admin Service exposes management APIs for listing, enabling, disabling, and inspecting plugins; these APIs enforce the same role model and provide audit trails for all plugin state changes.

## Outline

1. Plugins are packaged as signed bundles uploaded through the Game Design Service.
2. The Game Design Service validates each bundle's signature before storing it in the asset repository so versions can be tracked. See [Asset Storage Setup](asset-storage.md).
3. The Automation & Scripting Service executes plugin code with strict quotas similar to regular scripts.
4. A registry tracks which plugins are active for each game instance and exposes toggle APIs via the Logging & Admin Service.
5. Plugins can subscribe to events such as `onEnterRoom` or `onItemUse` to inject custom behavior.
6. Execution metrics and error logs are forwarded to the Logging & Admin Service for monitoring.
7. Plugin bundles are versioned along with other design assets and distributed when a new game version is published.

## Sandbox Capabilities & Quotas

At runtime, plugins use the same **component-based scripting DSL and sandbox** as the Automation & Scripting Service:

- Plugin graphs are authored from a curated set of DSL components. Platform owners may choose to expose the full component set or a restricted subset for plugins (for example, disallowing certain world-generation or administrative components) by configuration and policy.
- Plugins do not gain direct access to databases, Redis, or process internals; all state changes must still flow through domain services and the Game Session Service.
- Each plugin instance is represented as a script-like runtime object in the Automation & Scripting Service, typically tagged with `scriptType=PLUGIN` and annotated with `pluginId` and `pluginVersionId`. It participates in:
  - Per-script quotas and concurrency limits enforced by `ScriptQuotaService`.
  - Per-tenant budgets, including priority tiers (for example, `high`, `normal`, `background`).
  - Cluster-level automation ceilings and automation tick budgets, as described in `design/architecture/system-architecture-scripting-quotas-and-operations.md`.

Plugin executions appear in `script_event_audit` with the same identifiers as regular scripts, plus plugin-specific metadata, and contribute to the same automation metrics, enabling operators to monitor plugin behavior without a separate observability pipeline.

## Plugin Lifecycle & Rollback

Plugins follow a lifecycle similar to script patches but scoped to `<tenantId, pluginId>`:

- Each plugin version is identified by `pluginVersionId`. A registry in the Automation & Scripting Service tracks, per tenant:
  - `activeVersionId` – the plugin version currently enabled.
  - `pendingVersionId` – a plugin version being loaded or validated.
  - `pluginState` – state such as `IDLE`, `ENABLED`, `DISABLED`, `RELOADING`, or `FAILED`.
- Enabling a plugin sets `pluginState=ENABLED` for a `<tenantId, pluginId, pluginVersionId>` and allows the scheduler to admit triggers for that plugin, subject to quotas and budgets.
- Disabling a plugin can follow two modes:
  - **Hard disable** – immediately marks the plugin `DISABLED`; new triggers are rejected with a dedicated outcome (for example, `plugin_disabled`) and recorded in `script_event_audit`, while in-flight runs complete under existing budgets.
  - **Disable after drain** – transitions the plugin through a draining state (for example, `DISABLE_AFTER_DRAIN`) until queued triggers are processed or expired, then marks it `DISABLED`. New triggers are rejected once draining begins.
- Updating a plugin involves setting a new `pendingVersionId`, loading and validating the new plugin graphs and bindings, and then atomically switching `activeVersionId` if validation succeeds. If validation or initialization fails, the new version is marked `FAILED`, `activeVersionId` remains unchanged, and triggers for the failed version are rejected with an appropriate outcome (for example, `version_unavailable` or `plugin_version_failed`).

Plugin triggers share the same `scriptEventId` lifecycle as regular scripts. Each invocation is recorded in `script_event_audit` with `eventType`, `pluginId`, `pluginVersionId`, `scriptEventId`, and a canonical `outcome` / `reason` pair, so operators can correlate plugin behavior with publish and enable/disable operations.

## Related Documentation

- [Automation & Scripting Service](../automation-scripting-service/README.md)
- [Game Design Service Architecture](README.md)
- [User Journeys – Extensibility & External Tools](../../user-journeys-creators.md#8-extensibility--external-tools)
- [System Architecture – Scripting & Automation](../../system-architecture-scripting.md)
- [Asset Storage Setup](asset-storage.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md)
