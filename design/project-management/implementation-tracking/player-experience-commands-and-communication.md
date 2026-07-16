# Player Experience, Commands, and Communication

## Current Status

This tracker is the current reader-facing record for player-facing commands, admission, presentation, communication, presence, and client behavior. Most bounded capabilities are live, including the stored game-authored HELP path; broader command interpretation, rich help authoring/discovery, elevated staff behavior, richer communication, and the dedicated browser-service boundary remain explicitly incomplete. The source appendix is retained only as audit provenance.

## Implementation Record Index

Use this index to locate the current domain capability. The detailed evidence preserves every allocated legacy source line and is intentionally kept in the same document for comparison.

| Capability and ownership focus | Source-declared status | Source range | Evidence |
| --- | --- | --- | --- |
| [Session Activity and WHO Presence Vertical Slice](../vertical-slices/02.1.3-task-list-session-activity-and-who-presence-vertical-slice.md) - Player presence, activity, and WHO | complete at the current bounded boundary; broader activity-engine and later visibility-policy follow-through remain future work | 1-241 | [source evidence](#source-02-1-3-task-list-session-activity-and-who-presence-vertical-slice-1-241) |
| [Cross-Game Social Presence and Friend Activity Vertical Slice](../vertical-slices/02.1.4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice.md) - Cross-game social presence and friend activity | complete at bounded target | 1-155 | [source evidence](#source-02-1-4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice-1-155) |
| [Account Versus Character Social Scope Vertical Slice](../vertical-slices/02.1.4.1-task-list-account-vs-character-social-scope-vertical-slice.md) - Account and character social scope | complete at bounded target | 1-106 | [source evidence](#source-02-1-4-1-task-list-account-vs-character-social-scope-vertical-slice-1-106) |
| [02.1.4.2 Task List: Social Privacy Policy Propagation and Consumer Hardening Vertical Slice](../vertical-slices/02.1.4.2-task-list-social-privacy-policy-propagation-and-consumer-hardening-vertical-slice.md) - Social privacy policy and consumers | complete at bounded target | 1-102 | [source evidence](#source-02-1-4-2-task-list-social-privacy-policy-propagation-and-consumer-hardening-vertical-slice-1-102) |
| [Admin and God Capability and Visibility Vertical Slice](../vertical-slices/02.1.5-task-list-admin-god-capability-and-visibility-vertical-slice.md) - Player-facing elevated capabilities and visibility | direction locked; implementation is future work | 1-77 | [source evidence](#source-02-1-5-task-list-admin-god-capability-and-visibility-vertical-slice-1-77) |
| [Hidden Staff Modes and Capability Bundles Vertical Slice](../vertical-slices/02.1.5.1-task-list-hidden-staff-modes-and-capability-bundles-vertical-slice.md) - Hidden staff behavior and capability bundles | direction locked; implementation is future work | 1-64 | [source evidence](#source-02-1-5-1-task-list-hidden-staff-modes-and-capability-bundles-vertical-slice-1-64) |
| [Communication and Prompt Settings Vertical Slice Task List](../vertical-slices/02.10-task-list-communication-and-prompt-settings-vertical-slice.md) - Communication and prompt settings | implemented for pre-`06` scope | 1-77 | [source evidence](#source-02-10-task-list-communication-and-prompt-settings-vertical-slice-1-77) |
| [Standard Command Capability Policy Vertical Slice Task List](../vertical-slices/02.10.1-task-list-standard-command-capability-policy-vertical-slice.md) - Standard command capability policy | implemented | 1-32 | [source evidence](#source-02-10-1-task-list-standard-command-capability-policy-vertical-slice-1-32) |
| [LOOK and Transcript Settings Vertical Slice Task List](../vertical-slices/02.11-task-list-look-and-transcript-settings-vertical-slice.md) - LOOK and transcript settings | done for the pre-`06` scope | 1-105 | [source evidence](#source-02-11-task-list-look-and-transcript-settings-vertical-slice-1-105) |
| [Input, Output, and Presentation Model Vertical Slice Task List](../vertical-slices/02.13-task-list-input-output-and-presentation-model-vertical-slice.md) - Structured input, output, and presentation | pre-`06` complete | 1-119 | [source evidence](#source-02-13-task-list-input-output-and-presentation-model-vertical-slice-1-119) |
| [Normalized Command Envelope Rollout Vertical Slice Task List](../vertical-slices/02.13.1-task-list-normalized-command-envelope-rollout-vertical-slice.md) - Normalized command envelope | pre-`06` complete | 1-58 | [source evidence](#source-02-13-1-task-list-normalized-command-envelope-rollout-vertical-slice-1-58) |
| [Structured Transcript and Replay End-State Vertical Slice](../vertical-slices/02.13.10-task-list-structured-transcript-and-replay-end-state-vertical-slice.md) - Structured transcript and replay | complete at the current bounded reconnect and recent-output boundary; complete player archive/export remains separate future work | 1-72 | [source evidence](#source-02-13-10-task-list-structured-transcript-and-replay-end-state-vertical-slice-1-72) |
| [Structured Transcript Persistence and Replay Storage Vertical Slice](../vertical-slices/02.13.10.1-task-list-structured-transcript-persistence-and-replay-storage-vertical-slice.md) - Transcript persistence and replay storage | complete at the current design boundary | 1-72 | [source evidence](#source-02-13-10-1-task-list-structured-transcript-persistence-and-replay-storage-vertical-slice-1-72) |
| [Player Output Envelope Rollout Vertical Slice Task List](../vertical-slices/02.13.2-task-list-player-output-envelope-rollout-vertical-slice.md) - Player output envelope | pre-`06` complete | 1-54 | [source evidence](#source-02-13-2-task-list-player-output-envelope-rollout-vertical-slice-1-54) |
| [Prompt Pipeline and Structured Status Vertical Slice Task List](../vertical-slices/02.13.3-task-list-prompt-pipeline-and-structured-status-vertical-slice.md) - Prompt pipeline and structured status | pre-`06` complete | 1-56 | [source evidence](#source-02-13-3-task-list-prompt-pipeline-and-structured-status-vertical-slice-1-56) |
| [Presentation Policy: BRIEF, Color, and Room Refresh Vertical Slice Task List](../vertical-slices/02.13.4-task-list-presentation-policy-brief-color-and-room-refresh-vertical-slice.md) - Brief, color, and room refresh presentation | pre-`06` complete | 1-46 | [source evidence](#source-02-13-4-task-list-presentation-policy-brief-color-and-room-refresh-vertical-slice-1-46) |
| [Localization Foundation Vertical Slice Task List](../vertical-slices/02.13.5-task-list-localization-foundation-vertical-slice.md) - Player-facing localization foundation | pre-`06` complete. Built-in/platform text in Game Session now has a stable-key plus structured-variable path across the active renderer-owned built-in outputs, | 1-45 | [source evidence](#source-02-13-5-task-list-localization-foundation-vertical-slice-1-45) |
| [Command Interpretation and Alias Matching Vertical Slice](../vertical-slices/02.13.6-task-list-command-interpretation-and-alias-matching-vertical-slice.md) - Command interpretation and alias matching | partially implemented | 1-432 | [source evidence](#source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432) |
| [Action Classification and Activity Semantics Vertical Slice](../vertical-slices/02.13.7-task-list-action-classification-and-activity-semantics-vertical-slice.md) - Command activity classification | partially implemented | 1-129 | [source evidence](#source-02-13-7-task-list-action-classification-and-activity-semantics-vertical-slice-1-129) |
| [Built-In Command Registry and Dispatch Rollout Vertical Slice](../vertical-slices/02.13.8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice.md) - Built-in command registry and dispatch | complete for the built-in rollout | 1-160 | [source evidence](#source-02-13-8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice-1-160) |
| [02.13.8.1 Task List: Operator Built-In Command Alias Validation Readback Vertical Slice](../vertical-slices/02.13.8.1-task-list-operator-built-in-command-alias-validation-readback-vertical-slice.md) - Built-in command alias diagnostic | complete at the current bounded boundary | 1-87 | [source evidence](#source-02-13-8-1-task-list-operator-built-in-command-alias-validation-readback-vertical-slice-1-87) |
| [Dedicated First-Party Web App Service Vertical Slice](../vertical-slices/02.16-task-list-dedicated-first-party-web-app-service-vertical-slice.md) - First-party browser client and bootstrap experience | planned | 1-50 | [source evidence](#source-02-16-task-list-dedicated-first-party-web-app-service-vertical-slice-1-50) |
| [`02.18.14` Moderation Policy Definition and Enforcement Split](../vertical-slices/02.18.14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice.md) - Gameplay admission and chat-send enforcement behavior | complete | 24-28, 39-41, 43-48, 50-56 | [source evidence](#source-02-18-14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice-24-28-39-41-43-48-50-56) |
| [Gameplay Admission UX Alignment Vertical Slice Task List](../vertical-slices/02.2-task-list-gameplay-admission-ux-vertical-slice.md) - Gameplay admission command experience | baseline live; the core `WORLDS` / `LOGIN` / `PLAY` flow, stage-aware errors, direct WebSocket coverage, first-party path examples, Telnet no-typed-attach-hint | 1-119 | [source evidence](#source-02-2-task-list-gameplay-admission-ux-vertical-slice-1-119) |
| [Frontend Server-State Baseline and Query Convergence Vertical Slice](../vertical-slices/02.21-task-list-frontend-server-state-baseline-and-query-convergence-vertical-slice.md) - First-party frontend server-state convergence | implemented at the current baseline boundary | 1-61 | [source evidence](#source-02-21-task-list-frontend-server-state-baseline-and-query-convergence-vertical-slice-1-61) |
| [Data-Driven LOOK Vertical Slice Task List](../vertical-slices/03-task-list-data-driven-look-vertical-slice.md) - LOOK composition, rendering, transport, and replay behavior | the main LOOK flow and several regression tests are implemented; this document continues to describe the target-state behaviour, with implementation details and | 52-112 | [source evidence](#source-03-task-list-data-driven-look-vertical-slice-52-112) |
| [Chat & SAY Vertical Slice Task List](../vertical-slices/04-task-list-chat-and-social-vertical-slice.md) - Shared player communication and SAY | room-local `say` now resolves live player listeners plus NPC echoes through the shared communication path, while the broader configurable communication model re | 1-167 | [source evidence](#source-04-task-list-chat-and-social-vertical-slice-1-167) |
| [Shared Communication Infrastructure Vertical Slice Task List](../vertical-slices/04.1-task-list-shared-communication-infrastructure-vertical-slice.md) - Shared communication runtime | live for the baseline `say`/`whisper`/`tell` path; richer observer/interceptor delivery and any broader propagation modes remain later work | 1-75 | [source evidence](#source-04-1-task-list-shared-communication-infrastructure-vertical-slice-1-75) |
| [Whisper Communication Vertical Slice Task List](../vertical-slices/04.2-task-list-whisper-vertical-slice.md) - Whisper command behavior | baseline live for sender-side transcript, same-room target validation, and downstream delivery metadata; richer observer/interceptor outcomes remain deferred to | 1-93 | [source evidence](#source-04-2-task-list-whisper-vertical-slice-1-93) |
| [Tell Communication Vertical Slice Task List](../vertical-slices/04.3-task-list-tell-vertical-slice.md) - Tell command behavior | baseline live for online target resolution, sender-side transcript, and downstream direct-recipient metadata; richer interception or offline-message behavior re | 1-69 | [source evidence](#source-04-3-task-list-tell-vertical-slice-1-69) |
| [Communication Observers and Interceptors Vertical Slice Task List](../vertical-slices/04.4-task-list-communication-observers-and-interceptors-vertical-slice.md) - Communication observer and interceptor behavior | baseline live for metadata-only `whisper` observer resolution in Game Logic, including live recipient-side delivery over generic WebSocket and Telnet | 1-90 | [source evidence](#source-04-4-task-list-communication-observers-and-interceptors-vertical-slice-1-90) |
| [Communication Recipient Delivery Vertical Slice Task List](../vertical-slices/04.5-task-list-communication-recipient-delivery-vertical-slice.md) - Recipient communication delivery | baseline live for generic WebSocket and Telnet recipient delivery; first-party/MCP-aware presentation remains future work | 1-46 | [source evidence](#source-04-5-task-list-communication-recipient-delivery-vertical-slice-1-46) |
| [In-Game Help System Vertical Slice](../vertical-slices/04.6-task-list-in-game-help-system-vertical-slice.md) - Player HELP behavior | partially implemented | 1-98 | [source evidence](#source-04-6-task-list-in-game-help-system-vertical-slice-1-98) |
| [Speech Normalization and Dialogue Presentation Vertical Slice](../vertical-slices/04.7-task-list-speech-normalization-and-dialogue-presentation-vertical-slice.md) - Speech normalization and dialogue presentation | complete at the current `say` / `whisper` / `tell` boundary | 1-105 | [source evidence](#source-04-7-task-list-speech-normalization-and-dialogue-presentation-vertical-slice-1-105) |
| [Account Presence Runtime Authority Follow-Through Vertical Slice](../vertical-slices/09.1.10-task-list-account-presence-runtime-authority-follow-through-vertical-slice.md) - Account and friend presence readback behavior | complete at the current bounded boundary | 25-39 | [source evidence](#source-09-1-10-task-list-account-presence-runtime-authority-follow-through-vertical-slice-25-39) |
| [Communication Routing Availability Follow-Through Vertical Slice](../vertical-slices/09.1.3-task-list-communication-routing-availability-follow-through-vertical-slice.md) - Player communication delivery and availability behavior | complete at the current bounded boundary | 15-38 | [source evidence](#source-09-1-3-task-list-communication-routing-availability-follow-through-vertical-slice-15-38) |

## Canonical Design Sources

- [Player command model](../../architecture/system-architecture-player-command-model.md) defines command interpretation, stage behavior, typed metadata, and dispatch policy.
- [Input, output, and presentation](../../architecture/system-architecture-input-output-and-presentation.md) defines structured output, rendering, replay, prompts, localization, and protocol framing.
- [Settings model](../../architecture/system-architecture-settings-model.md) defines layered operator and tenant/game ownership, precedence, and effective-settings readback.
- [Authentication and authorization](../../architecture/system-architecture-authentication.md) defines gameplay presence, privacy, elevated actor policy, and admission stages.
- [Reconnection](../../architecture/system-architecture-reconnection.md) defines bounded replay and redraw behavior.
- [Game Session protocols](../../architecture/microservices/game-session-service/protocols.md) defines the player-facing text and WebSocket admission and command boundary.
- [Game Session Service](../../architecture/microservices/game-session-service/README.md), [Game Logic Service](../../architecture/microservices/game-logic-service/README.md), and [Social Groups Service](../../architecture/microservices/social-groups-service/README.md) define the owning service boundaries for player-facing behavior.

## Consolidated Implementation Record

### Admission and Transport-Parity Command Flow

The live player state machine has three command stages: connected but unauthenticated, authenticated in the lobby without gameplay binding, and in-world gameplay. Telnet and generic WebSocket use the same stage-aware text command path; first-party WebSocket bootstrap may hide some commands but lands in the same effective state machine.

`WORLDS` is available before login for public discovery, with richer personalized results possible after login. `LOGIN` authenticates the account; `PLAY <world> [character]` is the gameplay-binding and scope-selection step. Realm and character discovery helpers remain available where needed, but are not required ceremony in the normal flow. An unambiguous `PLAY` enters directly; ambiguous or inaccessible selections return selection-oriented or explicit bounded errors rather than low-level gameplay failures. Wrong-stage input produces guidance such as `LOGIN_REQUIRED` or `PLAY_REQUIRED`.

For first-party browser sessions, bare `LOGIN` consumes already-verified bootstrap identity rather than prompting for a second credential entry, while `PLAY` remains the sole gameplay-binding step. Trusted attach/bootstrap data is infrastructure metadata, not a player-typed command. Typed `SESSION` attach hints are not part of the normal Telnet or advanced-client flow, and no attach hint can bypass `LOGIN` plus `PLAY`.

The active built-in command surface is registry-driven. `TextCommandParser` tokenizes input, resolves aliases, and produces typed payloads; `TextCommandInterpreter` applies stage and prompt policy, then dispatches through a handler map keyed by `TextCommandDispatchGroup`. Built-in command definitions own canonical ids, aliases, stages, prompt policy, dispatch group, primary action category, source metadata, and recordability. The provider-backed aggregate registry rejects duplicate command-definition or alias ownership at construction time. Game-authored definitions from the player's admitted release artifact use that same registry and dispatch contract; built-ins retain precedence and there is no process-local configuration fallback.

Logging & Admin exposes the same built-in alias authority through `GET /command-registry/built-in-aliases/{alias}`. The global privileged-operator route returns `{supported, normalizedAlias}` from Game Session, rejects unauthorized callers before the control-plane read, and represents unsupported aliases as `supported=false` rather than a transport or 404 failure.

Movement shorthand and shaped forms such as `n`, `north`, and `GO north` resolve to canonical movement payloads. Movement accepts one canonical direction only; invalid directions and trailing operands remain malformed input and are rejected before durable enqueue. Inventory, equipment, container, room-ground, and transfer verbs converge through the shared item-command dispatcher. The live room-ground view command is the explicit no-operand shape `INV HERE`; it is distinct from carried `INVENTORY` and does not use `LOOK` prose as its data source. Stable visible instance references are live across inventory, container, and equipment paths; they remain attached to the concrete instance rather than changing with room, holder, or listing order. NPC targeting, explicit ambiguity outcomes, localized aliases, and active-registry discovery are not yet implemented.

The bounded normalized input model currently carries raw text, the normalized command, alias used, and typed payloads for the active `LOGIN`, `PLAY`, movement, communication, and gameplay-view paths. Stage remains enforced by the interpreter rather than being attached to a richer envelope, client metadata is not yet part of the envelope, and a broader schema/registry-driven command model remains future work.

### Command Capability, Classification, and Activity

`commandCapabilities` is the single effective tenant/game policy for optional standard command families: `SOCIAL`, `PRESENCE`, `INVENTORY`, and `COMMAND_HISTORY`. Operator configuration supplies safe defaults; Game Design settings authority persists tenant and game-instance overrides; the shared precedence chain resolves the effective policy. `SAY`, `WHISPER`, and `TELL` availability is controlled here, not by communication-specific booleans. Communication behavior settings remain separate and cover message length and metadata-only whisper-observer behavior. Command-history settings provide an effective retention bound, not an independent enable switch.

Game Session stage-gates, then capability-gates every text command before dispatch. Disabled direct invocation returns `FEATURE_UNAVAILABLE`; `HELP` hides disabled optional families and disabled direct topics. Game Logic repeats the `SOCIAL` gate at communication gRPC ingress so alternate callers cannot bypass text-command policy. The effective command-capability policy is available through Game Session operator settings readback.

Command definitions carry bounded primary categories (`GAMEPLAY`, `SOCIAL`, `META`, `ADMIN`, `SYSTEM`) and optional tags used by current runtime seams, including `MOVEMENT`, `COMMUNICATION`, `INVENTORY`, `UI`, session, and world-browse facets. Accepted commands update last-command activity; only accepted `GAMEPLAY` commands update meaningful gameplay activity. Auto-AFK uses meaningful activity and falls back to connection time when no meaningful action exists, so social, meta, admin, and system commands remain observable without postponing idle behavior. The same metadata reaches `onCommand` scripting payloads and bindings (`ACTION_CATEGORY` and `ACTION_TAG`), first-party structured command-result envelopes, classic communication/movement rendering, prompt-burst policy, durable command-family routing, and history eligibility. Authored command declarations can provide bounded category, tags, source, and recordability through the shared registry seam.

Broader action-classification policy consumers beyond these scripting, rendering, prompt, history, and richer-client seams remain incomplete.

### HELP, Settings, and Prompt Policy

The built-in `HELP` corpus remains platform/code-backed and filtered by effective command capability. A separate bounded game-authored topic path is live: published topics are tenant/template-scoped, resolve normalized exact canonical keys before aliases/tags, and are consumed through the admitted template with platform fallback. The no-argument index combines built-in topics with admitted authored command definitions, while stored authored topic pages use direct lookup. Rich authoring UI, localization-aware authored help, fuzzy or semantic search, related-topic graphs, stage-aware indexing, and broader dynamic command-discovery integration remain incomplete.

The first communication and prompt settings groups are explicit: `communication.behavior`, `prompts.coalescing`, and `prompts.transportPresentation`. `CommunicationProperties` surfaces `firemud.communication.max-message-length` and `firemud.communication.whisper-observer-metadata-enabled`; generated configuration metadata and service configuration docs exist. Reconnect transcript/buffer policy is typed in `FiremudReconnectionProperties`, including resume window, stale-resume fallback, screen-buffer TTL, and screen-buffer bounds, while prompt enablement, reconnect re-emission, and burst-window defaults are typed in `PresentationProperties`. Game Logic exposes merged communication behavior at `/actuator/settings/effective/communication`; Game Session exposes prompt defaults, scoped communication overrides, and effective presentation/reconnect settings on its session-oriented effective-settings surface, including normalized `transcriptRendering`, `reconnectionPolicy`, and `reconnectBuffer` views. Game Session does not claim ownership of Game Logic's merged operator defaults.

Prompts are a distinct output class, excluded from reconnect transcript buffers by default. Accepted `PLAY`, `LOOK`, movement, and communication flows can emit prompts when enabled. Classic text coalesces prompt bursts to one trailing prompt and uses a narrow per-session throttle window rather than blanket timed batching. Explicit boundary/view commands such as `LOOK` and accepted non-redraw `PLAY` retain their prompt opportunity; movement refreshes may or may not carry a trailing prompt depending on the burst. Reconnect restores transcript context, fresh `LOOK`, and one fresh prompt. Prompt payloads include a minimal structured status field list for non-text clients, and first-party WebSocket responses receive it directly. Richer game-defined or player-configurable prompt composition and broader burst scheduling remain future work.

### Structured Output, Presentation, and Replay

Game Session keeps player-visible results structured until the latest practical rendering step. `PlayerOutput` provides typed payloads, `PlayerOutputKind`, replay policy, and brief-policy metadata; the bounded top-level kinds are `message`, `view`, `prompt`, `error`, and `notice`. `TextCommandInterpretationResult` carries structured output lists. `LOGIN`, `PLAY`, `LOOK`, movement, `WORLDS`, communication, prompts, and interpreter-owned unknown-command/stage/LOOK failures use structured outputs on their active paths. Game Logic communication responses carry metadata-only delivery views rather than actor or recipient prose strings.

Text Telnet and generic WebSocket clients receive renderer-owned classic protocol text. First-party WebSocket command responses, asynchronous recipient pushes, and fresh reconnect `LOOK` project the same `PlayerOutput` batch as structured JSON envelopes. `WORLDS` is a typed `view` payload that retains its classic `OK WORLDS` rendering but remains excluded from reconnect replay; `PLAY` success is a normal notice rendered by the shared renderer. Replayed legacy text-only entries remain readable as transcript chunks; incomplete replay metadata fails closed to transcript chunks. Some narrower compatibility/replay seams still carry pre-rendered protocol text, but the main admission, browse, room-refresh, direct communication, and recipient paths no longer use raw strings as their canonical contract.

Structured view metadata is explicit rather than inferred from human-facing titles. Room views carry `LOOK`/`QUICKLOOK`/movement/reconnect refresh reasons and bounded brief hints. Inventory-family views identify `INVENTORY`, `EQUIPMENT`, or `CONTAINER` and distinguish carried from room-ground inventory. `WHO`, `FRIENDS`, browse views, prompts, and command results retain typed payload or command metadata at the first-party edge.

Durable Game Session `resume_transcript_entry` rows are the retained source of truth for bounded recent output. Each structured entry preserves session/gameplay identity, ordering, timestamp, output kind, structured payload metadata, and derived compatibility text. Redis is a best-effort hot reconnect cache. Effective tenant/game policy applies soft and hard byte ceilings, message/line floors, and optional inactivity expiry; a bounded background sweep removes expired rows even without reconnect. `LOGOUT` clears live presence and reconnect eligibility but retains bounded durable transcript rows. Replay is a separate projection decision: generic fresh `LOGIN` plus `PLAY` does not replay those rows, while the current first-party connect-context path does. Prompts are not replayed. Fresh room reads remain authoritative, and reconnect replay is not a full player archive, command-history, or export facility.

### LOOK, Room Refresh, Localization, and Presentation

Game Logic owns the data-driven `LOOK` aggregation boundary: it accepts identity and room context, orchestrates World Management room snapshots and Entity Management visible-entity data, applies visibility rules, and returns a structured result. Game Session enforces authentication before the gRPC call, maps success and `ROOM_NOT_FOUND`, `WORLD_UNAVAILABLE`, and `ENTITY_UNAVAILABLE` outcomes into structured/text protocol responses, captures downstream-call timers, and records bounded `gamesession.command.look.invocations` and `gamesession.command.look.failures` metrics plus structured logs. The canonical classic shape is room title, one composed descriptive block, exits, then a prompt outside the body.

`LOOK` and `QUICKLOOK` use the same authoritative structured room-view payload; `QUICKLOOK` suppresses long-description prose at render time. Successful movement uses the same structured room-refresh path. Fresh `LOOK` never serves a cached rendered room snapshot. Effective `BRIEF` and color settings are resolved before direct text rendering or caching. The renderer supports `none`, `basic`, and `rich` color branches, applies `SUPPRESS_IN_BRIEF` beyond only `LOOK`, and uses explicit movement/view metadata rather than command-name inference. Combat-sensitive brief-on-move behavior is not implemented because canonical combat presentation state is not yet available.

Built-in/platform text on active renderer-owned outputs uses stable keys plus structured variables, including login/play/look/move failures and notices, communication templates, and room-view labels. Built-in locale precedence is persisted session locale, then WebSocket/bootstrap locale, then `firemud.presentation.default-locale-tag`. Authored content uses a locale-tagged source-plus-variants model with exact-locale, language-only, then source-locale fallback. Room prose and adjacent exit target-room names are localized on the canonical LOOK and movement-refresh path through Game Session, Game Logic, and World Management. Runtime localization uses stored templates/variants and never live translation. Item, lore, and broader world-content adoption remains future work.

### Communication, Moderation, and Recipient Delivery

`SAY`, `WHISPER`, and online `TELL` enter through Game Session, pass through Game Logic, and use Social & Groups where downstream ownership applies. Game Session owns stage/auth gating, protocol rendering, and recipient transport delivery. Game Logic owns communication intent, target/scope resolution, gameplay perception/interception, and final outcome assembly. Social & Groups owns applicable membership checks, moderation, durable social history, and fanout; it is not an alternative top-level gameplay execution path. Communication envelopes distinguish communication type, actor/content, target/scope, resolved recipients, and per-recipient view metadata. The live observability split is explicit: communication type supplies baseline observability, target/scope qualifies listeners and observer candidates, and recipient capabilities/effects determine full-content, partial-content, or metadata-only views.

Room-local `SAY` is live for player listeners and NPC echoes through the shared communication path. The initiating player receives canonical direct prose; listeners receive canonical listener prose; downstream services receive deterministic type and recipient metadata. `CommunicationCommandHandler` records the `gamesession.command.say.*`, `gamesession.command.whisper.*`, and `gamesession.command.tell.*` command metrics/logs with bounded error-code tags. `WHISPER <character> <text>` is same-room and target-directed: sender and target receive full content, normal bystanders receive nothing by default, and observers can receive metadata-only content. The baseline observer is a room entity marked `observer_metadata_only`; its deterministic prose is `Emberline whispers something to Sora.`. Target absence/invalidity, unavailable targets, silenced senders, and downstream failures map to bounded communication errors rather than transport exceptions.

Communication application failures are returned as normal Game Logic responses carrying structured `ErrorDetail` data, allowing Game Session to map `PERMISSION_DENIED`, `UNAVAILABLE`, and related outcomes into the protocol's `ERROR COMMUNICATION_NOT_DELIVERED ...` shape without treating domain failures as gRPC transport errors.

Online `TELL <character> <text>` is direct character-to-character communication, not room-scoped and not mail. Sender and target receive full content; it is live/online only, with no default observer path. Target availability is checked through authoritative resolved gameplay context: a gameplay-name match is normalized before it is treated as online, and a stale or non-gameplay shell fails closed as `Target is not available`. Social & Groups receives explicit communication metadata for direct delivery, moderation, history where configured, and relevant presence/membership checks.

### Social & Groups Capability Boundary

Social & Groups currently exposes the documented REST surface `GET /ping`; `GET|POST /friends`; `GET|DELETE /friends/{friendAccountId}`; `GET|DELETE /friends/entry/{ordinal}`; `GET /friends/summary`; `GET /friends/presence`; `GET|PUT /friends/visibility`; `POST /mail`; `POST /chat`; `POST /guilds`; `POST /guilds/storage`; `POST /guilds/alliances`; `POST /guilds/members`; `POST /guilds/members/role`; `POST /guilds/members/remove`; and `POST /voice/token`. Its gRPC surface includes `Ping`, `SendMessage`, `CreateGuild`, `AddFriend`, `RemoveFriend`, `GetFriend`, `GetFriendByOrdinal`, `RemoveFriendByOrdinal`, `ListFriends`, `GetFriendRosterSummary`, `ListFriendPresence`, `GetFriendPresencePolicy`, `UpdateFriendPresencePolicy`, and `SendMail`. These contracts cover friends and presence/privacy, guild creation/membership/roles/storage/alliances, chat, asynchronous mail, and temporary voice-token issuance; they are service capabilities, not evidence that each capability is exposed as a live gameplay command.

The current gameplay-connected Social integration is still an early slice: room-local `SAY` proves the Game Session -> Game Logic -> Social & Groups path, while bounded `WHISPER`/`TELL` gameplay handling has explicit communication contracts. Guild, mail, voice-token, and richer real-time channel/presence behavior remain domain/API capability rather than a complete integrated player command surface, so richer-social integration maturity is below the core CRUD/domain model.

Recipient delivery is live for generic WebSocket and Telnet through Game Session's active transport-session registry, with Telnet benefiting from the existing bridged WebSocket path. Target and metadata-only observer views are delivered only to the intended actor-selected gameplay sessions in the same gameplay instance. Game Session renders recipient prose locally from structured Game Logic metadata and appends the resulting text to that recipient's reconnect buffer. These deliveries are ordinary per-recipient transcript context, not a distinct durable moderation/audit ledger. First-party/MCP-aware recipient presentation is not yet implemented.

Speech presentation uses one conservative normalizer for `say`, `whisper`, and `tell`: trim surrounding whitespace, capitalize the first alphabetic character when needed, append terminal punctuation only when missing, and preserve intentional punctuation. Raw input and normalized presented speech remain distinct; the final storage policy for retaining raw, normalized, or both is unresolved.

Moderation policy is state plus enforcement ownership, not routine account deletion. Game Session enforces gameplay-ban policy on `PLAY` admission; Social & Groups enforces mute/ban policy on chat send. The bounded implementation has focused proof for policy evaluation, non-destructive updates, admission enforcement, and chat-send enforcement.

### Presence, Friends, Privacy, and Elevated Visibility

Gameplay presence is a Redis-backed runtime authority distinct from authenticated session context. A live record carries tenant/game/account/character identity, connected state, connected-at time, last accepted command time, last meaningful gameplay-activity time, explicit AFK state/timestamp, and normalized `PLAYER`, `MODERATOR`, `ADMIN`, or `GOD` role classification. `WHO` is current-game-instance only, reads this presence store, groups gods before players, and renders typed activity state including `ACTIVE`, `AUTO_AFK`, and `EXPLICIT_AFK` with bounded `(idle)`/`(AFK)` labels.

The canonical presence lifecycle coordinator updates live and bounded recent presence on `PLAY`, accepted WebSocket command activity, unexpected disconnect, reconnect, takeover, TCP proxy disconnect hints, and deliberate `LOGOUT`. Recent presence retains `TRANSPORT_LOSS`, `LOGOUT`, or `TAKEOVER` disposition rather than reducing all departures to one `lastSeenAt`. Unexpected disconnect removes live presence while preserving reconnect-eligible state; reconnect restores live presence. Deliberate logout clears live presence and replay state while leaving bounded recent presence available for social queries. `AFK`, `AFK ON`, and `AFK OFF` update the canonical presence record, with explicit-AFK state in memory and Redis.

The bounded cross-game social model is intentionally mixed-scope: friendships, blocks, mute/ignore-style relationships, live cross-game presence, and last-seen are account-scoped; in-world visible identity is character-shaped when a live gameplay presence exists; friend-facing character exposure is policy-controlled. Social Groups queries Game Session's canonical `QueryAccountPresence` seam and does not read Redis or session data directly. Account/friend presence reads, friend roster reads, single-friend detail, ordinal detail/removal, summaries, and filtered views are available over REST and gRPC, and gameplay `FRIENDS` consumes the same seam.

The gameplay `FRIENDS` surface supports account-owned idempotent add/remove mutations, including name and ordinal removal, structured first-party mutation confirmations, canonical account/link identity, filtered `ONLINE`, `OFFLINE`, and `RECENT` views, visibility filters (`PUBLIC`, `FRIENDS_ONLY`, `PRIVATE`, `HIDDEN_STAFF`, and unspecified), gameplay-scope filters (`SHARED`, `ISOLATED`, and unspecified), `SHOW`, and `SUMMARY`. REST, gRPC, and gameplay filtered presence reads expose total and match counts using the same filter vocabulary. Summary reads expose linked, online, offline, recent-offline, visibility-bucket, and gameplay-scope-bucket counts. Unknown friend subcommands fail closed instead of degrading to a roster read; self-links are rejected at both Social Groups and gameplay boundaries.

Presence payloads conservatively expose online/offline state, current game instance and canonical world/realm labels when policy permits, current character when live, activity state, bounded recent `lastSeenAt`, and disconnect disposition. The account profile visibility policy is shared through one profile JSON helper and flows through the Game Session recent/live presence substrate. `PUBLIC`, `FRIENDS_ONLY`, and `PRIVATE` are player-facing policy values; `HIDDEN_STAFF` is reserved and role-clamped, is rejected by ordinary Account profile writers and direct Social Groups visibility updates, and suppresses ordinary friend-facing presence. Account access is enforced at Social Groups controller/gRPC boundaries, while tenant admins may read friend presence under the bounded contract. Shared test/runtime support resets and serializes the same profile policy shape used by live clients.

Account presence reads require one singular current runtime-target authority and a complete matching pointer bundle before freshness or display-name decoration is applied; ambiguous runtime authority fails closed. This prevents stale gameplay bindings from appearing online or receiving decorated presence.

Elevated actors remain ordinary player actors. Current runtime role classification supports bounded WHO grouping and presentation, but broader capability bundles and hidden staff modes are not implemented. Account visibility policy remains authoritative until an explicit staff-visibility owner exists; elevation does not implicitly hide a player from friend/social presence. The locked future separation is between capability bundles (`PLAYER`, `MODERATOR`, `ADMIN`, `GOD`) and staff-presentation modes (`NORMAL`, `STAFF_VISIBLE`, `STAFF_HIDDEN`, with a possible later observer mode), with hidden staff still visible to audit/operator tooling.

### First-Party Frontend Boundary

The current `web-client` baseline is React, Vite, MUI, and TanStack Query. It uses one shared `QueryClient`, typed query/mutation hooks, and `QueryClientProvider`; the starter scaffold has no Redux store or hooks dependency. Local feature/form state stays local, and Redux is an exception requiring a later concrete client-state justification. The first-party client consumes structured WebSocket output and server state at the current baseline boundary.

The target first-party browser boundary consumes the admitted published runtime bundle: `PLAY`/reconnect/realm-switch responses provide the resolved `versionId`, optional `scriptPatchVersion`, and manifest location/hash or equivalent; the React app then fetches the manifest from the CDN or Gateway `/assets/**` route and applies branding/theme assets without querying Game Design during gameplay. The current frontend baseline does not yet prove this complete asset-bootstrap flow.

A dedicated first-party web application service is planned as the long-term home for browser assets and browser bootstrap/product orchestration. Spring Cloud Gateway remains the public API/gameplay edge; the dedicated service, permanent browser asset-hosting/bootstrap boundary, and richer browser UX are not implemented by the current frontend baseline. The specific remaining frontend gaps are richer gameplay/product UX and stronger client data integration in `web-client`, expansion of the admin UI, expansion/integration of the Game Design visual editor, and end-to-end browser automation; the current baseline is not evidence that those admin, design-tool, or Playwright/browser-product surfaces are complete.

### Validation and Proof

Evidence records focused parser/interpreter, registry collision, alias-validation, capability-gate, HELP/history, action-classification, WebSocket/Telnet parity, LOOK, prompt, localization, transcript/reconnect, social-presence/privacy, communication, moderation, and frontend-baseline proof. Important bounded proofs include:

- direct WebSocket and Telnet admission flows covering optional `WORLDS`, `LOGIN`, `PLAY`, `LOOK`, no typed attach hint, wrong-stage guidance, and unambiguous `PLAY`;
- registry and parser tests for aliases, typed payloads, movement fail-closed behavior, dispatch ownership, duplicate command/alias rejection, and command metadata propagation;
- first-party structured WebSocket proof for accepted and rejected `LOGIN`/`PLAY`, command id/category/tag metadata, `WHO`, `FRIENDS`, communication delivery, and structured reconnect output;
- Game Session, Game Logic, World Management, Entity Management, Social Groups, Account, Logging & Admin, and cross-service tests covering LOOK, communication success/failure, recipient and observer delivery, canonical presence, privacy suppression, stale-target availability, and moderation enforcement;
- speech normalization unit and communication-handler proof plus WebSocket and Telnet cross-service transcripts for actor, listener, target, and observer views;
- durable transcript proof for ordered retention, structured metadata, compatibility text, byte/message/line bounds, expiry sweep, Redis hot-cache behavior, reconnect redraw, prompt exclusion, and logout handling;
- settings and documentation proof for generated metadata, effective-settings readback, shared policy mapping, `linkCheck`, and `lintMarkdown`.

The recorded validation includes `./gradlew spotlessApply`, `./gradlew linkCheck lintMarkdown`, focused service checks, and locked full checks for touched services. The standard-command capability evidence records that repository-wide `./gradlew check` reached an unrelated Common Saga Testcontainers integration path but could not initialize Docker because the local native Docker CLI segfaulted; hosted CI remains the full-repository runtime proof. This is a validation limitation, not a product behavior claim.

## Active Gaps

- The normalized command envelope is still text-command-first: stage and client metadata are outside the richer envelope. NPC targeting, explicit ambiguity outcomes, localized aliases, active-registry discovery, richer schema-driven definitions, and broader action-metadata policy consumers remain incomplete. Stable item references are live, but the broader duplicate-NPC and action-family resolution model is not.
- The bounded stored game-authored HELP path is live, but rich authoring UI, localization-aware content, fuzzy or semantic search, related-topic behavior, stage-aware indexes, moderation/versioning, and broader dynamic command-discovery integration are not implemented.
- Structured recent transcript retention is bounded and is not a complete player archive/export facility. Player-controlled export, long-term history/search, and final raw-versus-normalized speech storage policy remain separate work.
- Communication has no offline `TELL`, `SHOUT`, configurable topology propagation, richer partial/full interception, game-configurable observer policy, distinct observer moderation ledger, or first-party/MCP-aware recipient presentation.
- Localization is live for active built-in renderer outputs and room/exit prose, but item, lore, and broader world-content adoption and locale-specific speech policy remain incomplete.
- Presentation still lacks combat-state-driven brief-on-move behavior, richer configurable prompt composition, broader prompt scheduling, and broader smart-client presentation policy.
- Presence and social surfaces have a bounded canonical runtime/read model, but broader activity-engine consumers, richer recent-presence policy, broader social consumers, and operational hidden-staff capability enforcement remain incomplete.
- The dedicated first-party web application service, permanent browser asset-hosting boundary, and richer browser product surface remain planned.

## To Discuss

Resolve the remaining design choices in the canonical [player command model](../../architecture/system-architecture-player-command-model.md), [input/output and presentation model](../../architecture/system-architecture-input-output-and-presentation.md), [settings model](../../architecture/system-architecture-settings-model.md), and the scoped follow-up slices:

- Rich HELP authoring UI, localization-aware content, fuzzy/semantic search, stage-aware discovery, related-topic graphs, moderation/versioning, and dynamic command-discovery integration in [04.6](../vertical-slices/04.6-task-list-in-game-help-system-vertical-slice.md).
- Creator-authored command representation, namespace/precedence, localized aliases, explicit ambiguity behavior, and active-registry discovery in [02.13.6](../vertical-slices/02.13.6-task-list-command-interpretation-and-alias-matching-vertical-slice.md).
- Raw versus normalized speech retention, terminal-punctuation rules, locale-specific sentence behavior, and opt-out channels in [04.7](../vertical-slices/04.7-task-list-speech-normalization-and-dialogue-presentation-vertical-slice.md) and the transcript model.
- Archive/export retention and the boundary between recent output, history, and moderation/search in [02.13.10](../vertical-slices/02.13.10-task-list-structured-transcript-and-replay-end-state-vertical-slice.md).
- Hidden staff modes, capability bundles, operator visibility, and their consumers in [02.1.5](../vertical-slices/02.1.5-task-list-admin-god-capability-and-visibility-vertical-slice.md) and [02.1.5.1](../vertical-slices/02.1.5.1-task-list-hidden-staff-modes-and-capability-bundles-vertical-slice.md).
- Communication scope/topology, observer persistence and policy, offline messaging, and first-party recipient presentation in [04](../vertical-slices/04-task-list-chat-and-social-vertical-slice.md), [04.4](../vertical-slices/04.4-task-list-communication-observers-and-interceptors-vertical-slice.md), and [04.5](../vertical-slices/04.5-task-list-communication-recipient-delivery-vertical-slice.md).
- Combat-aware refresh and richer prompt composition in [02.13.3](../vertical-slices/02.13.3-task-list-prompt-pipeline-and-structured-status-vertical-slice.md) and [02.13.4](../vertical-slices/02.13.4-task-list-presentation-policy-brief-color-and-room-refresh-vertical-slice.md).

## Service and Contract Map

| Owner | Current responsibility | Primary contract boundary |
| --- | --- | --- |
| TCP Proxy and Gateway | Transport bridge, public edge, authentication/bootstrap propagation | Telnet bridge, gameplay WebSocket route, trusted ingress |
| Game Session | Command parsing/registry, stage and capability checks, canonical gameplay presence, structured output, rendering, prompt/replay, recipient push, account-presence query | Text command interpreter, WebSocket ingress, Game Session gRPC |
| Game Logic | Gameplay command application, action classification, communication intent, target/scope resolution, perception | Gameplay and communication gRPC contracts |
| Social Groups | Friends, applicable social policy, moderation, communication history and fanout | Friend REST/gRPC APIs and communication delivery contracts |
| World Management | Current room snapshots and localized room/exit source content | LOOK/world-read contracts |
| Game Design and settings authority | Effective command capability, presentation, communication, and prompt policy; tenant/game overrides | Shared settings precedence and Game Session effective-settings readback |
| Logging & Admin | Privileged built-in alias validation and operator diagnostics | Canonical control-plane readback |
| First-party frontend | Current structured WebSocket consumer and server-state baseline | Web client; dedicated web-app service remains planned |

The service boundaries above are the current ownership contract. Game Session is the player-facing policy/rendering and runtime-presence owner; Game Logic is the gameplay/action and communication-resolution owner; Social Groups is the social policy/history/fanout owner; World Management is the authoritative room-content owner; Account is the profile-policy source; and Logging & Admin consumes canonical control-plane diagnostics. Focused proof is summarized above, with exact source allocations retained below for audit.

## Source Evidence

The following records are the unchanged line-preserving transposition used as the audit backstop for the consolidated record above. Heading depth is shifted by three levels and same-directory Markdown links are rebased only so the combined tracker remains valid and navigable.

### source-02-1-3-task-list-session-activity-and-who-presence-vertical-slice-1-241

#### Session Activity and WHO Presence Vertical Slice - Player presence, activity, and WHO (source lines 1-241)

##### Preserved Source Text: source-02-1-3-task-list-session-activity-and-who-presence-vertical-slice-1-241

<!-- migration-source path="design/project-management/vertical-slices/02.1.3-task-list-session-activity-and-who-presence-vertical-slice.md" lines="1-241" sha256="5c007c29b1fdc7be4adefca6acce0e47cd65916ac6282732d14498a4c844cbd0" heading-offset="3" -->
#### source-02-1-3-task-list-session-activity-and-who-presence-vertical-slice-1-241: Session Activity and WHO Presence Vertical Slice

##### source-02-1-3-task-list-session-activity-and-who-presence-vertical-slice-1-241: Goal and Status

Goal: add one canonical gameplay presence/activity model that can power a real `WHO` command, idle and AFK tracking later, presence-aware delivery, and staff visibility rules without conflating "has authenticated session context" with "is actively present in game right now." Status: complete at the current bounded boundary; broader activity-engine and later visibility-policy follow-through remain future work.

##### source-02-1-3-task-list-session-activity-and-who-presence-vertical-slice-1-241: Implementation Notes

The first bounded `WHO` slice is already live:

- `WHO` now exists as a gameplay command;
- it is scoped to the current game instance only;
- it reads from a dedicated gameplay-presence store instead of raw session-context keys;
- real runtime uses Redis-backed presence rather than JVM-local presence;
- the current live output groups:
  - `Gods [N]: ...`
  - `Players [N]: ...`
- `WHO` now travels through a typed structured view payload rather than a text-only notice, so first-party clients and text rendering consume the same presence result shape;
- the current `WHO` presentation now consumes canonical activity-state resolution and renders bounded `(AFK)` / `(idle)` tags when the presence engine derives them;

The current implementation is intentionally narrower than the full target-state activity engine described below.

Live today:

- connected gameplay presence;
- tenant / game instance / account / character identity;
- god vs player role bucket for `WHO`;
- connected-at timestamp;
- last accepted command timestamp;
- last meaningful gameplay-activity timestamp keyed off canonical command classification.
- explicit `AFK`, `AFK ON`, and `AFK OFF` handling against the canonical gameplay-presence record;
- canonical explicit-AFK timestamp storage in both in-memory and Redis-backed presence services;
- one bounded auto-AFK resolver that derives `ACTIVE`, `AUTO_AFK`, or `EXPLICIT_AFK` from presence facts plus operator policy;
- one canonical gameplay-presence lifecycle coordinator now drives live presence plus bounded recent-presence updates across `PLAY`, accepted websocket command activity, unexpected disconnect, takeover, TCP proxy disconnect hints, and deliberate `LOGOUT`;
- bounded recent-presence state now also records canonical disconnect disposition (`TRANSPORT_LOSS`, `LOGOUT`, `TAKEOVER`) instead of flattening every transition into one generic `lastSeenAt` write, and that disposition now flows through account/social presence so later consumers do not need to reverse-engineer logout versus transport loss from timestamps alone;
- websocket proof that accepted built-in `AFK` updates live gameplay presence without falling back to the queued gameplay command path;
- focused unit proof that `WHO` omits removed/logged-out presence rather than reading stale local/session facts;
- focused unit proof that bounded `WHO` output keeps gods grouped first and emits the expected empty state when nobody is connected;
- focused websocket proof that first-party structured `WHO` output carries canonical activity-state values rather than a parallel text-only representation;
- websocket reconnect proof that unexpected disconnect removes live presence immediately, writes bounded recent-presence state, preserves reconnect-eligible session state, and re-establishes live presence on the new transport session;
- focused logout proof that deliberate `LOGOUT` clears live presence, clears replay state, and still leaves bounded recent-presence state available for later social queries.

Still future work above the current bounded implementation:

- reconnect/recent-presence policy facts beyond simple connected presence;
- broader activity-engine consumers beyond the first `WHO`;
- broader transport/lifecycle proof around presence continuity and later recent-presence consumers beyond the now-proven reconnect/logout/takeover disposition paths.

This slice is a follow-up to the login/session, reconnect, and logout work. The repo originally had authenticated session context, gameplay bindings, transport registries, and reconnect state without one authoritative answer for live gameplay presence. The current bounded implementation now supplies that canonical presence answer for `WHO`, AFK/activity state, and recent-presence handoff into later social consumers.

##### source-02-1-3-task-list-session-activity-and-who-presence-vertical-slice-1-241: Why This Slice Exists

`WHO` should not be implemented by scanning login/session records and hoping that means a player is really online.

Those are different things:

- authenticated session;
- gameplay-bound session;
- live connected transport;
- recently active player;
- deliberately logged-out player;
- staff/admin/god visibility classification.

If FireMUD fakes `WHO` from session context alone, it will immediately create bad semantics:

- logged-in but disconnected sessions look online;
- reconnect-suspended sessions look the same as active players;
- deliberate logout becomes hard to distinguish from stale TTL-backed session data;
- later idle, AFK, and staff visibility rules have no clean substrate.

FireMUD needs one activity/presence seam first, then `WHO` should query that seam.

##### source-02-1-3-task-list-session-activity-and-who-presence-vertical-slice-1-241: Scope

- Define the canonical difference between:
  - authenticated session context;
  - gameplay binding;
  - connected transport presence;
  - recent activity;
  - logout/termination.
- Define one reusable gameplay activity engine distinct from `WHO` presentation so later mechanics such as AFK protection, idle policies, and reconnect-aware gameplay rules can consume the same facts.
- Define the first authoritative runtime record for live gameplay presence.
- Define the minimum state that `WHO` needs, including:
  - tenant and game instance scope;
  - account and character identity;
  - transport-connected state;
  - explicit AFK state;
  - last command timestamp;
  - last meaningful gameplay-action timestamp;
  - recent-disconnect timestamp when needed later;
  - staff/admin/god classification or enough identity to derive it safely.
- Define the first `WHO` response shape on top of that presence model.
- Keep the first `WHO` contract scoped to the current game instance only.

##### source-02-1-3-task-list-session-activity-and-who-presence-vertical-slice-1-241: Out of Scope

- Full friends list or social graph presence.
- Cross-game or platform-wide presence discovery, including friend indicators outside the current game instance.
- Cross-region/global shard discovery beyond the first bounded gameplay scope.
- Rich first-party UI presence widgets.
- Deep stealth/invisibility rules beyond recording where those policies will hook in later.
- Full board/mail/forum visibility policy beyond recording that non-gameplay command activity should not automatically clear AFK.

##### source-02-1-3-task-list-session-activity-and-who-presence-vertical-slice-1-241: Target State

- FireMUD has one authoritative presence/activity model distinct from raw session-context persistence.
- `WHO` queries current gameplay presence, not stale authenticated-session records.
- `WHO` is scoped to the current game instance rather than acting as a broader social presence browser.
- The activity engine records raw runtime facts, while per-game and later per-player policy controls how those facts are presented in `WHO`.
- Presence state can distinguish:
  - active connected players;
  - reconnect-suspended players;
  - deliberately logged-out players;
  - recent but not currently connected gameplay state if later features need that.
- Activity state can distinguish:
  - explicit AFK chosen by the player;
  - auto-AFK derived from inactivity thresholds;
  - general command activity;
  - meaningful in-world gameplay activity.
- The first player-facing `WHO` output can separate:
  - game-admins/gods currently online;
  - players currently online.
- The first player-facing `WHO` output should be a grouped list with counts, with gods first and brighter presentation than ordinary players.
- The first `WHO` slice should not depend on recent-player presentation.

##### source-02-1-3-task-list-session-activity-and-who-presence-vertical-slice-1-241: First Implementation Boundary

The first narrow implementation should not attempt every presence feature at once.

The current repo has completed this first bounded order:

1. define the authoritative gameplay presence/activity record;
2. update the runtime lifecycle so login, `PLAY`, disconnect, reconnect, takeover, and logout drive it consistently;
3. implement explicit AFK state and optional game-configured auto-AFK evaluation on top of that record;
4. implement the first bounded `WHO` command on top of that record;
5. defer rich stealth/invisibility, friend-presence, and broader social discovery until the canonical model is stable.

This keeps `WHO` honest without turning the slice into a full social/presence platform.

##### source-02-1-3-task-list-session-activity-and-who-presence-vertical-slice-1-241: 1. Presence Ownership and Lifecycle Design

- [x] Re-read the login/session, reconnect, logout, TCP proxy, Gateway, and Game Session docs so one canonical presence model fits the existing session lifecycle.
- [x] Document that authenticated session context is not itself the authoritative online-presence source.
- [x] Define how presence state changes on:
  - `LOGIN`;
  - `PLAY`;
  - transport connect;
  - unexpected disconnect;
  - reconnect;
  - takeover;
  - `LOGOUT`.

##### source-02-1-3-task-list-session-activity-and-who-presence-vertical-slice-1-241: 2. Runtime Presence Model

- [x] Define the first authoritative presence record with at least:
  - tenant id;
  - game instance id;
  - session id;
  - account id;
  - character id and display name;
  - transport-connected state;
  - explicit AFK state;
  - last command timestamp;
  - last meaningful gameplay-action timestamp;
  - optional recent-disconnect timestamp;
  - reconnect-suspended or active classification;
  - role classification sufficient for `WHO`.
- [x] Keep the model authoritative and queryable without coupling it to transport-specific rendering.
- [x] Ensure the first model can later support idle, AFK, staff visibility, and targeted social features without schema churn.

##### source-02-1-3-task-list-session-activity-and-who-presence-vertical-slice-1-241: 3. Activity Engine Semantics

- [x] Define one reusable activity engine that records facts rather than presentation conclusions.
- [x] Distinguish at least:
  - transport connection state;
  - explicit AFK state;
  - last command activity;
  - last meaningful gameplay action.
- [x] Document that not every accepted command should count as a meaningful in-world action.
- [x] Keep the engine reusable for later systems such as AFK protection, idle policies, reconnect grace, and staff tooling rather than making it `WHO`-specific.

##### source-02-1-3-task-list-session-activity-and-who-presence-vertical-slice-1-241: 4. AFK and Recent-Presence Policy Hooks

- [x] Define explicit AFK command behavior, including future shaped commands such as `AFK`, `AFK ON`, and `AFK OFF`.
- [x] Define how optional auto-AFK works when enabled by game settings.
- [x] Document precedence between explicit AFK and auto-AFK.
- [x] Define whether recent disconnects are shown in the first `WHO` implementation or reserved for a later `WHO LAST`-style follow-up.
- [ ] Record that some games may want:
  - a minimal `WHO`;
  - colorful grouped `WHO` output;
  - recent-player display;
  - hidden recent-player behavior.

##### source-02-1-3-task-list-session-activity-and-who-presence-vertical-slice-1-241: 5. Role Classification for WHO

- [x] Define how `WHO` distinguishes:
  - game-admins/gods;
  - ordinary players.
- [x] Derive the first `WHO` role classification from authoritative role claims already available at gameplay admission or `PLAY` time, then carry that normalized classification in the presence record.
- [x] Avoid adding a fresh service lookup in `WHO` itself for the first slice.
- [x] Document how global roles and tenant/game-scoped roles map onto the first `WHO` categories.
- [x] Treat gods as players with elevated runtime role classification and presentation, not as a separate entity species.

##### source-02-1-3-task-list-session-activity-and-who-presence-vertical-slice-1-241: 6. WHO Command Surface

- [x] Add the first bounded `WHO` command only after the presence model exists.
- [x] Define the canonical first output shape, including:
  - `Gods [N]: ...`;
  - `Players [N]: ...`;
  - bounded empty-state behavior.
- [x] Keep gods listed first, with brighter color treatment than ordinary players.
- [x] Keep recent-player sections out of the first `WHO` output and scope the current activity annotations to bounded `(AFK)` / `(idle)` tags derived from canonical presence state.
- [x] Lock the first `WHO` query scope to the current game instance only.
- [ ] Define where game settings and later per-player overrides control:
  - grouping;
  - color/styling;
  - AFK tags;
  - recent-player visibility.
- [x] Keep the first `WHO` implementation deterministic and scope-limited rather than inventing broad social discovery semantics.

##### source-02-1-3-task-list-session-activity-and-who-presence-vertical-slice-1-241: 7. Testing and Cross-Service Proof

- [x] Add unit/integration coverage proving presence changes correctly across:
  - login and play;
  - unexpected disconnect and reconnect;
  - takeover;
  - logout.
- [x] Add coverage proving activity timestamps and AFK state react correctly to:
  - ordinary commands;
  - meaningful gameplay actions;
  - explicit AFK toggles.
- [x] Add a bounded proof that `WHO` reflects live presence and does not include stale TTL-backed or deliberately logged-out sessions.

##### source-02-1-3-task-list-session-activity-and-who-presence-vertical-slice-1-241: 8. Final QA Checklist

- [x] The repo has one explicit presence/activity model distinct from raw session context.
- [x] `WHO` is defined to query presence, not stale authenticated-session persistence.
- [x] The repo can distinguish command activity from meaningful gameplay activity.
- [x] Admin/god versus player listing is based on authoritative role data, not ad hoc local flags.
- [x] Logout, reconnect, and takeover produce different presence outcomes.
- [x] AFK and recent-player behavior are policy consumers of the activity engine, not ad hoc transport/session heuristics.
<!-- /migration-source -->

### source-02-1-4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice-1-155

#### Cross-Game Social Presence and Friend Activity Vertical Slice - Cross-game social presence and friend activity (source lines 1-155)

##### Preserved Source Text: source-02-1-4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice-1-155

<!-- migration-source path="design/project-management/vertical-slices/02.1.4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice.md" lines="1-155" sha256="8dfeeee10efac17cd48ac40b3b81772ce2a2785cc9542d01167f45e66b75b02b" heading-offset="3" -->
#### source-02-1-4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice-1-155: Cross-Game Social Presence and Friend Activity Vertical Slice

##### source-02-1-4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice-1-155: Goal and Status

Goal: define the future social-presence layer beyond in-game `WHO`, including friend activity, cross-game online indicators, and bounded last-seen/recent-presence behavior without overloading `WHO` itself. Status: complete at bounded target.

##### source-02-1-4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice-1-155: Implementation Notes

The first bounded account-scoped friend-presence substrate is now live:

- `social-groups-service` now exposes friend presence over both REST and gRPC;
- `social-groups-service` now also exposes a friend-roster read over both REST and gRPC that carries friendship metadata plus the same canonical embedded presence projection;
- the social friend-link mutation surface is now account-scoped only, supports both add and remove, and is idempotent so writes land in the same account-owned roster model every current consumer actually reads;
- live friend presence is account-scoped rather than character-scoped;
- `social-groups-service` queries `game-session-service` over gRPC for canonical account presence rather than reading Redis or session data directly;
- `game-session-service` now exposes a bounded `QueryAccountPresence` RPC that derives live account presence from canonical account-indexed gameplay-presence records plus game-session-owned recent-presence state;
- account access is now enforced explicitly at the controller/gRPC layer in `social-groups-service` rather than hiding behind an admin-only JWT interceptor.

The current implementation is intentionally narrower than the full target state described below.

Live today:

- account-scoped friend presence lookup;
- roster reads that pair account-scoped friendship metadata with the same canonical account presence projection used by player-facing `FRIENDS`;
- gameplay-facing `FRIENDS` command now consumes the same canonical social-presence seam instead of inventing a local game-session-only friend list;
- gameplay-facing `FRIENDS` now also manages the same account-owned roster model with `FRIENDS ADD <friendAccountId|characterName>` plus `FRIENDS REMOVE <friendAccountId|characterName|#entryNumber>` rather than leaving player-facing mutation on a separate contract path;
- first-party-web gameplay sessions now project canonical structured friend mutation results for `FRIENDS ADD`, `FRIENDS REMOVE`, and ordinal removal instead of collapsing those roster-management writes back to plain notice text even though the surrounding roster/detail/summary/policy surfaces are already typed;
- gameplay-facing `FRIENDS` now preserves canonical roster identity in its typed output by carrying account ids, link ids, link timestamps, and status alongside the embedded bounded presence projection rather than flattening the seam back to presence-only rows;
- gameplay-facing `FRIENDS` now exposes filtered bounded roster views with `FRIENDS ONLINE`, `FRIENDS OFFLINE`, `FRIENDS RECENT`, visibility-policy filters like `FRIENDS PUBLIC`, `FRIENDS FRIENDS_ONLY`, `FRIENDS PRIVATE`, and `FRIENDS HIDDEN_STAFF`, plus gameplay-scope filters like `FRIENDS SHARED`, `FRIENDS ISOLATED`, and `FRIENDS UNSPECIFIED_SCOPE`, all sourced from the same canonical account-scoped roster/presence seam rather than widening `WHO`;
- REST, gRPC, and gameplay `FRIENDS SHOW <friendAccountId|characterName|#entryNumber>` now expose one canonical single-friend detail read over the same roster/presence seam instead of forcing every consumer to re-scan the full roster;
- ordinal friend-detail and ordinal friend-removal reads now have their own canonical REST/gRPC surface, so gameplay `FRIENDS SHOW #entryNumber` and `FRIENDS REMOVE #entryNumber` no longer have to fetch the full roster just to re-resolve the same canonical identity a second time;
- REST, gRPC, and gameplay `FRIENDS SUMMARY` now expose one canonical roster-count read for linked, online, offline, and recent-offline totals plus visibility-policy bucket counts (`PUBLIC`, `FRIENDS_ONLY`, `PRIVATE`, `HIDDEN_STAFF`, and unspecified) and gameplay-scope bucket counts (`SHARED`, `ISOLATED`, and unspecified) so later consumers do not need to infer either summary totals or privacy posture by walking the full roster themselves;
- direct REST and gRPC friend-presence consumers now use the same canonical filtered view shape as roster consumers, including total/match counts and the same visibility-policy and gameplay-scope filter vocabulary, instead of reading a separate unstructured presence list;
- gameplay `FRIENDS VISIBILITY` now reads and updates that account-owned cross-game friend-presence policy through the canonical Social Groups seam instead of bypassing it directly to Account profile storage, so the first player-facing privacy control no longer stops at a read-only explanation or invents a fallback policy locally when the social seam is unavailable;
- `social-groups-service` now exposes that same account-owned friend-presence visibility policy over canonical REST and gRPC reads/writes backed by the Account profile seam, so later social consumers no longer need to route around Social Groups to read or update the first privacy control;
- the small Account-profile JSON contract that carries `displayName`, `bio`, and `presenceVisibilityPolicy` is now shared across Game Session, Social Groups, and the shared Account runtime stub instead of being hand-serialized three different ways, so later visibility-policy work starts from one canonical profile-shape helper rather than three drift-prone copies;
- gameplay `FRIENDS` command grammar now fails closed on unknown subcommands instead of silently degrading to a full roster list, and the documented `UNSPECIFIED_VISIBILITY` / `UNSPECIFIED_SCOPE` filter vocabulary is now part of that canonical player-facing contract;
- first-party-web gameplay sessions now project the richer friend roster/detail/summary/policy outputs over the live cross-service seam as structured payloads rather than only proving those shapes in local projector tests;
- first-party-web structured `FRIENDS` outputs now cover both roster reads and roster mutation confirmations, so social consumers on that transport no longer need to parse notice prose to learn which canonical friend identity was added or removed;
- canonical friend mutation now rejects self-link attempts at both the Social & Groups service boundary and the gameplay command surface rather than silently creating or deleting self-targeted rows;
- conservative live payloads:
  - online/offline;
  - current game instance id plus canonical world/realm labels when the visibility policy allows it;
  - current character name when the account has a live gameplay presence;
  - activity state derived from canonical gameplay presence;
- bounded offline `lastSeenAt` sourced from a canonical game-session-owned recent-presence store rather than fabricated at read time;
- the same recent-presence substrate now carries disconnect disposition (`TRANSPORT_LOSS`, `LOGOUT`, `TAKEOVER`) through account presence, Social & Groups, and the first gameplay-facing friend-presence consumer so offline friend views can distinguish bounded `logged out`, `replaced session`, and `connection lost` outcomes rather than flattening them into one generic last-seen label;
- account-owned profile visibility policy now flows through the canonical game-session recent/live presence substrate instead of being derived from gameplay role defaults;
- explicit internal presence-visibility policy is carried through account presence and enforced when friend presence is projected;
- `HIDDEN_STAFF` remains a role-clamped policy so ordinary friend-facing presence stays suppressed even if the account profile would otherwise expose broader detail;
- Account Service profile REST and gRPC writers reject `HIDDEN_STAFF` before persistence, closing the remaining direct-profile bypass around the reserved policy; staff-policy authoring remains outside ordinary profile updates.
- direct Social Groups visibility-policy updates keep the same first-pass contract as gameplay by allowing `PUBLIC`, `FRIENDS_ONLY`, and `PRIVATE` while rejecting `HIDDEN_STAFF` as a reserved role-clamped policy rather than creating a bypass around the existing player-facing seam;
- friend presence available to the owning account or tenant admins;
- player-facing suppression for `PRIVATE` and `HIDDEN_STAFF` style policies rather than implicit mapper behavior.
- shared test/runtime support now resets account-profile visibility policy back to the canonical default between scenarios, so cross-service friend-presence and privacy proof no longer inherits mutated profile state from earlier tests in the same suite.
- shared test/runtime support now also uses the same canonical Account-profile JSON helper as the live clients, so visibility-policy proof no longer relies on a hand-rolled stub-only serialization path that can diverge from the live Social Groups / Game Session clients.

Still future work under this slice:

- cross-game social UI/command consumers beyond the first bounded `FRIENDS` listing plus the now-canonical gameplay, REST, and gRPC visibility-policy seam;
- staff-hidden and privacy-mode suppression across broader social surfaces;
- later profile/privacy refinement beyond the first account-owned `PUBLIC` / `FRIENDS_ONLY` / `PRIVATE` / `HIDDEN_STAFF` seam.

##### source-02-1-4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice-1-155: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end-to-end at the bounded seam currently in scope.
- [x] Verify follow-ups and park the explicit remaining follow-through.

##### source-02-1-4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice-1-155: Why This Slice Exists

FireMUD has already locked `WHO` to the current game instance. That is the correct first in-game behavior, but it leaves a later social-presence problem unsolved:

- players may want to know whether friends are online in another game instance;
- platform-level friendships may need account-scoped online indicators;
- game-scoped friendships or character-scoped social graphs may need different visibility behavior;
- cross-game presence should not be retrofitted into `WHO`, which is now explicitly an in-game command.

This slice exists so broader social activity and last-seen behavior can be designed as a separate social-system concern rather than leaking back into the simpler gameplay presence model.

##### source-02-1-4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice-1-155: Scope

- Define the relationship between:
  - in-game `WHO`;
  - friend/relationship indicators;
  - cross-game social presence;
  - bounded recent/last-seen status.
- Define whether social presence is account-scoped, character-scoped, or both.
- Define visibility and privacy policy for friend presence and recent activity.
- Define how future social commands or UI surfaces consume this data without reusing the `WHO` contract.

##### source-02-1-4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice-1-155: Out of Scope

- Implementing cross-game chat or mail.
- Reopening `WHO` scope beyond current game instance.
- Full guild or faction presence systems.

##### source-02-1-4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice-1-155: Known Design Considerations

- `WHO` remains current-game-instance only.
- Cross-game presence belongs to Social & Groups or a dedicated social activity surface, not the `WHO` command.
- Presence visibility may differ by relationship strength:
  - account friendship;
  - character friendship;
  - in-game versus out-of-game relationship models.
- Recent/last-seen behavior should come from the activity/presence engine, but the policy for exposing it belongs here rather than in the first `WHO` slice.
- Actor runtime identity and broader social identity should remain separate concerns:
  - actor = in-world gameplay being in a game instance;
  - account/character linkage = identity attachment;
  - social presence/friend indicators = social-system concern beyond `WHO`.

##### source-02-1-4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice-1-155: Locked Direction

- FireMUD uses an intentional mixed social-scope model rather than forcing all social surfaces to one identity layer.
- The social graph is account-scoped:
  - friendships;
  - blocks;
  - mute/ignore style relationships.
- Cross-game online/offline and last-seen style presence is account-scoped.
- In-world visible identity remains character-scoped.
- `WHO` remains a character-scoped, current-instance gameplay surface rather than a cross-game presence tool.
- Friend-facing "currently playing as a specific character" exposure is policy-controlled rather than unconditional.
- Default cross-game presence payloads should stay conservative and privacy-aware.

##### source-02-1-4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice-1-155: First Implementation Boundary

The first implementation should land the canonical cross-service seam, not a local social-service-only approximation.

Recommended first order:

1. expose one bounded account-presence query from `game-session-service`;
2. consume that seam from `social-groups-service` rather than reading session/runtime storage directly;
3. expose the first friend-presence listing over player-facing REST and gRPC surfaces;
4. keep the payload conservative and honest;
5. defer later social consumers and later privacy refinements until the canonical source and policy seam exist.

This keeps the first slice structurally correct without pretending later policy sourcing and presentation work are already solved.

##### source-02-1-4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice-1-155: Completion Evidence

- `services/social-groups-service/src/main/java/net/firedevops/firemud/socialgroups/service/impl/FriendServiceImpl.java`
- `services/social-groups-service/src/main/java/net/firedevops/firemud/socialgroups/client/AccountClient.java`
- `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/AccountPresenceQueryServiceImpl.java`
- `services/game-session-service/src/test/java/crossservice/net/firedevops/firemud/gamesession/CommunicationWebSocketCrossServiceTest.java`
- `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/command/text/FriendsCommandHandlerTest.java`
- `services/social-groups-service/src/test/java/unit/net/firedevops/firemud/socialgroups/service/impl/FriendServiceImplTest.java`
- `services/social-groups-service/src/test/java/unit/net/firedevops/firemud/socialgroups/controller/FriendControllerTest.java`
- `design/project-management/vertical-slices/02.1.4.1-task-list-account-vs-character-social-scope-vertical-slice.md`
- `design/project-management/vertical-slices/02.1.4.2-task-list-social-privacy-policy-propagation-and-consumer-hardening-vertical-slice.md`

##### source-02-1-4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice-1-155: Deferred Follow-ups

- [x] Extend the now-live REST/gRPC plus gameplay `FRIENDS` friend-presence substrate into later social consumers without widening `WHO`.
- [x] Carry the same canonical visibility-policy enforcement onto the next real social consumers.
- [x] Broaden gameplay or operator-facing social consumers beyond the bounded current contract as a future child slice.
<!-- /migration-source -->

### source-02-1-4-1-task-list-account-vs-character-social-scope-vertical-slice-1-106

#### Account Versus Character Social Scope Vertical Slice - Account and character social scope (source lines 1-106)

##### Preserved Source Text: source-02-1-4-1-task-list-account-vs-character-social-scope-vertical-slice-1-106

<!-- migration-source path="design/project-management/vertical-slices/02.1.4.1-task-list-account-vs-character-social-scope-vertical-slice.md" lines="1-106" sha256="09071e7641ed7d2d695d0f099520d791847f9ee87263bb8c83bca96caf2323fc" heading-offset="3" -->
#### source-02-1-4-1-task-list-account-vs-character-social-scope-vertical-slice-1-106: Account Versus Character Social Scope Vertical Slice

##### source-02-1-4-1-task-list-account-vs-character-social-scope-vertical-slice-1-106: Goal and Status

Goal: decide whether future friendships, cross-game presence, and recent activity are account-scoped, character-scoped, or intentionally mixed by surface. Status: complete at bounded target.

##### source-02-1-4-1-task-list-account-vs-character-social-scope-vertical-slice-1-106: Implementation Notes

The first bounded implementation now follows the locked mixed-scope design:

- friend links remain account-scoped;
- cross-game live presence is queried by account id;
- in-world visible identity remains character-shaped when a live gameplay presence exists;
- the first player-facing friend-presence payload is deliberately conservative and policy-aware;
- bounded account-scoped `lastSeenAt` is now sourced from canonical game-session recent-presence state;
- one explicit visibility-policy seam now exists and is enforced during friend-presence projection.

Implementation is complete for this bounded scope: account-scoped friendship and friend presence are the canonical contract used by the first real consumers, with policy enforcement wired through the shared profile-backed visibility seam.

##### source-02-1-4-1-task-list-account-vs-character-social-scope-vertical-slice-1-106: Completion Evidence

- services/social-groups-service/src/main/java/net/firedevops/firemud/socialgroups/service/impl/FriendServiceImpl.java
- services/social-groups-service/src/main/java/net/firedevops/firemud/socialgroups/client/AccountClient.java
- services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/AccountPresenceQueryServiceImpl.java
- services/game-session-service/src/test/java/crossservice/net/firedevops/firemud/gamesession/CommunicationWebSocketCrossServiceTest.java
- services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/command/text/FriendsCommandHandlerTest.java
- services/social-groups-service/src/test/java/unit/net/firedevops/firemud/socialgroups/service/impl/FriendServiceImplTest.java
- services/social-groups-service/src/test/java/unit/net/firedevops/firemud/socialgroups/controller/FriendControllerTest.java

##### source-02-1-4-1-task-list-account-vs-character-social-scope-vertical-slice-1-106: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end-to-end.
- [x] Verify and close any follow-ups.

##### source-02-1-4-1-task-list-account-vs-character-social-scope-vertical-slice-1-106: Why This Slice Exists

The broader social-presence direction is good, but one key identity question is still too broad:

- should friend relationships attach to accounts;
- should they attach to characters;
- or should different social surfaces intentionally use different scope?

This needs an explicit pass before broader social-presence implementation begins.

##### source-02-1-4-1-task-list-account-vs-character-social-scope-vertical-slice-1-106: Scope

- Define account-scoped versus character-scoped friendship models.
- Define which social-presence surfaces consume which identity scope.
- Define whether last-seen/recent activity should be account-facing, character-facing, or both.

##### source-02-1-4-1-task-list-account-vs-character-social-scope-vertical-slice-1-106: Out of Scope

- Full guild or faction systems.
- Reopening current-game-instance `WHO`.

##### source-02-1-4-1-task-list-account-vs-character-social-scope-vertical-slice-1-106: Locked Direction

- FireMUD uses an intentional mixed model rather than forcing all social surfaces to one scope.
- The social graph is account-scoped:
  - friendships;
  - blocks;
  - mute/ignore style relationships.
- Cross-game online/offline and last-seen style presence is account-scoped.
- In-world visible identity remains character-scoped.
- `WHO` remains character-scoped and current-instance scoped.
- Direct in-world social targeting such as current-runtime `TELL` remains character-targeted in gameplay terms.
- Friend-facing "currently playing as a specific character" exposure is policy-controlled rather than unconditional.
- The default cross-game presence payload should stay conservative:
  - online/offline;
  - game/world name when allowed;
  - last-seen timestamp;
  - current character name only when visibility policy allows it.
- The default cross-game presence payload should not expose:
  - full alt rosters;
  - exact room/location;
  - hidden/privacy-restricted character identity.
- Privacy and staff exceptions should be mediated through one explicit presence-visibility policy seam rather than ad hoc per-surface flags.

The first explicit presence-visibility policy should be small and shared:

- `PUBLIC`;
- `FRIENDS_ONLY`;
- `PRIVATE`;
- `HIDDEN_STAFF`.

The intended behavior is:

- `PUBLIC`: friend/social presence may expose the normal conservative payload;
- `FRIENDS_ONLY`: richer live identity is only available to approved friends;
- `PRIVATE`: suppress current live character identity and expose only coarse presence/last-seen;
- `HIDDEN_STAFF`: suppress player-facing and friend-facing identity/presence surfaces while remaining visible to audit/operator tooling.

##### source-02-1-4-1-task-list-account-vs-character-social-scope-vertical-slice-1-106: Why This Direction

- It matches how users treat social relationships across alts and games.
- It preserves in-world character identity where the fiction and player-facing presence actually matter.
- It avoids duplicating friendships per character while keeping runtime visibility policy explicit.
- It leaves room for later privacy/staff rules without forcing a second social model.

##### source-02-1-4-1-task-list-account-vs-character-social-scope-vertical-slice-1-106: Current Remaining Work

Remaining scope is now explicitly out-of-slice:

- Extend the mixed-scope model into later friend/activity consumers without leaking exact in-world location or hidden identity is now handled in `02.1.4`.
- Non-default privacy/visibility policy-value authoring and settings-source governance is now tracked for later policy-surface follow-through.
<!-- /migration-source -->

### source-02-1-4-2-task-list-social-privacy-policy-propagation-and-consumer-hardening-vertical-slice-1-102

#### 02.1.4.2 Task List: Social Privacy Policy Propagation and Consumer Hardening Vertical Slice - Social privacy policy and consumers (source lines 1-102)

##### Preserved Source Text: source-02-1-4-2-task-list-social-privacy-policy-propagation-and-consumer-hardening-vertical-slice-1-102

<!-- migration-source path="design/project-management/vertical-slices/02.1.4.2-task-list-social-privacy-policy-propagation-and-consumer-hardening-vertical-slice.md" lines="1-102" sha256="16184211c354c27d1885c8034bad3945ea9a2f4da4bc7cc334925e2a0599d0f8" heading-offset="3" -->
#### source-02-1-4-2-task-list-social-privacy-policy-propagation-and-consumer-hardening-vertical-slice-1-102: 02.1.4.2 Task List: Social Privacy Policy Propagation and Consumer Hardening Vertical Slice

##### source-02-1-4-2-task-list-social-privacy-policy-propagation-and-consumer-hardening-vertical-slice-1-102: Goal and Status

Goal: carry the now-live cross-game presence visibility policy onto the next real social consumers so `PUBLIC`, `FRIENDS_ONLY`, `PRIVATE`, and `HIDDEN_STAFF` stay canonical enforcement truth instead of degrading to per-consumer suppression heuristics. Status: complete at bounded target.

##### source-02-1-4-2-task-list-social-privacy-policy-propagation-and-consumer-hardening-vertical-slice-1-102: Why This Slice Exists

`02.1.4` and `02.1.4.1` already locked the mixed social-scope model and the first visibility-policy seam for canonical `FRIENDS`, REST, and gRPC presence surfaces. One bounded policy gap remains:

- later social consumers can still drift if they reproject presence or identity without the same privacy enforcement helper/model;
- `HIDDEN_STAFF` and non-public visibility are especially easy to mishandle if each new consumer decides its own suppression logic;
- the remaining work is not to redesign privacy policy, but to propagate and harden the already-decided contract.

##### source-02-1-4-2-task-list-social-privacy-policy-propagation-and-consumer-hardening-vertical-slice-1-102: Scope

- the next real social consumers beyond the current canonical `FRIENDS` and direct presence surfaces;
- shared helper/model convergence for visibility-policy enforcement on those consumers;
- focused proof that policy-controlled suppression stays consistent on the touched surfaces.

##### source-02-1-4-2-task-list-social-privacy-policy-propagation-and-consumer-hardening-vertical-slice-1-102: Out of Scope

- reopening the mixed account-versus-character social-scope design from `02.1.4.1`;
- broad social-product design beyond the touched consumers;
- widening `WHO` into a cross-game presence surface.

##### source-02-1-4-2-task-list-social-privacy-policy-propagation-and-consumer-hardening-vertical-slice-1-102: Locked Direction

- visibility-policy enforcement should stay canonical and shared rather than consumer-local;
- `PUBLIC`, `FRIENDS_ONLY`, `PRIVATE`, and `HIDDEN_STAFF` remain distinct operator/player-facing truths;
- later social consumers should reuse the same bounded policy model instead of re-deriving hidden/private behavior ad hoc.

##### source-02-1-4-2-task-list-social-privacy-policy-propagation-and-consumer-hardening-vertical-slice-1-102: Planned Work

###### source-02-1-4-2-task-list-social-privacy-policy-propagation-and-consumer-hardening-vertical-slice-1-102: 1. Remaining Consumer Audit

- [x] Enumerate the live social consumers beyond the already-converged `FRIENDS`, REST, and gRPC presence surfaces.
- [x] Identify which of those consumers still project presence or identity without the canonical privacy-policy helper/model.
- [x] Skip stale or not-yet-real consumers.

Converged touched consumers in this bounded cut:

- gameplay `FRIENDS` command and payload surfaces in `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/command/text`
- canonical friend roster/presence REST endpoints in `services/social-groups-service/src/main/java/net/firedevops/firemud/socialgroups/controller`
- canonical friend roster/presence gRPC endpoints in `services/social-groups-service/src/main/java/net/firedevops/firemud/socialgroups/service/impl`
- canonical visibility-policy profile source in `services/social-groups-service/src/main/java/net/firedevops/firemud/socialgroups/client/AccountClient.java`
- account-profile presence policy contract in `services/account-service/src/main/java/net/firedevops/firemud/accountservice/entity/Profile.java` and `services/account-service/src/main/java/net/firedevops/firemud/accountservice/service/impl/AccountServiceImpl.java`

###### source-02-1-4-2-task-list-social-privacy-policy-propagation-and-consumer-hardening-vertical-slice-1-102: 2. Policy Propagation Follow-Through

- [x] Move the touched consumers onto the canonical visibility-policy enforcement seam.
- [x] Remove touched ad hoc suppression or fallback behavior that can drift from the policy contract.
- [x] Keep player-facing and operator-facing behavior explicit when `PRIVATE` or `HIDDEN_STAFF` changes what the consumer may show.

This slice also leaves policy-source enrichment and any additional future social consumers outside this cut to the next bounded follow-up.

###### source-02-1-4-2-task-list-social-privacy-policy-propagation-and-consumer-hardening-vertical-slice-1-102: 3. Focused Proof and Docs

- [x] Add or refresh focused proof for policy-controlled suppression on the touched consumers.
- [x] Update `02.1.4` docs/status so the remaining later-consumer privacy tail is explicit after this cut.
- [x] Re-run touched validation and Markdown/link proof.

##### source-02-1-4-2-task-list-social-privacy-policy-propagation-and-consumer-hardening-vertical-slice-1-102: Completion Evidence

- services/social-groups-service/src/main/java/net/firedevops/firemud/socialgroups/service/impl/FriendServiceImpl.java
- services/social-groups-service/src/main/java/net/firedevops/firemud/socialgroups/client/AccountClient.java
- services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/AccountPresenceQueryServiceImpl.java
- services/social-groups-service/src/main/java/net/firedevops/firemud/socialgroups/controller/FriendController.java
- services/social-groups-service/src/main/java/net/firedevops/firemud/socialgroups/service/impl/SocialGroupsGrpcService.java
- services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/command/text/FriendsCommandHandler.java
- services/social-groups-service/src/test/java/unit/net/firedevops/firemud/socialgroups/service/impl/FriendServiceImplTest.java
- services/social-groups-service/src/test/java/unit/net/firedevops/firemud/socialgroups/controller/FriendControllerTest.java
- services/social-groups-service/src/test/java/unit/net/firedevops/firemud/socialgroups/service/impl/SocialGroupsGrpcServiceTest.java
- services/game-session-service/src/test/java/crossservice/net/firedevops/firemud/gamesession/CommunicationWebSocketCrossServiceTest.java
- services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/command/text/FriendsCommandHandlerTest.java

##### source-02-1-4-2-task-list-social-privacy-policy-propagation-and-consumer-hardening-vertical-slice-1-102: Acceptance Shape

- the touched social consumers no longer enforce presence/privacy policy through consumer-local heuristics;
- `PUBLIC`, `FRIENDS_ONLY`, `PRIVATE`, and `HIDDEN_STAFF` stay materially consistent across the touched surfaces;
- focused proof covers the still-valid suppression behaviors for the touched consumers.

##### source-02-1-4-2-task-list-social-privacy-policy-propagation-and-consumer-hardening-vertical-slice-1-102: Spark Delegation Notes

- Audit remaining live social consumers first, then converge the smallest still-valid set in one pass.
- Keep the batch on privacy-policy propagation only; do not widen it into generic social UX redesign.
- Return exact touched consumers, exact changed files, and exact validation commands run.

##### source-02-1-4-2-task-list-social-privacy-policy-propagation-and-consumer-hardening-vertical-slice-1-102: Suggested Starting Surfaces

- `services/social-groups-service`
- `services/game-session-service`
- `services/account-service`
- `design/project-management/vertical-slices/02.1.4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice.md`

##### source-02-1-4-2-task-list-social-privacy-policy-propagation-and-consumer-hardening-vertical-slice-1-102: Validation

- `./gradlew spotlessApply`
- `./gradlew :social-groups-service:check -PfullCheck`
- `./gradlew :game-session-service:check -PfullCheck`
- `./gradlew :account-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-02-1-5-task-list-admin-god-capability-and-visibility-vertical-slice-1-77

#### Admin and God Capability and Visibility Vertical Slice - Player-facing elevated capabilities and visibility (source lines 1-77)

##### Preserved Source Text: source-02-1-5-task-list-admin-god-capability-and-visibility-vertical-slice-1-77

<!-- migration-source path="design/project-management/vertical-slices/02.1.5-task-list-admin-god-capability-and-visibility-vertical-slice.md" lines="1-77" sha256="56a7876798680bad7b63cba2153c187eb00d914056a53fffbb68cb9f19679f38" heading-offset="3" -->
#### source-02-1-5-task-list-admin-god-capability-and-visibility-vertical-slice-1-77: Admin and God Capability and Visibility Vertical Slice

##### source-02-1-5-task-list-admin-god-capability-and-visibility-vertical-slice-1-77: Goal and Status

Goal: define one canonical model for elevated player capabilities, god/admin visibility, privileged command access, and later in-game staff presentation without inventing a separate actor species for gods. Status: direction locked; implementation is future work.

##### source-02-1-5-task-list-admin-god-capability-and-visibility-vertical-slice-1-77: Checklist

- [x] Define target-state behavior and scope.
- [ ] Implement the slice end-to-end.
- [ ] Verify and close any follow-ups.

##### source-02-1-5-task-list-admin-god-capability-and-visibility-vertical-slice-1-77: Implementation Notes

- Current live behavior is still narrow:
  - `WHO` groups elevated gameplay roles separately from players;
  - runtime presence now retains the normalized `PLAYER`, `MODERATOR`, `ADMIN`, or `GOD` role classification for bounded presentation, sourced from current global and tenant-scoped claims;
  - elevated role does not implicitly hide staff from friend/social presence; the existing account visibility policy remains authoritative until an explicit staff-visibility owner lands;
  - broader capability bundles and hidden staff behavior are not implemented yet.
- The design direction below is now locked for future implementation.

##### source-02-1-5-task-list-admin-god-capability-and-visibility-vertical-slice-1-77: Why This Slice Exists

Recent `WHO` design work locked in the idea that gods are elevated players, not a separate entity type. That resolves the first gameplay presence view, but FireMUD still needs a fuller model for:

- privileged gameplay or moderation commands;
- god/admin visibility and hidden presence behavior;
- presentation differences for gods in `WHO`, chat, room views, or later admin tools;
- promotion from ordinary player to elevated operator role without changing the underlying actor kind.

This needs a dedicated slice so elevated access does not remain an ad hoc mixture of account roles, session flags, and one-off command checks.

##### source-02-1-5-task-list-admin-god-capability-and-visibility-vertical-slice-1-77: Scope

- Define elevated runtime role/capability classification for gods/admins.
- Define how privileges are expressed:
  - capabilities;
  - role bundles;
  - visibility flags;
  - later command gating.
- Define the intended relationship between:
  - ordinary players;
  - gods/admins;
  - hidden or invisible staff modes.
- Define how gameplay presence and presentation consume the elevated role model.

##### source-02-1-5-task-list-admin-god-capability-and-visibility-vertical-slice-1-77: Out of Scope

- Implementing the full admin command surface.
- Broader security hardening already covered by service-boundary/auth slices.
- Actor ontology itself, which belongs to the unified actor-model slice.

##### source-02-1-5-task-list-admin-god-capability-and-visibility-vertical-slice-1-77: Known Design Considerations

- Gods are still players underneath.
- `WHO` grouping/presentation already assumes brighter display and top ordering for gods.
- Hidden/invisible presence should be an explicit later staff capability, not an accidental side effect of missing activity records.
- Capability checks should eventually use one shared model rather than command-local role-name checks.
- The first normalized elevated-player fields should be:
  - role classification;
  - staff-presentation mode.
- Richer capability bundles should come later in this slice rather than being forced into the first actor-model pass.
- Source truth for elevated privileges still comes from account/admin policy for player actors.
- Gameplay systems should consume normalized actor runtime classification rather than repeatedly querying raw account/session claims.
- Staff-presentation mode should be explicit:
  - normal visibility;
  - later hidden/invisible staff modes.
- Capability and staff-presentation attachment should be actor-wide where semantically possible, not hardcoded as player-only plumbing.

##### source-02-1-5-task-list-admin-god-capability-and-visibility-vertical-slice-1-77: Locked Direction

- Capability bundles and staff-presentation modes are separate layers over one actor model.
- Gods/admins remain elevated player actors rather than a separate actor species.
- Capability bundles determine what powers and command families are available.
- Staff-presentation modes determine how elevated actors are surfaced to players, social consumers, and later observer tools. They are not game-wide targeting or perception fields.
- Hidden staff behavior must be explicit policy, not an accidental side effect of missing presence records.
- Audit/operator-facing tooling must still be able to observe hidden staff state where appropriate.
<!-- /migration-source -->

### source-02-1-5-1-task-list-hidden-staff-modes-and-capability-bundles-vertical-slice-1-64

#### Hidden Staff Modes and Capability Bundles Vertical Slice - Hidden staff behavior and capability bundles (source lines 1-64)

##### Preserved Source Text: source-02-1-5-1-task-list-hidden-staff-modes-and-capability-bundles-vertical-slice-1-64

<!-- migration-source path="design/project-management/vertical-slices/02.1.5.1-task-list-hidden-staff-modes-and-capability-bundles-vertical-slice.md" lines="1-64" sha256="4a472e1ed75f8caeb835c18b48a4e99e521f5e4d7d96d49e94c765d694de710d" heading-offset="3" -->
#### source-02-1-5-1-task-list-hidden-staff-modes-and-capability-bundles-vertical-slice-1-64: Hidden Staff Modes and Capability Bundles Vertical Slice

##### source-02-1-5-1-task-list-hidden-staff-modes-and-capability-bundles-vertical-slice-1-64: Goal and Status

Goal: turn the now-settled actor role/staff-presentation direction into an operational model for hidden staff behavior, privileged command gating, and reusable capability bundles. Status: direction locked; implementation is future work.

##### source-02-1-5-1-task-list-hidden-staff-modes-and-capability-bundles-vertical-slice-1-64: Checklist

- [x] Define target-state behavior and scope.
- [ ] Implement the slice end-to-end.
- [ ] Verify and close any follow-ups.

##### source-02-1-5-1-task-list-hidden-staff-modes-and-capability-bundles-vertical-slice-1-64: Why This Slice Exists

The actor/social audit locked the broad model:

- gods/admins are elevated player actors;
- role classification and staff-presentation mode should be normalized into actor runtime state first;
- richer capability bundles come later.

This slice exists to define that later operational model before implementation begins.

##### source-02-1-5-1-task-list-hidden-staff-modes-and-capability-bundles-vertical-slice-1-64: Scope

- Define hidden/invisible staff modes operationally.
- Define capability bundles for elevated player actors.
- Define how privileged command gating consumes those bundles.
- Define how `WHO`, room visibility, and later social surfaces consume hidden staff visibility rules.

##### source-02-1-5-1-task-list-hidden-staff-modes-and-capability-bundles-vertical-slice-1-64: Out of Scope

- Full admin UI.
- Broader service-boundary authorization.

##### source-02-1-5-1-task-list-hidden-staff-modes-and-capability-bundles-vertical-slice-1-64: Locked Direction

- Capability bundles and staff-presentation modes are separate layers over one actor model.
- Hidden staff is not a separate actor type; it is a staff-presentation mode applied to an elevated actor.
- Capability bundles determine what powers/commands are available.
- Staff-presentation modes determine how that actor is surfaced to other players and social consumers; they are not game-wide targeting or perception fields.
- The first bounded capability bundles should be:
  - `PLAYER`;
  - `MODERATOR`;
  - `ADMIN`;
  - `GOD`.
- The first bounded visibility modes should be:
  - `NORMAL`;
  - `STAFF_VISIBLE`;
  - `STAFF_HIDDEN`;
  - later `STAFF_OBSERVER` if needed.
- Hidden visibility policy must be consumed consistently by:
  - `WHO`;
  - room presence and room-view surfaces;
  - friend/social presence surfaces;
  - later transcript/observer visibility consumers.
- Hidden mode does not implicitly grant broader powers.
- Broader powers do not implicitly force hidden mode.
- Hidden staff must still remain visible in audit/operator-facing tooling.

##### source-02-1-5-1-task-list-hidden-staff-modes-and-capability-bundles-vertical-slice-1-64: Why This Direction

- It avoids overloading one `isAdmin`-style boolean with unrelated concerns.
- It keeps the actor model unified for later player/NPC/god convergence.
- It makes moderation, audit, and visibility policy more legible and testable.
<!-- /migration-source -->

### source-02-10-task-list-communication-and-prompt-settings-vertical-slice-1-77

#### Communication and Prompt Settings Vertical Slice Task List - Communication and prompt settings (source lines 1-77)

##### Preserved Source Text: source-02-10-task-list-communication-and-prompt-settings-vertical-slice-1-77

<!-- migration-source path="design/project-management/vertical-slices/02.10-task-list-communication-and-prompt-settings-vertical-slice.md" lines="1-77" sha256="efe14b9fd8784522a9395614504e51eb3df0f745b100627c22875a1b47bbcf8a" heading-offset="3" -->
#### source-02-10-task-list-communication-and-prompt-settings-vertical-slice-1-77: Communication and Prompt Settings Vertical Slice Task List

##### source-02-10-task-list-communication-and-prompt-settings-vertical-slice-1-77: Goal and Status

Goal: fold communication and prompt behavior into the new platform settings model so `say`, `whisper`, `tell`, reconnect prompt handling, and later first-party/MCP-aware presentation stop depending on hardcoded defaults scattered across services. Status: implemented for pre-`06` scope.

##### source-02-10-task-list-communication-and-prompt-settings-vertical-slice-1-77: Implementation Notes

- Communication now has surfaced behavior settings via `CommunicationProperties`, `firemud.communication.max-message-length`, `firemud.communication.whisper-observer-metadata-enabled`, and generated configuration metadata in `game-logic-service`.
- Standard-command availability is not a communication mode setting. `SAY`, `WHISPER`, and `TELL` are governed with other optional standard command families by the persisted `commandCapabilities` policy; Game Logic repeats the `SOCIAL` gate at gRPC ingress.
- The first canonical settings groups for this area are now explicit:
  - `communication.behavior`
  - `prompts.coalescing`
  - `prompts.transportPresentation`
- Prompt-related knobs now exist in typed config objects:
  - reconnect-oriented transcript/buffer policy lives in `FiremudReconnectionProperties`
  - prompt enablement, reconnect re-emit behavior, and burst-window defaults live in `PresentationProperties`
- Current effective-config behavior is now explicit:
  - communication behavior resolves in Game Logic from `CommunicationProperties` with persisted tenant/game overrides, while command availability resolves from the shared `commandCapabilities` policy; Game Logic exposes the merged communication behavior at `/actuator/settings/effective/communication`;
  - prompt defaults now resolve as a first-class section on Game Session's effective-settings read surface rather than only as an incidental nested `presentation.prompt` object;
  - Game Session also exposes the scoped shared `communication` override layer it sees for the same session or synthesized scope, so prompt/communication settings are inspectable from one session-oriented operator surface without pretending Game Session owns Game Logic's merged operator defaults;
  - command availability and communication behavior use the shared persisted tenant/game settings authority after operator defaults; prompt settings retain their documented scope behavior.
- Built-in communication prose and prompt presentation still flow through the shared communication/output model. Tenant/game behavior overrides adjust the same pipeline; availability is handled by the shared command capability policy rather than ad hoc per-verb switches.
- Generation-ready settings references now exist in:
  - `design/architecture/microservices/game-logic-service/configuration.md`
  - `design/architecture/microservices/game-session-service/configuration.md`
- The canonical layered ownership and precedence model for these settings is now captured in `design/architecture/system-architecture-settings-model.md`.
- Prompt payloads now carry a first minimal structured status field list alongside classic prompt text, and first-party web command responses now receive that structured prompt payload through the WebSocket edge envelope.
- Pre-`06` completion note:
  - only the agreed first bounded subset of communication behavior is surfaced here; broader communication modes and topology-sensitive scope remain later slices by design; and
  - richer prompt batching/layout remains later prompt-pipeline work rather than an open `02.10` settings gap.

This slice follows `02.9`. It should not redesign the communication architecture itself. It should surface the already-agreed communication and prompt policy into the layered settings model with operator caps, tenant/game overrides, and generated reference docs.

##### source-02-10-task-list-communication-and-prompt-settings-vertical-slice-1-77: 1. Domain Inventory

- [x] Audit the currently surfaced communication and prompt behavior now living in code, docs, and tests across Game Session, Game Logic, and reconnect flows.
- [x] Classify the surfaced operator-default behaviors as:
  - operator/bootstrap/runtime setting;
  - tenant/game behavior setting;
  - or internal-only implementation detail for now.

##### source-02-10-task-list-communication-and-prompt-settings-vertical-slice-1-77: 2. Communication Settings Surface

- [x] Define the first canonical `communication` settings group, with the live group centered on `communication.behavior`.
- [x] Keep availability for `say`, `whisper`, and `tell` in the shared standard-command capability policy rather than duplicating per-verb booleans.
- [x] Surface the first metadata-only whisper observer behavior default.
- [x] Document how games can override prose/presentation defaults without bypassing the shared communication model.
- [x] Keep future `shout` explicitly deferred until the topology/game-settings model is ready to express scope cleanly.

##### source-02-10-task-list-communication-and-prompt-settings-vertical-slice-1-77: 3. Prompt Settings Surface

- [x] Define the first canonical `prompts` settings groups, with the first live groups centered on:
  - `prompts.coalescing`
  - `prompts.transportPresentation`
- [x] Surface the agreed prompt rules:
  - prompts are a distinct output class;
  - prompts are excluded from reconnect transcript buffers by default;
  - prompt emission should be coalesced across output bursts.
- [x] Document how first-party web and future MCP-aware clients may consume prompt/state output differently from Telnet transcript rendering.

##### source-02-10-task-list-communication-and-prompt-settings-vertical-slice-1-77: 4. Persistence and Effective Config

- [x] Decide which communication and prompt settings are tenant/game-configurable versus operator-capped.
- [x] Document the current effective-config rule for communication rendering and prompt emission.
- [x] Keep transport/runtime-only implementation knobs out of the tenant/game surface unless deliberately promoted.

##### source-02-10-task-list-communication-and-prompt-settings-vertical-slice-1-77: 5. Docs and Generated Reference

- [x] Add a generated or generation-ready settings reference for the communication and prompt domains.
- [x] Update the communication docs so they reference surfaced settings rather than only describing hardcoded behavior.
- [x] Update the reconnect docs so they reference surfaced settings rather than only describing hardcoded behavior.

##### source-02-10-task-list-communication-and-prompt-settings-vertical-slice-1-77: 6. Final QA Checklist

- [x] Confirm the baseline `say` / `whisper` / `tell` and prompt behaviors can each be traced to surfaced settings metadata rather than only code/tests.
- [x] Confirm the resulting settings model is compatible with later creator/admin tooling and first-party/MCP-aware presentation work.
<!-- /migration-source -->

### source-02-10-1-task-list-standard-command-capability-policy-vertical-slice-1-32

#### Standard Command Capability Policy Vertical Slice Task List - Standard command capability policy (source lines 1-32)

##### Preserved Source Text: source-02-10-1-task-list-standard-command-capability-policy-vertical-slice-1-32

<!-- migration-source path="design/project-management/vertical-slices/02.10.1-task-list-standard-command-capability-policy-vertical-slice.md" lines="1-32" sha256="8bbceaf7fc4e51f875f2598c7b8dd2948d4271161ce84d22e7569743c4998080" heading-offset="3" -->
#### source-02-10-1-task-list-standard-command-capability-policy-vertical-slice-1-32: Standard Command Capability Policy Vertical Slice Task List

##### source-02-10-1-task-list-standard-command-capability-policy-vertical-slice-1-32: Goal and Status

Goal: converge standard optional command availability on one persisted tenant/game policy rather than per-command or per-service booleans. Status: implemented.

##### source-02-10-1-task-list-standard-command-capability-policy-vertical-slice-1-32: Boundary

- `commandCapabilities` is the canonical settings domain for `SOCIAL`, `PRESENCE`, `INVENTORY`, and `COMMAND_HISTORY`.
- Operator configuration supplies safe defaults. Game Design settings authority persists tenant and game-instance overrides; the standard shared precedence chain resolves the effective policy.
- Game Session stage-gates, then capability-gates every text command before dispatch. Disabled direct invocation returns `FEATURE_UNAVAILABLE`.
- `HELP` hides disabled optional command families and disabled direct topics remain undiscoverable.
- Game Logic repeats the `SOCIAL` gate at communication gRPC ingress so alternate callers cannot bypass text-command policy.
- Command-history retention is separate from availability: it retains only its effective entry bound and does not own an enabled switch.
- Communication settings retain behavior only: message length and whisper observer metadata. Per-verb availability settings are removed.

##### source-02-10-1-task-list-standard-command-capability-policy-vertical-slice-1-32: Implementation Checklist

- [x] Add typed command capability metadata to standard command definitions and resolved command metadata.
- [x] Add operator defaults and persisted tenant/game override mapping for the shared `commandCapabilities` domain.
- [x] Resolve the same effective policy at Game Session dispatch, HELP discovery, command-history recording, and Game Logic communication ingress.
- [x] Remove superseded communication availability and command-history enabled settings from application configuration, metadata, proto contracts, and settings publication.
- [x] Surface effective command capability policy through the Game Session operator settings readback.
- [x] Add and run focused negative-path proof for disabled and unavailable policy paths, persisted mapping, and Game Logic ingress.
- [x] Run formatting, service checks, documentation hygiene, and repository validation.

##### source-02-10-1-task-list-standard-command-capability-policy-vertical-slice-1-32: Validation Notes

- Compile proof covered Common Platform Core, Game Design, Game Session, and Game Logic after the contract replacement.
- Focused unit and integration proof passed for shared settings precedence and proto mapping, Game Design authority mapping, Game Session capability gates/HELP/history/metadata/effective settings, and Game Logic communication ingress/effective settings.
- `spotlessApply`, `linkCheck lintMarkdown`, and locked full checks for Common Platform Core, Game Design, Game Session, and Game Logic passed.
- Repository-wide `./gradlew check` reached the unrelated Common Saga Testcontainers integration path but could not initialize Docker because the local native Docker CLI segfaults. Hosted CI remains the full-repository runtime proof.
<!-- /migration-source -->

### source-02-11-task-list-look-and-transcript-settings-vertical-slice-1-105

#### LOOK and Transcript Settings Vertical Slice Task List - LOOK and transcript settings (source lines 1-105)

##### Preserved Source Text: source-02-11-task-list-look-and-transcript-settings-vertical-slice-1-105

<!-- migration-source path="design/project-management/vertical-slices/02.11-task-list-look-and-transcript-settings-vertical-slice.md" lines="1-105" sha256="a57dbd065614793f5e0b6347d9760dc5301a788c3bc1b8c68eb7c319129fb93e" heading-offset="3" -->
#### source-02-11-task-list-look-and-transcript-settings-vertical-slice-1-105: LOOK and Transcript Settings Vertical Slice Task List

##### source-02-11-task-list-look-and-transcript-settings-vertical-slice-1-105: Goal and Status

Goal: fold `LOOK`, `QUICKLOOK`, reconnect screen-buffer policy, and transcript rendering defaults into the platform settings model so room-view behavior evolves through explicit settings rather than fixed renderer assumptions. Status: done for the pre-`06` scope.

##### source-02-11-task-list-look-and-transcript-settings-vertical-slice-1-105: Implementation Notes

- Reconnect and transcript policy now have a real typed settings seam in `FiremudReconnectionProperties`:
  - resume window;
  - stale-resume fallback;
  - screen-buffer TTL and bounds;
- Those reconnect/transcript settings are now surfaced in the Game Session service defaults, configuration metadata, and generated-facing configuration docs under `firemud.reconnection.*`.
- Prompt emission/re-render policy now lives in `PresentationProperties`, while reconnect transcript policy remains in `FiremudReconnectionProperties`.
- Room-view and transcript presentation defaults now have a generation-ready typed settings seam in `PresentationProperties`:
  - default color mode;
  - brief-by-default rendering policy;
  - prompt enablement and burst-window policy.
- Those settings are now surfaced in the Game Session service configuration metadata and docs.
- The canonical layered ownership and precedence model for these settings is now captured in `design/architecture/system-architecture-settings-model.md`.
- Current effective-config behavior is now explicit:
  - reconnect/transcript retention resolves directly from `FiremudReconnectionProperties`;
  - room-view and prompt presentation now resolve through Game Session's first effective-settings read surface, merging `PresentationProperties` operator defaults with tenant/game overrides from the shared Game Design settings authority;
  - that bounded read surface is now inspectable at `/actuator/settings/effective` so operator/debug flows can see the current resolved presentation plus reconnect defaults for one session or synthesized scope;
  - the same response now exposes normalized subgroup views for the live room-view/transcript seams:
    - `transcriptRendering`
    - `reconnectionPolicy`
    - `reconnectBuffer`
  - reconnect transcript policy is still operator/file-env-backed only today rather than part of a tenant/game settings authority.
- The classic default transcript shape is now explicit in the architecture and presentation docs:
  - room title;
  - one composed descriptive block;
  - exits;
  - prompt outside the body.
- `LOOK` has moved onto the structured `LookViewOutput` path for explicit room refreshes, and `QUICKLOOK` now reuses the same room-view payload while suppressing the long-description prose at render time.
- The classic standalone view/replay path now also resolves the correct success envelope from the structured payload family itself, so replayable inventory views and async `WHO` / `FRIENDS` / browse views no longer fall back to a hardcoded `OK LOOK` label outside full command-result rendering.
- Standalone room-view replay and cached protocol text now also preserve `QUICKLOOK` versus `LOOK` from the structured refresh reason, so replayable quicklook output and reconnect-adjacent cached room text no longer collapse back to `OK LOOK` once they leave the direct command-response path.
- Standalone inventory-family replay and generic push now also resolve `INVENTORY` / `EQUIPMENT` / `CONTAINER` from explicit structured payload metadata instead of inferring protocol envelopes from human-facing titles like `Equipment:` or `Container:`.
- Structured inventory replay/push metadata now also distinguishes carried versus room-ground inventory directly in `InventoryViewOutput`, and first-party/replayed inventory payloads now surface a stable `inventory_view` type instead of degrading to `unknown`.
- The older direct `LOOK` text-render path now also resolves effective presentation settings before rendering or caching, so scoped BRIEF/color/prompt defaults do not bypass the bounded settings model.
- Fresh `LOOK` remains authoritative rather than being served from cached rendered room output.
- The older reconnect-adjacent rendered-room snapshot helper is no longer part of the surfaced settings model and should not be treated as an operator-facing `LOOK` cache policy.
- The pre-`06` settings slice is complete:
  - the live room-view/transcript seams are surfaced in typed settings metadata and the generated reference;
  - reconnect retention and resume policy are operator-visible and inspectable;
  - room-view transcript rendering defaults resolve through the shared effective-settings path; and
  - the remaining work has moved to later slices rather than staying inside `02.11`.

Nothing further remains in `02.11` for the pre-`06` scope. Follow-up work belongs to later slices instead:

- `02.13.4` for richer presentation-policy behavior such as combat-sensitive brief-on-move;
- `06` and later inventory/equipment slices for broader composed room-view content;
- future settings slices only if transcript overlay policy becomes a real surfaced runtime setting rather than a documented extension bucket.

This slice follows `02.9`. It should not redesign the `LOOK` ownership split. It should surface the already-agreed room-view and transcript rules into the canonical settings model and generated docs.

##### source-02-11-task-list-look-and-transcript-settings-vertical-slice-1-105: 1. Domain Inventory

- [x] Audit the existing surfaced `LOOK`, reconnect screen-buffer, and transcript-shape rules currently expressed in docs, tests, and rendering code.
- [x] Classify the surfaced operator-default behaviors as:
  - operator/runtime tuning;
  - tenant/game behavior policy;
  - or internal-only implementation detail for now.

##### source-02-11-task-list-look-and-transcript-settings-vertical-slice-1-105: 2. Room-View Settings Surface

- [x] Surface the first canonical room-view presentation settings under `firemud.presentation`, including:
  - default color mode;
  - brief-by-default rendering policy;
  - prompt burst-window defaults.
- [x] Surface the agreed classic default transcript shape:
  - room title;
  - one composed descriptive block;
  - exits;
  - prompt outside the body.
- [x] Capture `QUICKLOOK` as a standard built-in variant that omits room-description prose while reusing the same underlying structured room-view model.

##### source-02-11-task-list-look-and-transcript-settings-vertical-slice-1-105: 3. Transcript and Screen-Buffer Settings Surface

- [x] Define the first canonical transcript/screen-buffer settings groups, such as:
  - `transcript.reconnectBuffer`
  - `transcript.rendering`
  - `transcript.overlayPolicy`
- [x] Surface the agreed reconnect screen-buffer rules:
  - minimum message floor;
  - minimum line floor;
  - byte ceiling;
  - short TTL;
  - fresh `LOOK` after replay.
- [x] Document how overlays like combat/hazards/ambient effects should later fit into configurable transcript rendering without reopening the base `LOOK` contract.

##### source-02-11-task-list-look-and-transcript-settings-vertical-slice-1-105: 4. Persistence and Effective Config

- [x] Decide which room-view and transcript settings are tenant/game-configurable versus operator-capped.
- [x] Document how effective config is currently resolved for room-view rendering and reconnect redraw behavior.

##### source-02-11-task-list-look-and-transcript-settings-vertical-slice-1-105: 5. Docs and Generated Reference

- [x] Add a generated or generation-ready settings reference for the surfaced room-view and transcript presentation domains.
- [x] Update the `03` design docs to reference surfaced settings where appropriate rather than only prose contracts.

##### source-02-11-task-list-look-and-transcript-settings-vertical-slice-1-105: 6. Final QA Checklist

- [x] Confirm the baseline `LOOK`/`QUICKLOOK`/reconnect transcript behavior can each be traced to surfaced settings metadata rather than only code/tests.
- [x] Confirm the resulting settings model remains compatible with future room-ground inventory visibility from `06`.
<!-- /migration-source -->

### source-02-13-task-list-input-output-and-presentation-model-vertical-slice-1-119

#### Input, Output, and Presentation Model Vertical Slice Task List - Structured input, output, and presentation (source lines 1-119)

##### Preserved Source Text: source-02-13-task-list-input-output-and-presentation-model-vertical-slice-1-119

<!-- migration-source path="design/project-management/vertical-slices/02.13-task-list-input-output-and-presentation-model-vertical-slice.md" lines="1-119" sha256="e2dd99d5391f24ad73f11cd5dfc6d574c07eb79faf410aa146b2762d47ef8eaf" heading-offset="3" -->
#### source-02-13-task-list-input-output-and-presentation-model-vertical-slice-1-119: Input, Output, and Presentation Model Vertical Slice Task List

##### source-02-13-task-list-input-output-and-presentation-model-vertical-slice-1-119: Goal and Status

Goal: define and then implement the first canonical structured input and output model for FireMUD so command parsing, player-visible transcript generation, prompt handling, color policy, and `BRIEF` behavior no longer accrete as ad hoc string formatting decisions across services. The preferred first model should use small normalized envelopes with typed payloads and presentation tags, not brittle one-class-per-command or one-class-per-output hierarchies. Status: pre-`06` complete.

##### source-02-13-task-list-input-output-and-presentation-model-vertical-slice-1-119: Implementation Notes

- Game Session now has the first normalized player-output envelope in code:
  - `PlayerOutput`
  - typed payloads under the `presentation` package
  - `PlayerOutputKind`
  - replay policy
  - brief-policy placeholders
  - late rendering through `TextPlayerOutputRenderer`
- `TextCommandInterpretationResult` now carries structured output lists instead of only a single raw response string, and reconnect buffering now keys off output replay eligibility rather than hardcoded command lists.
- `LOOK` has started moving toward structured late rendering through `LookViewOutput`, and communication actor plus recipient transcript rendering now goes through the same renderer path from metadata-only delivery views.
- First-party web now consumes the first structured command-response and async-player-output envelopes at the WebSocket edge, while generic WebSocket and Telnet remain on the classic text renderer.
- Prompt output is now emitted on the main accepted in-game command flows, reconnect restore ends with one fresh prompt, and renderer-side prompt coalescing is live.
- Interpreter-owned failures now use real `error` envelopes for unknown-command, stage-gating, and immediate `LOOK` error mapping.
- The current boundary remains intentionally narrow:
  - the normalized command envelope is still a small text-command-first model rather than a richer schema- or registry-driven command system;
  - prompt scheduling remains deliberately narrow rather than a generic timed batching subsystem;
  - combat-sensitive brief-on-move remains future work once combat state exists as a canonical presentation hint;
  - replay storage remains transcript-text-backed even though first-party replay and fresh redraw delivery now project through the structured output boundary.

This slice exists because several other slices already depend on an implicit presentation contract:

- reconnect now restores a bounded screen buffer plus a fresh `LOOK`;
- `LOOK` already has a canonical transcript shape;
- prompts are already treated as a separate output class in design;
- communication now has structured delivery metadata plus canonical prose;
- first-party web and future MCP-aware clients need more than raw plain-text lines.

The platform now needs one explicit model that keeps gameplay outcomes structured until the latest practical rendering step.

##### source-02-13-task-list-input-output-and-presentation-model-vertical-slice-1-119: 1. Architecture Alignment and Canonical Scope

- [x] Re-read the current protocol, reconnect, `LOOK`, communication, and settings docs to capture the already-agreed presentation invariants rather than redefining them piecemeal.
- [x] Add or refine one canonical architecture document that states the structured input and structured output model, late-rendering ownership, prompt separation, and player presentation settings model.
- [x] Explicitly define the core output classes for gameplay traffic:
  - `message`;
  - `view`;
  - `prompt`;
  - `error`;
  - `notice`.
- [x] Explicitly define the input pipeline from raw text or smart-client metadata to a normalized command envelope to execution, keeping menu-stage intents and gameplay-stage intents distinct.
- [x] Document that the authoritative abstraction is not raw strings end-to-end; rendered transport text is the final presentation form, not the canonical internal contract.
- [x] Document why the preferred first implementation uses normalized envelopes plus typed payload shapes and presentation tags instead of one hard-coded class per command or per output.
- [x] Note that a richer future schema-driven command model or document-tree presentation model may be desirable later for creator tooling and highly configurable clients, but that it is not the first implementation boundary.

##### source-02-13-task-list-input-output-and-presentation-model-vertical-slice-1-119: 2. Prompt and Screen-Buffer Contract

- [x] Fold the already-agreed prompt behavior into the canonical model:
  - prompts are a separate output class;
  - prompts are coalesced rather than emitted after every message;
  - prompts are not part of the reconnect transcript buffer by default;
  - reconnect restores transcript context first, then a fresh redraw such as `LOOK`, then one fresh prompt.
- [x] Document how first-party web and future MCP-aware clients may consume prompt/status as structured state instead of transcript text.
- [x] Define the initial prompt-coalescing policy and identify which parts are fixed defaults versus future settings-driven behavior.

##### source-02-13-task-list-input-output-and-presentation-model-vertical-slice-1-119: 3. Color, BRIEF, and Presentation Settings Model

- [x] Define the canonical player-facing presentation settings relevant to the first implementation pass:
  - color mode (`none`, `basic`, `rich`);
  - `BRIEF` versus verbose room-view behavior;
  - prompt visibility and prompt rendering policy.
- [x] Document that `BRIEF` mode should primarily be driven by structured presentation tags and renderer suppression rather than dual-authored prose for every action.
- [x] Identify the small set of built-in outputs that may justify concise alternate brief-specific text later, while keeping that as the exception rather than the default authoring model.
- [x] Cross-link this slice with the broader settings-model work so these presentation options have a clear future home in the layered operator/file versus tenant/database config model.

##### source-02-13-task-list-input-output-and-presentation-model-vertical-slice-1-119: 4. First Implementation Boundary

- [x] Choose a minimal first implementation that proves the model without refactoring every gameplay output at once.
- [x] Recommended first proof:
  - keep existing command parsing surfaces;
  - introduce a normalized player-output envelope for `LOOK`, communication acknowledgements, and prompt/state;
  - render them late in Game Session for Telnet and WebSocket;
  - allow prompt suppression or alternate handling for first-party web.
- [x] Decide whether the first implementation needs only normalized output envelopes or whether any bounded part of the normalized input-envelope model should land at the same time.
- [x] Define exactly which current string-producing paths are in scope for the first proof and which remain legacy internal formatting until later follow-up slices.
- [x] Decide where the first shared renderer abstractions live so future client-specific renderers can extend them cleanly.

##### source-02-13-task-list-input-output-and-presentation-model-vertical-slice-1-119: 5. Testing and Regression Coverage

- [x] Define the first regression shape for this slice:
  - plain-text transcript expectations for Telnet and generic WebSocket;
  - prompt coalescing behavior under bursts of output;
  - reconnect replay excluding prompts while still ending with a fresh prompt;
  - `BRIEF` filtering for a built-in gameplay view such as movement-triggered room redraw.
- [x] Add or plan snapshot-style tests for canonical rendered outputs so future color, prompt, and accessibility changes do not silently break the classic MUD text contract.
- [x] Add or plan structured-output tests that do not depend only on final rendered text, so first-party web and later MCP-aware work can evolve without rewriting gameplay semantics.

##### source-02-13-task-list-input-output-and-presentation-model-vertical-slice-1-119: 6. Documentation and Follow-on Slice Planning

- [x] Update the architecture indexes and vertical-slice indexes so input/output/presentation is a tracked workstream, not a hidden side concern.
- [x] Identify follow-on slices likely to branch from this one, including:
  - prompt and structured status delivery for first-party web/MCP-aware clients;
  - color/capability negotiation;
  - `BRIEF` and accessibility presentation behavior;
  - richer schema-driven input definitions if command metadata becomes creator-configurable;
  - richer document-tree presentation if later clients need more than envelope-plus-tags rendering.
- [x] Reconcile any duplicated notes in `LOOK`, communication, reconnect, or protocol docs so they point to the canonical presentation model once this slice lands.

##### source-02-13-task-list-input-output-and-presentation-model-vertical-slice-1-119: Follow-on Slices

The remaining rollout work should now be tracked in dedicated follow-up slices:

- [02.13.1-task-list-normalized-command-envelope-rollout-vertical-slice.md](../vertical-slices/02.13.1-task-list-normalized-command-envelope-rollout-vertical-slice.md)
- [02.13.2-task-list-player-output-envelope-rollout-vertical-slice.md](../vertical-slices/02.13.2-task-list-player-output-envelope-rollout-vertical-slice.md)
- [02.13.3-task-list-prompt-pipeline-and-structured-status-vertical-slice.md](../vertical-slices/02.13.3-task-list-prompt-pipeline-and-structured-status-vertical-slice.md)
- [02.13.4-task-list-presentation-policy-brief-color-and-room-refresh-vertical-slice.md](../vertical-slices/02.13.4-task-list-presentation-policy-brief-color-and-room-refresh-vertical-slice.md)
- [02.13.5-task-list-localization-foundation-vertical-slice.md](../vertical-slices/02.13.5-task-list-localization-foundation-vertical-slice.md)

##### source-02-13-task-list-input-output-and-presentation-model-vertical-slice-1-119: Notes

- The first pass should not attempt to expose every presentation or formatting option as a user-editable setting immediately.
- The first pass should establish the canonical model and one narrow implementation proof, then let `02.9` through `02.12` absorb the settings surface area cleanly.
- Avoid creating a second competing raw-string contract beside the structured output model. If an output is canonical enough to matter for reconnect, web UI, accessibility, or future smart clients, it should move toward the structured model rather than away from it.
<!-- /migration-source -->

### source-02-13-1-task-list-normalized-command-envelope-rollout-vertical-slice-1-58

#### Normalized Command Envelope Rollout Vertical Slice Task List - Normalized command envelope (source lines 1-58)

##### Preserved Source Text: source-02-13-1-task-list-normalized-command-envelope-rollout-vertical-slice-1-58

<!-- migration-source path="design/project-management/vertical-slices/02.13.1-task-list-normalized-command-envelope-rollout-vertical-slice.md" lines="1-58" sha256="92082eca8f30bbecdaed502b23f15fb3187c9ca582bbc413eadf974220cb8709" heading-offset="3" -->
#### source-02-13-1-task-list-normalized-command-envelope-rollout-vertical-slice-1-58: Normalized Command Envelope Rollout Vertical Slice Task List

##### source-02-13-1-task-list-normalized-command-envelope-rollout-vertical-slice-1-58: Goal and Status

Goal: carry the canonical input model from architecture into code by introducing a normalized command envelope with typed payload shapes through the active Game Session command path, without attempting a one-shot rewrite of every parser and handler. Status: pre-`06` complete.

##### source-02-13-1-task-list-normalized-command-envelope-rollout-vertical-slice-1-58: Implementation Notes

- The first bounded envelope step is now live in the Game Session text parser:
  - `TextCommand` carries `aliasUsed` and a typed `TextCommandPayload`;
  - `TextCommandParser` produces typed payloads for the current built-in command set instead of only raw token lists;
  - and the active handlers for `LOGIN`, `PLAY`, movement, built-in communication, and gameplay view requests now consume those typed payloads directly instead of relying on legacy `args()` fallback.
- This slice is still incomplete:
  - stage is still enforced in the interpreter rather than being attached to a normalized envelope object;
  - client metadata is not yet part of the command envelope;
  - `LOOK` and the remaining lower-level command metadata still have not been folded into a richer normalized envelope beyond the current text command shape;
  - and later schema- or registry-driven command definitions remain future work.

##### source-02-13-1-task-list-normalized-command-envelope-rollout-vertical-slice-1-58: Scope

- Replace ad hoc raw-string command handling where it matters most with a normalized command envelope.
- Keep menu/lobby and gameplay-stage intents distinct.
- Avoid one-class-per-command; use a small set of payload shapes and normalized verb metadata.

##### source-02-13-1-task-list-normalized-command-envelope-rollout-vertical-slice-1-58: Key Tasks

- [x] Define the first runtime command-envelope shape in code, including:
  - normalized verb;
  - alias used;
  - raw line;
  - typed payload.
- [x] Identify the first bounded payload shapes needed for the current live command set, such as:
  - credentials payload;
  - selection payload;
  - targeted-message payload;
  - directional payload;
  - gameplay-view request payload.
- [x] Refactor the active Game Session parser path so it produces the normalized envelope rather than only ad hoc command objects or raw text.
- [x] Keep stage legality in the interpreter/session gate rather than moving it into payload parsing.
- [x] Convert the highest-value current commands first:
  - `LOGIN`
  - `PLAY`
  - `LOOK`
  - `SAY`
  - `WHISPER`
  - `TELL`
  - movement
- [x] Leave room for later schema/registry-driven command definitions without requiring them in this slice.

##### source-02-13-1-task-list-normalized-command-envelope-rollout-vertical-slice-1-58: Tests

- [x] Add focused parser/interpreter tests proving normalized envelope production for the first bounded command set.
- [x] Add regression coverage showing aliases and stage legality still behave correctly after the normalization step.

##### source-02-13-1-task-list-normalized-command-envelope-rollout-vertical-slice-1-58: Notes

- This slice is about input normalization, not about adding new gameplay verbs.
- Avoid inventing a large command class hierarchy; the goal is a small stable envelope with typed payloads.
<!-- /migration-source -->

### source-02-13-10-task-list-structured-transcript-and-replay-end-state-vertical-slice-1-72

#### Structured Transcript and Replay End-State Vertical Slice - Structured transcript and replay (source lines 1-72)

##### Preserved Source Text: source-02-13-10-task-list-structured-transcript-and-replay-end-state-vertical-slice-1-72

<!-- migration-source path="design/project-management/vertical-slices/02.13.10-task-list-structured-transcript-and-replay-end-state-vertical-slice.md" lines="1-72" sha256="066e9737347e1a25b4ef410a52c8f60c90d77bf1ee1b7bde2205369a0d9ba0f0" heading-offset="3" -->
#### source-02-13-10-task-list-structured-transcript-and-replay-end-state-vertical-slice-1-72: Structured Transcript and Replay End-State Vertical Slice

##### source-02-13-10-task-list-structured-transcript-and-replay-end-state-vertical-slice-1-72: Goal and Status

Goal: define the structured transcript, replay, and rendering model so all player-visible output remains canonical, replay-safe, client-policy-safe, and transport-independent. Status: complete at the current bounded reconnect and recent-output boundary; complete player archive/export remains separate future work.

##### source-02-13-10-task-list-structured-transcript-and-replay-end-state-vertical-slice-1-72: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end-to-end for bounded reconnect and recent-output retention.
- [x] Verify and close current follow-ups.

##### source-02-13-10-task-list-structured-transcript-and-replay-end-state-vertical-slice-1-72: Implementation Notes

The repo already has a meaningful first structured-output substrate:

- `PlayerOutput` and typed payloads are the canonical live output envelope before final rendering.
- `ReplayPolicy` and prompt payloads already distinguish replayable output from non-replayable prompt/error/notice flows.
- classic text and first-party web already consume the same canonical output objects through different projectors/renderers.
- reconnect replay now stores structured `PlayerOutput` metadata alongside rendered protocol text in the hot screen buffer when the output came from the structured path, so first-party clients can receive typed transcript entries on reconnect while Telnet/generic WebSocket clients still receive classic text.
- the current replay-buffer producers now converge on one shared structured replay-entry builder, so recipient communication delivery no longer downcasts buffered output back to legacy text-only entries and the replayability gate/LOOK-view rendering rule teach one boundary.
- live session delivery now centralizes the non-first-party `VIEW` projection rule in `WebSocketOutputProjector` instead of repeating the same LOOK-render fallback in multiple callers.
- replay-buffer entry construction now also reuses that same projector-owned classic-rendering seam, so generic `VIEW` output to live clients and hot reconnect buffers cannot drift on the LOOK-render fallback rule.
- durable and Redis retention now charge every scope-bound structured transcript envelope rather than only rendered protocol text, so replay metadata and structured payloads cannot evade configured byte bounds.
- first-party structured command-result envelopes now also retain canonical `commandId` alongside coarse `commandType`, so authored commands do not collapse to the generic `AUTHORED` bucket when projected to richer clients.
- focused first-party WebSocket integration proof now asserts that canonical `commandId` survives on both accepted and rejected `LOGIN` / `PLAY` structured envelopes, so the richer-client contract is covered beyond projector unit tests.
- legacy text-only reconnect buffer entries remain readable and replay as transcript chunks.
- incomplete reconnect-buffer metadata now fails closed back to transcript-chunk replay instead of emitting half-structured first-party transcript envelopes, so the hot replay boundary teaches one complete structured-entry contract.
- Game Session persists bounded reconnect transcript entries as ordered `resume_transcript_entry` rows, preserving structured replay metadata plus derived compatibility text; Redis is a best-effort hot cache rather than the retained source of truth.
- durable entries enforce the effective reconnect retention bounds and optional expiry, and a bounded background sweep removes expired entries even when the player never reconnects.
- the narrower storage-model follow-through in `02.13.10.1` is complete in both implementation and design: reconnect replay and durable recent output share one structured transcript-entry model, while rendered text remains derivative compatibility data.

So this slice is no longer about inventing structured output from scratch or leaving durable reconnect state as a future placeholder. Later complete archive/export work is deliberately separate from the bounded recent-output contract.

##### source-02-13-10-task-list-structured-transcript-and-replay-end-state-vertical-slice-1-72: Why This Slice Exists

FireMUD already has strong structured output and presentation-policy slices, but the long-term transcript/replay end state is still only partially implied. The platform needs one explicit design target for:

- replay buffers;
- reconnect transcript restoration;
- prompt separation;
- color and presentation policy;
- later richer clients versus plain text clients.

##### source-02-13-10-task-list-structured-transcript-and-replay-end-state-vertical-slice-1-72: Scope

- Define the canonical boundary between structured output, transcript persistence, replay state, and final rendering.
- Define what is eligible for replay and what is not.
- Define how prompts, status lines, room views, combat text, social text, and system/admin messages coexist in one transcript model.
- Define how presentation policies such as color, brief/full, and future client-specific rendering consume the same structured transcript source.

##### source-02-13-10-task-list-structured-transcript-and-replay-end-state-vertical-slice-1-72: Out of Scope

- Rebuilding existing output slices from scratch.
- Full MCP/GUI rendering design.
- Long-term archival/log-retention policy.

##### source-02-13-10-task-list-structured-transcript-and-replay-end-state-vertical-slice-1-72: Known Design Considerations

- Player-visible output should stay structured first, rendered second.
- Reconnect replay should only apply to recovery paths, not deliberate logout.
- Prompts should remain distinct from the underlying transcript content.
- Later richer clients should consume the same canonical output envelope rather than a separate gameplay-output contract.
- The current durable reconnect store keeps structured replay metadata beside derived rendered compatibility text, so later work must preserve that canonical source rather than reverting to text-only replay.

##### source-02-13-10-task-list-structured-transcript-and-replay-end-state-vertical-slice-1-72: Locked Direction

- Canonical transcript persistence should use structured transcript entries rather than rendered text chunks as source truth.
- Rendered plain text remains a derived cache/export/compatibility surface rather than the authoritative transcript form.
- Reconnect short-window buffers and longer transcript history should share one conceptual transcript-entry model even if they use different storage backends.
- Rendering policy such as color and style belongs at projection time rather than being baked into canonical transcript storage.
- The storage split is now live: durable transcript entries are the retained source of truth, while Redis is an optional hot reconnect cache; both use the same conceptual transcript-entry model.
<!-- /migration-source -->

### source-02-13-10-1-task-list-structured-transcript-persistence-and-replay-storage-vertical-slice-1-72

#### Structured Transcript Persistence and Replay Storage Vertical Slice - Transcript persistence and replay storage (source lines 1-72)

##### Preserved Source Text: source-02-13-10-1-task-list-structured-transcript-persistence-and-replay-storage-vertical-slice-1-72

<!-- migration-source path="design/project-management/vertical-slices/02.13.10.1-task-list-structured-transcript-persistence-and-replay-storage-vertical-slice.md" lines="1-72" sha256="7d1b4a51a3ac68ba5028b4fba5a056544dd934167a53fd15e960ba793352c97b" heading-offset="3" -->
#### source-02-13-10-1-task-list-structured-transcript-persistence-and-replay-storage-vertical-slice-1-72: Structured Transcript Persistence and Replay Storage Vertical Slice

##### source-02-13-10-1-task-list-structured-transcript-persistence-and-replay-storage-vertical-slice-1-72: Goal and Status

Goal: narrow the remaining transcript/replay design into one concrete persistence and replay-storage model so reconnect, transcript history, and richer client playback do not remain split between structured live output and text-oriented replay buffers. Status: complete at the current design boundary.

##### source-02-13-10-1-task-list-structured-transcript-persistence-and-replay-storage-vertical-slice-1-72: Checklist

- [x] Define target-state behavior and scope.
- [x] Land the canonical storage/replay model in the architecture and slice docs.
- [x] Verify the surviving docs teach one concrete transcript-entry and retention-class model.

##### source-02-13-10-1-task-list-structured-transcript-persistence-and-replay-storage-vertical-slice-1-72: Current Snapshot (2026-06-29)

- This slice is complete at the design boundary.
- The canonical storage model is now explicit in the architecture docs and live in Game Session: reconnect replay and durable recent output share one structured transcript-entry model, rendered text remains derived compatibility data, and Redis is only a best-effort cache.
- Retention is bounded by effective soft/hard byte ceilings, message/line floors, and optional inactivity expiry rather than a transient-only replay mode.

##### source-02-13-10-1-task-list-structured-transcript-persistence-and-replay-storage-vertical-slice-1-72: Why This Slice Exists

The broader transcript slice is now clear at a high level:

- structured live output already exists;
- reconnect replay now persists a structured-output envelope beside rendered protocol text for new buffer entries and keeps old text-only chunks backward-readable;
- the final persistence/replay model now uses durable recent output, while complete archive/export remains separate future work.

This follow-up exists so the exact storage decision is discussed before transcript end-state implementation resumes.

##### source-02-13-10-1-task-list-structured-transcript-persistence-and-replay-storage-vertical-slice-1-72: Scope

- Define the canonical persisted transcript unit.
- Define whether reconnect replay stores structured output, rendered text, or both.
- Define the relationship between raw spoken text, normalized spoken text, and rendered transcript text.
- Define how richer clients consume replay without inventing a separate transcript contract.

##### source-02-13-10-1-task-list-structured-transcript-persistence-and-replay-storage-vertical-slice-1-72: Out of Scope

- Full long-term archival retention policy.
- Search, moderation analytics, or mail history.

##### source-02-13-10-1-task-list-structured-transcript-persistence-and-replay-storage-vertical-slice-1-72: Locked Direction

- Canonical persistence should use structured transcript entries rather than rendered text chunks as the source of truth.
- The canonical persisted unit should be a transcript entry carrying:
  - session/gameplay identity;
  - ordering token;
  - output kind;
  - structured payload;
  - timestamp metadata.
- Rendered plain text remains a cache/export/compatibility surface, not the only authoritative replay form.
- Reconnect short-window buffers and longer transcript history should share one conceptual transcript-entry model even if they use different storage backends at first.
- The storage split is:
  - durable bounded recent output in Game Session as the retained source of truth;
  - Redis or equivalent runtime storage as an optional hot reconnect cache.
- The first retention model should be explicit and bounded rather than defaulting to permanent universal history.
- Richer client replay should consume the same structured transcript model rather than inventing a separate browser/client-only transcript contract.
- Speech-related transcript storage should preserve canonical structured content and leave room for raw-versus-normalized speech fields where needed, but rendered text should remain derivative.
- Color/style/rendering policy belongs at render/replay projection time, not in the canonical stored transcript entry.

Every replayable entry is retained through the durable transcript contract. Effective tenant/game settings define soft/hard byte ceilings with message/line floors plus an optional maximum age; the hot reconnect buffer is only the most recent delivery window over that durable history. The later player-history archive/export capability remains separate from ordinary recent-output retention and must not turn it into an unbounded transcript store.

##### source-02-13-10-1-task-list-structured-transcript-persistence-and-replay-storage-vertical-slice-1-72: Completion Notes

- 2026-06-29: Completed at the design boundary by promoting the transcript-entry storage model and retention policy into the architecture docs instead of leaving them implied only in this slice note.
- 2026-07-12: The durable-retention policy supersedes the earlier `RECONNECT_ONLY` / `SHORT_HISTORY` / `EXTENDED_HISTORY` labels: structured transcript entries are source truth, rendered text is derivative, hot reconnect replay is a delivery window over durable recent output, and the effective tenant/game policy bounds retention by size plus optional age.
- 2026-07-12: Game Session persists the bounded reconnect transcript as ordered `resume_transcript_entry` rows through `DurableScreenBufferService`. The durable rows preserve structured replay metadata plus compatibility text; Redis is a best-effort hot cache. `LOGOUT` retains that bounded context for the next `LOGIN` + `PLAY`. Every append refreshes the scope-wide inactivity expiry recorded on its retained rows, and a bounded background sweep removes expired rows even if the player never reconnects. `firemud.reconnection.buffer.ttl-ms: 0` means no inactivity expiry, with the existing byte/message/line bounds still enforced.

##### source-02-13-10-1-task-list-structured-transcript-persistence-and-replay-storage-vertical-slice-1-72: Why This Direction

- It avoids collapsing back into text-only replay and transport-specific transcript truth.
- It keeps Telnet, generic WebSocket, and later first-party clients on one transcript contract.
- It does not require a full event-sourced simulation model just to replay player-visible output.
<!-- /migration-source -->

### source-02-13-2-task-list-player-output-envelope-rollout-vertical-slice-1-54

#### Player Output Envelope Rollout Vertical Slice Task List - Player output envelope (source lines 1-54)

##### Preserved Source Text: source-02-13-2-task-list-player-output-envelope-rollout-vertical-slice-1-54

<!-- migration-source path="design/project-management/vertical-slices/02.13.2-task-list-player-output-envelope-rollout-vertical-slice.md" lines="1-54" sha256="e1eed2e6d4722918ef735a7628699b43635c1f19cc519835faec13df79410c90" heading-offset="3" -->
#### source-02-13-2-task-list-player-output-envelope-rollout-vertical-slice-1-54: Player Output Envelope Rollout Vertical Slice Task List

##### source-02-13-2-task-list-player-output-envelope-rollout-vertical-slice-1-54: Goal and Status

Goal: extend the first `PlayerOutput` proof into the canonical path for more gameplay responses so plain strings stop being the default internal output contract. Status: pre-`06` complete.

Implementation notes:

- `LOOK`, communication acknowledgements, prompts, and several interpreter-owned error paths now use `PlayerOutput` rather than only ad hoc strings.
- `LOGIN` immediate success and failure outputs now also use structured `message` and `error` envelopes rather than a response-text-only path.
- `PLAY` and direct communication handlers now return structured output lists to the interpreter rather than raw response strings, so the interpreter is no longer re-wrapping those success paths as ad hoc notices or messages.
- Structured `error` envelope usage is now live for unknown-command, stage-gating, and immediate `LOOK` error-mapping paths.
- Reconnect screen-buffer eligibility already keys off `PlayerOutput` replay policy rather than command-name lists.
- Replay-category regression coverage now exists for bufferable versus non-replay output kinds, including prompts, notices, and errors.
- Movement-triggered room refresh now returns structured view output rather than a pre-rendered protocol block, so the renderer owns the final room-refresh transcript shape on both `LOOK` and successful `MOVE`.
- `PLAY` success paths now travel as normal notice outputs rather than pre-rendered protocol blocks, with the renderer owning the final classic `OK ...` framing for those single-line success bodies.
- `WORLDS` now travels as a typed `view` payload rather than a multiline notice string, while still rendering to the same classic `OK WORLDS` wire format and remaining excluded from reconnect replay.
- The old `LOOK` and `WORLDS` string-helper paths have been removed from the main code. `LookCommandHandler` now owns only structured `PlayerOutput` creation for room views, and cached/replayed `LOOK` protocol framing is renderer-owned rather than hand-built in the command handler.
- The old string-based `view` compatibility path is now gone from the main code as well. `VIEW` outputs are typed room/world payloads, and `LookTextRenderer` is now only a mapper from authoritative room results into structured output rather than a parallel text renderer.
- The communication contract between Game Logic and Game Session no longer carries actor or recipient prose strings. Game Logic now returns metadata-only delivery views, and Game Session renders actor, target, and observer transcript text through the same `PlayerOutput` and message-catalog path used elsewhere.
- First-party WebSocket command responses now project the structured `PlayerOutput` batch directly as JSON envelopes instead of collapsing everything back to plain text at the final edge, while generic WebSocket and Telnet keep the classic text renderer.
- Async recipient communication push now reuses that same first-party projection path, so first-party direct command responses and first-party recipient fanout no longer diverge at the final WebSocket boundary.
- First-party reconnect restore now also uses that same structured boundary for the fresh reconnect `LOOK`, while replayed screen-buffer text is wrapped as an explicit transcript-chunk envelope at the first-party edge instead of falling back to raw text frames.
- Replay storage is still transcript-text-backed, but it no longer appends whole rendered command responses whenever one replayable output appears. The screen buffer now stores replay-eligible rendered outputs as individual entries, which keeps prompt/error/notice exclusion explicit and reduces replay-side command framing leakage.
- The remaining transitional seams are now concentrated around replay/delivery boundaries and compatibility helpers rather than the main built-in command handlers.
- Some narrower seams still store or transmit pre-rendered protocol text, but the main admission, world-browse, room-refresh, and direct communication actor and recipient paths no longer do.

##### source-02-13-2-task-list-player-output-envelope-rollout-vertical-slice-1-54: Scope

- Expand the normalized player-output envelope beyond the first `LOOK` and communication-ack paths.
- Keep the top-level output kinds small:
  - `message`
  - `view`
  - `prompt`
  - `error`
  - `notice`

##### source-02-13-2-task-list-player-output-envelope-rollout-vertical-slice-1-54: Key Tasks

- [x] Review the current `PlayerOutput` model and tighten any fields needed for stable rollout.
- [x] Move more active Game Session output paths off ad hoc strings and onto `PlayerOutput`.
- [x] Introduce real `error` envelope usage instead of relying only on raw protocol strings.
- [x] Audit reconnect screen-buffer eligibility so it keys off output policy rather than ad hoc command lists.
- [x] Remove transitional pre-rendered protocol payload usage where a structured envelope can now carry the same intent cleanly.
- [x] Keep the envelope small; do not explode it into per-feature output subclasses.

##### source-02-13-2-task-list-player-output-envelope-rollout-vertical-slice-1-54: Tests

- [x] Add or extend unit tests for envelope creation and renderer behavior across the first bounded output kinds.
- [x] Add regression tests ensuring transcript replay includes only the intended output categories.

##### source-02-13-2-task-list-player-output-envelope-rollout-vertical-slice-1-54: Notes

- This slice is pre-`06` complete. Future work is about richer replay storage and later smart-client presentation, not about returning to raw-string output as the canonical contract.
<!-- /migration-source -->

### source-02-13-3-task-list-prompt-pipeline-and-structured-status-vertical-slice-1-56

#### Prompt Pipeline and Structured Status Vertical Slice Task List - Prompt pipeline and structured status (source lines 1-56)

##### Preserved Source Text: source-02-13-3-task-list-prompt-pipeline-and-structured-status-vertical-slice-1-56

<!-- migration-source path="design/project-management/vertical-slices/02.13.3-task-list-prompt-pipeline-and-structured-status-vertical-slice.md" lines="1-56" sha256="6f352a7ac76ff54df67dd177584d065669c3a64269c982bb3db4d76a33c4a383" heading-offset="3" -->
#### source-02-13-3-task-list-prompt-pipeline-and-structured-status-vertical-slice-1-56: Prompt Pipeline and Structured Status Vertical Slice Task List

##### source-02-13-3-task-list-prompt-pipeline-and-structured-status-vertical-slice-1-56: Goal and Status

Goal: turn prompt/state handling into a real first-class pipeline with coalescing, structured status delivery, and future support for game-defined and player-configurable prompt composition. Status: pre-`06` complete.

Implementation notes:

- Prompt outputs are now emitted on accepted in-game `PLAY`/`LOOK`/movement/communication flows when prompt rendering is enabled.
- Classic-text rendering now coalesces prompt bursts to one trailing prompt.
- Reconnect restore now emits a fresh prompt after transcript replay and fresh `LOOK`.
- A first narrow per-session prompt-throttling window is now live at the WebSocket/Game Session edge so prompts are suppressed when one was just emitted moments ago.
- Explicit view/boundary commands such as `LOOK` and accepted non-redraw `PLAY` now retain their prompt inside that narrow window.
- Movement-triggered room refreshes may render with or without a trailing prompt depending on the surrounding burst, while explicit `LOOK` remains a prompt opportunity.
- Broader burst-end scheduling and richer prompt batching policy remain future work; FireMUD still explicitly avoids blanket timed batching of all output traffic.
- Prompt payloads now carry a first minimal structured status field list alongside classic prompt text so non-text clients can consume identifiers without scraping transcript strings.
- First-party web command responses now receive those structured prompt payloads directly in the WebSocket output envelope, and fresh reconnect prompt delivery now uses that same structured edge path. Reconnect transcript replay itself is still text-backed storage and is only wrapped as transcript chunks at the first-party edge.
- Richer configurable prompt composition is still future work.

##### source-02-13-3-task-list-prompt-pipeline-and-structured-status-vertical-slice-1-56: Scope

- Prompt coalescing after output bursts
- Tiny per-session burst window for naturally adjacent outputs
- Prompt-throttling so prompts append at most every configured interval while output is already flowing
- Fresh prompt emission after reconnect restore
- Structured prompt/status delivery for first-party web later
- Game-defined and player-configurable prompt field composition hooks

##### source-02-13-3-task-list-prompt-pipeline-and-structured-status-vertical-slice-1-56: Key Tasks

- [x] Implement the first real prompt-coalescing policy rather than treating prompts as only a distinct output class.
- [x] Add the first tiny per-session burst-window policy rather than globally batching all output on a fixed timer.
- [x] Add prompt-throttling so prompt emission is treated as a completion or burst-end opportunity rather than every command path permanently hardcoding `+ prompt`.
- [x] Ensure reconnect behavior remains:
  - transcript buffer;
  - fresh `LOOK`;
  - one fresh prompt.
- [x] Define the first structured prompt/status payload shape for non-text clients.
- [x] Document or prototype how game-defined status fields and player-selected prompt composition will plug into the prompt pipeline later.
- [x] Keep prompt text rendering for classic clients as one renderer over the same structured prompt/status model.

##### source-02-13-3-task-list-prompt-pipeline-and-structured-status-vertical-slice-1-56: Tests

- [x] Add prompt-burst tests that prove one coalesced prompt rather than prompt spam.
- [x] Add focused tests for the first prompt-throttling window.
- [x] Add reconnect tests showing prompt exclusion from transcript replay and fresh prompt regeneration afterward.

##### source-02-13-3-task-list-prompt-pipeline-and-structured-status-vertical-slice-1-56: Notes

- Prompt handling should remain distinct from transcript history.
- This slice should not hardcode one universal prompt format across all games.
- FireMUD should avoid a blanket "flush every N ms" rule for all output traffic; burst coalescing should stay narrow so normal command responsiveness remains immediate.
- The current prompt pipeline should evolve by adding game-defined status fields upstream of `PromptComposer`, then letting player-selected prompt layouts choose and order those fields before `TextPlayerOutputRenderer` turns the resulting payload into classic text. That keeps:
  - gameplay/status authority upstream;
  - prompt layout policy in the prompt pipeline;
  - classic text rendering as one client-specific projection over the same structured prompt payload.
<!-- /migration-source -->

### source-02-13-4-task-list-presentation-policy-brief-color-and-room-refresh-vertical-slice-1-46

#### Presentation Policy: BRIEF, Color, and Room Refresh Vertical Slice Task List - Brief, color, and room refresh presentation (source lines 1-46)

##### Preserved Source Text: source-02-13-4-task-list-presentation-policy-brief-color-and-room-refresh-vertical-slice-1-46

<!-- migration-source path="design/project-management/vertical-slices/02.13.4-task-list-presentation-policy-brief-color-and-room-refresh-vertical-slice.md" lines="1-46" sha256="504f7fd7e68188daffdc7af308cdd0f4a234e535d37aa81e9a6fabe4570250da" heading-offset="3" -->
#### source-02-13-4-task-list-presentation-policy-brief-color-and-room-refresh-vertical-slice-1-46: Presentation Policy: BRIEF, Color, and Room Refresh Vertical Slice Task List

##### source-02-13-4-task-list-presentation-policy-brief-color-and-room-refresh-vertical-slice-1-46: Goal and Status

Goal: make `BRIEF`, color capability, and room-refresh presentation policy real parts of the structured presentation model rather than scattered string-shaping rules. Status: pre-`06` complete.

##### source-02-13-4-task-list-presentation-policy-brief-color-and-room-refresh-vertical-slice-1-46: Implementation Notes

- `game-session-service` now has the first real color-mode branches in `TextPlayerOutputRenderer` for built-in room-view labels, notices, and prompts.
- The renderer now also honors `BriefRenderPolicy.SUPPRESS_IN_BRIEF` for non-view outputs, making `BRIEF` more than a single `LOOK`-specific boolean proof.
- Successful movement now reuses the same structured room-view output path as `LOOK`, so room-refresh presentation policy is no longer blocked on a movement-specific protocol-text bypass.
- Structured room views now carry both an explicit refresh reason (`EXPLICIT_LOOK`, `QUICKLOOK`, `MOVE_REFRESH`, `RECONNECT_REFRESH`) and a bounded brief-rendering hint, so brief-style room-refresh policy no longer depends on renderer inference from command names or refresh reasons alone.
- `QUICKLOOK` is now a real built-in command using the same structured room-view payload as `LOOK`, with presentation policy deciding that it suppresses long-description prose by default.
- Combat-sensitive brief-on-move behavior remains follow-up work once movement/combat state is available as an explicit presentation hint rather than only command context.

##### source-02-13-4-task-list-presentation-policy-brief-color-and-room-refresh-vertical-slice-1-46: Scope

- `BRIEF` and verbose room-view policy
- color mode behavior (`none`, `basic`, `rich`)
- movement-triggered room refresh policy
- combat-sensitive brief-style movement refresh default

##### source-02-13-4-task-list-presentation-policy-brief-color-and-room-refresh-vertical-slice-1-46: Key Tasks

- [x] Expand the current `BRIEF` handling from the initial proof into a deliberate presentation-policy layer.
- [x] Define how structured outputs carry the tags or hints needed for `BRIEF` suppression.
- [x] Implement the first real color-mode branches in the renderer without leaking ANSI/rich formatting into gameplay logic.
- [x] Refine movement-triggered room refresh to use the same room-view model with different presentation policy.
- [x] Add an explicit room-view brief-rendering hint so movement refresh can prefer brief-style output without the renderer hard-coding `MOVE_REFRESH`.
- [x] Explicitly defer the combat-sensitive brief-on-move rule to later work once combat state exists as a canonical presentation hint.

##### source-02-13-4-task-list-presentation-policy-brief-color-and-room-refresh-vertical-slice-1-46: Tests

- [x] Add renderer tests for `BRIEF` versus verbose behavior on room views.
- [x] Add color-mode regression tests for the first bounded built-in outputs.
- [x] Document that combat-sensitive brief-on-move tests belong with the later combat-state-driven policy work rather than blocking this pre-`06` slice.

##### source-02-13-4-task-list-presentation-policy-brief-color-and-room-refresh-vertical-slice-1-46: Notes

- `BRIEF` should remain primarily tag/policy driven, not dual-authored prose everywhere.
- Combat-sensitive brief-on-move remains an explicitly documented future policy target once combat state exists as a canonical presentation hint; that future dependency does not block this pre-`06` slice from being complete.

##### source-02-13-4-task-list-presentation-policy-brief-color-and-room-refresh-vertical-slice-1-46: Validation

- [x] `./gradlew check`
- [x] `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-02-13-5-task-list-localization-foundation-vertical-slice-1-45

#### Localization Foundation Vertical Slice Task List - Player-facing localization foundation (source lines 1-45)

##### Preserved Source Text: source-02-13-5-task-list-localization-foundation-vertical-slice-1-45

<!-- migration-source path="design/project-management/vertical-slices/02.13.5-task-list-localization-foundation-vertical-slice.md" lines="1-45" sha256="98b52cdfbf3408857296fe9c14526f60f89da12708ac7bde22f2e3fd8d22f13f" heading-offset="3" -->
#### source-02-13-5-task-list-localization-foundation-vertical-slice-1-45: Localization Foundation Vertical Slice Task List

##### source-02-13-5-task-list-localization-foundation-vertical-slice-1-45: Goal and Status

Goal: establish the first runtime and content-model foundation for localization without introducing live translation latency on the gameplay hot path. Status: pre-`06` complete. Built-in/platform text in Game Session now has a stable-key plus structured-variable path across the active renderer-owned built-in outputs, including localized login/play/look/move failure rendering, localized login/play notices, localized communication templates already on the `PlayerOutput` path, localized room-view labels, and bounded alternate-locale renderer/integration tests. Locale precedence is now explicit for built-in outputs: persisted session locale when known, then current websocket/bootstrap locale, then `firemud.presentation.default-locale-tag`. A first authored-content variant model now exists as a small locale-tagged source-plus-variants container with exact-locale, language-only, and source-locale fallback rules plus focused unit tests. Authored localized room prose is live on the canonical `LOOK` and movement-refresh path by forwarding a preferred locale through Game Session and Game Logic into World Management room snapshot reads, and room snapshots now also localize adjacent exit target room naming before they reach the renderer. Broader item/world adoption and the AI-assisted offline workflow remain future work.

##### source-02-13-5-task-list-localization-foundation-vertical-slice-1-45: Scope

- Template/key-based localization for built-in/platform text
- Explicit localized variants for authored world/game prose
- Future AI-assisted offline localization workflow as a content-production aid, not a runtime dependency

##### source-02-13-5-task-list-localization-foundation-vertical-slice-1-45: Key Tasks

- [x] Define the first built-in/system text localization strategy using stable keys plus structured variables.
- [x] Define how authored game/world content stores explicit localized variants.
- [x] Keep runtime localization based on stored variants/templates rather than live translation APIs.
- [x] Thread preferred locale through the canonical room-read path so authoritative room snapshots can resolve stored localized prose before they reach the renderer.
- [x] Document the future AI-assisted offline workflow:
  - canonical source language;
  - tone/world/glossary notes;
  - AI-generated draft localized variants;
  - optional creator editing;
  - stored localized runtime output.
- [x] Ensure the output/presentation model leaves room for locale selection in the renderer without moving semantic gameplay decisions into translation.

##### source-02-13-5-task-list-localization-foundation-vertical-slice-1-45: Tests

- [x] Add the first bounded localization tests for one or two built-in/system message templates.
- [x] Add data-model tests or examples for authored localized content variants.

##### source-02-13-5-task-list-localization-foundation-vertical-slice-1-45: Notes

- Template/key localization and explicit localized content variants are complementary, not competing solutions.
- Do not add live external translation on the gameplay hot path.
- The current authored-content variant model is intentionally small:
  - canonical source locale text is required;
  - explicit locale-tagged variants are stored alongside it;
  - runtime resolves exact locale first, then language-only match, then source locale;
  - later world/item/room systems can adopt the same model without requiring a separate live translation layer.
- Built-in runtime localization is now player/session aware, but only for the built-in renderer-owned outputs already on the `PlayerOutput` path.
- Authored room prose now has a first runtime path on `LOOK` and movement refresh, and room snapshots also localize exit target room naming when stored variants exist. Item/world/lore adoption is still future work and should keep using stored localized variants rather than live translation.
- Remaining work inside `02.13.5` is narrow:
  - extend the same stored-variant adoption pattern beyond room snapshots into additional authored world/item/lore surfaces as those surfaces become canonical;
  - keep new built-in outputs on the stable-key plus structured-variable path instead of introducing fresh raw-English renderer text.
<!-- /migration-source -->

### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432

#### Command Interpretation and Alias Matching Vertical Slice - Command interpretation and alias matching (source lines 1-432)

##### Preserved Source Text: source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432

<!-- migration-source path="design/project-management/vertical-slices/02.13.6-task-list-command-interpretation-and-alias-matching-vertical-slice.md" lines="1-432" sha256="23da08facdda389cc89ca5c3748c06dfc235e694f0dd5dd67f32f34473648650" heading-offset="3" -->
#### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Command Interpretation and Alias Matching Vertical Slice

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Goal and Status

Define one canonical command-interpretation model for FireMUD so raw player input becomes a deterministic structured action request with clear stage gating, alias handling, ambiguity rules, and future game-specific extension points. Status: partially implemented.

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Implementation Notes

- The active built-in command surface parses through `TextCommandParser` and one registry-owned command definition for each command, rather than handler-local token matching.
- Built-in aliases, including movement shorthand and `GO <direction>`, resolve before dispatch into canonical command ids and typed payloads. Invalid movement shape remains malformed and fails before durable enqueue.
- `TextCommandInterpreter` applies registry-owned stage and prompt policy, then dispatches by `TextCommandDispatchGroup`; the built-in rollout is complete under `02.13.8`.
- Game-authored definitions use the same registry and dispatch contract from the player's admitted release artifact. Built-ins retain precedence, and no process-local configuration fallback is permitted.
- Stable visible item references are allocated and accepted across gameplay inventory, container, and equipment paths. NPC targeting, action-family ambiguity policy, localized aliases, and a discoverable active-registry contract remain future work. Do not mark this slice complete until those target-state decisions have a concrete implementation boundary.

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Checklist

- [x] Define target-state behavior and scope.
- [ ] Implement the slice end-to-end.
- [ ] Verify and close any follow-ups.

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Why This Slice Exists

The structured input/output model establishes that FireMUD must not treat raw transport text as the authoritative internal contract. This slice records the target state and the remaining parser/target-resolution follow-through so command growth does not drift back into handler-local string checks.

This gap already matters because:

- shorthand aliases such as `s` for `south` are expected MUD behavior;
- stage-sensitive commands such as `LOGIN`, `PLAY`, `LOOK`, and later `HELP` need deterministic gating;
- future creator-authored actions will need a safe place to participate in command matching without silently shadowing built-in commands;
- tenant- and game-specific behavior must remain explicit and authoritative rather than leaking through ad hoc parser conditionals;
- the system needs one bounded answer for where token matching, argument parsing, alias expansion, and ambiguity handling live.

The implemented registry-owned interpretation boundary prevents drift into handler-local string checks, overlapping regexes, or transport-specific command behavior; remaining work extends that same boundary for the explicitly deferred ambiguity and localization cases.

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Scope

- Define the canonical stages for command interpretation:
  - pre-login / unauthenticated;
  - authenticated lobby / account-owned but not actively playing;
  - in-world gameplay.
- Define the interpretation pipeline from raw input to canonical command id plus typed arguments.
- Define how built-in aliases work for common commands such as movement.
- Define how action-specific or creator-authored commands are linked into the same interpretation system later.
- Define precedence and ambiguity rules across:
  - exact command names;
  - aliases;
  - shaped command forms such as `go north`;
  - future game-defined actions.
- Define how generic noun references and explicit instance references coexist for items and NPCs.
- Define which parts of explicit target-reference syntax are parser concerns versus presentation and tab-completion concerns.
- Define the ownership model for command definitions at the platform, tenant, game, and action levels.
- Define what parts of command interpretation should remain deterministic/token-based and where bounded pattern matching is acceptable.

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Out of Scope

- Replacing the current deterministic parser with a general-purpose grammar engine.
- A fully creator-editable grammar authoring UI.
- Natural-language understanding or fuzzy intent matching.
- Locale-specific parsing beyond recording where localization-sensitive behavior will eventually belong.

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Target-State Interpretation Model

- FireMUD should use a staged deterministic interpreter, not an unbounded regex-first command engine.
- Raw input should first be normalized into tokens plus preserved raw text.
- The interpreter should resolve one canonical command id and one typed argument payload before execution.
- Alias handling should happen during interpretation, not inside action handlers.
- Built-in commands should remain authoritative and easy to reason about.
- Future game-defined commands should plug into explicit registries or definitions rather than arbitrary handler-local pattern checks.
- Built-in command dispatch should move toward a registry-owned model rather than continuing to grow as a hardcoded branch tree inside `TextCommandInterpreter`.
- Command definitions should become the shared metadata seam for:
  - stage eligibility;
  - prompt policy;
  - action classification;
  - ownership/source metadata.
- That metadata seam should be expanded before authored commands arrive, so built-ins and authored commands do not drift into separate command-definition models.

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Command Stages

###### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: 1. Pre-Login

Commands such as:

- `WORLDS`
- `LOGIN <email> <password>`
- `HELP`

must be recognized without any gameplay session context.

###### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: 2. Lobby / Session-Owned but Not Playing

Commands such as:

- `PLAY <world> [character]`
- `SESSION`
- future account/help/lobby commands

must be recognized only when the player has authenticated but is not yet actively playing in-world.

###### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: 3. In-World Gameplay

Commands such as:

- `LOOK`
- movement commands
- `SAY`
- `WHISPER`
- `TELL`
- future game-specific actions

must be interpreted against gameplay context and the current tenant/game/action model.

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Canonical Interpretation Pipeline

The target-state pipeline should be:

1. Preserve raw input.
2. Normalize transport-level whitespace and trivial casing rules where appropriate.
3. Tokenize into a deterministic command line model.
4. Select the active command stage.
5. Resolve the leading command token or shaped command form into one canonical command id.
6. Expand aliases into canonical verbs or typed argument forms.
7. Parse typed arguments.
8. Apply stage gating and ownership checks.
9. Produce one structured interpretation result for execution.

Handlers should receive canonical command ids and typed arguments, not perform fresh alias or pattern matching.

The built-in registry and dispatcher rollout is complete under `02.13.8`. Further work in this slice should preserve that registry-owned parsing and dispatch boundary rather than reopening interpreter-local branching.

The first bounded parser follow-through now also keeps built-in movement fail-closed: `MOVE` accepts one canonical direction only, including `GO <direction>` and one-token directional aliases. Invalid directions or trailing operands remain malformed parsed input and the movement dispatcher rejects them before durable command enqueue.

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Instance Reference Model

FireMUD should distinguish between:

- a generic reference to a type or display noun, such as `satchel` or `goblin`;
- a specific reference to one concrete instance, such as one particular satchel or one particular goblin.

The interpreter should therefore normalize target references into a structured model with:

- noun or display key;
- optional explicit instance suffix or instance token;
- enough metadata to tell whether the player requested "first matching noun" versus "this exact instance".

This matters for:

- duplicate room items;
- duplicate containers carried by one character;
- duplicate NPCs in one room;
- tab completion;
- later combat target persistence and target switching.

The canonical long-term item model should assume that physical items are true item instances underneath, even when views later choose to render compatible identical items as grouped stacks for convenience.

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Generic Versus Specific References

The design should support both:

- generic references:
  - `get satchel`
  - `attack goblin`
- specific references:
  - `get satchel12345`
  - `get satchel-12345`
  - `attack goblin8821`

Generic references should resolve through action-specific selection rules.

Specific references should bypass ambiguity and resolve the exact intended instance whenever the player provides a valid explicit instance form.

For item targets, generic resolution should follow one stable ordering derived from the relevant management/list view for that scope so the player can predict what `get sword` or `wear ring` will hit without hidden randomness.

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Explicit Instance Syntax

The intended syntax split is:

- one canonical displayed/completed explicit form;
- optional additional accepted input aliases;
- no generic spaced instance syntax as the default platform rule.

Recommended platform default:

- canonical displayed form:
  - `satchel12345`
- optional readability-focused accepted alias:
  - `satchel-12345`
- not a standard universal syntax:
  - `satchel 12345`

Visible explicit references should be stable for the lifetime of the underlying item or entity instance. They should not be reassigned based on room membership, current visibility set, or recent ordering changes.

The spaced form should not be the platform-standard explicit instance syntax because it becomes grammatically ambiguous once commands have multiple operands. For example:

- `attack goblin 12345`
- `put torch 12345 in satchel`
- `give potion 12345 to goblin`

If a future command explicitly defines a spaced numeric operand, that command can own it locally, but the general command-reference system should not treat `noun number` as the default explicit-instance grammar.

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Parser Versus Presentation Policy

Parser acceptance and displayed reference format should be treated as separate concerns, but the platform default should strongly optimize for the text-client tab-completion path.

The parser may accept more than one equivalent explicit reference form, but the game should teach players one canonical displayed/completed form.

That means:

- input acceptance may be broader;
- output, help, and tab completion should emit exactly one canonical form;
- players should learn the reference style from what the game shows them, not from a growing list of parser-only aliases.

Recommended default:

- display and tab-complete `satchel12345`;
- accept `satchel12345` as the primary explicit instance form;
- optionally accept `satchel-12345` as a readability alias for clients or UIs where tab completion is not the dominant interaction pattern.

Visible explicit references do not need to appear in every ordinary prose transcript. The better default is:

- natural names in ordinary room prose, descriptions, and similar narrative views;
- explicit compact refs in inventory-style and management/reference listings where the player is expected to choose or manipulate concrete instances directly.

The numeric suffix should be a stable monotonic sequence allocated for that concrete instance within its type family, not a room-local or visibility-local slot number.

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Player Preference Versus Shared Game State

Explicit instance-reference formatting could eventually become a player-facing presentation preference, but the strong default should remain the compact tab-complete-friendly form until there is a concrete client-driven reason to broaden it.

The shared authoritative state should remain:

- concrete internal instance identity;
- canonical interpreter-owned target model.

Possible alternate rendered forms for the same target could include:

- `satchel12345`
- `satchel-12345`

That preference should not affect:

- world state;
- which instance is actually targeted;
- other players' views;
- persistence;
- combat or inventory logic.

The suggested rollout is:

1. implement one platform-default displayed form first;
2. optionally accept equivalent parser aliases where cheap and safe;
3. only later consider per-player rendered-format preference if richer clients or UI affordances make that worthwhile.

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Visible Instance Id Allocation

The platform should use one stable player-visible reference scheme for concrete items and entities:

- visible form:
  - `typeSlug + stableSequence`
- examples:
  - `satchel12`
  - `goblin3`

The recommended allocation rule is:

- allocate a monotonic sequence per type slug within the relevant game/world namespace;
- assign it once when the concrete instance is created;
- keep it fixed for the lifetime of that instance;
- do not renumber it when the instance moves rooms, changes owners, is equipped, dropped, picked up, or otherwise changes location.

This means FireMUD should not use:

- room-local numbering such as "the first goblin in this room is goblin1 right now";
- visibility-derived numbering that changes as entities appear or disappear;
- random GUID-like visible tokens.

The internal authoritative id may still be opaque and unrelated. The visible instance ref exists for player-facing targeting, help, transcripts, and tab completion.

For items, this visible instance model should sit on top of full item-instance identity rather than a hybrid "some items are real instances and some are only stacks" ontology. Stack behavior should be treated as presentation and command-resolution policy layered over item instances, not as a separate storage truth for simple items.

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Resolution Rules

Resolution should be action-family-specific but deterministic.

Recommended general order:

1. if the player supplied a specific explicit instance reference, resolve that exact instance;
2. otherwise resolve by the action's scoped search rules;
3. if multiple matches remain, apply deterministic tie-breaking or return explicit ambiguity.

Deterministic defaults are preferred over random choice because they are:

- easier to learn;
- easier to test;
- easier to debug;
- less surprising in text-first gameplay.

For item-like targets, the default deterministic tie-break should come from one static ordering for the relevant holder or location that remains stable until the underlying state changes. That ordering should also drive the corresponding inventory or management listing so the player can inspect the list and understand which generic target would win.

Examples:

- `get item from pouch`
  - resolve `pouch` among accessible containers;
  - if several pouches match, exact explicit instance wins, otherwise use deterministic resolution or explicit ambiguity.
- `attack goblin`
  - resolve among visible hostile room entities;
  - prefer explicit instance ref, then current focus or recent interaction if that becomes part of the combat model, then stable room ordering.

Random target selection should not be the platform default.

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Matching Strategy

The preferred matching strategy is:

- exact token lookup first;
- bounded alias lookup second;
- bounded shaped-command matchers third;
- explicit ambiguity failure rather than hidden “best guess” interpretation.

This means FireMUD should not treat regex as the primary command engine.

Bounded pattern matching is still allowed where it is genuinely useful, for example:

- direction shorthand normalization;
- shaped forms such as `go north`;
- argument validation for structured forms.

But those patterns should live inside explicit interpreter-owned matchers, not inside arbitrary handlers.

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Aliases and Movement Shorthand

The initial target-state examples should include:

- `n` -> `MOVE direction=NORTH`
- `s` -> `MOVE direction=SOUTH`
- `e` -> `MOVE direction=EAST`
- `w` -> `MOVE direction=WEST`
- `u` -> `MOVE direction=UP`
- `d` -> `MOVE direction=DOWN`
- `north` -> `MOVE direction=NORTH`
- `south` -> `MOVE direction=SOUTH`
- `go north` -> `MOVE direction=NORTH`

The important invariant is that all of these normalize into the same canonical action request before execution.

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Ownership and Definition Layers

Command interpretation must remain authoritative across ownership boundaries.

The intended ownership layers are:

- platform-owned built-in command definitions;
- tenant- or game-scoped command extensions where explicitly allowed;
- action-linked or system-linked command aliases that point at canonical authored actions.

The platform should define which layers are allowed to introduce:

- new command names;
- aliases;
- topic/help references;
- shaped command forms.

The design should assume that not every command is globally available:

- some actions will only exist for one tenant or one game instance;
- some aliases may be action-specific;
- some command availability may depend on owned capabilities, current stage, or actor state.

That means the interpreter eventually needs a clear way to combine:

- global built-ins;
- tenant/game-scoped registries;
- actor/current-context visibility;
- explicit precedence rules.

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Precedence Rules

The target-state precedence should be explicit:

1. exact built-in command for the current stage;
2. exact allowed game-defined command for the current stage;
3. exact alias resolution;
4. bounded shaped-command matcher;
5. explicit unknown-command result.

If multiple candidates remain after interpretation:

- do not guess;
- return an ambiguity result with enough metadata for a clear player-facing error and future diagnostics.

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Structured Interpretation Result

The structured interpretation result should preserve:

- raw input text;
- selected stage;
- canonical command id;
- typed normalized arguments;
- whether the player supplied a generic reference or an explicit instance reference;
- any normalized instance-reference metadata needed by execution and diagnostics;
- whether alias expansion happened;
- whether matching used a shaped-command rule;
- enough metadata for later help, diagnostics, telemetry, and transcript reasoning.

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Testing Expectations

The eventual implementation should prove:

- shorthand movement aliases normalize to one canonical movement action;
- stage-invalid commands fail clearly and consistently;
- aliases do not bypass stage or ownership checks;
- game-defined commands cannot silently shadow built-ins unless explicitly allowed by design;
- ambiguity is explicit and deterministic;
- explicit instance references resolve the exact intended item or NPC when duplicates exist;
- generic references still behave deterministically when duplicates exist;
- visible instance references remain stable for an instance lifetime even as the instance moves between rooms or holders;
- Telnet, generic WebSocket, and first-party web all produce the same canonical interpretation result for the same raw input.

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Open Design Questions

- Whether creator-authored commands are best represented as action-linked aliases, command-definition records, or a richer grammar registry.
- Whether built-in and game-defined aliases share one namespace or layered namespaces.
- Which parts of command interpretation should eventually become tenant/game-editable versus permanently platform-owned.
- How localization should affect canonical command names versus localized aliases.
- Whether future smart clients should be able to discover the active command registry and alias metadata directly.

##### source-02-13-6-task-list-command-interpretation-and-alias-matching-vertical-slice-1-432: Follow-On

- Runtime implementation of the staged interpreter
- Movement shorthand rollout
- `HELP` integration with command discovery metadata
- Future creator-authored command/action linking
- Future localized alias support
<!-- /migration-source -->

### source-02-13-7-task-list-action-classification-and-activity-semantics-vertical-slice-1-129

#### Action Classification and Activity Semantics Vertical Slice - Command activity classification (source lines 1-129)

##### Preserved Source Text: source-02-13-7-task-list-action-classification-and-activity-semantics-vertical-slice-1-129

<!-- migration-source path="design/project-management/vertical-slices/02.13.7-task-list-action-classification-and-activity-semantics-vertical-slice.md" lines="1-129" sha256="d17f71f4a164f7d107e6d025b1bb973f973c6e3447d55fe769ba6f0bf4400f06" heading-offset="3" -->
#### source-02-13-7-task-list-action-classification-and-activity-semantics-vertical-slice-1-129: Action Classification and Activity Semantics Vertical Slice

##### source-02-13-7-task-list-action-classification-and-activity-semantics-vertical-slice-1-129: Goal and Status

Goal: define one canonical action-classification model so FireMUD can distinguish in-world gameplay actions from social, meta, admin, and system activity, and use that classification consistently for AFK rules, scripting hooks, triggers, analytics, and future gameplay policy. Status: partially implemented.

##### source-02-13-7-task-list-action-classification-and-activity-semantics-vertical-slice-1-129: Checklist

- [x] Define target-state behavior and scope.
- [ ] Implement the slice end-to-end.
- [ ] Verify and close any follow-ups.

This slice is a follow-up to the command-interpretation and presence/activity work. FireMUD should not guess whether something is a "real gameplay action" by checking command names in random handlers.

##### source-02-13-7-task-list-action-classification-and-activity-semantics-vertical-slice-1-129: Why This Slice Exists

Several upcoming systems need the same distinction:

- `WHO` / AFK should know whether a command counts as meaningful gameplay activity;
- games may want explicit AFK to remain visible while the player reads boards, mail, or other out-of-world interfaces;
- scripts and triggers will need clean hooks for "movement action", "combat action", "social action", and similar cases;
- future analytics, moderation, protection systems, and automation should not depend on ad hoc string matching of command names.

If FireMUD does not define one action-classification layer, each subsystem will invent its own notion of "real action", and the result will drift quickly.

##### source-02-13-7-task-list-action-classification-and-activity-semantics-vertical-slice-1-129: Scope

- Define one canonical action-classification model that sits above raw command text and below gameplay/presentation policy.
- Define a required primary action category for built-in and future authored actions.
- Define optional tags or facets that can refine action behavior without replacing the primary category.
- Define how command interpretation and later authored actions attach action classification to the structured action request.
- Define how the activity/presence engine consumes that classification.

##### source-02-13-7-task-list-action-classification-and-activity-semantics-vertical-slice-1-129: Out of Scope

- Full gameplay scripting implementation.
- Full authored trigger UI.
- Detailed combat or AI rule execution.

##### source-02-13-7-task-list-action-classification-and-activity-semantics-vertical-slice-1-129: Target State

- Every player-visible action has one primary category.
- Optional tags can refine the action for later scripting and policy hooks.
- AFK/activity logic keys off action classification rather than raw command names.
- Future creator-authored actions can declare their category and tags explicitly.
- Built-in command definitions should carry this classification metadata before authored commands arrive, so classification becomes part of one shared command-definition model rather than a second later metadata path.

Current branch state:

- built-in command definitions now carry a bounded primary category in the game-session command registry;
- built-in command definitions now also carry bounded optional action tags, so movement, communication, world-browse, inventory, session, and UI facets are explicit metadata rather than future free-form strings;
- the first typed authored-action model can now also declare bounded optional tags alongside its required primary category through the same registry seam;
- built-in command definitions also carry source metadata so authored commands can later reuse the same command-definition seam;
- the gameplay presence substrate now records last accepted command activity for any accepted command and last meaningful activity only for commands whose current built-in category is `GAMEPLAY`;
- websocket command handling updates those timestamps only after accepted interpretation, so the activity seam follows command-definition metadata instead of ad hoc command-name checks;
- the live Game Session `onCommand` scripting payload now also carries bounded `actionCategory` plus `actionTags[]` enrichment sourced from the same command-definition registry, so later scripting consumers no longer have to reverse-engineer category/tag truth from raw command names alone;
- the first real scripting consumers of that classification metadata are now live through `onCommand` binding scopes for `ACTION_CATEGORY` and `ACTION_TAG`, so automation handlers can resolve from canonical category/tag truth instead of only command-specific alias targeting;
- first-party structured WebSocket command-result envelopes now also carry canonical `actionCategory` plus `actionTags[]`, so richer clients do not have to reconstruct command semantics from coarse `commandType`/`commandId` pairs alone, and focused first-party proof now covers session, world-browse, social-presence, built-in communication, and authored communication command-result metadata instead of only one narrow command path;
- classic text command rendering now also consumes canonical communication tags for inline prose formatting, including fallback renderer entrypaths, so authored or future communication-tagged commands no longer depend on hardcoded `SAY` / `WHISPER` / `TELL` name checks at the presentation seam;
- classic text response labeling now also consumes canonical `MOVEMENT` tags for `OK LOOK` remapping on view-returning movement commands, so authored or future movement-tagged commands no longer depend on a presentation-local `MOVE` type special case to preserve the movement-to-look envelope contract;
- classic text movement labeling now relies exclusively on that canonical `MOVEMENT` tag; a command merely using the legacy `MOVE` enum type cannot acquire movement presentation behavior without registered metadata, while an explicit `MOVE_REFRESH` view event remains authoritative renderer output;
- websocket prompt-burst policy now also keys off canonical `UI` action tags instead of only hardcoded `LOOK` / `QUICKLOOK` names, so repeated browse, presence, or other UI-tagged command responses do not rely on transport-local command-name knowledge to preserve prompts through the coalesce window;
- WebSocket prompt forcing for session-to-gameplay entry now also consumes the command registry's projected dispatch group plus prompt policy instead of a local `PLAY` type check, so commands cannot acquire that transport behavior from an enum value alone;
- durable gameplay execution now also consumes canonical command metadata for command-family routing instead of keeping a second local hardcoded communication/activity family switch: `DefaultDurableGameplayCommandExecutionService` resolves dispatch-group plus action-tag truth from the command registry so built-in aliases like `BRB` and `GUARD` follow the same replay-backed AFK/action-state execution policy as their canonical command ids;
- activity command dispatch no longer keeps its own hardcoded `AFK` / `BLOCK` allowlist before enqueue: once a command has already been resolved into the canonical `ACTIVITY` dispatch group, the handler now trusts that shared classification seam so future authored activity commands can reuse the same enqueue path without another local type gate;
- classic text success envelopes now also use canonical command-id labels even when an accepted command returns no non-prompt body, so authored commands no longer degrade to `OK AUTHORED` just because they completed without a view/message payload on the renderer path;
- direct `onCommand` script-event publications now also preserve the original raw command text through one shared helper, so built-in and authored alias usage continues through the scripting seam instead of disappearing on non-durable command paths before canonical alias/category resolution runs;
- accepted-command history eligibility is now explicit command-definition metadata: platform and authored definitions declare whether an accepted command is recordable, Game Design validates it on published authored declarations, and unresolved extension definitions fail closed rather than being retained accidentally;
- richer policy consumers beyond those scripting, richer-client envelope, classic-rendering, and prompt-burst policy seams are still future work.

##### source-02-13-7-task-list-action-classification-and-activity-semantics-vertical-slice-1-129: Recommended Model

Primary category should be required and bounded. Suggested starting set:

- `GAMEPLAY`
- `SOCIAL`
- `META`
- `ADMIN`
- `SYSTEM`

Optional tags can add finer-grained semantics, for example:

- `MOVEMENT`
- `COMBAT`
- `COMMUNICATION`
- `INVENTORY`
- `AUTHORING`
- `UI`
- `ASYNC`

The primary category should answer questions like:

- does this count as meaningful gameplay activity?
- should this clear auto-AFK?
- can this trigger in-world systems?

Tags should answer questions like:

- is this movement?
- is this combat?
- is this a communication action?

##### source-02-13-7-task-list-action-classification-and-activity-semantics-vertical-slice-1-129: First Policy Consumers

- Activity engine:
  - track last command timestamp for any accepted player command;
  - track last meaningful gameplay-action timestamp only for actions whose category/policy says they count.
- `WHO` / AFK:
  - avoid clearing AFK just because the player used a meta or UI-like command.
- Future scripting:
  - allow scripts to subscribe to categories or tags instead of hardcoding command-name checks.

##### source-02-13-7-task-list-action-classification-and-activity-semantics-vertical-slice-1-129: Design Rules

- Do not rely on free-form tags alone.
- Do not hardcode "meaningful action" by raw command name in each subsystem.
- Use one required primary category plus optional tags.
- Keep built-in commands explicitly classified.
- Make future authored actions declare their classification as data rather than inheriting accidental defaults.

##### source-02-13-7-task-list-action-classification-and-activity-semantics-vertical-slice-1-129: Follow-On

- Attach primary category and optional tags to built-in command definitions in the command registry rollout before authored-command registration begins.
- [x] Hook the activity/presence engine to action classification.
- [x] Define which built-in commands count as meaningful gameplay actions in the first pass.
- Current first-pass rule: only `GAMEPLAY` category commands update the meaningful-activity timestamp; `SOCIAL`, `META`, `ADMIN`, and `SYSTEM` commands still update last-command activity without clearing future AFK/idle consumers.
- Auto-AFK now derives inactivity exclusively from meaningful gameplay activity, falling back to connection time when none exists; accepted non-gameplay commands remain observable in command activity without postponing idle state.
- [x] Extend creator-authored actions to declare their category and tags.
- Remaining: extend category/tag truth into broader policy consumers beyond the first scripting, richer-client envelope, and classic text-rendering seams instead of leaving that metadata usable only at ingress binding resolution.
<!-- /migration-source -->

### source-02-13-8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice-1-160

#### Built-In Command Registry and Dispatch Rollout Vertical Slice - Built-in command registry and dispatch (source lines 1-160)

##### Preserved Source Text: source-02-13-8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice-1-160

<!-- migration-source path="design/project-management/vertical-slices/02.13.8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice.md" lines="1-160" sha256="278660f19cd03f709e27f19cc0d880017259f72f6a1cc3107fc5a83d6d48661f" heading-offset="3" -->
#### source-02-13-8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice-1-160: Built-In Command Registry and Dispatch Rollout Vertical Slice

##### source-02-13-8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice-1-160: Goal and Status

Goal: replace the growing hardcoded per-command branching in `TextCommandInterpreter` with one registry-driven built-in command dispatch model so command growth stays manageable and future game-authored commands can plug into the same architecture. Status: complete for the built-in rollout.

##### source-02-13-8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice-1-160: Implementation Notes

The first bounded runtime pass is now in place on branch:

- built-in command definitions and a registry exist for the active built-in command surface;
- built-in command definitions now sit behind a provider-backed aggregate registry contract, so extension/game-authored command definitions can later join the same lookup seam without replacing the built-in registry;
- `TextCommandInterpreter` now resolves definitions and stage, then delegates command-family dispatch through a handler map keyed by `TextCommandDispatchGroup` instead of owning the family switch itself;
- stage and prompt policy now live in command definitions;
- prompt policy semantics now distinguish `WHEN_LOGGED_IN` from true `WHEN_GAMEPLAY` prompt behavior instead of treating both as “any authenticated context”;
- built-in command definitions now also carry bounded primary action-category and source metadata so later authored commands do not need a second metadata seam;
- built-in alias ownership now also lives in command definitions, and `TextCommandParser` resolves the leading verb token through the active `TextCommandRegistry` instead of a hardcoded alias table;
- normalized built-in `ViewRequest` payloads now also carry the bounded `LOOK` vs `QUICKLOOK` long-description flag directly, so the look dispatch seam does not have to reconstruct room-refresh shape from a second local command-type check after parsing;
- Logging & Admin now exposes the bounded `ValidateBuiltInCommandAlias` read directly, so operator tooling can query the same canonical alias authority without reimplementing registry normalization rules outside Game Session;
- inventory, equipment, and container verbs now converge through one `ItemCommandHandler` dispatcher seam in the gameplay command layer;
- duplicate command-definition ownership and duplicate alias ownership are now rejected at registry construction time rather than silently allowing one provider to override another.

The built-in rollout is now complete. The runtime also has the first non-built-in provider registered, so future authored command growth should extend this provider-backed seam rather than reopening the interpreter-local branch model.

This slice is now urgent because the active command surface already includes login/lobby commands, room/view commands, communication, inventory, equipment, containers, and `WHO`. The current interpreter shape was acceptable for the first few vertical slices, but it should not become the long-term command architecture.

##### source-02-13-8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice-1-160: Why This Slice Exists

The current `TextCommandInterpreter` still directly knows too much about individual built-in commands:

- per-command stage decisions;
- per-command handler routing;
- command-family branching in one class;
- prompt-append behavior mixed with dispatch selection;
- increasing coupling every time a new built-in command lands.

This creates the wrong architectural pressure:

- every new built-in command grows a central branch tree;
- built-ins are modeled by where the code was added, not by one command-definition system;
- future game-authored commands will have no clean way to participate in the same pipeline;
- inventory/equipment/container drift is easier to introduce because dispatch ownership is spread across ad hoc branches rather than explicit command-family definitions.

FireMUD already has the design direction for staged deterministic interpretation in `02.13.6`. This slice turns that direction into the first concrete runtime dispatcher architecture for built-in commands.

##### source-02-13-8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice-1-160: Scope

- Introduce one built-in command registry for the active command surface.
- Move built-in stage eligibility out of ad hoc interpreter branching and into command definitions.
- Introduce one command-dispatch layer between parsed `TextCommand` values and concrete handler families.
- Keep existing parser/payload shapes where practical for the first pass.
- Keep existing command-family handlers where practical for the first pass.
- Make the interpreter shrink toward parse + stage resolve + registry dispatch + common post-processing.
- Keep the new model compatible with later tenant/game/action-authored command registration.

##### source-02-13-8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice-1-160: Out of Scope

- Full creator-editable command authoring.
- Natural-language or fuzzy command matching.
- Replacing the entire parser/payload model in the same slice.
- Solving all item-target resolution or action-classification follow-ups in the same change.

##### source-02-13-8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice-1-160: Target State

- Built-in commands are registered through one canonical registry rather than hidden in `TextCommandInterpreter` branches.
- Each registered built-in command definition owns at least:
  - canonical command id;
  - allowed stages;
  - command family / dispatcher target;
  - prompt behavior policy;
  - activity/action classification hook;
  - ownership/source metadata for authored-command compatibility.
- `TextCommandInterpreter` stops being the primary owner of per-command dispatch logic.
- Existing family handlers remain the immediate execution units for the first pass, but the item-manipulation path should converge toward one shared handler seam rather than continuing to split inventory, equipment, and containers into separate dispatch worlds:
  - session/auth commands;
  - help;
  - world/view commands;
  - movement;
  - communication;
  - one unified item-manipulation family covering inventory, equipment, room-ground, and containers.
- New built-in commands should be added by registering a definition and extending the relevant command-family handler, not by growing another interpreter branch.

##### source-02-13-8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice-1-160: Required Architectural Decisions

- [x] Use one registry-driven dispatch model for built-ins now rather than waiting for game-authored commands.
- [x] Move stage eligibility into command definitions rather than leaving it implicit in interpreter control flow.
- [x] Keep the first dispatcher grouped by command family rather than forcing one class per command.
- [x] Preserve the current parser and typed payloads in the first pass unless a payload shape is actively blocking the registry rollout.
- [x] Grow command definitions into the classification/ownership seam before authored commands arrive, rather than letting authored commands invent a second metadata model later.
- [x] Treat future game-authored commands as later users of the same registry concept, not as a second parallel dispatch path.

##### source-02-13-8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice-1-160: Recommended First Implementation Boundary

1. add a built-in command definition model;
2. add a built-in command registry keyed by canonical `TextCommandType`;
3. add a dispatcher that routes registered commands to family handlers;
4. move stage eligibility and prompt policy into command definitions;
5. simplify `TextCommandInterpreter` to:
   - parse;
   - resolve session/stage;
   - dispatch through registry;
   - apply shared post-processing.
6. collapse the current inventory/equipment/container command routing into one built-in item-manipulation dispatcher path so the command architecture matches the unified holder/transfer design under `06.4`.

This first pass should not try to solve game-authored command persistence, but it must clearly leave room for it.

##### source-02-13-8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice-1-160: Suggested Built-In Family Ownership

- `SessionCommandHandler`
  - `LOGIN`
  - `PLAY`
  - later `LOGOUT`
- `HelpCommandHandler`
  - `HELP`
- `ViewCommandHandler`
  - `WORLDS`
  - `LOOK`
  - `QUICKLOOK`
  - `WHO`
- `MovementCommandHandler`
  - `MOVE`
- `CommunicationCommandHandler`
  - `SAY`
  - `WHISPER`
  - `TELL`
- `ItemCommandHandler`
  - `INVENTORY`
  - `EQUIPMENT`
  - `CONTAINER`
  - `GET`
  - `DROP`
  - `WEAR`
  - `REMOVE`
  - `PUT`
  - `TAKE`

The important part is not the exact class names. The important part is that command growth extends explicit family ownership rather than interpreter-owned branching.

##### source-02-13-8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice-1-160: Recommended Implementation Order

1. Introduce the built-in command-definition model and registry without changing command behavior.
2. Add a dispatcher that routes registered commands to existing command-family handlers.
3. Move stage gating and prompt policy into command definitions.
4. [x] Add action classification and ownership/source metadata to command definitions.
5. Collapse the item-command families behind one shared item-manipulation handler seam.
6. Remove the now-redundant per-command branch tree from `TextCommandInterpreter`.
7. Only after that, continue expanding built-ins or game-authored command work.

##### source-02-13-8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice-1-160: Validation

- [x] Prove the interpreter no longer owns the main per-command branch tree for built-ins.
- [x] Prove adding a new built-in command requires a registry definition plus family-handler extension rather than a new central branch.
- [x] Keep the active command suite green with parser, interpreter, unit, and cross-service tests.
- [x] Confirm the resulting structure is compatible with later game-authored command registration.
- [x] Prove command-definition ownership collisions fail fast instead of silently overriding earlier providers.
- [x] Prove duplicate alias ownership also fails fast instead of silently shadowing earlier providers.

##### source-02-13-8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice-1-160: Follow-On

- Keep later authored-command expansion on the same provider-backed registry seam under `02.13.9` instead of reintroducing interpreter-local fallback logic.
<!-- /migration-source -->

### source-02-13-8-1-task-list-operator-built-in-command-alias-validation-readback-vertical-slice-1-87

#### 02.13.8.1 Task List: Operator Built-In Command Alias Validation Readback Vertical Slice - Built-in command alias diagnostic (source lines 1-87)

##### Preserved Source Text: source-02-13-8-1-task-list-operator-built-in-command-alias-validation-readback-vertical-slice-1-87

<!-- migration-source path="design/project-management/vertical-slices/02.13.8.1-task-list-operator-built-in-command-alias-validation-readback-vertical-slice.md" lines="1-87" sha256="9b07ee74a46e2803efd9e329b78355c7c783d5eae7d16b4089f511a0ace8f033" heading-offset="3" -->
#### source-02-13-8-1-task-list-operator-built-in-command-alias-validation-readback-vertical-slice-1-87: 02.13.8.1 Task List: Operator Built-In Command Alias Validation Readback Vertical Slice

##### source-02-13-8-1-task-list-operator-built-in-command-alias-validation-readback-vertical-slice-1-87: Goal and Status

Goal: expose the canonical Game Session `ValidateBuiltInCommandAlias` diagnostic through Logging & Admin so operator tooling can verify one built-in command alias against the live registry without dropping to gRPC or reimplementing registry normalization rules. Status: complete at the current bounded boundary.

##### source-02-13-8-1-task-list-operator-built-in-command-alias-validation-readback-vertical-slice-1-87: Why This Slice Exists

`02.13.8` already converged the built-in command runtime onto one canonical registry and alias resolver. One operator-facing gap remained:

- the canonical built-in alias validator existed only as a Game Session control-plane gRPC method;
- operator or activation-adjacent tooling still had no published HTTP surface to check whether one alias is supported and what its normalized alias is;
- leaving alias validation gRPC-only kept the registry boundary one transport seam short of convergence just as more runtime and plugin consumers now depend on the same alias authority.

##### source-02-13-8-1-task-list-operator-built-in-command-alias-validation-readback-vertical-slice-1-87: Scope

- Logging & Admin client, service, controller, and DTO support for `ValidateBuiltInCommandAlias`;
- one global operator REST route for built-in alias validation;
- focused controller/service proof and published OpenAPI coverage for the bounded validation surface.

##### source-02-13-8-1-task-list-operator-built-in-command-alias-validation-readback-vertical-slice-1-87: Out of Scope

- changes to Game Session built-in registry ownership or alias-resolution rules;
- authored-command validation, which remains future work under later command slices;
- broader gameplay command status or registry inventory surfaces.

##### source-02-13-8-1-task-list-operator-built-in-command-alias-validation-readback-vertical-slice-1-87: Locked Direction

- Logging & Admin must consume the canonical Game Session alias validator directly rather than recreating alias normalization rules locally;
- the route is global control-plane metadata, so it should require a global privileged operator role instead of tenant scope;
- unsupported aliases should return a normal bounded result (`supported=false`) rather than being widened into not-found semantics.

##### source-02-13-8-1-task-list-operator-built-in-command-alias-validation-readback-vertical-slice-1-87: Planned Work

###### source-02-13-8-1-task-list-operator-built-in-command-alias-validation-readback-vertical-slice-1-87: 1. Operator Validation Surface

- [x] Add a Logging & Admin control-plane client method for `ValidateBuiltInCommandAlias`.
- [x] Add a global operator Logging & Admin route for built-in alias validation.
- [x] Map the canonical response onto a bounded DTO carrying `supported` and optional `normalizedAlias`.

###### source-02-13-8-1-task-list-operator-built-in-command-alias-validation-readback-vertical-slice-1-87: 2. Proof and Docs

- [x] Add focused Logging & Admin controller/service proof for successful validation, unsupported alias handling, and privileged-role enforcement.
- [x] Update Logging & Admin `openapi.yaml` so the published contract includes the new validation route and DTO.
- [x] Update `02.13.8` parent/index/progress docs so the operator readback is tracked as landed follow-through.

##### source-02-13-8-1-task-list-operator-built-in-command-alias-validation-readback-vertical-slice-1-87: Acceptance Shape

- Logging & Admin exposes `GET /command-registry/built-in-aliases/{alias}`;
- the route returns the canonical Game Session verdict for that alias as `{supported, normalizedAlias}`;
- callers without a global privileged role are rejected before the control-plane read;
- unsupported aliases return `supported=false` with no normalized alias rather than a transport or 404 failure.

##### source-02-13-8-1-task-list-operator-built-in-command-alias-validation-readback-vertical-slice-1-87: Completion Notes

- `GameSessionControlPlaneClient` now exposes `validateBuiltInCommandAlias(String alias)` for Logging & Admin.
- `CommandRegistryController` now serves `GET /command-registry/built-in-aliases/{alias}` and enforces global privileged-role access before delegating.
- `CommandRegistryServiceImpl` now maps the canonical Game Session response onto `BuiltInCommandAliasValidationDto`, preserving unsupported-alias results as a normal bounded payload.
- Logging & Admin `openapi.yaml` now documents the alias-validation route and DTO shape so the published operator contract matches the landed endpoint.

##### source-02-13-8-1-task-list-operator-built-in-command-alias-validation-readback-vertical-slice-1-87: Completion Evidence

- Logging & Admin implementation:
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/client/GameSessionControlPlaneClient.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/controller/CommandRegistryController.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/CommandRegistryService.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/impl/CommandRegistryServiceImpl.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/dto/BuiltInCommandAliasValidationDto.java`
  - `services/logging-admin-service/src/main/resources/openapi.yaml`
- Focused Logging & Admin proof:
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/CommandRegistryControllerTest.java`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/CommandRegistryServiceImplTest.java`
- Existing Game Session alias-validation contract proof reused by this operator surface:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcServiceTest.java`

##### source-02-13-8-1-task-list-operator-built-in-command-alias-validation-readback-vertical-slice-1-87: Validation

- `./gradlew :logging-admin-service:test --tests 'net.firedevops.firemud.loggingadmin.controller.CommandRegistryControllerTest' --tests 'net.firedevops.firemud.loggingadmin.service.impl.CommandRegistryServiceImplTest'`
- `./gradlew spotlessApply`
- `dev-tools/validation/run-locked-gradle.sh :logging-admin-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`

##### source-02-13-8-1-task-list-operator-built-in-command-alias-validation-readback-vertical-slice-1-87: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-02-16-task-list-dedicated-first-party-web-app-service-vertical-slice-1-50

#### Dedicated First-Party Web App Service Vertical Slice - First-party browser client and bootstrap experience (source lines 1-50)

##### Preserved Source Text: source-02-16-task-list-dedicated-first-party-web-app-service-vertical-slice-1-50

<!-- migration-source path="design/project-management/vertical-slices/02.16-task-list-dedicated-first-party-web-app-service-vertical-slice.md" lines="1-50" sha256="bb2958bff8cbdfcf6bd8fa38f64209d51eb6ebb68e33d76c9333ae11b73e582e" heading-offset="3" -->
#### source-02-16-task-list-dedicated-first-party-web-app-service-vertical-slice-1-50: Dedicated First-Party Web App Service Vertical Slice

##### source-02-16-task-list-dedicated-first-party-web-app-service-vertical-slice-1-50: Goal and Status

Introduce a dedicated first-party web application service as the canonical long-term home for FireMUD's browser client, browser asset hosting, and first-party web bootstrap/user experience. Status: planned.

##### source-02-16-task-list-dedicated-first-party-web-app-service-vertical-slice-1-50: Checklist

- [ ] Define target-state behavior and scope.
- [ ] Implement the slice end-to-end.
- [ ] Verify and close any follow-ups.

##### source-02-16-task-list-dedicated-first-party-web-app-service-vertical-slice-1-50: Why This Slice Exists

FireMUD needs a clear final browser architecture early. Spring Cloud Gateway is the public API and gameplay edge, but it should not become the permanent host for frontend assets or product-specific browser orchestration.

This slice locks in that:

- the long-term browser home is a dedicated first-party web application service
- the first version of that service may be only a terminal-style browser client
- richer web UX can grow later without collapsing browser/product logic into the gateway

##### source-02-16-task-list-dedicated-first-party-web-app-service-vertical-slice-1-50: Scope

- Define and implement the dedicated first-party web application service.
- Host the player browser assets from that service.
- Own first-party web bootstrap UX there rather than in Spring Cloud Gateway.
- Keep Gateway as the public gameplay/API edge.
- Allow the first practical browser UI to be terminal-style before richer gameplay UI exists.

##### source-02-16-task-list-dedicated-first-party-web-app-service-vertical-slice-1-50: Out of Scope

- Replacing TCP/Telnet as a manual proof path
- Rich admin tooling redesign
- Solving every future frontend feature before the service exists

##### source-02-16-task-list-dedicated-first-party-web-app-service-vertical-slice-1-50: Architecture Notes

- The service may begin as a thin browser terminal.
- Browser gameplay bootstrap uses the `Firemud-Connect-Token` HttpOnly cookie set by `POST /auth/connect-token`; arbitrary custom WebSocket headers are reserved for non-browser clients.
- Mudlet/custom smart clients remain important supported client surfaces, but they do not define the browser architecture.
- Hosted preview's first milestone remains TCP/Telnet manual proof; browser work should start here only once preview usefulness no longer depends on browser-only helper paths.
- Spring Cloud Gateway remains the public edge, not the permanent home for first-party web assets or product-specific browser orchestration even if temporary bootstrap conveniences exist during transition work.

##### source-02-16-task-list-dedicated-first-party-web-app-service-vertical-slice-1-50: Acceptance Shape

- First-party browser assets are no longer permanently hosted from Spring Cloud Gateway.
- Gateway remains the public edge for API/gameplay traffic.
- The first browser UX can authenticate/bootstrap and enter gameplay using the dedicated web service.
- The initial UX may still look like a terminal, but the service boundary is correct from the beginning.
<!-- /migration-source -->

### source-02-18-14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice-24-28-39-41-43-48-50-56

#### `02.18.14` Moderation Policy Definition and Enforcement Split - Gameplay admission and chat-send enforcement behavior (source lines 24-28, 39-41, 43-48, 50-56)

##### Preserved Source Text: source-02-18-14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice-24-28-39-41-43-48-50-56

<!-- migration-source path="design/project-management/vertical-slices/02.18.14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice.md" lines="24-28, 39-41, 43-48, 50-56" sha256="c51802a5032fa25e8201852a76bda888061f9d49abcf2f0f540b15c23428dd14" heading-offset="3" -->
- gameplay admission enforcement
- chat/send enforcement
- policy distribution/read model for enforcement owners
- bounded enforcement behavior on `PLAY` and chat send paths

<!-- source-gap: lines 29-38 -->
- Game Session owns gameplay-admission enforcement.
- Social & Groups owns chat-send enforcement.
- account deletion is not the default substrate for routine moderation policy.
<!-- source-gap: lines 42-42 -->
##### source-02-18-14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice-24-28-39-41-43-48-50-56: Acceptance Shape

- destructive “record then delete account and stop session” moderation flow is removed or reduced to deliberate account-security cases only.
- gameplay-ban policy is enforced during admission on the live `PLAY` / runtime entry path.
- chat mute/ban policy is enforced on chat send, not only logged after the fact.
- docs describe one policy-definition and enforcement split rather than mixed destructive behavior.
<!-- source-gap: lines 49-49 -->
##### source-02-18-14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice-24-28-39-41-43-48-50-56: Checklist

- [x] Define the canonical moderation policy model and the owner/enforcer split.
- [x] Replace the destructive moderation substrate with policy-state plus enforcement-owner actions.
- [x] Add gameplay admission enforcement for gameplay-ban policy.
- [x] Add chat-send enforcement for mute/ban policy.
- [x] Add focused tests for policy evaluation, chat policy checks, gameplay policy checks, and non-destructive moderation-policy updates.
<!-- /migration-source -->

### source-02-2-task-list-gameplay-admission-ux-vertical-slice-1-119

#### Gameplay Admission UX Alignment Vertical Slice Task List - Gameplay admission command experience (source lines 1-119)

##### Preserved Source Text: source-02-2-task-list-gameplay-admission-ux-vertical-slice-1-119

<!-- migration-source path="design/project-management/vertical-slices/02.2-task-list-gameplay-admission-ux-vertical-slice.md" lines="1-119" sha256="002ff4984b5a2bd9f98c4ff3ba8546d1976f8f49551adac92a429388ba97228b" heading-offset="3" -->
#### source-02-2-task-list-gameplay-admission-ux-vertical-slice-1-119: Gameplay Admission UX Alignment Vertical Slice Task List

##### source-02-2-task-list-gameplay-admission-ux-vertical-slice-1-119: Goal and Status

Goal: align the implemented login and lobby flow with the intended player-facing MUD experience so normal clients can move from connection to gameplay with minimal ceremony while preserving the current security and gameplay-binding model. Status: baseline live; the core `WORLDS` / `LOGIN` / `PLAY` flow, stage-aware errors, direct WebSocket coverage, first-party path examples, Telnet no-typed-attach-hint path, and smoke/status doc cleanup are implemented, while the remaining unchecked work is final manual QA only.

This slice is a follow-up to **Telnet to Gameplay**, **Login and Session**, and **Login and Session Hardening**. It does not replace the current security model. Instead, it reshapes the user-facing command flow so the system behaves more like a conventional MUD front door:

```text
WORLDS        # optional public browse
LOGIN user pass
PLAY gamename [character]
```

Optional helper commands such as `REALMS` and `CHARS` remain available, but they should no longer feel like required ceremony before entry.

##### source-02-2-task-list-gameplay-admission-ux-vertical-slice-1-119: 1. Protocol and UX Canonicalization

- [x] Re-read the [Game Session protocols](../../architecture/microservices/game-session-service/protocols.md), [Authentication & Authorization](../../architecture/system-architecture-authentication.md), [TCP Proxy protocols](../../architecture/microservices/tcp-proxy-service/protocols.md), and [Reconnection Strategy](../../architecture/system-architecture-reconnection.md) docs and reconcile any remaining contradictions around `WORLDS`, `LOGIN`, `PLAY`, and `SESSION`.
- [x] Make one command-stage model canonical in the design docs:
  - connected but not logged in;
  - logged in but not yet playing;
  - in game.
- [x] Document `WORLDS` as available before `LOGIN` for public browsing/discovery, while clarifying that authenticated callers may see a richer personalized result set after login.
- [x] Explicitly document that `LOGIN` authenticates account identity and `PLAY` binds gameplay identity/scope; these commands are sequential in the player experience but semantically distinct.
- [x] Add at least one canonical Telnet transcript and one canonical WebSocket transcript for the ordinary human flow:
  - `WORLDS`
  - `LOGIN user pass`
  - `PLAY gamename [character]`
  - first gameplay command

##### source-02-2-task-list-gameplay-admission-ux-vertical-slice-1-119: 2. Stage-Aware Command Handling in Game Session

- [x] Before changing this service for the slice, run `./gradlew :game-session-service:test` and stabilize the baseline if necessary.
- [x] Replace the current player-facing wrong-stage behavior so pre-login gameplay-like input does not surface as `NOT_AUTHENTICATED` and post-login/pre-`PLAY` gameplay-like input does not surface as `WORLD_NOT_SELECTED`.
- [x] Introduce or refine explicit stage-aware protocol errors such as:
  - `LOGIN_REQUIRED`
  - `PLAY_REQUIRED`
  - and any bounded ambiguity/help responses needed for `PLAY`
- [x] Ensure the interpreter can distinguish:
  - public/discovery commands available before login;
  - authenticated lobby commands available after login but before gameplay binding;
  - true in-game gameplay commands.
- [x] Add unit/integration tests showing that wrong-stage input produces menu/help guidance instead of backend-shaped or gameplay-mechanics-shaped errors.

##### source-02-2-task-list-gameplay-admission-ux-vertical-slice-1-119: 3. Simplified `PLAY` Resolution Rules

- [x] Define and implement the normal happy-path `PLAY` contract as `PLAY <world> [character]`, with realm optional in the ordinary case.
- [x] If `PLAY <world>` resolves to exactly one admissible gameplay target and one visible character choice, enter directly.
- [x] If `PLAY <world>` is ambiguous, return a selection-oriented response that guides the player toward `REALMS`, `CHARS`, or a more specific `PLAY` form rather than returning a low-level failure.
- [x] Decide and document whether `PLAY <world>` with exactly one admissible realm but multiple characters should:
  - prompt/select;
  - return a bounded ambiguity response;
  - or require `PLAY <world> <character>` explicitly.
- [x] Add tests for:
  - direct unambiguous `PLAY`
  - ambiguous `PLAY` needing more selection
  - inaccessible world/realm/character
  - default public production realm entry

##### source-02-2-task-list-gameplay-admission-ux-vertical-slice-1-119: 4. First-Party Web Alignment

- [x] Update the first-party `/ws/game/**` design docs so the web client still maps cleanly onto the same player-facing model even though it uses bootstrap, connect-token, and connect-context machinery under the hood.
- [x] Make explicit that first-party bare `LOGIN` is an identity-consumption/binding step using already-verified bootstrap identity, not a second credential-entry step.
- [x] Keep bootstrap/connect-context machinery from reintroducing gameplay binding into `LOGIN`; `PLAY` remains the sole gameplay-admission and gameplay-scope binding step.
- [x] Ensure first-party UI/discovery flows still conceptually terminate in the same `PLAY` semantics as Telnet/generic WebSocket clients rather than inventing a second gameplay-admission model.
- [x] Add or refresh tests/documented examples showing the first-party path results in the same effective gameplay state machine even if the UI hides some of the lobby commands.

##### source-02-2-task-list-gameplay-admission-ux-vertical-slice-1-119: 5. Smart-Client Attach Scope

- [x] Rework docs and examples so typed attach hints are no longer presented as part of the normal human-facing Telnet flow.
- [x] Remove the typed `SESSION` command from the target-state player and advanced-client model.
- [x] Decide whether the preferred long-term transport for smart-client attach hints should be:
  - the existing typed `SESSION` line;
  - hidden MCP metadata;
  - or another explicit advanced-client channel.
- [x] Ensure the chosen attach-hint path remains strictly advisory and cannot bypass `LOGIN` + `PLAY`.
- [x] Update Telnet examples and tests so normal parity coverage does not rely on typed attach hints.

##### source-02-2-task-list-gameplay-admission-ux-vertical-slice-1-119: 6. Cross-Service and Transport-Parity Coverage

- [x] Keep direct generic WebSocket coverage as a lower-layer internal/test seam and prefer Gateway or TCP Proxy for real end-to-end player-path verification.

- [x] Add or refresh a Telnet cross-service test for the normal human flow without `SESSION`:
  - connect
  - optional `WORLDS`
  - `LOGIN`
  - `PLAY`
  - `LOOK`
- [x] Add or refresh a direct WebSocket / Gateway parity test for the same flow.
- [x] Add focused coverage for:
  - pre-login public `WORLDS`
  - wrong-stage command guidance
  - unambiguous `PLAY`
  - no hidden dependence on typed attach hints

##### source-02-2-task-list-gameplay-admission-ux-vertical-slice-1-119: 7. Developer Workflows, Docs, and Cleanup

- [x] Update smoke/manual verification guidance so the normal example path is `LOGIN` -> `PLAY` instead of `LOGIN` -> `WORLDS` -> `REALMS` -> `CHARS` -> `PLAY`.
- [x] Refresh service-status docs so they describe the simplified admission UX and stage-aware error model rather than the older implementation-shaped behavior.
- [x] Remove or rewrite examples that imply all pre-`PLAY` mistakes are gameplay-auth failures.

##### source-02-2-task-list-gameplay-admission-ux-vertical-slice-1-119: 8. Final QA Checklist

- [ ] Manually verify the normal Telnet flow and generic WebSocket flow both feel like:
  - connect
  - optional browse
  - `LOGIN`
  - `PLAY`
  - in game
- [ ] Confirm the first-party web flow still lands on the same gameplay binding semantics.
- [x] Confirm normal players are never expected to type any attach metadata directly.

---

##### source-02-2-task-list-gameplay-admission-ux-vertical-slice-1-119: Deferred Follow-Up

- A later slice may revisit prompt-driven selection UIs or richer in-band menus if the platform wants deeper MUD-style prompts rather than command-only disambiguation.
- If smart-client attach hints become necessary later, they should be hidden MCP metadata rather than a typed `SESSION` gameplay line.
<!-- /migration-source -->

### source-02-21-task-list-frontend-server-state-baseline-and-query-convergence-vertical-slice-1-61

#### Frontend Server-State Baseline and Query Convergence Vertical Slice - First-party frontend server-state convergence (source lines 1-61)

##### Preserved Source Text: source-02-21-task-list-frontend-server-state-baseline-and-query-convergence-vertical-slice-1-61

<!-- migration-source path="design/project-management/vertical-slices/02.21-task-list-frontend-server-state-baseline-and-query-convergence-vertical-slice.md" lines="1-61" sha256="db98009fad7c3ea76919723f1bb9d82837074df0f75d26dc37cd7653040c0f24" heading-offset="3" -->
#### source-02-21-task-list-frontend-server-state-baseline-and-query-convergence-vertical-slice-1-61: Frontend Server-State Baseline and Query Convergence Vertical Slice

##### source-02-21-task-list-frontend-server-state-baseline-and-query-convergence-vertical-slice-1-61: Goal and Status

Goal: make FireMUD's canonical frontend baseline `React + Vite + MUI + TanStack Query`, replace the current thin `Redux Toolkit + RTK Query` scaffold before it calcifies into house style, and document that Redux is an exception used only when proven client-state complexity genuinely needs it. Status: implemented at the current baseline boundary.

##### source-02-21-task-list-frontend-server-state-baseline-and-query-convergence-vertical-slice-1-61: Why This Slice Exists

The repo already has a real browser architecture document, but the canonical frontend guidance is stale. It still teaches `Redux Toolkit + RTK Query` as the default state-management model even though the current `web-client` barely uses Redux beyond a starter scaffold:

- one global store;
- one RTK Query API surface;
- typed Redux hooks;
- no substantial reducer/slice/thunk ecosystem;
- no proven long-lived cross-screen client-state problem that actually earns Redux.

That makes this a cheap point to correct the baseline. If FireMUD keeps the current scaffold and docs unchanged, future frontend work will inherit Redux by inertia rather than because the browser actually needs a global client-state store.

##### source-02-21-task-list-frontend-server-state-baseline-and-query-convergence-vertical-slice-1-61: Implemented Surface

The `web-client` baseline now uses:

- `src/api/queryClient.ts` for one canonical shared `QueryClient`;
- `src/api/firemudApi.ts` for typed `TanStack Query` query/mutation hooks over the current demo API surface;
- `src/main.tsx` `QueryClientProvider` wiring instead of Redux `<Provider>`;
- no Redux store/hooks dependency in the current frontend starter scaffold.

This is the intended current boundary: FireMUD now has one explicit server-state baseline without pretending it already needs a browser-wide client-state store.

##### source-02-21-task-list-frontend-server-state-baseline-and-query-convergence-vertical-slice-1-61: Architecture Decision

- FireMUD's canonical frontend baseline is `React + Vite + MUI + TanStack Query`.
- `TanStack Query` is the default server-state substrate for browser reads, mutations, caching, invalidation, refetching, and polling.
- Local component/form/editor state should stay close to the owning feature until proven otherwise.
- Redux is not the default browser state layer. It should be introduced only by an explicit later slice when real client-state complexity earns it.
- Large world/editor datasets alone do not justify Redux. "Big" server-backed data is still primarily a query/cache concern until the browser becomes a true long-lived local stateful editor/runtime for that data.

##### source-02-21-task-list-frontend-server-state-baseline-and-query-convergence-vertical-slice-1-61: Scope

- update the high-level frontend architecture docs so they describe `TanStack Query` as the canonical default instead of `RTK Query`;
- update repo-facing frontend/developer docs so contributors do not infer Redux as the default house style;
- replace the current `web-client` Redux/RTK Query starter scaffold with a `TanStack Query` baseline;
- define the bar for any future Redux introduction so frontend growth does not turn into case-by-case tool debates.

##### source-02-21-task-list-frontend-server-state-baseline-and-query-convergence-vertical-slice-1-61: Out of Scope

- designing the final long-term state architecture for advanced collaborative world editors before those editor surfaces exist;
- banning Redux forever even if a later client-side editing/runtime slice proves it necessary;
- broader frontend product-surface work such as the dedicated first-party web-app service or richer gameplay/admin UI implementation.

##### source-02-21-task-list-frontend-server-state-baseline-and-query-convergence-vertical-slice-1-61: Dependencies

- This slice should precede or land alongside meaningful new admin/editor/browser feature work so those surfaces start from the intended baseline instead of inheriting the current scaffold.
- Later frontend slices may introduce a richer client-state layer only if they document the concrete problem that `TanStack Query + local feature state` no longer solves cleanly.

##### source-02-21-task-list-frontend-server-state-baseline-and-query-convergence-vertical-slice-1-61: Checklist

- [x] Verify that the current frontend docs still prescribe Redux/RTK Query even though the live scaffold barely uses Redux.
- [x] Make the `TanStack Query` default and "Redux only by exception" decision explicit in architecture/docs.
- [x] Replace the current `web-client` Redux/RTK Query starter scaffold with a `TanStack Query` baseline.
- [x] Keep the frontend architecture, repository-facing tooling docs, and `web-client` docs aligned with the new baseline.
<!-- /migration-source -->

### source-03-task-list-data-driven-look-vertical-slice-52-112

#### Data-Driven LOOK Vertical Slice Task List - LOOK composition, rendering, transport, and replay behavior (source lines 52-112)

##### Preserved Source Text: source-03-task-list-data-driven-look-vertical-slice-52-112

<!-- migration-source path="design/project-management/vertical-slices/03-task-list-data-driven-look-vertical-slice.md" lines="52-112" sha256="453637cf37341b1ff9ddd06777ed8f8b83c227bd29658ff5289121804a860782" heading-offset="3" -->
##### source-03-task-list-data-driven-look-vertical-slice-52-112: 4. Game Logic Service: LOOK Aggregation and Formatting

- [x] Before changing this service for the slice, run `./gradlew :game-logic-service:test` and either get the existing tests passing or clearly document/temporarily disable any failing tests so the baseline is stable.
- [x] Introduce or refine a `LOOK`-oriented gRPC method in the Game Logic Service (for example `ResolveLook` or `GetLookDescription`) that accepts identity context (`tenantId`, `session_id`, `characterId`, and `roomInstance`) and returns a high-level `LookResult` DTO.
- [x] Implement the Game Logic `LOOK` handler so it orchestrates calls to World Management (room snapshot) and Entity Management (visible entities), applies any simple rules needed for this slice (for example, hiding entities the player should not see), and produces a structured `LookResult`.
- [x] Implement a straightforward text rendering routine that preserves the structured `LookResult` internally but renders the default text `LOOK` output in classic MUD order: room title, one descriptive block containing room prose plus visible characters/items, then exits, leaving hooks for richer colorization and overlays in future slices.
- [x] Add unit tests around the Game Logic `LOOK` handler and text rendering to cover at least: empty room, room with exits only, room with multiple entities, and error propagation when World or Entity services fail.
- [x] Document the new Game Logic `LOOK` API and its responsibilities in the Game Logic design docs, clearly distinguishing it from future combat/movement systems.

##### source-03-task-list-data-driven-look-vertical-slice-52-112: 5. Game Session Service: Wiring Text LOOK to Game Logic

- [x] Before changing this service for the slice, run `./gradlew :game-session-service:test` and either get the existing tests passing or clearly document/temporarily disable any failing tests so the baseline is stable.
- [x] Replace the current hard-coded `LookCommandHandler` (or equivalent) in Game Session with a flow that calls Game Logic’s `LOOK`/`ResolveLook` gRPC method, passing along `tenantId`, `sessionId`, and `characterId` from the Redis session context.
- [x] Add a dedicated Spring client/component that encapsulates the World and Entity service calls and captures Micrometer timers for the remote invocations. The maintained implementation now uses the canonical downstream-service path rather than a dependency-light fallback.
- [x] Ensure that the text command interpreter continues to enforce authentication before invoking the `LOOK` gRPC call and that unauthenticated requests still return `ERROR NOT_AUTHENTICATED` without hitting downstream services.
- [x] Keep the textual `LOOK` output compatible with the existing smoke tests (same `OK LOOK` framing) while expanding the body to include the new data-driven content.
- [x] Map Game Logic `LookResult` and error codes into the text protocol response format so clients see a consistent `LOOK` description or `ERROR <CODE> <message>` when the call fails (for example, `ERROR ROOM_NOT_FOUND`, `ERROR WORLD_UNAVAILABLE`, `ERROR ENTITY_UNAVAILABLE`).
- [x] Add Micrometer metrics and structured logs in Game Session for `LOOK` commands (for example `gamesession.command.look.invocations`, `gamesession.command.look.failures`) using bounded metric tags such as high-level error codes, with `tenantId` carried in structured logs rather than ordinary metric labels.
- [x] Add unit and/or integration tests in `services/game-session-service` that exercise the text `LOOK` path end-to-end against a stubbed Game Logic client, verifying correct handling of success, room-not-found, and downstream failure scenarios.
- [x] Replace the temporary Redis stub used by `services/tcp-proxy-service/src/test/java/crossservice/net/firedevops/firemud/TelnetGatewayGameSessionAccountCrossServiceIntegrationTest.java` with shared Redis test utilities (or direct Testcontainers wiring) so the slice exercises the same Redis configuration used in production.
- [x] Document the LOOK instrumentation (metrics/logs) in `design/project-management/slice-support/look-instrumentation.md` so operators know what to monitor while the slice stabilizes.

##### source-03-task-list-data-driven-look-vertical-slice-52-112: 6. Cross-Service End-to-End Tests (Telnet and WebSocket)

- [x] Extend or add a WebSocket-focused cross-service integration test that boots Game Session, Game Logic, World Management, and Entity Management (Testcontainers or lightweight stubs as needed), executes `LOGIN` + `LOOK`, and asserts the multiline response (`OK LOOK` plus room title, composed descriptive block, and exits) matches the canonical transcript defined in Section 1.
- [x] Add a Telnet-focused cross-service variant (TCP Proxy + Gateway harness) that performs `WORLDS` + `LOGIN` + `PLAY` + `LOOK` and asserts the Telnet transcript is semantically identical to the WebSocket output except for the framing and prompt lines.
- [x] Cover at least one happy-path room and one failure path (for example, a tenant attempting `LOOK` in a missing room) in these tests, verifying that the recorded `ERROR ROOM_NOT_FOUND` / `ERROR WORLD_UNAVAILABLE` lines appear exactly once without dropping Telnet/WebSocket connections.
- [x] Instrument the cross-service flows so we can assert the `LOOK` command traversed the pipeline (e.g., via log capture, metrics tags, or gRPC interceptors) and ensure `gamesession.command.look.*` counters increment for both success and failure paths.
- [x] Wire the new cross-service tests into a dedicated Gradle target (e.g., `crossServiceTest`) so they can run without slowing down the default unit suite, and reference the target in README/test docs.
- [x] Re-enable `services/tcp-proxy-service/src/test/java/crossservice/net/firedevops/firemud/TelnetGatewayGameSessionAccountCrossServiceIntegrationTest.java` (and add an analogous WebSocket harness) once the true Game Logic → World → Entity pipeline is available so we can replay the documented transcripts end-to-end.
- [x] Refer to `design/project-management/slice-support/look-cross-service-tests.md` for detailed automation steps, metrics assertions, and Gradle wiring when implementing these tests.
- [x] Add a short note in `design/project-management/testing-focus-areas.md` under the command parsing / game logic sections pointing to these data-driven `LOOK` cross-service tests as examples.
- [x] Implement the WebSocket cross-service regression test placeholder (`LookWebSocketCrossServiceTest`) using the new LOOK fixtures/stubs and the documented metrics/log assertions before expanding to the Telnet flow.

Implementation notes for wiring the stubbed World/Entity/Account services, capturing the canonical transcripts, and validating the `gamesession.command.look.*` meters/logs live in `design/project-management/slice-support/look-cross-service-tests.md#implementation-notes`; follow them while building the WebSocket and Telnet flows so the automation exercises both success and error paths as documented.

##### source-03-task-list-data-driven-look-vertical-slice-52-112: 7. Developer Workflows, Smoke Tests, and Documentation Updates

- [x] Add or update a smoke test script (or documented curl/WebSocket sequence) that demonstrates `LOGIN` + `LOOK` against the sample world over WebSocket, including the expected room description in the script output or comments (`design/project-management/slice-support/look-smoke-tests.md`).
- [x] Add a second smoke test or documented Telnet transcript/example that demonstrates `WORLDS` + `LOGIN` + `PLAY` + `LOOK` via Telnet through TCP Proxy and Gateway, verifying that the same room description is returned (same doc).
- [x] Update the Game Session, Game Logic, World Management, and Entity Management design docs to include a short "Implementation status" note for the `LOOK` slice, clarifying what is live, what is stubbed, and what is deferred to future slices (for example, dynamic lighting, line-of-sight, or script-driven room text).
- [x] Expand the World Management Service design doc to describe the `/ws/game/**` `LOOK` contract fields, how Game Session aggregates entity/world context before replying to WebSocket/Telnet clients, and what configuration toggles (such as `WORLD_SERVICE_ENDPOINT`) developers can use locally.
- [x] Revisit the `Minimal Text Command Protocol` and any existing gameplay examples to ensure they reference the data-driven `LOOK` behavior instead of the original hard-coded room stub, updating examples where necessary.
- [x] Ensure logging and monitoring docs (including relevant sections under Logging & Admin) mention the new `LOOK`-related metrics and logs so operators know how to debug issues in this path.

##### source-03-task-list-data-driven-look-vertical-slice-52-112: 8. Optional Follow-up: Reconnection Experience

- [x] (If time permits) Keep reconnect redraw user-friendly by replaying bounded transcript context and then issuing a fresh authoritative `LOOK` before the trailing prompt.
- [x] Keep the reconnect-support store bounded to transcript/context restoration rather than treating a rendered `LOOK` snapshot as authoritative room state.
- [x] On reconnect (WebSocket `GameSessionWebSocketHandler`, TCP Proxy `TelnetServerHandler`, etc.) deliver the bounded transcript replay first, then a fresh `LOOK`, then one fresh prompt.
- [x] Add focused regression tests that cover reconnect replay and fresh-room redraw behavior without treating a stale rendered `LOOK` snapshot as the canonical answer to a new `LOOK`.

---

Note: After completing tasks in this checklist, go back and update the existing per-service status documents (such as `design/project-management/service-status-game-session-service.md`, `design/project-management/service-status-game-logic-service.md`, `design/project-management/service-status-world-management-service.md`, and `design/project-management/service-status-entity-management-service.md`) and the relevant design docs so duplicated items are reconciled and the architecture documentation reflects the completed vertical slice.

<!--
Prompt for Codex to generate the next vertical slice task list after these items are done:

"Context: We just completed the Login and Session vertical slice described in design/project-management/vertical-slices/02-task-list-login-and-session-vertical-slice.md. Please inspect the current code and design docs, then propose a new markdown task list file under design/project-management/ focused on the next smallest playable/demo slice that follows this flow deeper into the system (for example, data-driven LOOK that integrates World and Entity services, the SAY/chat path through Social & Groups, or more advanced reconnection edge cases). Each task should be small enough to hand to Codex as a single chunk, and the file should end with a note reminding us to reconcile any duplicated items in existing per-service status docs and design docs."
-->
<!-- /migration-source -->

### source-04-task-list-chat-and-social-vertical-slice-1-167

#### Chat & SAY Vertical Slice Task List - Shared player communication and SAY (source lines 1-167)

##### Preserved Source Text: source-04-task-list-chat-and-social-vertical-slice-1-167

<!-- migration-source path="design/project-management/vertical-slices/04-task-list-chat-and-social-vertical-slice.md" lines="1-167" sha256="a0dbd1b66e224125fc759708d0a3236fb7de3deb5a64a769f146893e9bc6b706" heading-offset="3" -->
#### source-04-task-list-chat-and-social-vertical-slice-1-167: Chat & SAY Vertical Slice Task List

##### source-04-task-list-chat-and-social-vertical-slice-1-167: Goal and Status

Goal: establish the first implementation of the shared communication infrastructure so WebSocket and Telnet behaviour and observability stay in lockstep across Game Session, Game Logic, and Social/Groups services. The first standard built-ins are `say`, `whisper`, and `tell`, with room-local `say` as the first fully implemented mode. Status: room-local `say` now resolves live player listeners plus NPC echoes through the shared communication path, while the broader configurable communication model remains follow-on work described here and in the associated microservice design docs.

Follow-up design direction agreed after the main slice landed:

- The long-term platform model should not remain `SAY`-centric. FireMUD should use a shared, configurable communication infrastructure with `say`, `whisper`, and `tell` as standard built-in communication types and `shout` reserved as a future built-in once communication-scope settings are designed properly.
- Communication should be modeled around:
  - a communication intent emitted by an actor,
  - a game-configured communication type definition,
  - one or more communication targets/scopes,
  - recipient resolution performed by those targets/scopes,
  - and per-recipient presentation/rendering.
- In-world communication should usually target scope objects such as a room, area, region, map, or other spatial/social aggregate, rather than precomputing a flat recipient list in the sender path.
- Target-owned recipient resolution must be able to include normal listeners plus observers/interceptors such as eavesdroppers, spies, magical listeners, or game-specific sneaky skills.
- Game-configured communication definitions should control propagation rules, moderation category, and the presentation style that players see in-game.
- All communication actions should enter through Game Logic as gameplay/application actions. Game Logic owns target/scope resolution, gameplay interception/perception rules, and the final communication outcome, then dispatches to Social & Groups or other owning services as needed for membership checks, durable history, moderation, and delivery fanout.
- Observer perception should be split cleanly:
  - communication type defines the baseline observability contract,
  - target/scope resolves who qualifies as a normal listener or observer,
  - and recipient capabilities/effects decide whether the final view is full-content, partial-content, or metadata-only.

After `LOOK` flows through the new Game Logic + World + Entity path, the next smallest playable slice expands the text command fabric with the `SAY` chat path that lets players speak to others in the same room, touching Game Logic aggregation, Game Session command parsing, and the cross-service regression suites so we can assert both WebSocket and Telnet experiences stay in sync.

Scope note: this slice is the first implemented room-speech pathway, but it should now be understood as the first delivery mode on top of a broader configurable communication model rather than as the permanent abstraction for all speech.

Out of scope for the first implementation inside this slice: fully differentiated `whisper` and `tell` semantics beyond their baseline defaults, and any `shout` implementation. `Shout` should remain an explicit future built-in because its propagation depends on the game-settings model, for example whether regions exist and whether shout scope is region-wide, map-wide, or otherwise configured. Those behaviors should land later as configuration and target-resolution changes rather than as a rewrite of the communication model.

##### source-04-task-list-chat-and-social-vertical-slice-1-167: 1. Protocol, UX, and Design Alignment for the Initial Communication Modes

- [x] Update the [Minimal Text Command Protocol](../../architecture/microservices/game-session-service/README.md#minimal-text-command-protocol) section so the first standard built-ins document their required arguments and defaults, with room-local `say` as the first fully live implementation and `whisper` / `tell` reserved in the shared communication model.
- [x] Decide and document the order of appearance for in-room chat lines, speaker attribution, and how co-located listeners see the `SAY` payload (e.g., prefixed with nick vs. `PlayerName says ...`). Capture at least one Telnet and one WebSocket transcript showing `SAY` reaching two clients plus a nearby NPC echo.
- [x] Add a short subsection to the [Game Session Service](../../architecture/microservices/game-session-service/README.md) and [Game Logic Service](../../architecture/microservices/game-logic-service/README.md) design docs (noting the [Social/Groups Service](../../architecture/microservices/social-groups-service/README.md) stub) describing how the initial room-speech requests flow through Game Session -> Game Logic -> Social/Groups services and how the shared communication model should expand later without replacing this path.
- [x] Confirm the design docs reiterate `SAY` requires the same authenticated session guard already pulled into `LOOK` and that unauthenticated clients receive `ERROR NOT_AUTHENTICATED`.

###### source-04-task-list-chat-and-social-vertical-slice-1-167: In-room `SAY` ordering and transcripts

Document that the initial communication slices render canonical actor prose directly for the initiating player while still preserving deterministic downstream type and recipient metadata for logging and regression assertions.

For future communication modes, treat this structured payload as an implementation/testing surface rather than the only long-term player-facing UX. The long-term model should preserve a distinction between:

- the communication intent and its target/scope,
- the resolved recipient set (including observers/interceptors),
- and the per-recipient narrative/presentation that players actually see.
- For observers and interceptors specifically, the communication type should define the baseline observability semantics while the target/scope and recipient capabilities decide who qualifies and what that qualified observer perceives.

Supply at least two transcripts (one Telnet-style, one WebSocket-style) that highlight this ordering and show the chat hitting two active clients plus a nearby NPC echo. For example:

Telnet - Emberline's client (emitter):

```text
SAY Hello travelers
You say, "Hello travelers"
```

Telnet - Emberline whispering to Sora:

```text
WHISPER Sora Keep quiet
You whisper to Sora, "Keep quiet"
```

Telnet - Emberline telling Sora directly:

```text
TELL Sora Meet me at the forge
You tell Sora, "Meet me at the forge"
```

The initiating-player transcript is now direct prose, while room listeners receive canonical prose derived from the same shared communication payload and Game Logic plus Social & Groups still exchange deterministic type and recipient metadata for ordering, audit, and later fanout behavior.

WebSocket - Emberline's connection (emitter) observes the same initiating-player transcript, while Social & Groups receives the normalized metadata:

```json
{
  "event": "chat",
  "command": "SAY",
  "actorView": "You say, \"Hello travelers\"",
  "deliveredTo": ["Emberline", "Sora", "Kobold Scout"],
  "message": "Hello travelers"
}
```

These transcripts demonstrate how both transports preserve the same initiating-player prose, canonical listener prose, and deterministic downstream metadata, ensuring regression suites can assert parity between Telnet and WebSocket experiences.

##### source-04-task-list-chat-and-social-vertical-slice-1-167: 2. Game Logic Service: Communication Aggregation

- [x] Introduce a Game Logic gRPC entry for the first communication mode and shape its documentation so later communication intents can carry communication type, target/scope, and delivery metadata instead of hard-coding room speech as the permanent abstraction. All communication actions should conceptually enter through Game Logic, even when downstream services such as Social & Groups own parts of audience validation, history, moderation, or delivery fanout.
- [x] Implement the initial room-speech handler to validate message length, call the Social/Group facades (or stubbed in-memory broadcaster), and emit failure statuses when the service refuses (e.g., `PERMISSION_DENIED` for silenced players).
- [x] Add unit tests covering successful `SAY` (single recipient + multiple recipients), message validation failures, and propagation of backend errors (Social service unavailable).
- [x] Document the communication API in the Game Logic design doc with its responsibilities, especially the distinction between communication act, target/scope, recipient resolution, and recipient-facing presentation, and spell out that logical failures such as `PERMISSION_DENIED` or backend `UNAVAILABLE` return structured `ErrorDetail` objects so Game Session can map them to `ERROR COMMUNICATION_NOT_DELIVERED ...` while keeping the communication metrics aligned.

##### source-04-task-list-chat-and-social-vertical-slice-1-167: 3. Game Session Service: Wiring the Initial Text Communication Mode

- [x] Extend the `TextCommandInterpreter` neighborhood so the text communication commands route to `CommunicationCommandHandler`, which translates tokens+session context into `SendCommunication` gRPC calls while the interpreter reuses the same authenticated session guard before issuing communication requests.
- [x] Map Game Logic communication errors into the text protocol (`ERROR COMMUNICATION_NOT_DELIVERED ...`), preserve canonical actor prose when delivery succeeds, and emit `gamesession.command.say.*`, `gamesession.command.whisper.*`, and `gamesession.command.tell.*` metrics/logs tagged by tenant and error codes.
- [x] Add unit/integration tests for `CommunicationCommandHandler` using stubbed Game Logic clients to cover success and error branches, while keeping the handler shape open for later explicit communication modes such as observer-aware `whisper`, richer `tell`, and later settings-driven `shout`.

##### source-04-task-list-chat-and-social-vertical-slice-1-167: 4. Cross-Service Chat Regression Tests

- [x] Extend the WebSocket cross-service suite so a second WebSocket client (or Telnet proxy replay) joins the room, a `SAY` command is issued, and all participants observe the canonical transcript plus `gamesession.command.say.*` metrics (reuse a shared fixture for the transcript).
- [x] Add a Telnet variant that verifies the Telnet transcript matches the WebSocket output up to framing differences and that `ERROR COMMUNICATION_NOT_DELIVERED` appears exactly once when the Game Logic backend rejects the message.
- [x] Capture the canonical SAY transcript fixture (similar to LOOK) so both WebSocket and Telnet suites assert the same reference output.
- [x] Instrument these flows (via log capture/metrics) to assert the chat command traversed Game Session → Game Logic and triggered Social/Group service calls, ensuring `gamesession.command.say.invocations`/`failures` counters move for both success and failure paths.
- [x] Wire the chat regression suites into the existing `crossServiceTest` targets and mention the new tests in the README/test docs so they can be run locally and in CI.
- [x] Capture a failure-mode transcript (Social/Groups unavailable or other backend error) so the regression docs show what `ERROR COMMUNICATION_NOT_DELIVERED` looks like over both transports.

###### source-04-task-list-chat-and-social-vertical-slice-1-167: Failure-mode transcript

Document a short Telnet + WebSocket transcript triggered by a backend failure (e.g., Social & Groups stub returns `PERMISSION_DENIED` or is unreachable) so regression suites can assert the error response shape:

- **Telnet emitter (Emberline)**:

```text
SAY Hello travelers
ERROR COMMUNICATION_NOT_DELIVERED Backend unavailable
```

- **Telnet listener (Sora)**:

```text
ERROR COMMUNICATION_NOT_DELIVERED Unable to reach chat service
```

- **WebSocket emitter/listener**:

```json
{
  "event": "chat",
  "command": "ERROR COMMUNICATION_NOT_DELIVERED",
  "speaker": "Emberline",
  "error": {
    "code": "UNAVAILABLE",
    "message": "Social service unreachable"
  }
}
```

These transcripts make it easy to add regression assertions for failure paths and ensure both transports handle backend outages in the same way, explicitly ensuring the flow is documented as: Social service returns `UNAVAILABLE` → Game Logic responds with an application error carrying `ErrorDetail`(code=`UNAVAILABLE`) → Game Session translates that into `ERROR COMMUNICATION_NOT_DELIVERED ...` for Telnet and `"command": "ERROR COMMUNICATION_NOT_DELIVERED", "error": {"code":"UNAVAILABLE", ...}` for WebSocket.

##### source-04-task-list-chat-and-social-vertical-slice-1-167: 5. Developer Workflows and Instrumentation

- [x] Create or update the WebSocket + Telnet example script (or documented sequence) that demonstrates `LOGIN` + `SAY` against the sample world and references the canonical transcript fixture described in `design/project-management/slice-support/chat-say-developer-guide.md`.
- [x] Update logging/monitoring docs (look instrumentation, logging & admin sections) to mention the new `gamesession.command.say.*` metrics.
- [x] Add a short "Implementation status" note to the Game Session/Game Logic/Social design docs so folks know what of this slice is live, stubbed, or deferred (e.g., channel filters, listening area heuristics).

##### source-04-task-list-chat-and-social-vertical-slice-1-167: 6. Final QA Checklist

- Run the canonical Telnet and WebSocket flows manually (`WORLDS`, `LOGIN`, `PLAY`, `LOOK`, `SAY`) and verify the transcripts match the documented samples plus the `gamesession.command.say.*` counters increment.
- Inspect `/actuator/prometheus` during regression runs to confirm both `gamesession.command.look.*` and `gamesession.command.say.*` metrics move as expected.
- Mention the new SAY regression suites in the PR/README so reviewers know to run `./gradlew crossServiceTest` before merging.

---

Note: After completing tasks in this checklist, reconcile any overlapping items in the existing per-service status docs and design docs so the architecture docs reflect the new chat slice instead of duplicating details.

##### source-04-task-list-chat-and-social-vertical-slice-1-167: Future Follow-On Scope

- A later communication slice should split the current `SAY` family into explicit speech-mode and audience-scope concepts so delivery is not permanently modeled as a single room-local broadcast with alias decoration.
- The preferred target-state is a configurable communication system where in-world communication targets scope objects such as rooms or areas, and those targets resolve both normal recipients and observer/interceptor recipients such as spies or eavesdroppers.
- Candidate future behaviors include:
  - target-limited `WHISPER` / `TELL` with sender, recipient, and optional overhear rules;
  - broader `SHOUT` semantics with configurable propagation across area, region, map, or continent boundaries once the game-settings/configuration model is designed properly;
  - guild, party, or other channel-backed speech that shares infrastructure with room speech without inheriting the same delivery rules;
  - game-defined speech variants that change formatting, moderation policy, or routing without requiring one-off pipelines.
<!-- /migration-source -->

### source-04-1-task-list-shared-communication-infrastructure-vertical-slice-1-75

#### Shared Communication Infrastructure Vertical Slice Task List - Shared communication runtime (source lines 1-75)

##### Preserved Source Text: source-04-1-task-list-shared-communication-infrastructure-vertical-slice-1-75

<!-- migration-source path="design/project-management/vertical-slices/04.1-task-list-shared-communication-infrastructure-vertical-slice.md" lines="1-75" sha256="688b67e75abf5cfec2926f2b08f9dadc34a75eea7c1de03dbcbd52f2942a4feb" heading-offset="3" -->
#### source-04-1-task-list-shared-communication-infrastructure-vertical-slice-1-75: Shared Communication Infrastructure Vertical Slice Task List

##### source-04-1-task-list-shared-communication-infrastructure-vertical-slice-1-75: Goal and Status

Goal: replace the old `SAY`-centric implementation shape with a shared communication infrastructure that all gameplay communication actions can use, while keeping room-local `say` as the first fully live mode. Status: live for the baseline `say`/`whisper`/`tell` path; richer observer/interceptor delivery and any broader propagation modes remain later work.

This follow-up slice builds on the original chat/social slice by introducing the minimum shared model needed for future `whisper`, `tell`, observer/interceptor mechanics, and later configurable communication types without rewriting the pathway a second time.

Canonical design assumptions for this slice:

- all communication actions enter through Game Logic;
- Game Logic owns communication intent handling, target/scope resolution, gameplay interception/perception rules, and final delivery outcome assembly;
- Social & Groups remains the downstream owner for moderation, durable history, membership checks, and delivery fanout where those concerns apply;
- the first standard built-ins are `say`, `whisper`, and `tell`;
- `shout` remains a future built-in and is explicitly deferred until the game-settings model can describe topology-dependent propagation cleanly.

##### source-04-1-task-list-shared-communication-infrastructure-vertical-slice-1-75: 1. Shared Communication Model

- [x] Define a minimal shared `CommunicationIntent`/`CommunicationEnvelope` contract in Game Logic that can represent:
  - communication type,
  - actor identity,
  - content payload,
  - target/scope kind,
  - target reference,
  - and recipient-view metadata.
- [x] Ensure the design and proto contracts separate:
  - communication act/type,
  - target/scope,
  - resolved recipient candidates,
  - and per-recipient presentation/view.
- [x] Document the baseline observability split clearly:
  - communication type defines baseline observability,
  - target/scope resolves qualified listeners and observers,
  - recipient capabilities/effects determine full-content vs partial-content vs metadata-only views.
- [x] Update the Game Logic and Social & Groups design docs so they explicitly describe one shared communication model rather than a permanent `BroadcastSay`-only pathway.

##### source-04-1-task-list-shared-communication-infrastructure-vertical-slice-1-75: 2. Game Logic Orchestration Path

- [x] Refactor the current room-speech path in Game Logic so it routes through the shared communication infrastructure internally.
- [x] Make target/scope resolution explicit for room-local `say`, rather than treating the current room recipient set as an ad hoc special case.
- [x] Keep current `SAY` behaviour stable while routing it through the new communication envelope and target-resolution path.
- [x] Add unit tests that assert:
  - `say` uses the shared communication pipeline,
  - target resolution happens before downstream delivery,
  - and downstream Social & Groups calls receive explicit communication metadata rather than relying on verb-name inference alone.

##### source-04-1-task-list-shared-communication-infrastructure-vertical-slice-1-75: 3. Game Session Integration

- [x] Keep Game Session as the stage/auth gate and protocol renderer only; do not let it grow its own communication-routing rules.
- [x] Update the command-interpreter/handler boundary so the communication command handler becomes the concrete adapter onto the shared communication model rather than owning a `say`-specific delivery pipeline forever.
- [x] Preserve the canonical actor transcript and existing metrics/logs while the internal pathway changes.
- [x] Add focused tests that prove Game Session still:
  - rejects unauthenticated communication commands before they reach Game Logic,
  - emits the same `gamesession.command.say.*` metrics,
  - and renders the same success/error protocol shapes.

##### source-04-1-task-list-shared-communication-infrastructure-vertical-slice-1-75: 4. Social & Groups Downstream Contract

- [x] Update the Social & Groups service contract so it is documented as receiving explicit communication metadata from Game Logic:
  - communication type,
  - target/scope context,
  - resolved recipient or audience metadata,
  - and recipient-view/presentation directives where needed.
- [x] Confirm moderation and history ownership boundaries remain there, but make it explicit that socially rooted communications still enter through Game Logic first.
- [x] Add or update tests around the Social stub/facade so downstream delivery remains deterministic while the upstream model changes.

##### source-04-1-task-list-shared-communication-infrastructure-vertical-slice-1-75: 5. Regression and Documentation

- [x] Re-run the current WebSocket/Telnet `SAY` regressions and ensure they still pass unchanged after the internal refactor.
- [x] Update the communication-related docs and service-status notes to describe the shared communication infrastructure as live once this slice lands.
- [x] Add a short note to the slice docs and architecture docs that `shout` is intentionally deferred until the game-settings/configuration model is designed.

---

Note: After completing this slice, reconcile overlapping communication notes in the original `04` slice, Game Session protocols, Game Logic API contracts, Social & Groups contracts, and any service-status docs so the shared communication infrastructure becomes the canonical current description.
<!-- /migration-source -->

### source-04-2-task-list-whisper-vertical-slice-1-93

#### Whisper Communication Vertical Slice Task List - Whisper command behavior (source lines 1-93)

##### Preserved Source Text: source-04-2-task-list-whisper-vertical-slice-1-93

<!-- migration-source path="design/project-management/vertical-slices/04.2-task-list-whisper-vertical-slice.md" lines="1-93" sha256="8a5b42a6810e29aade6705868cd0f4ac6101c6d6f0a7b674f9aeea1539c1637e" heading-offset="3" -->
#### source-04-2-task-list-whisper-vertical-slice-1-93: Whisper Communication Vertical Slice Task List

##### source-04-2-task-list-whisper-vertical-slice-1-93: Goal and Status

Goal: implement the first target-directed in-room communication mode, `whisper`, on top of the shared communication infrastructure. Status: baseline live for sender-side transcript, same-room target validation, and downstream delivery metadata; richer observer/interceptor outcomes remain deferred to `04.4`.

Default `whisper` semantics agreed so far:

- `whisper` targets one character in the current room;
- sender and target receive full content;
- normal bystanders receive nothing by default;
- observer/interceptor recipients receive metadata-only by default unless a capability/effect explicitly upgrades what they perceive.

##### source-04-2-task-list-whisper-vertical-slice-1-93: 1. Protocol and UX

- [x] Update the Minimal Text Command Protocol so `WHISPER <character> <text>` is documented as a real target-directed built-in command rather than a cosmetic alias of room speech.
- [x] Define the canonical player-facing transcript/prose for:
  - sender,
  - target,
  - normal bystanders,
  - and metadata-only observers.
- [x] Document the default failure cases:
  - target not present,
  - target invalid,
  - target unavailable,
  - muted/silenced sender,
  - and generic backend failure.

Canonical baseline prose:

- sender: `You whisper to Sora, "Keep quiet"`
- target: `Emberline whispers to you, "Keep quiet"`
- normal bystanders: no transcript by default
- metadata-only observer: `Emberline whispers something to Sora.`

Default failure mapping:

- target not present in the current room or target name invalid: `ERROR COMMUNICATION_NOT_DELIVERED Target not present in room: <name>`
- target unavailable for direct resolution: `ERROR INVALID_ARGUMENT Target is not available: <name>`
- muted or silenced sender: `ERROR COMMUNICATION_NOT_DELIVERED silenced`
- unexpected downstream failure: `ERROR COMMUNICATION_NOT_DELIVERED <backend message>` or `Game Logic unavailable` when the upstream gRPC call itself fails

##### source-04-2-task-list-whisper-vertical-slice-1-93: 2. Game Logic Behaviour

- [x] Implement `whisper` as a communication type with:
  - direct target in current room,
  - same-room validation,
  - baseline observability semantics,
  - and recipient-view generation.
- [x] Reuse the shared communication infrastructure introduced in `04.1`, not a one-off whisper handler.
- [x] Ensure target resolution is explicit and room-scoped rather than implicit string matching buried in a renderer.
- [x] Add unit tests that cover:
  - success,
  - target missing,
  - target not in room,
  - metadata-only observer outcome,
  - and backend failure propagation.

##### source-04-2-task-list-whisper-vertical-slice-1-93: 3. Game Session and Protocol Rendering

- [x] Add or refine a `WhisperCommandHandler` (or equivalent command-to-intent adapter) in Game Session that uses the shared communication pathway rather than special-casing delivery there.
- [x] Define the canonical text-protocol success/error mapping for `whisper`.
- [x] Keep stage/auth gating in the interpreter layer, not inside the domain renderer.
- [x] Add unit/integration tests proving `WHISPER` behaves consistently across WebSocket and Telnet protocol rendering.

##### source-04-2-task-list-whisper-vertical-slice-1-93: 4. Social & Groups Participation

- [x] Ensure Social & Groups receives enough metadata to persist/log/direct the communication properly without needing to infer room semantics from the verb alone.
- [x] Confirm moderation and history treatment for `whisper` is documented distinctly from room-local `say`.
- [x] Decide whether metadata-only observer outcomes are persisted distinctly for audit/history or only for runtime delivery, and document the result.

Current baseline decision:

- metadata-only observer outcomes are runtime-delivery semantics only in the baseline `whisper` slice;
- durable observer/interceptor history remains deferred to `04.4`, where hidden-observer moderation and audit requirements will be designed explicitly instead of being implied by the first `whisper` implementation.

##### source-04-2-task-list-whisper-vertical-slice-1-93: 5. Cross-Service Regressions

- [x] Add cross-service regression coverage for:
  - successful `whisper`,
  - invalid target,
  - and muted/silenced sender.
- [x] Capture canonical transcripts for sender and target, plus at least one observer metadata-only outcome.
- [x] Ensure regressions still prove the intended service path:
  - Game Session -> Game Logic -> Social & Groups.

Current fixture note:

- canonical sender, target, and metadata-only observer transcript fixtures now live in [ChatTestFixtures.java](../../../services/game-session-service/src/testFixtures/java/net/firedevops/firemud/gamesession/test/ChatTestFixtures.java) so later recipient-push and observer slices can reuse the same wording instead of inventing a second transcript style.

---

Note: After completing this slice, reconcile any old docs that still describe `WHISPER` as a lightweight alias of room-local `SAY`.
<!-- /migration-source -->

### source-04-3-task-list-tell-vertical-slice-1-69

#### Tell Communication Vertical Slice Task List - Tell command behavior (source lines 1-69)

##### Preserved Source Text: source-04-3-task-list-tell-vertical-slice-1-69

<!-- migration-source path="design/project-management/vertical-slices/04.3-task-list-tell-vertical-slice.md" lines="1-69" sha256="2ac4968388ba912c367a50a47188ae7d5fdd9a61cb9862f836e98b9a47feee1b" heading-offset="3" -->
#### source-04-3-task-list-tell-vertical-slice-1-69: Tell Communication Vertical Slice Task List

##### source-04-3-task-list-tell-vertical-slice-1-69: Goal and Status

Goal: implement the first direct non-room-scoped communication mode, `tell`, on top of the shared communication infrastructure. Status: baseline live for online target resolution, sender-side transcript, and downstream direct-recipient metadata; richer interception or offline-message behavior remains future work.

Default `tell` semantics agreed so far:

- `tell` is a direct character-to-character communication action;
- it is not room-scoped by default;
- sender and target receive full content;
- no observer path exists by default unless a game rule/capability explicitly enables one;
- this built-in gameplay `tell` is live/online only and does not collapse into mail.

##### source-04-3-task-list-tell-vertical-slice-1-69: 1. Protocol and UX

- [x] Add `TELL <character> <text>` to the Minimal Text Command Protocol as a standard built-in communication action.
- [x] Define the canonical prose/transcript for:
  - sender,
  - target,
  - and failure outcomes such as target offline or unreachable.
- [x] Document explicitly that `tell` is not mail and does not imply asynchronous delivery.

##### source-04-3-task-list-tell-vertical-slice-1-69: 2. Game Logic Behaviour

- [x] Implement `tell` as a communication type using the shared communication infrastructure from `04.1`.
- [x] Resolve the direct target through authoritative gameplay/account/character identity rules rather than through ad hoc parser shortcuts.
- [x] Keep `tell` outside room scope by default while still flowing through Game Logic so gameplay interception/perception rules can later participate if a game adds that capability.
- [x] Add unit tests covering:
  - successful live tell,
  - target offline,
  - invalid target,
  - muted/silenced sender,
  - and backend failure propagation.

##### source-04-3-task-list-tell-vertical-slice-1-69: 3. Social & Groups Participation

- [x] Use Social & Groups for the parts it owns:
  - direct-message delivery fanout,
  - moderation,
  - durable history if configured,
  - and presence or membership checks where applicable.
- [x] Document the contract so Social & Groups receives explicit communication metadata and does not become an alternative top-level execution path.
- [x] Keep the design open for future gameplay interception or surveillance mechanics on direct tells without requiring a path rewrite.

##### source-04-3-task-list-tell-vertical-slice-1-69: 4. Game Session and Rendering

- [x] Add or refine a `TellCommandHandler` (or equivalent adapter) in Game Session that packages the communication action and delegates all domain behaviour to Game Logic.
- [x] Define canonical protocol-level success and failure shapes for `tell`.
- [x] Add WebSocket and Telnet rendering tests that prove the same semantics over both transports.

##### source-04-3-task-list-tell-vertical-slice-1-69: 5. Cross-Service Regressions

- [x] Add cross-service coverage for:
  - successful live tell,
  - target offline,
  - target invalid,
  - and backend failure.
- [x] Capture canonical transcript fixtures for sender and target.
- [x] Confirm the regression flow still demonstrates:
  - Game Session -> Game Logic -> Social & Groups.

Current fixture note:

- canonical sender and target `tell` transcript fixtures now live in [ChatTestFixtures.java](../../../services/game-session-service/src/testFixtures/java/net/firedevops/firemud/gamesession/test/ChatTestFixtures.java) so later recipient-push delivery can reuse the same wording and avoid protocol drift between transports.

---

Note: After completing this slice, reconcile any old docs or examples that imply all non-room speech should bypass Game Logic and go “straight to Social & Groups.”
<!-- /migration-source -->

### source-04-4-task-list-communication-observers-and-interceptors-vertical-slice-1-90

#### Communication Observers and Interceptors Vertical Slice Task List - Communication observer and interceptor behavior (source lines 1-90)

##### Preserved Source Text: source-04-4-task-list-communication-observers-and-interceptors-vertical-slice-1-90

<!-- migration-source path="design/project-management/vertical-slices/04.4-task-list-communication-observers-and-interceptors-vertical-slice.md" lines="1-90" sha256="817b96c37bf853ab6fe95ff1182480db75f3ec16a84e18ee3429915fe543ec3b" heading-offset="3" -->
#### source-04-4-task-list-communication-observers-and-interceptors-vertical-slice-1-90: Communication Observers and Interceptors Vertical Slice Task List

##### source-04-4-task-list-communication-observers-and-interceptors-vertical-slice-1-90: Goal and Status

Goal: implement the first real observer/interceptor behaviour on top of the shared communication infrastructure so eavesdropping, spy-style skills, magical listening, and similar mechanics stop being theoretical design notes. Status: baseline live for metadata-only `whisper` observer resolution in Game Logic, including live recipient-side delivery over generic WebSocket and Telnet.

Canonical observer-perception model already agreed:

- communication type defines baseline observability;
- target/scope resolves which normal listeners and observer/interceptor candidates qualify;
- recipient capabilities/effects determine whether the final view is:
  - full content,
  - partial content,
  - or metadata-only.

Implementation note:

- This slice now focuses on structured observer resolution and recipient-view metadata in the shared communication model.
- Live recipient push and reconnect screen-buffer fanout now exist for the baseline metadata-only `whisper` observer path, with Game Session rendering observer prose from structured metadata rather than reusing Game Logic-rendered text. Richer first-party/MCP-aware presentation remains part of the follow-on delivery work.

##### source-04-4-task-list-communication-observers-and-interceptors-vertical-slice-1-90: 1. First Observer Capability

- [x] Choose one small concrete observer/interceptor mechanic to implement first (for example, metadata-only overhear on `whisper` or a simple eavesdropping flag/capability) rather than trying to build every surveillance mechanic at once.
- [x] Document the exact player-facing prose for:
  - sender,
  - target,
  - normal bystanders,
  - and the observer.
- [x] Confirm the observer outcome is deterministic and testable, not dependent on undocumented flavor rules.

Current chosen mechanic:

- `WHISPER` now supports a metadata-only observer outcome for room entities flagged with `observer_metadata_only`.
- sender: `You whisper to Sora, "Keep quiet"`
- target: `Emberline whispers to you, "Keep quiet"`
- normal bystanders: no transcript by default
- observer: `Emberline whispers something to Sora.`

##### source-04-4-task-list-communication-observers-and-interceptors-vertical-slice-1-90: 2. Game Logic Resolution

- [x] Extend the shared communication pipeline so target resolution can return:
  - ordinary recipients,
  - observer/interceptor candidates,
  - and a recipient-view classification per resolved recipient.
- [x] Keep this as a structured extension of the communication model rather than attaching extra text strings ad hoc at the edge.
- [x] Add unit tests that prove:
  - observer qualification,
  - metadata-only vs full-content outcomes,
  - and non-observing bystanders staying blind where intended.

##### source-04-4-task-list-communication-observers-and-interceptors-vertical-slice-1-90: 3. Game Session and Protocol Rendering

- [x] Keep the sender/target/player-facing narrative prose separate from the internal resolved-recipient metadata used for tests and debugging.
- [x] Deliver the baseline metadata-only observer view to live non-actor recipients over generic WebSocket and Telnet using the shared recipient-delivery path.
- [x] Add protocol examples for the observer case at the documentation level and keep richer first-party/MCP-aware presentation as later work.

##### source-04-4-task-list-communication-observers-and-interceptors-vertical-slice-1-90: 4. Social & Groups / Audit Considerations

- [x] Decide how observer/interceptor deliveries are represented for moderation and durable history:
  - persisted distinctly,
  - not persisted,
  - or persisted with recipient-view metadata.
- [x] Document the chosen approach so future moderation/audit work does not silently lose observer deliveries or over-persist hidden mechanics.

Current baseline decision:

- observer recipient views are part of the structured Game Logic response and test/debug contract now;
- live recipient delivery reuses those metadata-only views, renders prose in Game Session, and appends the resulting transcript text to the recipient reconnect screen buffer;
- durable hidden-observer history and moderation treatment are still not persisted as a distinct moderation/audit ledger.

##### source-04-4-task-list-communication-observers-and-interceptors-vertical-slice-1-90: 5. Regression Coverage

- [x] Add focused tests for the chosen first observer mechanic at the Game Logic aggregation layer.
- [x] Ensure the regressions prove the intended layered model:
  - communication type baseline,
  - target qualification,
  - recipient capability deciding final perceived view.

Current implementation note:

- The foundational proof for this slice still lives at the Game Logic aggregation layer.
- Cross-service coverage now also proves the baseline observer delivery path over generic WebSocket and Telnet.

---

Note: The current implemented observer model is intentionally narrow:

- only metadata-only `whisper` observers are baseline live;
- richer observer outcomes like partial-content or full-content interception remain future work;
- game-configurable observer policies are still deferred.
<!-- /migration-source -->

### source-04-5-task-list-communication-recipient-delivery-vertical-slice-1-46

#### Communication Recipient Delivery Vertical Slice Task List - Recipient communication delivery (source lines 1-46)

##### Preserved Source Text: source-04-5-task-list-communication-recipient-delivery-vertical-slice-1-46

<!-- migration-source path="design/project-management/vertical-slices/04.5-task-list-communication-recipient-delivery-vertical-slice.md" lines="1-46" sha256="9637d2afb35b69db3e7238ab32581fb2d3e4b76ca59fd457079f2979ff0f8197" heading-offset="3" -->
#### source-04-5-task-list-communication-recipient-delivery-vertical-slice-1-46: Communication Recipient Delivery Vertical Slice Task List

##### source-04-5-task-list-communication-recipient-delivery-vertical-slice-1-46: Goal and Status

Goal: turn the structured per-recipient communication views from `04.4` into real recipient-side runtime delivery so targets and observers can receive their own rendered transcripts over Telnet, WebSocket, and later first-party/MCP-aware clients. Status: baseline live for generic WebSocket and Telnet recipient delivery; first-party/MCP-aware presentation remains future work.

This follow-up exists because the current shared communication path only returns actor-side output synchronously. `04.4` establishes the authoritative recipient-view model first; this slice makes that model visible to non-actor clients.

##### source-04-5-task-list-communication-recipient-delivery-vertical-slice-1-46: 1. Delivery Boundary

- [x] Decide and document the runtime path for recipient-side communication delivery:
  - direct Game Session push,
  - Social & Groups-mediated fanout,
  - or another bounded internal bus.
- [x] Ensure Game Session consumes structured recipient views from Game Logic and renders target/observer prose locally instead of depending on pre-rendered downstream strings.

##### source-04-5-task-list-communication-recipient-delivery-vertical-slice-1-46: 2. Target Delivery

- [x] Deliver target-side views for `WHISPER` and `TELL` to live recipients.
- [x] Ensure sender, target, and non-recipients each receive only the intended transcript.
- [x] Add WebSocket and Telnet examples proving target-side delivery.

##### source-04-5-task-list-communication-recipient-delivery-vertical-slice-1-46: 3. Observer Delivery

- [x] Deliver metadata-only observer views for the `WHISPER` observer mechanic from `04.4`.
- [x] Ensure observers do not receive full content unless a later capability explicitly upgrades them.
- [x] Keep the observer path separately testable from ordinary target delivery.

##### source-04-5-task-list-communication-recipient-delivery-vertical-slice-1-46: 4. Persistence and Audit

- [x] Decide whether recipient-side target and observer deliveries are persisted distinctly, transient only, or persisted with recipient-view metadata.
- [x] Align Social & Groups and moderation docs with the chosen delivery/persistence rule.

Recipient-side delivery is currently persisted as ordinary per-recipient screen-buffer transcript context, not as a separate durable moderation/audit ledger. Social & Groups still receives the canonical communication act for social ownership and moderation concerns; Game Session reuses the structured recipient metadata from Game Logic and renders recipient prose itself for live delivery and reconnect context.

##### source-04-5-task-list-communication-recipient-delivery-vertical-slice-1-46: 5. Regression Coverage

- [x] Add focused cross-service tests for sender, target, and observer delivery over both Telnet and WebSocket.
- [x] Confirm the service path remains coherent:
  - Game Session -> Game Logic -> Social & Groups where applicable -> recipient delivery back through Game Session.

##### source-04-5-task-list-communication-recipient-delivery-vertical-slice-1-46: Implementation Notes

- Generic WebSocket and Telnet delivery are live through the Game Session active transport-session registry; Telnet benefits automatically because the proxy is already a bridged WebSocket upstream.
- Recipient-side delivery currently targets actor-selected gameplay sessions in the same gameplay instance, renders recipient prose in Game Session from metadata-only views, and appends the resulting transcript text into that recipient's reconnect screen buffer.
- First-party web/MCP-aware client presentation remains future work, where recipient delivery should integrate with structured prompt/status output rather than assuming plain transcript-only rendering.
<!-- /migration-source -->

### source-04-6-task-list-in-game-help-system-vertical-slice-1-98

#### In-Game Help System Vertical Slice - Player HELP behavior (source lines 1-98)

##### Preserved Source Text: source-04-6-task-list-in-game-help-system-vertical-slice-1-98

<!-- migration-source path="design/project-management/vertical-slices/04.6-task-list-in-game-help-system-vertical-slice.md" lines="1-98" sha256="d0e03b5ec286ba5ec487b1a32174f43d571a3805810a831f05ad2fe52a30dec5" heading-offset="3" -->
#### source-04-6-task-list-in-game-help-system-vertical-slice-1-98: In-Game Help System Vertical Slice

##### source-04-6-task-list-in-game-help-system-vertical-slice-1-98: Goal and Status

Add a real in-game `HELP` system with one canonical default help corpus, editable game-specific help topics, and alias/tag-based topic lookup so players can discover commands and gameplay concepts without out-of-band instructions. Status: partially implemented.

##### source-04-6-task-list-in-game-help-system-vertical-slice-1-98: Checklist

- [x] Define target-state behavior and scope.
- [ ] Implement the slice end-to-end.
- [ ] Verify and close any follow-ups.

##### source-04-6-task-list-in-game-help-system-vertical-slice-1-98: Why This Slice Exists

The current playable loop exposes command discovery gaps:

- the built-in `HELP` command is live for platform-default topics but still code-backed and limited to shared defaults
- there is no game-authored topic system yet for game-specific lore or commands
- later games will need help text that is editable rather than hard-coded into one shared service binary
- players commonly expect alias/topic lookup such as:
  - `HELP MOVE`
  - `HELP MOVEMENT`
  - `HELP WALK`
  - shorthand and related-topic resolution through tags rather than one exact page key

This needs a dedicated slice before implementation so the repo converges on one deliberate help model instead of growing ad hoc command-specific text and duplicated onboarding prose.

##### source-04-6-task-list-in-game-help-system-vertical-slice-1-98: Scope

- Define one canonical runtime `HELP` command behavior for:
  - pre-login/lobby command help
  - in-world gameplay topic help
- Define the default built-in help corpus the platform ships with for common topics such as:
  - login
  - worlds
  - play
  - movement
  - look
  - communication
- Define how editable game-specific help content overrides or extends the default corpus.
- Define topic lookup rules using canonical topic ids plus aliases/tags.
- Define the player-facing behavior for:
  - `HELP`
  - `HELP <topic>`
  - unknown topic lookup
  - ambiguous or aliased topic lookup
- Add acceptance-oriented slices/tests later for runtime delivery over Telnet, generic WebSocket, and first-party web clients.

##### source-04-6-task-list-in-game-help-system-vertical-slice-1-98: Out of Scope

- Full implementation of the `HELP` runtime path in this slice.
- Rich authoring UI for help-page editing.
- Search ranking, fuzzy semantic search, or AI-generated live help answers.
- Broader command parser redesign beyond what the `HELP` topic lookup contract needs.

##### source-04-6-task-list-in-game-help-system-vertical-slice-1-98: Architecture Notes

- The help system should have one canonical topic model, not separate hard-coded help text per transport.
- Default help content should exist even when a game has not authored its own help corpus yet.
- Game-specific help content should be editable data, not code changes.
- Topic lookup should support aliases/tags so multiple player phrasings can resolve to one canonical help page.
- The lookup model should stay deterministic and operator-debuggable:
  - canonical topic id
  - alias/tag list
  - explicit precedence between built-in defaults and game-authored topics
- `HELP` should work at different command stages, but the returned topics may differ by stage.
- Unknown-topic behavior should be deliberate and player-friendly rather than silent no-op behavior.

##### source-04-6-task-list-in-game-help-system-vertical-slice-1-98: Open Design Questions

- Whether built-in help topics live in shared authored-content storage, static seed data, or another editable content home.
- Whether game-specific help overrides built-in topics by canonical topic id, by explicit precedence rules, or only extends them.
- Whether tags and aliases are exact normalized tokens only or later support broader matcher rules.
- How much stage-awareness the help index needs:
  - pre-login only
  - logged-in lobby
  - in-world gameplay
- Whether related-topic links are a first slice requirement or a later enhancement.

##### source-04-6-task-list-in-game-help-system-vertical-slice-1-98: Acceptance Shape

- A future implementation can support:
  - `HELP` with a useful default overview
  - `HELP <topic>` with canonical topic resolution
  - alias/tag resolution such as `HELP MOVE` reaching the movement page
- The repo has one explicit target-state design for:
  - default help corpus
  - game-specific editable help content
  - topic alias/tag lookup
  - unknown-topic handling
- The eventual implementation path is clear enough that later slices can focus on delivery and storage rather than re-arguing the core model.

##### source-04-6-task-list-in-game-help-system-vertical-slice-1-98: Follow-On

- Runtime `HELP` command implementation and transcript delivery
- Default built-in help corpus authoring
- Game-specific editable help-page storage and moderation/admin editing path
- Topic alias/tag normalization and lookup tests
<!-- /migration-source -->

### source-04-7-task-list-speech-normalization-and-dialogue-presentation-vertical-slice-1-105

#### Speech Normalization and Dialogue Presentation Vertical Slice - Speech normalization and dialogue presentation (source lines 1-105)

##### Preserved Source Text: source-04-7-task-list-speech-normalization-and-dialogue-presentation-vertical-slice-1-105

<!-- migration-source path="design/project-management/vertical-slices/04.7-task-list-speech-normalization-and-dialogue-presentation-vertical-slice.md" lines="1-105" sha256="0de4a581a985b4ce5c4db0469cbf8bc144d007ca4d2cff17952a42ef29469aee" heading-offset="3" -->
#### source-04-7-task-list-speech-normalization-and-dialogue-presentation-vertical-slice-1-105: Speech Normalization and Dialogue Presentation Vertical Slice

##### source-04-7-task-list-speech-normalization-and-dialogue-presentation-vertical-slice-1-105: Goal and Status

Define one canonical policy for how player-entered spoken text is normalized for presentation so speech reads like deliberate dialogue without silently mutating meaning or creating transport-specific inconsistencies. Status: complete at the current `say` / `whisper` / `tell` boundary.

##### source-04-7-task-list-speech-normalization-and-dialogue-presentation-vertical-slice-1-105: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end-to-end.
- [x] Verify and close any follow-ups.

##### source-04-7-task-list-speech-normalization-and-dialogue-presentation-vertical-slice-1-105: Implementation Notes

The first bounded rollout is live for:

- `say`
- `whisper`
- `tell`

Current behavior uses one shared conservative speech normalizer in `game-session-service`:

- trim surrounding whitespace;
- capitalize the first alphabetic character when needed;
- append terminal punctuation only when it is missing;
- preserve intentional punctuation when it is already present.

Live proof now covers both actor and recipient presentation surfaces:

- actor prose for `say`, `whisper`, and `tell`;
- live listener/target prose for room-local `say`, `whisper`, and `tell`;
- shared transport parity through websocket and telnet communication regression suites.

What remains outside this slice is broader channel adoption and the final transcript-storage/raw-vs-rendered policy around normalized speech.

##### source-04-7-task-list-speech-normalization-and-dialogue-presentation-vertical-slice-1-105: Why This Slice Exists

Current speech output exposes a presentation-policy gap:

- `say` currently preserves raw player casing and punctuation too literally
- simple player input such as `say hello` can render as awkward dialogue instead of a sentence-like utterance
- the repo does not yet define whether spoken text normalization belongs in:
  - command parsing,
  - domain/event creation,
  - or final presentation rendering
- later communication modes such as `whisper`, `tell`, emotes, and NPC dialogue will face the same question

This needs explicit design before implementation so the system does not accumulate one-off command-specific rewrites or transport-specific behavior.

##### source-04-7-task-list-speech-normalization-and-dialogue-presentation-vertical-slice-1-105: Scope

- Define the normalization boundary for spoken player text.
- Define conservative default rules for dialogue-style presentation.
- Define where raw player text is preserved versus where presented text is normalized.
- Define the initial adoption set for speech normalization:
  - `say`
  - `whisper`
  - `tell`
- Define how later dialogue-like communication should either reuse or explicitly opt out of the same policy.
- Define basic acceptance behavior for:
  - leading capitalization
  - terminal punctuation
  - preserving intentional punctuation/casing when already present

##### source-04-7-task-list-speech-normalization-and-dialogue-presentation-vertical-slice-1-105: Out of Scope

- Extending the runtime normalization path beyond the initial `say` / `whisper` / `tell` adoption set.
- Broad natural-language rewriting.
- Grammar correction, spelling correction, or AI-assisted polishing.
- Full emote/social-text policy beyond what spoken-sentence normalization needs.

##### source-04-7-task-list-speech-normalization-and-dialogue-presentation-vertical-slice-1-105: Architecture Notes

- Spoken-text normalization should be conservative, not creative.
- The system should preserve player intent and avoid surprising rewrites.
- Raw command input and presented speech may need to remain distinct concepts.
- Normalization should be shared across transports so Telnet, generic WebSocket, and first-party web do not drift.
- The first implementation should adopt one shared policy across `say`, `whisper`, and `tell` together rather than landing per-command formatting rules at different times.
- The policy should be explicit enough that tests can assert exact transcript behavior.

##### source-04-7-task-list-speech-normalization-and-dialogue-presentation-vertical-slice-1-105: Open Design Questions

- Whether normalization happens before structured communication events are emitted or only at render time.
- Whether stored transcript text keeps raw input, normalized output, or both.
- Which punctuation marks count as terminal punctuation for “do not append anything” behavior.
- Whether all speech-like commands share one policy or whether channels can opt into stricter/looser rules later.
- How locale-specific sentence handling should interact with the localization/presentation model.

##### source-04-7-task-list-speech-normalization-and-dialogue-presentation-vertical-slice-1-105: Acceptance Shape

- The repo has one explicit target-state policy for spoken-text normalization.
- The first implementation can make `say`, `whisper`, and `tell` read naturally without silently over-rewriting player input.
- Later communication slices can either reuse the same normalization rules or explicitly document why a channel does not behave like spoken dialogue.

##### source-04-7-task-list-speech-normalization-and-dialogue-presentation-vertical-slice-1-105: Verification Notes

- Unit proof passed through `SpeechPresentationNormalizerTest`, `CommunicationOutputMapperTest`, and `CommunicationCommandHandlerTest` in `game-session-service`.
- Cross-service websocket proof covers normalized actor/listener or actor/target transcripts for `say`, `whisper`, and `tell` in `CommunicationWebSocketCrossServiceTest`.
- Cross-service telnet proof covers normalized actor/listener or actor/target transcripts for the same communication family in `TelnetGatewayGameSessionAccountCrossServiceIntegrationTest`.

##### source-04-7-task-list-speech-normalization-and-dialogue-presentation-vertical-slice-1-105: Follow-On

- Transcript/storage policy proving whether raw spoken input, normalized rendered speech, or both are retained in the long-term transcript model
- Extending the same normalization policy to later dialogue-like channels where appropriate
- Explicit opt-out or alternate-policy rules for channels that should not behave like spoken dialogue
<!-- /migration-source -->

### source-09-1-10-task-list-account-presence-runtime-authority-follow-through-vertical-slice-25-39

#### Account Presence Runtime Authority Follow-Through Vertical Slice - Account and friend presence readback behavior (source lines 25-39)

##### Preserved Source Text: source-09-1-10-task-list-account-presence-runtime-authority-follow-through-vertical-slice-25-39

<!-- migration-source path="design/project-management/vertical-slices/09.1.10-task-list-account-presence-runtime-authority-follow-through-vertical-slice.md" lines="25-39" sha256="d245b018446c20535610c156757466b54f67f41a10ab598981b42572f827238a" heading-offset="3" -->
##### source-09-1-10-task-list-account-presence-runtime-authority-follow-through-vertical-slice-25-39: Scope

- account and friend presence read-model validation over live gameplay presence;
- recent-presence display-name decoration where current runtime authority is consulted.

##### source-09-1-10-task-list-account-presence-runtime-authority-follow-through-vertical-slice-25-39: Out of Scope

- interactive gameplay admission and reconnect paths already covered by earlier `09.1` children;
- broader roster or social-surface policy work outside the runtime-authority seam.

##### source-09-1-10-task-list-account-presence-runtime-authority-follow-through-vertical-slice-25-39: Locked Direction

- account-level presence reads must consume singular current runtime-target authority, not selector lookup alone;
- display-name decoration is allowed only when current authority proves one complete matching pointer bundle;
- ambiguous runtime-target authority fails closed for presence freshness and decoration.
<!-- /migration-source -->

### source-09-1-3-task-list-communication-routing-availability-follow-through-vertical-slice-15-38

#### Communication Routing Availability Follow-Through Vertical Slice - Player communication delivery and availability behavior (source lines 15-38)

##### Preserved Source Text: source-09-1-3-task-list-communication-routing-availability-follow-through-vertical-slice-15-38

<!-- migration-source path="design/project-management/vertical-slices/09.1.3-task-list-communication-routing-availability-follow-through-vertical-slice.md" lines="15-38" sha256="597b72f23a433f17b13cf971e67a7f487b8a992b68a9641815dc707044fd9681" heading-offset="3" -->
One smaller seam still remained in communication:

- `CommunicationCommandHandler` used a raw `findByGameplayName(tenantId, gameInstanceId, targetName)` lookup to decide whether `TELL` targets were “online”;
- the later delivery path already normalized the resolved recipient before fan-out, so a stale gameplay binding could still look available at command-parse time and then disappear before delivery;
- the same handler still carried an unused `GameplayWorldCatalog` dependency after the surrounding routing-authority cleanup had already made it unnecessary.

This slice closes that smaller but real drift rather than leaving “online target availability” as a last raw-session exception inside the routing-fence family.

##### source-09-1-3-task-list-communication-routing-availability-follow-through-vertical-slice-15-38: Implementation Notes

- `CommunicationCommandHandler` no longer carries the dead `GameplayWorldCatalog` dependency.
- `TELL` target-availability checks now normalize the gameplay-name hit through `SessionAuthenticationService.normalizeResolvedContext(...)` before treating the target as online.
- A gameplay-name hit that collapses to a non-gameplay shell now fails closed as “Target is not available” instead of pretending delivery is possible.
- Focused unit proof now covers the stale-target normalization case directly.

##### source-09-1-3-task-list-communication-routing-availability-follow-through-vertical-slice-15-38: Scope

- gameplay communication availability checks that still trusted raw session rows after admitted routing normalization existed;
- adjacent dead routing-authority dependencies in the same handler.

##### source-09-1-3-task-list-communication-routing-availability-follow-through-vertical-slice-15-38: Out of Scope

- public world/realm browse and `PLAY` selection surfaces that intentionally still use the catalog as a browse-time UX substrate;
- broader chat/social feature growth outside the current `TELL` availability fence.
<!-- /migration-source -->
