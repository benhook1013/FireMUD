# Temporary Design Audit Checklist

Purpose: track high-level design review areas before resuming further implementation work. Expand each section with findings, open questions, and slice/doc follow-ups as the audit proceeds.

## Review Order

- [ ] Item / Holder / Instance Model (`06.x`)
- [ ] Session / Presence / Auth (`02.1.x`)
- [ ] Command / Interaction Model (`02.13.x`)
- [ ] Stats / Conditions / Effects / Actions (`07.x`, `02.13.7`, `02.13.9`, `02.13.11`)
- [ ] Output / Transcript / Help (`02.13.10`, `04.6.1`, `04.7`)
- [ ] Actor / Role / Social (`07.4`, `02.1.3`, `02.1.4`, `02.1.5`)
- [ ] Service Boundary / Runtime Hardening (`02.18.x` and related runtime slices)

## 1. Item / Holder / Instance Model (`06.x`)

Focus:

- Item-instance truth across inventory, equipment, room-ground, and containers
- Unified holder / transfer model
- Stackable / fungible item policy
- Visible refs and targeting behavior
- Prose room output vs management views

Notes:

- Review status: first pass completed
- Slice/doc drift:
  - `06-task-list-inventory-containers-equipment-vertical-slice.md` still says "planned; implementation has not started", which is no longer true.
  - The top-level `06` slice still describes the original MVP ordering instead of the current live state after `06.2`, `06.3`, `06.3.1`, and `06.4` work.
- Design/implementation alignment that looks good:
  - physical item-instance truth is now in place across inventory, equipment, room-ground, and container contents;
  - visible refs are persisted and surfaced through management views and exact-item matching;
  - `INV HERE` cleanly separates room management from ordinary room prose;
  - `06.3` and `06.3.1` largely match the current code state.
- Important remaining design gaps:
  - the top-level `06` slice needs to be rewritten around current target-state behavior rather than the original MVP-before-implementation framing;
  - `06.4` still lacks a canonical transfer operation shape, and the implementation still routes through separate service methods (`pickup`, `drop`, `wear`, `remove`, `put`, `take`) rather than one explicit source-holder -> destination-holder contract;
  - transfer audit semantics are still described as core design intent but not specified tightly enough in current live-holder terms;
  - holder-policy validation is still only partially unified: slot checks are close, but container accessibility and shared transfer validation are not yet described as one canonical rule model.
- Likely design drift or under-specification:
  - the current implementation uses direct holder fields on `item_instances` (`character`, `equipment_slot`, `game_instance_id`, `room_instance_id`, `container_instance_id`) rather than a first-class transfer object/holder contract; design docs should either bless that as the canonical runtime model or state what further abstraction is still intended;
  - `ItemCommandHandler` is a real seam, but still mostly a dispatch wrapper over inventory/equipment/container handlers, not yet a unified item-transfer command model;
  - the old `06` wording still talks about hidden/internal character inventory containers as core design language, while the live model is now more directly item-instance holder-based;
  - visible refs are well specified for management views, but the "ordinary prose surfaces" boundary should be restated once in `06` rather than split between `06.3` and `06.3.1`.
- Open follow-up questions for later deeper review:
  - should the canonical holder model remain direct fields on `item_instances`, or should docs still aim for a more explicit holder object/transfer contract;
  - where exactly should stack quantity be represented once authored stackability lands;
  - whether room prose should ever expose visible refs when ambiguity becomes player-visible without using a management command.

## 2. Session / Presence / Auth (`02.1.x`)

Focus:

- Login / OTP / TOTP model
- PLAY / LOGOUT lifecycle
- Reconnect vs fresh login semantics
- WHO / presence scope and truth
- Session/runtime state ownership

Notes:

- Review status: first pass completed
- Design/implementation alignment that looks good:
  - `WHO` is now real and correctly scoped to current game instance only;
  - presence is no longer JVM-local in real runtime and is backed by Redis for the first live slice;
  - `WHO` groups gods and players and derives god/admin classification from role claims carried at gameplay admission time.
- Important design/implementation drift:
  - `02.1.3` still says `planned`, but the first bounded `WHO` / gameplay-presence slice is already implemented;
  - the live presence model is much narrower than the full slice design: it only stores connected gameplay presence plus role bucket, not the broader activity-engine facts (`explicit_afk`, last command time, last meaningful action time, reconnect classification);
  - `02.1.1` describes the future text-auth model (`LOGIN <email>` / `LOGIN <email> <secret>` with account-selected modes and shared OTP secret), but current code still implements the older password-first model;
  - current user-facing prompts/help still say `LOGIN <email> <password>` and optional OTP, which is consistent with live code but inconsistent with the future auth slice unless that distinction is made more explicit;
  - `02.1.2` remains fully planned: there is still no real player-facing `LOGOUT` command despite the lifecycle design being settled.
- Current implementation reality:
  - `LoginCommandHandler` still expects credential payloads mapped to login name + password + optional OTP;
  - `AccountServiceImpl.authenticate(...)` still does password verification first and only applies OTP for elevated roles with a stored `twoFactorSecret`;
  - the live `GameplayPresence` record is only:
    - session id;
    - tenant id;
    - game instance id;
    - account id;
    - character id;
    - character name;
    - role bucket.
- Likely design gaps or cleanup needs:
  - `02.1.3` should be split more clearly into "implemented first WHO presence slice" versus "later full activity engine";
  - the repo needs one explicit statement that current login/auth implementation is still the old model while `02.1.1` is target-state auth design, otherwise docs and prompts read as contradictory rather than intentionally future-facing;
  - reconnect, logout, and presence docs should be checked together once `LOGOUT` work starts, because the current live system still only has disconnect/takeover cleanup plus presence removal hooks.
- Open follow-up questions for later deeper review:
  - whether the first activity-engine implementation should extend the existing `GameplayPresence` record or introduce a second richer runtime record;
  - whether the future auth redesign should replace the current password-first command shape directly or stage through compatibility text/help updates first.

## 3. Command / Interaction Model (`02.13.x`)

Focus:

- Built-in command registry / dispatcher end state
- Authored commands joining the same pipeline
- Command ambiguity and error UX
- Prompt / replay / post-processing boundaries
- Action classification consistency

Notes:

- Review status: first pass completed
- Design/implementation alignment that looks good:
  - built-in registry/dispatcher rollout is real, not aspirational;
  - `TextCommandInterpreter` no longer owns the old central per-command branch tree in the earlier style;
  - stage gating and prompt policy now live in command definitions;
  - item commands now converge through one `ItemCommandHandler` seam in the gameplay layer.
- Important design/implementation drift:
  - `02.13.6` still reads heavily like pre-implementation architecture guidance even though a meaningful built-in registry/dispatcher now exists;
  - `TextCommandDefinition` is currently narrower than the long-term design: it only carries type, dispatch group, stage requirement, and prompt policy;
  - action classification is still only a design slice, not attached to live command definitions yet;
  - built-in registry is still package-local and built-in-only, so the future authored-command integration seam is still conceptual rather than structurally visible in code;
  - parser behavior is still largely token-switch driven in `TextCommandParser`, which is acceptable for now but means `02.13.6` and `02.13.8` should more explicitly describe the current "registry + family handlers over existing parser" state.
- Current implementation reality:
  - `BuiltInTextCommandRegistry` is the live command-definition source for built-ins;
  - `TextCommandInterpreter` now does:
    - parse;
    - resolve session/stage;
    - registry lookup;
    - dispatch by built-in family group;
    - shared prompt post-processing;
  - `ItemCommandHandler` is a real convergence seam but still mostly a dispatcher over inventory/equipment/container handlers rather than a unified action executor;
  - explicit target/ref syntax for items is already flowing through the parser/payload path, but the parser is still command-specific rather than definition-driven.
- Likely design gaps or cleanup needs:
  - `02.13.6` should acknowledge that the first registry/dispatcher pass is already live and that the remaining work is broadening the command-definition model rather than "avoid building a branch tree";
  - `02.13.8` should probably call out that the current registry is intentionally minimal and does not yet carry action classification or authored-command ownership metadata in code;
  - the current codebase has no obvious structural placeholder for authored commands using the same registry concept yet, so that future seam is still under-specified in implementation terms;
  - action classification (`02.13.7`) is still disconnected from live command definitions, presence, and command execution.
- Open follow-up questions for later deeper review:
  - whether command definitions should eventually own parser linkage / payload parsing metadata rather than only post-parse dispatch metadata;
  - whether the next command-layer cleanup should be attaching action classification to command definitions before authored-command work begins.

## 4. Stats / Conditions / Effects / Actions (`07.x`, `02.13.7`, `02.13.9`, `02.13.11`)

Focus:

- Game-authored stat definitions
- Condition runtime model
- Shared effect engine
- Action requirements / categories / tags
- Timing / duration / cooldown semantics

Notes:

- Review status: first pass completed
- Overall assessment:
  - this slice family is directionally strong and mostly coherent;
  - the main risk is not contradiction, but that the glue between the slices is still implied rather than explicitly staged.
- Design/structure that looks good:
  - `07` defines the broad four-layer gameplay-state model cleanly;
  - `07.1`, `07.2`, and `07.3` form a sensible progression from shared effect model -> equipment/action-state contribution -> later damage/mitigation consumer;
  - `02.13.9` and `02.13.11` already point toward the same shared effect/timing substrate rather than inventing parallel systems;
  - `07.4` correctly keeps actor-model concerns adjacent without collapsing them into the stats slice itself.
- Important design gaps or under-specification:
  - there is not yet one explicit "first concrete runtime seam" document tying together:
    - action definition;
    - action classification;
    - shared effect engine;
    - timing/duration semantics;
    - actor state ownership;
    - effective-state query surface;
  - `07` says "persistent conditions", "transient action states", and "equipment-derived modifiers" are different, but the boundary between their runtime storage/evaluation shapes is still broad rather than operationally precise;
  - `02.13.11` timing/scheduler semantics are still fully standalone placeholder text and do not yet specify how durations are represented on active conditions or transient action states;
  - `07.4` actor-model placeholder is correct, but the connection between actor state ownership and the `07` runtime-state owner (likely entity-management vs game-logic) is still implicit.
- Likely cleanup or follow-up needs:
  - add a tighter bridge note somewhere between `07`, `02.13.9`, and `02.13.11` saying that authored actions produce or apply effect sources, while the effect engine evaluates them against actor state using shared timing semantics;
  - tighten the first implementation order for `07` so it is obvious whether the very first runtime proof should be:
    - effective-state query seam first;
    - or authored stat/condition definition persistence first.
- Open follow-up questions for later deeper review:
  - which service should own active condition instances and transient action-state instances in the first implementation;
  - whether cooldowns belong on action-state runtime records, separate timing records, or a shared scheduled-effect representation;
  - whether the first `07` proof should deliberately exclude creator-authored actions and use only built-in/equipment-driven effect sources.

## 5. Output / Transcript / Help (`02.13.10`, `04.6.1`, `04.7`)

Focus:

- Structured transcript end state
- Replay buffer semantics
- HELP storage / layering
- Speech / dialogue presentation
- Output formatting and color policy ownership

Notes:

- Review status: first pass completed
- Design/implementation alignment that looks good:
  - the live player-output path is already structured around `PlayerOutput`, typed payloads, `ReplayPolicy`, prompt payloads, and transport-specific projection;
  - first-party web already consumes structured output envelopes while classic text clients render from the same canonical output objects;
  - prompt separation is real in code rather than purely aspirational;
  - speech normalization for `say`, `whisper`, and `tell` is already implemented through a shared conservative normalizer and communication output mapper;
  - built-in `HELP` is live and coherent as a platform-owned code-backed corpus.
- Important design/implementation drift:
  - `02.13.10` still reads like a pure placeholder even though the repo already has a meaningful structured-output and replay substrate in `PlayerOutput`, `ReplayPolicy`, `TextPlayerOutputRenderer`, and `WebSocketOutputProjector`;
  - `04.7` still frames speech normalization as entirely pre-implementation, but the first runtime adoption for `say` / `whisper` / `tell` already exists;
  - `04.6.1` is still correctly future-state for game-authored help storage, but it should be read explicitly as a follow-up to an already-live built-in help system rather than as if help itself is still missing.
- Current implementation reality:
  - replay eligibility is already an explicit property on `PlayerOutput`;
  - prompt output is non-replayable and structurally separate from ordinary message/view payloads;
  - reconnect replay is currently driven through websocket reconnect/screen-buffer logic and replayable rendered text chunks rather than a richer persisted structured transcript store;
  - first-party web receives structured output envelopes for command responses and player outputs, but replay chunks are still transmitted as transcript text blocks rather than fully structured replay envelopes;
  - built-in help remains entirely code-backed in `HelpCommandHandler`;
  - game-authored help storage, lookup layering, and game-design integration are not implemented yet.
- Likely design gaps or cleanup needs:
  - `02.13.10` should acknowledge that the first structured-output substrate is already live and that the remaining work is about canonical transcript persistence/replay boundaries and richer-client end state, not "invent structured output";
  - `04.7` should be updated to say the first bounded speech-normalization rollout is already implemented, while broader channel policy and transcript/raw-vs-rendered storage questions remain open;
  - the transcript slice should more clearly describe the current gap between:
    - structured live outputs;
    - text-oriented reconnect buffer persistence;
    - future canonical structured transcript persistence/replay;
  - `04.6.1` should remain planned, but could state more explicitly that its dependency is a live built-in help corpus rather than a missing help command.
- Open follow-up questions for later deeper review:
  - whether reconnect replay should eventually persist canonical structured `PlayerOutput`-like envelopes rather than rendered text;
  - whether transcript storage should preserve raw spoken text, normalized spoken text, or both once the broader transcript model is formalized;
  - where color/style policy ultimately belongs for richer clients once the structured transcript end state is implemented.

## 6. Actor / Role / Social (`07.4`, `02.1.3`, `02.1.4`, `02.1.5`)

Focus:

- Unified actor model
- Player / NPC / god relationship
- Admin / god visibility and capabilities
- In-game WHO vs future cross-game social presence
- Friendship / identity distinctions

Notes:

- Pending expansion

## 7. Service Boundary / Runtime Hardening (`02.18.x`)

Focus:

- Auth boundaries
- Audit vs moderation separation
- Runtime state ownership
- Replay protection
- Transactional side effects / outbox direction

Notes:

- Pending expansion
