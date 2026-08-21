# FireMUD System Architecture: Input, Output, and Presentation

This document defines the canonical model for how FireMUD accepts player input, represents player-visible output, and renders that output across Telnet, generic WebSocket, first-party web, and future MCP-aware clients. It owns the compact versioned output, bounded semantic reconnect context, and deferred-localization contracts (decision keys `CMD-03`, `CMD-04`, and `CMD-05`).

The goal is to keep gameplay and UX decisions structured until the latest practical layer so the platform can support classic MUD text, richer clients, accessibility modes, and game-specific presentation policy without duplicating gameplay logic.

## Implemented Status

- Game Session now has the canonical pre-`06` normalized player-output seam: `TextCommandInterpretationResult` carries `PlayerOutput` envelopes instead of only a single raw response string.
- The first output kinds and payloads are live in code for messages, views, prompts, notices, and errors, with replay policy and brief-policy placeholders on the envelope.
- `LOGIN`, `PLAY`, room views, movement refresh, and direct communication acknowledgements now all have real structured output paths; the main built-in handlers no longer depend on raw response strings as their canonical internal contract.
- `LOOK` and `QUICKLOOK` now flow through `LookViewOutput` and `TextPlayerOutputRenderer`; room views carry explicit refresh reasons for `LOOK`, `QUICKLOOK`, movement refresh, and reconnect refresh, plus a bounded brief-rendering hint so movement-style refresh policy no longer depends on renderer inference from `MOVE_REFRESH` alone. `LOOK` protocol framing, including fresh reconnect reconstruction, is now renderer-owned rather than hand-built in the command handler. Semantic recent-context restoration stores structured output metadata for new entries while retaining classic rendered text for Telnet/generic WebSocket compatibility and legacy text-only records; cached room snapshots are never replayed, and fresh authoritative `LOOK` reconstructs current state.
- Communication actor and recipient responses now flow through the same late renderer from metadata-only Game Logic delivery views. First-party web now also receives structured command-response, async-player-output, and reconnect-refresh envelopes at the WebSocket edge. Classic transport replay still projects derived text, while canonical durable entries retain structured metadata and replay only replay-eligible outputs rather than whole command responses; first-party replay wraps compatibility text in explicit transcript envelopes instead of falling back to raw text.
- Prompt output is modeled separately, presentation defaults are now bound from typed properties, and prompt payloads now carry a first minimal structured field list alongside classic prompt text.
- Prompt output now has the pre-`06` baseline pipeline: prompt coalescing, a narrow per-session prompt-throttling window, reconnect prompt regeneration, and structured first-party prompt delivery are live. Richer burst-end scheduling, broader game-defined composition, and canonical buffered prompt/status replay remain future work.
- Built-in/system text now has the first usable localization foundation in Game Session: stable keys plus structured variables on built-in message, notice, and error outputs; per-session renderer locale selection; localized login/play/look/move failure rendering; localized room-view labels; and bounded alternate-locale renderer/integration tests.
- Authored localized content now also has a first bounded model: locale-tagged explicit variants with a required source locale and deterministic exact-locale, explicitly stored base-language, then source-locale fallback. Room prose is live on the authoritative `LOOK` and movement-refresh path by passing a preferred locale through Game Session and Game Logic into World Management snapshot reads, and room snapshots now also localize adjacent exit target room naming before rendering. Broader item/world adoption remains future work; no arbitrary regional sibling or live provider translation is selected.
- The semantic recent-context implementation is partial: ordered `resume_transcript_entry` rows and best-effort Redis caching are live, but namespace-key migration to `{tenantId, playableStateNamespaceId, characterId}`, strict complete-envelope byte-bound and oversize omission/marker proof, and first-party post-logout suppression remain incomplete. Rendered plain text remains a derived compatibility surface rather than transcript source truth, and context is not delivery acknowledgement, exact missed-message replay, command-input history, or a complete archive.

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

Every supported `PlayerOutput` envelope is compact and versioned: it carries an explicit schema version, a bounded top-level kind, typed payload, presentation tags, and delivery/replay policy. Schema compatibility rules and unsupported-version behavior are part of the supported structured-client contract; internal Java records and an unversioned edge projection are not by themselves that contract. Every envelope must also have a deterministic plain-text projection preserving its essential meaning. Telnet and generic text WebSocket consume that projection; structured clients may consume typed payloads but cannot make text compatibility optional.

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
  - schema version
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

Messages are the main source for bounded semantic recent-context restoration.

#### `view`

Views are structured snapshots or redraws such as:

- `LOOK`
- `QUICKLOOK`
- later inventory/equipment views

Views may be cached in narrow built-in view caches for ordinary hot reads, but cached room snapshots are never replayed on fresh edge reconnect. A fresh authoritative `LOOK` reconstructs current state, and views are not equivalent to ordinary transcript lines.

#### `prompt`

Prompts are used for current player state summaries such as:

- health
- stamina or movement points
- combat state
- other game-configured short status indicators

Prompts are not ordinary transcript output.

It should usually be:

- coalesced rather than emitted after every single output event
- regenerated fresh rather than stored in the semantic recent-context buffer
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
- reconnect restore still ends with one fresh prompt after semantic recent-context rendering and fresh `LOOK` when both effective reconnect-prompt settings are enabled; if either is disabled, it emits no reconnect prompt
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

That richer direction is a valid future option, but adopting a general presentation/document tree requires a separate consequential decision under [ADR 0135](./decisions/adr-0135-compact-versioned-player-output-and-late-rendering.md). The first implementation should begin with the smaller output-envelope model rather than treating the broader tree as already accepted.

### Canonical resume-context model

Structured `PlayerOutput` is the live output contract. The canonical semantic recent context sits one step below that live envelope:

- replay-eligible `PlayerOutput` values are projected into canonical semantic-context entries;
- semantic-context entries are the durable context source of truth;
- rendered plain text remains a derived compatibility cache for classic text transports.

The namespace, complete-envelope bound, omission-marker, and logout-revocation rules below are target behavior; implemented ordered-row persistence and its current proof gaps are recorded in [Implemented Status](#implemented-status) above.

The canonical semantic recent-context entry is one entry carrying:

- `{tenantId, playableStateNamespaceId, characterId}` identity;
- ordering token;
- output kind;
- structured payload;
- replay and presentation metadata needed to project the same output after reconnect;
- derived rendered compatibility text;
- timestamp metadata.

For the target architecture, that means:

- the durable recent context is keyed by the admitted `{tenantId, playableStateNamespaceId, characterId}` identity and follows that durable namespace across replaceable runtime instances;
- every replay-eligible entry is appended to that durable bounded context, including output that would otherwise fall out of a hot reconnect cache;
- after authorized reconnect, structured entries are re-rendered under the current session and presentation policy; any persisted `renderedText` is compatibility-only for legacy text-only records and never replaces the structured entry;
- Redis may cache the current resume window for reconnect speed, but it is not the source of truth and a Redis reset must not discard the retained resume context;
- after authorized reconnect completes fresh `LOGIN` + `PLAY`, target behavior renders retained context in ordering-token order, then obtains a fresh authoritative `LOOK` and emits exactly one reconnect prompt only when both effective `firemud.presentation.prompt.enabled` and `firemud.presentation.prompt.emit-after-reconnect-restore` are enabled; if either is disabled, it emits zero reconnect prompts. Explicit gameplay `LOGOUT` terminates the binding and suppresses its private replay, so a later `LOGIN` + `PLAY` does not replay context from that terminated binding;
- context may repeat output already shown or omit output not retained/available; it is not an acknowledgement, exact missed-message list, client-input history, or complete transcript archive;
- prompt/status output remains outside ordinary semantic recent-context persistence unless a future explicit transcript policy says otherwise.

Speech-related transcript storage should preserve canonical structured content and leave room for raw-versus-normalized speech fields where needed. Color, styling, and final transcript formatting stay projection-time concerns and should not be baked into canonical transcript storage.

### Resume-context bounds

Every game uses the durable semantic recent-context model. This is not a player preference and it has no transient-only mode. The effective policy resolves from platform defaults with an optional tenant/game override.

The policy defines:

- soft and hard retained-byte ceilings, with message and line floors for the soft ceiling;
- optional expiry after a character has been inactive for a configured duration, or `never`.

Target behavior: when a byte bound is exceeded, FireMUD evicts complete oldest retained entries. The soft ceiling preserves the configured message and line floors where possible. The hard ceiling is absolute for the complete persisted context, including scope and metadata; when one complete entry alone exceeds it, FireMUD omits that entry or stores a bounded omission marker whose own complete persisted size fits within the ceiling. FireMUD never stores a partial or silently truncated semantic entry, and no single entry may bypass the hard ceiling. Inactivity expiry removes the whole retained context. A normal multiplayer game may keep a small recent window and expire it after a period such as sixty inactive days; a persistent RPG may retain a larger short window without inactivity expiry. Neither case implies an unbounded archive of all gameplay output.

Target hard-bound accounting is deterministic: each retained entry costs the UTF-8 byte length of its complete scope-bound persisted context envelope, not only its rendered text. The compact outer JSON envelope has lexically ordered members for `briefRenderPolicy`, `characterId`, `occurredAt`, `orderingToken`, `outputKind`, `payload`, `payloadType`, `playableStateNamespaceId`, `renderedText`, `replayPolicy`, `schemaVersion`, and `tenantId`; every optional outer member is present and absent values are JSON `null`. Outer-envelope strings are normalized to Unicode NFC, timestamps are RFC 3339 UTC with fixed millisecond precision, numbers use their shortest normalized JSON form, and no insignificant whitespace is emitted. The `payload` member preserves the canonical structured-output JSON emitted by the live output encoder. This accounts for entry metadata, structured payload, and the derived rendered-text compatibility projection exactly once in both the durable source of truth and Redis hot cache. FireMUD never separately adds the same payload or rendered text again for transport projections. A complete entry or omission marker is retained only when its complete persisted size fits within the hard bound.

### Separate history features

The bounded durable semantic recent context is not a command-input history or a complete player archive.

- [`HISTORY [count]`](./system-architecture-player-command-model.md#command-history) is a live, separate optional safe command-input history. It records only successfully accepted safe commands rather than screen output; unknown, malformed, rejected, and secret-bearing input is never history data. The effective tenant/game policy owns its bounded retention and count limits.
- A future Player Transcript Archive and Export feature may retain the complete player-visible transcript as append-only archive segments for a finite tenant/game-configured period. It must preserve the canonical structured entries with derived rendered text for export, let players obtain an export before FireMUD-side expiry, and remain separate from the small resume context. The first export surface should be a FireMUD-managed downloadable artifact; arbitrary external destinations require later credential, privacy, retry, and deletion design.

The current implementation persists the bounded durable semantic recent context in Game Session as ordered `resume_transcript_entry` rows and retains structured replay metadata beside rendered compatibility text. Redis is only a best-effort hot cache. Command history is separate from reconnect retention, and Player Transcript Archive and Export remains later work.

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
Game Session owns the final player-facing transcript/rendering responsibility for gameplay traffic, including mapping semantic outcomes into versioned `PlayerOutput`, selecting presentation policy, and producing the mandatory deterministic text projection.

That means:

- gameplay services should not become the primary owners of final transport strings
- Game Session should apply presentation policy and render per client surface
- built-in view caches should store player-facing rendered output only when that is the intentional cache purpose; reconnect context remains structured durable state rendered for the current client surface

This means the canonical model has two related layers:

- gameplay/domain result objects from downstream services
- player-output envelopes and renderers in Game Session

The latter is the player-presentation contract this document standardizes.

### Causal `LOOK` composition

`LOOK` is a composed presentation read whose target behavior uses the causal-floor contract in [ADR 0059](./decisions/adr-0059-causal-floor-cross-service-presentation-reads.md). The target `CausalReadFence` identity is at least `{tenantId, gameInstanceId, regionId, roomInstanceId, regionEpoch, committedTickId}`. `playableStateNamespaceId` and `playableStateScope` may accompany broader validated request/admission context, but they are not runtime fence identity. Game Session supplies the requested fence; World and Entity must each return the same tenant/game-instance/region/room scope and epoch, prove `servedThroughTickId >= requestedFloor`, and return their own opaque component version. Game Logic rejects, retries, or fails the composition when scope/epoch or floor evidence is missing or mismatched. Component versions are evidence identifiers only: they are never compared numerically, and numeric version skew or equality is not a correctness fence. The current `ResolveLook`/World/Entity request path remains floor-free and returns only deterministic scope markers, so this propagation and participant proof are target behavior rather than current implementation.

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
- runtime resolves exactly:
  1. an exact requested locale tag;
  2. an explicitly stored base-language variant;
  3. the bundle's source locale text;
- an arbitrary regional sibling is never selected merely because its base language matches;
- the runtime should not synthesize or fetch missing translations on demand during live gameplay.

The first authored-content runtime adoption should stay equally small and explicit:

- Game Session should forward the current preferred locale on canonical room-read paths such as explicit `LOOK` and movement-triggered room refresh;
- Game Logic should propagate that locale through room snapshot requests without inventing a second localization authority;
- World Management should resolve stored localized room variants before returning the authoritative room snapshot;
- richer item/world/lore adoption can follow the same model later without moving localization onto the live renderer hot path.

For runtime behavior, FireMUD should prefer stored localized variants over live translation calls on the gameplay hot path. There are no live translation-provider calls or provider-selection decisions on that path; added network latency, jitter, outage, privacy, and mutable-output risk would directly hurt responsiveness and semantic stability.

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

Reconnect prompt precedence is an effective-settings rule. After authorized semantic recent-context restoration and a fresh authoritative `LOOK`, the reconnect path emits exactly one reconnect prompt only when both `firemud.presentation.prompt.enabled` and `firemud.presentation.prompt.emit-after-reconnect-restore` resolve to enabled; if either effective setting is disabled, it emits zero reconnect prompts. When enabled, this preserves the existing duplicate-prevention rule. The two settings use the normal layered precedence in the [Settings Model](./system-architecture-settings-model.md); this rule does not change ordinary prompt composition or coalescing.

Canonical default behavior:

- prompts are coalesced after short output bursts
- prompts are not stored in the semantic recent-context buffer
- reconnect restores semantic recent context first, then a fresh gameplay redraw such as `LOOK`, then one fresh prompt when both effective reconnect-prompt settings are enabled, or zero reconnect prompts when either is disabled
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
- bounded semantic recent-context restoration followed by a fresh authoritative `LOOK` and exactly one prompt when both effective reconnect-prompt settings are enabled, or zero reconnect prompts when either is disabled

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

- Reconnection restores bounded durable semantic recent context from retained structured entries, followed by a fresh authoritative redraw such as `LOOK`; it never replays cached room snapshots, arbitrary missed transport bytes, or unsent frames.
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
