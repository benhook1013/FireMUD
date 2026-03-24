# LLM-Assisted Content Authoring

This document describes a later-phase capability for using large language models (LLMs) as assistants for creating and refining game content such as room descriptions, NPC backstories, item flavor text, and quest dialogue. The focus is on design-time workflows that operate through the Game Design Service and related tooling, not on in-game chatbot behavior.

> 🔗 See the [Game Design Service](./microservices/game-design-service/README.md) for the core content authoring APIs.

## Scope

- Help creators draft and iterate on narrative content (rooms, NPCs, items, quests, and lore).
- Suggest alternative wordings, difficulty tuning notes, and accessibility improvements for existing text.
- Operate through the Game Design Service admin UI, which orchestrates all LLM-backed generation; LLMs never modify design assets directly or offline.
- Keep all LLM-assisted changes under creator control; humans review and commit content before it is published.

## Non-Goals

- No in-game NPC chatbots or live conversational agents. Runtime behavior remains scripted via the Automation & Scripting Service and related systems.
- No direct write access from LLMs to production databases. All changes flow through versioned design workflows.

## Integration Model

LLM-assisted authoring is implemented as a set of small, composable capabilities exposed only through the Game Design Service and its admin UI. The LLM’s role is to generate text or structured suggestions on top of existing world data—often including procedurally generated layouts—while FireMUD-owned services remain responsible for reading data, making edits, and talking to backend systems. The LLM never calls design APIs directly.

- The Game Design Service exposes “generate” endpoints (for example, generate room description, NPC backstory, or quest bundle) that:
  - accept structured instructions and context from the admin UI
  - call an LLM (directly or via a dedicated helper service)
  - turn the result into one or more design revisions or draft artifacts

From the platform’s perspective, these flows behave like any other design client: they create revisions, group them into versions, and rely on the existing publish workflow to promote changes to runtime.

## Phased Implementation

To keep complexity manageable, LLM-assisted workflows evolve in stages:

1. **Direct draft generation (experimental tooling)** – For early experiments, external scripts may export a slice of design data, build a prompt, call a locally hosted LLM, and write the result into a draft file or revision that a human reviews and imports. The LLM is not aware of FireMUD’s APIs; it only sees text and draft artifacts.
2. **Service-backed generation** – The Game Design Service adds dedicated “generate” endpoints that:
   - read world summaries, rooms, NPCs, and items in a structured format
   - call an LLM (directly or via a helper service) using that context
   - accept a structured “quest or content bundle” (for example, `quest_bundle.json`) and turn it into one or more design revisions
   The admin UI becomes a thin client over these endpoints.
3. **Offline agent sandbox** – Optionally, run an LLM-driven agent in a sandbox process that can call a handful of read-only helper tools plus one or two “write draft bundle” endpoints on the Game Design Service. The agent may chain helper calls to propose new quests or content using real world data, but the only outputs that matter are structured draft bundles that go through normal review and publish workflows.

Each phase builds on the previous one and can be useful on its own; nothing requires deploying an agent before basic draft-generation tools exist.

## Agent Sandbox Model

For more powerful flows such as “generate a new quest based on existing characters and locations,” an offline agent can be introduced with strict boundaries:

- The agent runs in its own sandboxed environment with access only to:
  - read-only helper tools such as `list_world_overview`, `get_room_detail`, or `get_npc_profile` exposed by the Game Design Service or a companion helper.
  - one or more endpoints that accept a well-typed draft artifact (for example, `quest_bundle.json`) and create corresponding design revisions.
- The agent may create temporary scratch files while reasoning, but the only contract with the rest of the system is a final structured artifact that passes validation.
- Importing that artifact into FireMUD always happens through the Game Design Service, so all schema and business rules are enforced outside the LLM.

This model allows the agent to query existing world data and propose coherent content while keeping all authoritative writes inside the Game Design Service and its design pipeline.

## Safety and Review

- All LLM-assisted changes must be traceable back to a human reviewer via the Game Design Service’s revision history.
- Tools should prefer idempotent operations that can be rerun safely, such as “generate a new draft into a separate revision” rather than overwriting live content in place.
- Validation (for example, schema checks, linting, and content guidelines) remains the responsibility of the design pipeline; LLM suggestions are treated as drafts, not authoritative data.

## Related Documentation

- [Game Design Service](./microservices/game-design-service/README.md)
- [Automation & Scripting Service](./microservices/automation-scripting-service/README.md)
- [Procedural Generation](./system-architecture-procedural-generation.md)
- [Scripting & Automation Framework](./system-architecture-scripting.md)
- [AGENTS.md](../../AGENTS.md)
