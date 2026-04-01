# FireMUD System Architecture: Input, Output, and Presentation

This document defines the canonical model for how FireMUD accepts player input, represents player-visible output, and renders that output across Telnet, generic WebSocket, first-party web, and future MCP-aware clients.

The goal is to keep gameplay and UX decisions structured until the latest practical layer so the platform can support classic MUD text, richer clients, accessibility modes, and game-specific presentation policy without duplicating gameplay logic.

---

## Canonical Decisions

- Player input should be normalized into structured intents before gameplay execution rather than remaining raw strings through the whole stack.
- Player-visible output should be represented as structured output objects and rendered as late as possible for the target client surface.
- Prompt/status output is a separate output class from transcript lines and gameplay view redraws.
- Player presentation settings such as color mode and `BRIEF` behavior should primarily alter rendering policy, not require duplicate authored gameplay prose for every action.
- `BRIEF` mode should primarily suppress or omit tagged output segments rather than requiring a second fully-authored text path for every output.

---

## Input Model

FireMUD has two related but distinct input stages:

- menu/lobby input
- in-game gameplay input

Both start as text lines for Telnet and generic text WebSocket clients, but the platform should not treat raw strings as the long-term domain abstraction.

### Input pipeline

The canonical pipeline is:

1. transport receives raw client input
2. protocol layer normalizes transport details into text lines or structured client metadata
3. parser maps normalized input into a structured input intent
4. session-stage guard validates whether the intent is legal for the current stage
5. gameplay or menu handler executes the intent

This means FireMUD should distinguish between:

- raw input line
- parsed command
- normalized input intent
- gameplay or menu action

Examples:

- `LOGIN demo@example.com swordfish` -> login intent
- `PLAY demo sora` -> gameplay admission intent
- `LOOK` -> gameplay view intent
- `SAY Hello travelers` -> communication intent

Smart-client metadata, such as future MCP-carried attach hints or prompt capabilities, should remain distinct from human command text and must not leak into the canonical typed command UX.

### Menu-stage and gameplay-stage handling

Menu-stage and gameplay-stage parsing may share infrastructure, but they should remain explicit in the structured intent model.

- Menu/lobby intents include `LOGIN`, `PLAY`, `WORLDS`, `REALMS`, `CHARS`, `HELP`, and `QUIT`.
- Gameplay intents include `LOOK`, `QUICKLOOK`, communication, movement, inventory, combat, and later game-defined actions.

The session-stage guard remains authoritative for deciding whether a parsed intent is valid in the current player state. The parser should not be forced to re-encode session-stage policy in every command path.

---

## Output Model

The authoritative output abstraction should be structured output objects, not fully-rendered strings.

Examples of output classes include:

- transcript event
- gameplay view
- prompt/status snapshot
- command error
- system notice
- asynchronous world event

These output objects are then rendered into the final client-facing form appropriate for the transport and client capability.

### Canonical output classes

#### Transcript events

Transcript events are scrollback-worthy player-visible narrative lines or blocks such as:

- communication heard or sent
- movement narration
- combat narration
- system notices that belong in history

Transcript events are the main source for reconnect screen-buffer replay.

#### Gameplay views

Gameplay views are structured snapshots or redraws such as:

- `LOOK`
- later `QUICKLOOK`
- later inventory/equipment views

Gameplay views may be cached in narrow built-in view caches and replayed or redrawn on reconnect, but they are not equivalent to ordinary transcript lines.

#### Prompt/status snapshots

Prompt/status is a distinct output class used for current player state summaries such as:

- health
- stamina or movement points
- combat state
- other game-configured short status indicators

Prompt/status is not ordinary transcript output.

It should usually be:

- coalesced rather than emitted after every single output event
- regenerated fresh rather than stored in the reconnect transcript buffer
- consumable as structured data by first-party web or MCP-aware clients

#### Command errors

Command errors remain structured outcomes that can be rendered into plain-text protocol errors, richer UI notices, or future accessibility-specific treatments.

---

## Late Rendering

FireMUD should render output as late as practical.

Structured gameplay results and structured presentation objects should survive until the platform knows:

- the transport type
- the client capability profile
- the relevant player presentation settings
- the game-specific presentation definition

This allows one gameplay outcome to support:

- plain text Telnet
- ANSI/basic color Telnet
- richer text-capable MUD clients
- first-party web rendering
- future MCP-aware structured presentation

### Rendering ownership

Game/domain services should return structured gameplay or communication results.
Game Session owns the final player-facing transcript/rendering responsibility for gameplay traffic.

That means:

- gameplay services should not become the primary owners of final transport strings
- Game Session should apply presentation policy and render per client surface
- built-in view caches and reconnect screen buffers should store player-facing rendered output only when that is the intentional cache purpose

---

## Player Presentation Settings

Presentation settings should sit between structured output objects and the final renderer.

These settings belong in the broader platform settings model:

- operator caps in file/env config where needed
- tenant/game defaults and player-facing behavior in database-backed settings

Important presentation settings include:

- color mode
- `BRIEF` or verbose view policy
- prompt behavior
- later accessibility-specific transcript shaping

### Color modes

FireMUD should support at least a capability-aware model such as:

- `none`
- `basic`
- `rich`

The exact wire-level implementation may vary by client capability, but the settings model should not reduce color to a single boolean forever.

### BRIEF mode

`BRIEF` mode should primarily be driven by output classification and renderer policy.

The preferred default is:

- structured outputs carry presentation tags
- the renderer suppresses or omits tagged content for players in `BRIEF`

Examples:

- room long description on movement: suppressible in `BRIEF`
- room title: always show
- exits: always show
- critical combat or hazard lines: always show
- optional ambient flavor: suppressible in `BRIEF`

Alternate brief-specific prose should be the exception, not the default authoring burden. When needed, specific built-in outputs may supply a concise alternate rendering, but the platform should not require dual-authored prose for every event.

### Prompt behavior

Prompt behavior should be configurable and capability-aware.

Canonical default behavior:

- prompts are coalesced after short output bursts
- prompts are not stored in the reconnect transcript buffer
- reconnect restores transcript context first, then a fresh gameplay redraw such as `LOOK`, then one fresh prompt
- first-party web and MCP-aware clients may consume prompt/state as structured data without showing prompt text in the main transcript

---

## Client Surface Expectations

### Telnet and generic text WebSocket

These clients primarily consume rendered plain text.

They should still benefit from:

- color-mode settings
- `BRIEF` filtering
- coalesced prompts
- reconnect screen-buffer restore plus fresh redraw

### First-party web

First-party web clients should not be forced to treat every output as raw transcript text.

The same structured output model should allow:

- transcript events in the main scrollback
- gameplay views rendered into dedicated UI areas if desired
- prompt/status consumed as structured state instead of transcript text

### MCP-aware or future smart clients

Future smart clients may consume richer prompt or output metadata over MCP or equivalent structured channels, but the canonical gameplay and presentation model must still work when reduced to plain text.

---

## Relationship to Existing Systems

- Reconnection uses a bounded per-player screen buffer for transcript context, plus a fresh redraw such as `LOOK`; it does not replay arbitrary missed transport bytes.
- `LOOK` remains a structured gameplay view rendered into a classic MUD transcript shape for text clients.
- Communication actions already distinguish structured delivery metadata from canonical player-facing prose and should continue moving toward later rendering rather than hard-coding plain strings too early.
- Prompt/status remains a separate output class and should not be treated as ordinary transcript history.

---

## Deferred and Follow-on Work

This document defines the target-state model. Follow-on slices should implement it incrementally for:

- structured input intents and parser boundaries
- structured output objects and late-renderer contracts
- prompt/status as a first-class output type
- color and capability-aware rendering
- `BRIEF` and later accessibility presentation settings
- first-party web and MCP-aware structured output consumption
