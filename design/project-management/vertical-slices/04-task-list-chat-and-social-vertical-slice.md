# Chat & SAY Vertical Slice Task List

## Goal and Status

Goal: establish the first implementation of the shared communication infrastructure so WebSocket and Telnet behaviour and observability stay in lockstep across Game Session, Game Logic, and Social/Groups services. The first standard built-ins are `say`, `whisper`, and `tell`, with room-local `say` as the first fully implemented mode. Status: key pieces of the initial room-speech path and its tests are implemented; this document describes the broader target-state behaviour, with up-to-date implementation details captured in the associated microservice design docs and regression test plans.

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

## 1. Protocol, UX, and Design Alignment for the Initial Communication Modes

- [x] Update the [Minimal Text Command Protocol](../../architecture/microservices/game-session-service/README.md#minimal-text-command-protocol) section so the first standard built-ins document their required arguments and defaults, with room-local `say` as the first fully live implementation and `whisper` / `tell` reserved in the shared communication model.
- [x] Decide and document the order of appearance for in-room chat lines, speaker attribution, and how co-located listeners see the `SAY` payload (e.g., prefixed with nick vs. `PlayerName says ...`). Capture at least one Telnet and one WebSocket transcript showing `SAY` reaching two clients plus a nearby NPC echo.
- [x] Add a short subsection to the [Game Session Service](../../architecture/microservices/game-session-service/README.md) and [Game Logic Service](../../architecture/microservices/game-logic-service/README.md) design docs (noting the [Social/Groups Service](../../architecture/microservices/social-groups-service/README.md) stub) describing how the initial room-speech requests flow through Game Session -> Game Logic -> Social/Groups services and how the shared communication model should expand later without replacing this path.
- [x] Confirm the design docs reiterate `SAY` requires the same authenticated session guard already pulled into `LOOK` and that unauthenticated clients receive `ERROR NOT_AUTHENTICATED`.

### In-room `SAY` ordering and transcripts

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

The initiating-player transcript is now direct prose, while Game Logic and Social & Groups still exchange deterministic type and recipient metadata for ordering, audit, and later fanout behavior.

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

These transcripts demonstrate how both transports preserve the same initiating-player prose and deterministic downstream metadata, ensuring regression suites can assert parity between Telnet and WebSocket experiences.

## 2. Game Logic Service: Communication Aggregation

- [x] Introduce a Game Logic gRPC entry for the first communication mode and shape its documentation so later communication intents can carry communication type, target/scope, and delivery metadata instead of hard-coding room speech as the permanent abstraction. All communication actions should conceptually enter through Game Logic, even when downstream services such as Social & Groups own parts of audience validation, history, moderation, or delivery fanout.
- [x] Implement the initial room-speech handler to validate message length, call the Social/Group facades (or stubbed in-memory broadcaster), and emit failure statuses when the service refuses (e.g., `PERMISSION_DENIED` for silenced players).
- [x] Add unit tests covering successful `SAY` (single recipient + multiple recipients), message validation failures, and propagation of backend errors (Social service unavailable).
- [x] Document the communication API in the Game Logic design doc with its responsibilities, especially the distinction between communication act, target/scope, recipient resolution, and recipient-facing presentation, and spell out that logical failures such as `PERMISSION_DENIED` or backend `UNAVAILABLE` return structured `ErrorDetail` objects so Game Session can map them to `ERROR COMMUNICATION_NOT_DELIVERED ...` while keeping the communication metrics aligned.

## 3. Game Session Service: Wiring the Initial Text Communication Mode

- [x] Extend the `TextCommandInterpreter` neighborhood so the text communication commands route to `CommunicationCommandHandler`, which translates tokens+session context into `SendCommunication` gRPC calls while the interpreter reuses the same authenticated session guard before issuing communication requests.
- [x] Map Game Logic communication errors into the text protocol (`ERROR COMMUNICATION_NOT_DELIVERED ...`), preserve canonical actor prose when delivery succeeds, and emit `gamesession.command.say.*`, `gamesession.command.whisper.*`, and `gamesession.command.tell.*` metrics/logs tagged by tenant and error codes.
- [x] Add unit/integration tests for `CommunicationCommandHandler` using stubbed Game Logic clients to cover success and error branches, while keeping the handler shape open for later explicit communication modes such as observer-aware `whisper`, richer `tell`, and later settings-driven `shout`.

## 4. Cross-Service Chat Regression Tests

- [x] Extend the WebSocket cross-service suite so a second WebSocket client (or Telnet proxy replay) joins the room, a `SAY` command is issued, and all participants observe the canonical transcript plus `gamesession.command.say.*` metrics (reuse a shared fixture for the transcript).
- [x] Add a Telnet variant that verifies the Telnet transcript matches the WebSocket output up to framing differences and that `ERROR COMMUNICATION_NOT_DELIVERED` appears exactly once when the Game Logic backend rejects the message.
- [x] Capture the canonical SAY transcript fixture (similar to LOOK) so both WebSocket and Telnet suites assert the same reference output.
- [x] Instrument these flows (via log capture/metrics) to assert the chat command traversed Game Session → Game Logic and triggered Social/Group service calls, ensuring `gamesession.command.say.invocations`/`failures` counters move for both success and failure paths.
- [x] Wire the chat regression suites into the existing `crossServiceTest` targets and mention the new tests in the README/test docs so they can be run locally and in CI.
- [x] Capture a failure-mode transcript (Social/Groups unavailable or other backend error) so the regression docs show what `ERROR COMMUNICATION_NOT_DELIVERED` looks like over both transports.

### Failure-mode transcript

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

## 5. Developer Workflows and Instrumentation

- [x] Create or update the WebSocket + Telnet example script (or documented sequence) that demonstrates `LOGIN` + `SAY` against the sample world and references the canonical transcript fixture described in `design/project-management/slice-support/chat-say-developer-guide.md`.
- [x] Update logging/monitoring docs (look instrumentation, logging & admin sections) to mention the new `gamesession.command.say.*` metrics.
- [x] Add a short "Implementation status" note to the Game Session/Game Logic/Social design docs so folks know what of this slice is live, stubbed, or deferred (e.g., channel filters, listening area heuristics).

## 6. Final QA Checklist

- Run the canonical Telnet and WebSocket flows manually (`SESSION`, `LOGIN`, `LOOK`, `SAY`) and verify the transcripts match the documented samples plus the `gamesession.command.say.*` counters increment.
- Inspect `/actuator/prometheus` during regression runs to confirm both `gamesession.command.look.*` and `gamesession.command.say.*` metrics move as expected.
- Mention the new SAY regression suites in the PR/README so reviewers know to run `./gradlew crossServiceTest` before merging.

---

Note: After completing tasks in this checklist, reconcile any overlapping items in the existing per-service status docs and design docs so the architecture docs reflect the new chat slice instead of duplicating details.

## Future Follow-On Scope

- A later communication slice should split the current `SAY` family into explicit speech-mode and audience-scope concepts so delivery is not permanently modeled as a single room-local broadcast with alias decoration.
- The preferred target-state is a configurable communication system where in-world communication targets scope objects such as rooms or areas, and those targets resolve both normal recipients and observer/interceptor recipients such as spies or eavesdroppers.
- Candidate future behaviors include:
  - target-limited `WHISPER` / `TELL` with sender, recipient, and optional overhear rules;
  - broader `SHOUT` semantics with configurable propagation across area, region, map, or continent boundaries once the game-settings/configuration model is designed properly;
  - guild, party, or other channel-backed speech that shares infrastructure with room speech without inheriting the same delivery rules;
  - game-defined speech variants that change formatting, moderation policy, or routing without requiring one-off pipelines.
