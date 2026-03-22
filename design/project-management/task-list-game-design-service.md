# Game Design Service Task List

## Game Templates & Publishing

- [x] Provide game templates and configuration tools
- [ ] Configure default administrator accounts when creating a new game template
- [ ] Incorporate world layout from World Management Service into templates
- [ ] Include starter items and NPCs in templates
- [ ] Store default rulesets and runtime flags in a structured template schema
- [ ] Provide gRPC endpoints for template CRUD operations
- [ ] Support viewing, updating, and deleting templates
- [x] Enable publishing of game versions
- [x] Use saga orchestrator for game publishing workflow
- [x] Ensure domain services copy data by `version_id` and never query the design database at runtime
- [x] Create design-time database models

## Design Tools

- [ ] Implement world editing & customization tools
- [x] Implement scripting & event design tools
- [x] Build a **visual scripting editor** using a **component-based DSL**
- [x] Sandbox script execution with quotas via the Automation & Scripting Service
- [ ] Implement ability & action design tools
- [ ] Implement item & equipment balancing tools
- [ ] Add action sequencer for chained abilities
- [ ] Provide balancing metrics and integrate with item statistics
- [ ] Support ability categories for organization
- [ ] Copy abilities to Game Logic Service during version publish
- [ ] Add item stat editor with real-time validation
- [ ] Visualize equipment curves across item tiers
- [ ] Preview economy impact for vendor prices and drop rates
- [ ] Integrate item balancing with ability design tools
- [ ] Provide drag-and-drop interface for balancing views
- [ ] Aggregate stats and cost vs. power graphs
- [ ] Copy finalized item stats to Entity Management Service during publish
- [x] Track version history and patch notes for published games
- [x] Build a web-based visual design interface
- [x] Integrate version control for design assets
- [ ] Implement data-driven rule configuration so games can adjust mechanics without redeploying
- [ ] Support JSON import for rooms, items, and NPCs
- [x] Configure database storage for game assets
  - [x] Provide asset upload API in Game Design Service
  - [x] Document asset storage setup and configuration
  - [ ] Provide asset download and delete APIs
  - [ ] Add gRPC endpoints for asset management
  - [ ] Upload published assets and a `manifest.json` to version-scoped object
        storage so runtime clients can load them from a CDN

## Scripting & Modding

- [x] Implement event-driven scripting API for game creators
- [ ] Implement in-game modding/plugin framework
- [x] Implement scripted AI behaviors for NPCs
- [ ] Forward plugin metrics and error logs to the Logging & Admin Service
- [ ] Expose plugin enable/disable APIs via the Logging & Admin Service
- [ ] Notify downstream services when new versions are published
- [ ] Add import/export of design assets for sharing between games
- [ ] Add `owner_id` association to games and API

## Admin, Security & MCP

- [ ] Wire TLS and JWT secret watchers to reload credentials without downtime
- [ ] Add MCP commands for room and item editing
- [ ] Support bulk import and transactional MCP content creation

## Versioning & Runtime Configuration

- [x] Implement cross-service game version publishing workflow
  - [ ] Create `runtime_flag` table and API for flag definitions
  - [ ] Provide UI for runtime flag definitions in the Game Design Service
- [ ] Support script-only patch publishing (`scriptPatchVersion`) for hotfixes
- [x] Store immutable versions in the Game Design Service
- [x] Copy published data to domain services using the `version_id`
  - [x] Activate versions and runtime flags via the Game Session Service
  - [x] Expose admin APIs for runtime flag toggles through the Logging & Admin Service

## Reusable Microservice Checklist

These tasks apply to every FireMUD service unless noted otherwise.
