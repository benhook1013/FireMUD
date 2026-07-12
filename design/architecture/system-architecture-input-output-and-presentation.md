# FireMUD System Architecture: Input, Output, and Presentation

This document defines the canonical model for how FireMUD accepts player input, represents player-visible output, and renders that output across Telnet, generic WebSocket, first-party web, and future MCP-aware clients.

The goal is to keep gameplay and UX decisions structured until the latest practical layer so the platform can support classic MUD text, richer clients, accessibility modes, and game-specific presentation policy without duplicating gameplay logic.

## Implemented Status

- Game Session now has the canonical pre-`06` normalized player-output seam: `TextCommandInterpretationResult` carries `PlayerOutput` envelopes instead of only a single raw response string.
- The first output kinds and payloads are live in code for messages, views, prompts, notices, and errors, with replay policy and brief-policy placeholders on the envelope.
- `LOGIN`, `PLAY`, room views, movement refresh, and direct communication acknowledgements now all have real structured output paths; the main built-in handlers no longer depend on raw response strings as their canonical internal contract.
- `LOOK` and `QUICKLOOK` now flow through `LookViewOutput` and `TextPlayerOutputRenderer`; room views carry explicit refresh reasons for `LOOK`, `QUICKLOOK`, movement refresh, and reconnect refresh, plus a bounded brief-rendering hint so movement-style refresh policy no longer depends on renderer inference from `MOVE_REFRESH` alone. Cached/replayed `LOOK` protocol framing is now renderer-owned rather than hand-built in the command handler. Hot reconnect replay stores structured output metadata for new entries while retaining classic rendered text for Telnet/generic WebSocket compatibility and legacy text-only buffer records.
- Communication actor and recipient responses now flow through the same late renderer from metadata-only Game Logic delivery views. First-party web now also receives structured command-response, async-player-output, and reconnect-refresh envelopes at the WebSocket edge. Reconnect screen-buffer storage still remains transcript-text-backed for now, but replay now buffers only replay-eligible rendered outputs rather than whole command responses, and first-party replay wraps those text chunks in explicit transcript envelopes instead of falling back to raw text.
- Prompt output is modeled separately, presentation defaults are now bound from typed properties, and prompt payloads now carry a first minimal structured field list alongside classic prompt text.
- Prompt output now has the pre-`06` baseline pipeline: prompt coalescing, a narrow per-session prompt-throttling window, reconnect prompt regeneration, and structured first-party prompt delivery are live. Richer burst-end scheduling, broader game-defined composition, and canonical buffered prompt/status replay remain future work.
- Built-in/system text now has the first usable localization foundation in Game Session: stable keys plus structured variables on built-in message, notice, and error outputs; per-session renderer locale selection; localized login/play/look/move failure rendering; localized room-view labels; and bounded alternate-locale renderer/integration tests.
- Authored localized content now also has a first bounded model: locale-tagged explicit variants with a required source locale and deterministic exact-locale, language-only, then source-locale fallback. Room prose is live on the authoritative `LOOK` and movement-refresh path by passing a preferred locale through Game Session and Game Logic into World Management snapshot reads, and room snapshots now also localize adjacent exit target room naming before rendering. Broader item/world adoption remains future work.
- The canonical transcript storage model is now locked at the architecture layer: reconnect replay and later durable history share one conceptual structured transcript-entry model, while rendered plain text remains a derived cache/compatibility surface rather than transcript source truth.

---

## Canonical Decisions

- Player input should be normalized into a small structured command envelope with typed payload shapes before gameplay execution rather than remaining raw strings through the whole stack.
- Player-visible output should be represented as a small structured output envelope with presentation tags and rendered as late as possible for the target client surface.
- Prompt/status output is a separate output class from transcript lines and gameplay view redraws.
- Player presentation settings such as color mode and `BRIEF` behavior should primarily alter rendering policy, not require duplicate authored gameplay prose for every action.
- `BRIEF` mode should primarily suppress or omit tagged output segments rather than requiring a second fully-authored text path for every output.
- Movement-triggered room refresh remains governed by the shared presentation plus movement settings model: transcript/rendering controls own briefness and locale/color policy, while `movement.postMoveView` owns whether a successful move produces a destination redraw at all.
- FireMUD should avoid brittle one-class-per-command and one-class-per-output taxonomies as the default model. A richer schema-driven or document-tree representation may still become desirable later, but the first canonical model should stay smaller and easier to evolve.

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
- normalized command envelope
- gameplay or menu action

Examples:

- `LOGIN demo@example.com swordfish` -> login intent
- `PLAY demo sora` -> gameplay admission intent
- `LOOK` -> gameplay view intent
- `SAY Hello travelers` -> communication intent

Smart-client metadata, such as future MCP-carried attach hints or prompt capabilities, should remain distinct from human command text and must not leak into the canonical typed command UX.

### Preferred input representation

The preferred first canonical representation is not one hard-coded class per command.

Instead, FireMUD should use:

- a normalized command envelope
- a small set of typed payload shapes
- a verb registry or schema that maps verbs and aliases to payload parsing and execution rules

Examples of payload shapes include:

- credentials payload
- selection payload
- targeted-message payload
- directional payload
- gameplay-view request payload

This gives the platform enough structure for validation, dispatch, logging, and future creator tooling without forcing every built-in or future game-defined command into its own rigid class hierarchy.

In practical terms, the platform should prefer a model like:

- normalized command envelope
  - stage
  - normalized verb
  - alias used
  - raw line
  - normalized payload
  - client metadata

over a model that requires a separate code type for every command in the system.

### Future command-model evolution

The first canonical model should stay relatively small.

If FireMUD later needs more creator-driven configurability, richer client presentation, or stronger DSL compatibility, this normalized envelope approach can evolve into a more schema-driven command model where:

- verbs are defined by metadata and validation rules
- argument patterns come from declarative schemas
- dispatch metadata is creator- or game-configurable

That richer direction is valid future work, but it should build on the smaller normalized-command model rather than replacing raw strings with an equally brittle forest of command classes.

### Menu-stage and gameplay-stage handling

Menu-stage and gameplay-stage parsing may share infrastructure, but they should remain explicit in the structured intent model.

- Menu/lobby intents include `LOGIN`, `PLAY`, `WORLDS`, `REALMS`, `CHARS`, `HELP`, and `QUIT`.
- Gameplay intents include `LOOK`, `QUICKLOOK`, communication, movement, inventory, combat, and later game-defined actions.

The session-stage guard remains authoritative for deciding whether a parsed intent is valid in the current player state. The parser should not be forced to re-encode session-stage policy in every command path.

---

## Output Model

The authoritative output abstraction should be structured output objects, not fully-rendered strings.

The preferred first canonical representation is not a large family of unrelated output classes. Instead, FireMUD should use a small normalized player-output envelope with a small set of top-level kinds plus presentation tags and delivery/replay policy.

These structured outputs are then rendered into the final client-facing form appropriate for the transport and client capability.

### Preferred output representation

The top-level output kinds should stay deliberately small, for example:

- message
- view
- prompt
- error
- notice

Most nuance should live in presentation tags and policies rather than exploding the number of top-level output types.

In practical terms, the platform should prefer a model like:

- player output envelope
  - kind
  - audience role
  - structured content
  - presentation tags
  - replay policy
  - brief policy
  - color hints

over a model that invents a separate hard-coded output class for every gameplay feature.

### Canonical output kinds

#### `message`

Messages are scrollback-worthy player-visible narrative lines or blocks such as:

- communication heard or sent
- movement narration
- combat narration
- system notices that belong in history

Messages are the main source for reconnect screen-buffer replay.

#### `view`

Views are structured snapshots or redraws such as:

- `LOOK`
- `QUICKLOOK`
- later inventory/equipment views

Views may be cached in narrow built-in view caches and replayed or redrawn on reconnect, but they are not equivalent to ordinary transcript lines.

#### `prompt`

Prompts are used for current player state summaries such as:

- health
- stamina or movement points
- combat state
- other game-configured short status indicators

Prompts are not ordinary transcript output.

It should usually be:

- coalesced rather than emitted after every single output event
- regenerated fresh rather than stored in the reconnect transcript buffer
- consumable as structured data by first-party web or MCP-aware clients

Future game-defined prompt composition should extend this model by separating:

- upstream status-field production
- prompt-layout selection
- final client rendering

In practical terms, gameplay/domain services should publish structured status fields, the prompt pipeline should choose and order those fields according to player/game layout policy, and the text renderer should remain only one projection of that structured prompt payload for classic clients.

The next intended refinement is not global transport batching. Instead, FireMUD should prefer:

- a very small per-session burst window that can catch naturally adjacent outputs from one logical event chain
- a prompt-throttling policy that appends a prompt at most every configured interval when output is already flowing
- prompt emission as a command-completion or burst-end opportunity rather than every command path hardcoding `+ prompt`

This means FireMUD should usually avoid a blanket rule like "flush all client output every 100 ms". That would add unnecessary latency and make normal play feel sluggish. The better policy is:

- ordinary command results and urgent output flush immediately
- tiny burst coalescing applies only where it reduces prompt or status chatter
- reconnect restore still ends with one fresh prompt after transcript replay and fresh `LOOK`
- explicit commands like `LOOK` usually still create a prompt opportunity, but the prompt pipeline decides whether to emit immediately, append to a trailing burst, or suppress because one was just emitted moments ago
- explicit view/boundary commands such as `LOOK` and accepted non-redraw `PLAY` may still force prompt retention inside the small prompt window so classic command-completion behavior stays crisp

#### `error`

Errors remain structured outcomes that can be rendered into plain-text protocol errors, richer UI notices, or future accessibility-specific treatments.

#### `notice`

Notices are structured non-gameplay-status outputs such as:

- security warnings
- connection-state notices
- bounded operator-visible or player-visible system messages

Some notices may be rendered into transcript history, while others may be surfaced separately depending on client type and delivery policy.

### Future presentation-model evolution

If FireMUD later needs richer output composition for configurable games, accessibility features, or first-party UI rendering, this output envelope can evolve toward a more document-like or schema-driven presentation tree where:

- outputs contain structured blocks and spans
- semantic segments are tagged for styling and suppression
- multiple renderers consume the same presentation tree

That richer direction is a valid future option, but the first implementation should begin with the smaller output-envelope model rather than jumping immediately to a full presentation document system.

### Canonical transcript persistence model

Structured `PlayerOutput` is the live output contract. Canonical transcript persistence sits one step below that live envelope:

- replay-eligible `PlayerOutput` values are projected into canonical transcript entries;
- transcript entries are the persistence/replay source of truth;
- rendered plain text remains a derived compatibility cache for classic text transports.

The canonical persisted transcript unit is one transcript entry carrying:

- session/gameplay identity;
- ordering token;
- output kind;
- structured payload;
- timestamp metadata.

For the current architecture, that means:

- a reconnect buffer entry may keep derived rendered text alongside the structured transcript entry so Telnet and generic WebSocket replay remain simple;
- durable transcript history persists the same conceptual transcript-entry model rather than inventing a second browser-only or archive-only contract;
- prompt/status output remains outside ordinary transcript persistence unless a future explicit transcript policy says otherwise.

Speech-related transcript storage should preserve canonical structured content and leave room for raw-versus-normalized speech fields where needed. Color, styling, and final transcript formatting stay projection-time concerns and should not be baked into canonical transcript storage.

### Durable Recent Output

Replayable output is always retained durably. The reconnect buffer is a hot, bounded delivery cache over that durable recent-output history, not a distinct transient-only retention class. Effective tenant/game settings define a bounded entry and byte budget plus an optional maximum age. Omitting the age allows a game to retain its bounded recent output indefinitely by time; it never means retaining unbounded gameplay output forever.

This keeps reconnect replay and durable recent output on one transcript contract while still allowing a hot reconnect cache in Redis or equivalent runtime storage.

### Player History And Export

Player-facing command history and player-visible output history are separate products with shared identity and ordering conventions:

- `HISTORY [count]` displays only safe, successfully accepted player commands. Unknown, malformed, rejected, and secret-bearing input is never history data. The effective tenant/game policy owns the default count, the maximum request count, and the retained command-entry bound; the optional argument selects a smaller newest subset.
- Durable recent output retains the bounded transcript needed to situate a reconnecting player. It is not a `HISTORY` command source and must not be conflated with command-entry history.
- A later player-history archive/export capability may capture the complete ordered player-visible transcript to a configured export destination. That capability has its own bounded server-side retention and delivery lifecycle so a player can retain an export independently without making the ordinary reconnect or recent-output store unbounded.

The archive/export capability is intentionally future work. It must consume the canonical transcript-entry model and must not introduce a transport-specific text log as a competing source of truth.

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

This means the canonical model has two related layers:

- gameplay/domain result objects from downstream services
- player-output envelopes and renderers in Game Session

The latter is the player-presentation contract this document standardizes.

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

### Localization and translation

Localization should distinguish between two complementary mechanisms:

- template/key-based localization for built-in platform and system text;
- explicit localized content variants for authored world/game prose such as room descriptions, lore text, and item descriptions.

These mechanisms solve different problems and should coexist rather than compete.

For built-in runtime rendering, locale selection should currently follow this precedence:

- persisted session/player locale when known;
- current websocket or bootstrap locale when present;
- `firemud.presentation.default-locale-tag`.

The first authored-content model should stay small and explicit:

- one canonical source locale is required;
- localized variants are stored by locale tag;
- runtime resolves:
  - exact locale first;
  - then language-only match where available;
  - then the source locale text;
- the runtime should not synthesize or fetch missing translations on demand during live gameplay.

The first authored-content runtime adoption should stay equally small and explicit:

- Game Session should forward the current preferred locale on canonical room-read paths such as explicit `LOOK` and movement-triggered room refresh;
- Game Logic should propagate that locale through room snapshot requests without inventing a second localization authority;
- World Management should resolve stored localized room variants before returning the authoritative room snapshot;
- richer item/world/lore adoption can follow the same model later without moving localization onto the live renderer hot path.

For runtime behavior, FireMUD should prefer stored localized variants over live translation calls on the gameplay hot path. The platform should not assume per-message external translation during active gameplay because added network latency and jitter would directly hurt responsiveness.

The preferred future AI-enabled model is:

1. creators author canonical source content in one original language;
2. creators provide world/tone/glossary guidance where needed;
3. an offline or out-of-band AI localization workflow generates draft localized variants;
4. creators optionally review and edit those generated variants;
5. runtime serves the stored localized variants without depending on live translation APIs.

This keeps runtime latency predictable while still allowing AI to reduce the authoring burden for accessibility and internationalization.

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

Future movement/combat presentation policy may also treat "in combat" as a first-class presentation hint. A sensible default target is that when the player is currently flagged as in combat, movement-triggered room refreshes automatically render in brief-style form even if the player's general room-display mode is more verbose. This should remain a rendering-policy decision layered on top of the same room-view data, not a separate movement-specific room model.

### Prompt behavior

Prompt behavior should be configurable and capability-aware.

Canonical default behavior:

- prompts are coalesced after short output bursts
- prompts are not stored in the reconnect transcript buffer
- reconnect restores transcript context first, then a fresh gameplay redraw such as `LOOK`, then one fresh prompt
- first-party web and MCP-aware clients may consume prompt/state as structured data without showing prompt text in the main transcript

Prompts should also remain compatible with future game-defined and player-configurable prompt composition. Different games may expose different status fields, and players may want to choose which fields appear in their text prompt or first-party UI status display. That future flexibility is another reason prompt/state should remain a structured output type rather than being treated as ordinary transcript text.

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
