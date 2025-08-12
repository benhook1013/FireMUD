# In-Game Modding and Plugin Framework

This document outlines the modding system that lets administrators extend a published game without republishing a full version.

Plugins use the same **component-based DSL** and sandbox as the Automation & Scripting Service so custom logic can be hot reloaded safely.
Management APIs for enabling or disabling plugins reside in the Logging & Admin Service.

Plugin bundles are uploaded through the Game Design Service and stored in the same asset repository used for other design files.

## 🎯 Goals

- Enable runtime loading of approved plugins written in the same **component‑based** scripting DSL used for automation.
- Provide a secure sandbox so plugins cannot access unauthorized data or system resources.
- Allow plugins to hook into game events exposed by the Game Logic and World Management services.
- Isolate plugin data per `tenantId` so multiple games can run on the same infrastructure.
- Forward plugin execution metrics and error logs to the Logging & Admin Service for auditing.

## Outline

1. Plugins are packaged as signed bundles uploaded through the Game Design Service.
2. The Game Design Service validates each bundle's signature before storing it in the asset repository so versions can be tracked. See [Asset Storage Setup](asset-storage.md).
3. The Automation & Scripting Service executes plugin code with strict quotas similar to regular scripts.
4. A registry tracks which plugins are active for each game instance and exposes toggle APIs via the Logging & Admin Service.
5. Plugins can subscribe to events such as `onEnterRoom` or `onItemUse` to inject custom behavior.
6. Execution metrics and error logs are forwarded to the Logging & Admin Service for monitoring.
7. Plugin bundles are versioned along with other design assets and distributed when a new game version is published.

## 📚 Related Documentation

- [Automation & Scripting Service](../automation-scripting-service/README.md)
- [Game Design Service Architecture](README.md)
- [User Journeys – Extensibility & External Tools](../user-journeys.md#21-extensibility--external-tools)
- [System Architecture – Scripting & Automation](../system-architecture-scripting.md)
- [Asset Storage Setup](asset-storage.md)
- [Multi-Tenancy](../system-architecture-multi-tenancy.md)
- [Versioning & Runtime Configuration](../system-architecture-versioning-runtime.md)
