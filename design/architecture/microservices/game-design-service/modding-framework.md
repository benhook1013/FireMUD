# In-Game Modding and Plugin Framework

This document sketches the planned modding system that allows administrators to extend a published game without republishing a full version.

## Goals

- Enable runtime loading of approved plugins written in the same scripting DSL used for automation.
- Provide a secure sandbox so plugins cannot access unauthorized data or system resources.
- Allow plugins to hook into game events exposed by the Game Logic and World Management services.

## Outline

1. Plugins are packaged as signed bundles uploaded through the Game Design Service.
2. The Automation & Scripting Service executes plugin code with strict quotas similar to regular scripts.
3. A registry tracks which plugins are active for each game instance and exposes toggle APIs via the Logging & Admin Service.
4. Plugins can subscribe to events such as `onEnterRoom` or `onItemUse` to inject custom behavior.

## 📚 Related Documentation

- [Automation & Scripting Service](../automation-scripting-service/README.md)
- [Game Design Service Architecture](README.md)
