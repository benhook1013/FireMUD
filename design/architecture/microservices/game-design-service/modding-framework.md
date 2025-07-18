# In-Game Modding and Plugin Framework

This document sketches the planned modding system that allows administrators to extend a published game without republishing a full version. (TODO: Not yet implemented)
> **Status: In Progress** – The modding framework is still under active development and not yet available in production.
Plugins will share the same component-based DSL and sandbox used by the Automation & Scripting Service so custom logic can be hot reloaded safely. (TODO: Not yet implemented) Management APIs for enabling or disabling plugins will live in the Logging & Admin Service. (TODO: Not yet implemented)

## 🎯 Goals

- Enable runtime loading of approved plugins written in the same **component‑based** scripting DSL used for automation. (TODO: Not yet implemented)
- Provide a secure sandbox so plugins cannot access unauthorized data or system resources. (TODO: Not yet implemented)
- Allow plugins to hook into game events exposed by the Game Logic and World Management services. (TODO: Not yet implemented)
- Isolate plugin data per `tenantId` so multiple games can run on the same infrastructure. (TODO: Not yet implemented)
- Forward plugin execution metrics and error logs to the Logging & Admin Service for auditing. (TODO: Not yet implemented)

## Outline

1. Plugins are packaged as signed bundles uploaded through the Game Design Service. (TODO: Not yet implemented)
2. The Game Design Service validates each bundle's signature before storing it in the asset repository so versions can be tracked. See [Asset Storage Setup](asset-storage.md). (TODO: Not yet implemented)
3. The Automation & Scripting Service executes plugin code with strict quotas similar to regular scripts. (TODO: Not yet implemented)
4. A registry tracks which plugins are active for each game instance and exposes toggle APIs via the Logging & Admin Service. (TODO: Not yet implemented)
5. Plugins can subscribe to events such as `onEnterRoom` or `onItemUse` to inject custom behavior. (TODO: Not yet implemented)
6. Execution metrics and error logs are forwarded to the Logging & Admin Service for monitoring. (TODO: Not yet implemented)

## 📚 Related Documentation

- [Automation & Scripting Service](../automation-scripting-service/README.md)
- [Game Design Service Architecture](README.md)
- [User Journeys – Extensibility & External Tools](../user-journeys.md#21-extensibility--external-tools)
- [System Architecture – Scripting & Automation](../system-architecture-scripting.md)
- [Asset Storage Setup](asset-storage.md)
- [Multi-Tenancy](../system-architecture-multi-tenancy.md)
