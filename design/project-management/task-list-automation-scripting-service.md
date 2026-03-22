# Automation & Scripting Service Task List

## Scripting Framework

- [x] Create sandboxed script runtime *(see [Scripting & Automation Framework](../architecture/system-architecture-scripting.md))*
- [x] Support hot reloading of scripts published by the Game Design Service *(see [Automation & Scripting Service Design](../architecture/microservices/automation-scripting-service/README.md))*
- [x] Provide web UI for script creation and testing
- [x] Add advanced AI modules for complex behaviors
- [ ] Copy published version data into scripting schema via Saga
- [x] Enforce fairness quotas and per-script resource limits
- [ ] Support runtime generation requests via isolated ticks
- [ ] Persist generation seed metadata and spacing rules
- [ ] Trigger script-driven population after generation

## NPC & AI Behavior

- [x] Implement state-driven & event-driven NPC behaviors *(see [System Architecture: Scripting](../architecture/system-architecture-scripting.md))*
- [x] Implement AI memory & dynamic NPC behaviors (NPCs remember past player interactions) *(see [Automation & Scripting Service Design](../architecture/microservices/automation-scripting-service/README.md))*
- [x] Implement player vs. environment (PvE) mechanics (random encounters, environmental hazards)
- [ ] Expand PvE encounter library with additional biome-specific events and difficulty scaling
- [x] Implement faction & reputation system (players gain faction reputation over time)
- [x] Implement NPC aggression states (hostile, neutral, passive)
- [x] Implement NPC fleeing/surrender logic
- [x] Implement NPC formations & squad AI
- [x] Implement scripted events for game mechanics and NPC interactions *(see [Automation & Scripting Service Design](../architecture/microservices/automation-scripting-service/README.md))*

## World Generation

- [x] Implement procedural world generation
- [ ] Implement OverworldMapGenerator for biome-based terrain
- [ ] Support selectable room generation modes (sparse vs full) per request
- [ ] Provide pluggable GeneratorRegistry in the Automation & Scripting Service with scriptable or DSL-based generators
- [ ] Validate biome compatibility and connectivity when generating maps
- [ ] Generate procedural POI lore and descriptions
- [ ] Support seasonal or climate-based biome variations
- [ ] Expose runtime tuning parameters via scripting

## Security & Operations

- [ ] Wire TLS and JWT secret watchers to reload credentials without downtime

## Reusable Microservice Checklist

These tasks apply to every FireMUD service unless noted otherwise.
