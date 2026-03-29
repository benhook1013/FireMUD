# Chat & SAY Vertical Slice Task List

## Goal and Status

Goal: extend the text command fabric with a `SAY`-centric chat slice that keeps WebSocket and Telnet behaviour and observability in lockstep across Game Session, Game Logic, and Social/Groups services. Status: key pieces of the SAY path and its tests are implemented; this document describes the target-state behaviour, with up-to-date implementation details captured in the associated microservice design docs and regression test plans.

Follow-up design direction agreed after the main slice landed:

- The long-term platform model should not remain `SAY`-centric. FireMUD should use a shared, configurable communication infrastructure with `say` as only the first built-in communication type.
- Communication should be modeled around:
  - a communication intent emitted by an actor,
  - a game-configured communication type definition,
  - one or more communication targets/scopes,
  - recipient resolution performed by those targets/scopes,
  - and per-recipient presentation/rendering.
- In-world communication should usually target scope objects such as a room, area, region, map, or other spatial/social aggregate, rather than precomputing a flat recipient list in the sender path.
- Target-owned recipient resolution must be able to include normal listeners plus observers/interceptors such as eavesdroppers, spies, magical listeners, or game-specific sneaky skills.
- Game-configured communication definitions should control propagation rules, moderation category, and the presentation style that players see in-game.
- Observer perception should be split cleanly:
  - communication type defines the baseline observability contract,
  - target/scope resolves who qualifies as a normal listener or observer,
  - and recipient capabilities/effects decide whether the final view is full-content, partial-content, or metadata-only.

After `LOOK` flows through the new Game Logic + World + Entity path, the next smallest playable slice expands the text command fabric with the `SAY` chat path that lets players speak to others in the same room, touching Game Logic aggregation, Game Session command parsing, and the cross-service regression suites so we can assert both WebSocket and Telnet experiences stay in sync.

Scope note: this slice is the first implemented room-speech pathway, but it should now be understood as the first delivery mode on top of a broader configurable communication model rather than as the permanent abstraction for all speech.

Out of scope for this slice implementation: fully differentiated speech semantics such as directed/private `WHISPER` or `TELL`, propagation beyond the current room, and topology-aware audible ranges such as area/map/region/continent shouts. Those behaviors should be treated as later follow-up slices, but the shared communication model should be designed now so they land as configuration and target-resolution changes rather than a later rewrite.

## 1. Protocol, UX, and Design Alignment for the Initial Room-Speech Mode

- [x] Update the [Minimal Text Command Protocol](../../architecture/microservices/game-session-service/README.md#minimal-text-command-protocol) section so the first live room-speech mode documents the required arguments, the canonical response shape (`OK SAY`, speaker annotations, delivered-to list), and how edge cases (empty message, overly long text) report `ERROR INVALID_ARGUMENT`.
- [x] Decide and document the order of appearance for in-room chat lines, speaker attribution, and how co-located listeners see the `SAY` payload (e.g., prefixed with nick vs. `PlayerName says ...`). Capture at least one Telnet and one WebSocket transcript showing `SAY` reaching two clients plus a nearby NPC echo.
- [x] Add a short subsection to the [Game Session Service](../../architecture/microservices/game-session-service/README.md) and [Game Logic Service](../../architecture/microservices/game-logic-service/README.md) design docs (noting the [Social/Groups Service](../../architecture/microservices/social-groups-service/README.md) stub) describing how the initial room-speech requests flow through Game Session -> Game Logic -> Social/Groups services and how the shared communication model should expand later without replacing this path.
- [x] Confirm the design docs reiterate `SAY` requires the same authenticated session guard already pulled into `LOOK` and that unauthenticated clients receive `ERROR NOT_AUTHENTICATED`.

### In-room `SAY` ordering and transcripts

Document that every `SAY`-family response follows a fixed sequence: the transport echoes `OK SAY`, then a `Speaker` annotation identifies the originator, `Delivered-To` lists recipients in deterministic order (sender first, followed by attendees sorted by name), and `Message` repeats the chat text. Co-located listeners (players or NPC loggers) may render the WHO metadata into a narrative such as `Emberline says, "Hello travelers"` while still relying on the structured payload for ordering.

For future communication modes, treat this structured payload as an implementation/testing surface rather than the only long-term player-facing UX. The long-term model should preserve a distinction between:

- the communication intent and its target/scope,
- the resolved recipient set (including observers/interceptors),
- and the per-recipient narrative/presentation that players actually see.
- For observers and interceptors specifically, the communication type should define the baseline observability semantics while the target/scope and recipient capabilities decide who qualifies and what that qualified observer perceives.

Supply at least two transcripts (one Telnet-style, one WebSocket-style) that highlight this ordering and show the chat hitting two active clients plus a nearby NPC echo. For example:

Telnet - Emberline's client (emitter):

```text
SAY Hello travelers
OK SAY
Speaker: Emberline
Delivered-To: Emberline, Sora, Kobold Scout
Message: Hello travelers
```

Telnet - Sora's client (listener) displays only the shared payload rendered in narrative form:

```text
Emberline says, "Hello travelers"
Kobold Scout echoes: "Kobold Scout nods and replies, `Stay awhile.`"
```

The Telnet `OK SAY` block above is the canonical structured payload (even though it is rendered as plain text), and the narrative lines that follow (`Emberline says…`, the NPC echo, etc.) are derived from that payload for readability, mirroring the same distinction the LOOK slice makes between the canonical output and flavored rendering.

WebSocket - Emberline's connection (emitter) observes the same structured response, while Sora's connection consumes a mirrored packet. The NPC echo can be represented as an automated webhook from the Social service:

```json
{
  "event": "chat",
  "command": "OK SAY",
  "speaker": "Emberline",
  "deliveredTo": ["Emberline", "Sora", "Kobold Scout"],
  "message": "Hello travelers"
}
```

Nearby NPC echo:

```text
Kobold Scout says, "Stay awhile."
```

These transcripts demonstrate how both transports serialize the canonical `OK SAY` structure before layering flavor text for players or NPCs, ensuring regression suites can assert parity between Telnet and WebSocket experiences.

## 2. Game Logic Service: Communication Aggregation

- [x] Introduce a Game Logic gRPC entry for the first communication mode, e.g. `BroadcastSay`, and shape its documentation so later communication intents can carry communication type, target/scope, and delivery metadata instead of hard-coding room speech as the permanent abstraction.
- [x] Implement the initial room-speech handler to validate message length, call the Social/Group facades (or stubbed in-memory broadcaster), and emit failure statuses when the service refuses (e.g., `PERMISSION_DENIED` for silenced players).
- [x] Add unit tests covering successful `SAY` (single recipient + multiple recipients), message validation failures, and propagation of backend errors (Social service unavailable).
- [x] Document the communication API in the Game Logic design doc with its responsibilities, especially the distinction between communication act, target/scope, recipient resolution, and recipient-facing presentation, and spell out that logical failures such as `PERMISSION_DENIED` or backend `UNAVAILABLE` return structured `ErrorDetail` objects so Game Session can map them to `ERROR SAY_NOT_DELIVERED ...` while keeping `gamesession.command.say.*` metrics aligned.

## 3. Game Session Service: Wiring the Initial Text Communication Mode

- [x] Extend the `TextCommandInterpreter` / `LookCommandHandler` neighborhood so the initial `SAY` text command routes to a `SayCommandHandler` that translates tokens+session context into `BroadcastSay` gRPC calls while the interpreter reuses the same authenticated session guard (via the `LookCommandHandler` validation) before issuing communication requests.
- [x] Map Game Logic chat errors (`ERR_ROOM_SILENCED`, `ERR_SOCIAL_UNAVAILABLE`, etc.) into the text protocol (`ERROR SAY_NOT_DELIVERED ...`), preserve `OK SAY` when delivery succeeds, and emit `gamesession.command.say.invocations`/`gamesession.command.say.failures` metrics/logs tagged by tenant and error codes.
- [x] Add unit/integration tests for `SayCommandHandler` using stubbed Game Logic clients to cover success and error branches, while keeping the handler shape open for later explicit communication modes such as target-directed `WHISPER`, `TELL`, or wider propagation acts.

## 4. Cross-Service Chat Regression Tests

- [x] Extend the WebSocket cross-service suite so a second WebSocket client (or Telnet proxy replay) joins the room, a `SAY` command is issued, and all participants observe the canonical transcript plus `gamesession.command.say.*` metrics (reuse a shared fixture for the transcript).
- [x] Add a Telnet variant that runs `SESSION` + `LOGIN` + `SAY`, verifying the Telnet transcript matches the WebSocket output up to framing differences and that `ERROR SAY_NOT_DELIVERED` appears exactly once when the Game Logic backend rejects the message.
- [x] Capture the canonical SAY transcript fixture (similar to LOOK) so both WebSocket and Telnet suites assert the same reference output.
- [x] Instrument these flows (via log capture/metrics) to assert the chat command traversed Game Session → Game Logic and triggered Social/Group service calls, ensuring `gamesession.command.say.invocations`/`failures` counters move for both success and failure paths.
- [x] Wire the chat regression suites into the existing `crossServiceTest` targets and mention the new tests in the README/test docs so they can be run locally and in CI.
- [x] Capture a failure-mode transcript (Social/Groups unavailable or other backend error) so the regression docs show what `ERROR SAY_NOT_DELIVERED` looks like over both transports.

### Failure-mode transcript

Document a short Telnet + WebSocket transcript triggered by a backend failure (e.g., Social & Groups stub returns `PERMISSION_DENIED` or is unreachable) so regression suites can assert the error response shape:

- **Telnet emitter (Emberline)**:

```text
SAY Hello travelers
ERROR SAY_NOT_DELIVERED Backend unavailable
```

- **Telnet listener (Sora)**:

```text
ERROR SAY_NOT_DELIVERED Unable to reach chat service
```

- **WebSocket emitter/listener**:

```json
{
  "event": "chat",
  "command": "ERROR SAY_NOT_DELIVERED",
  "speaker": "Emberline",
  "error": {
    "code": "UNAVAILABLE",
    "message": "Social service unreachable"
  }
}
```

These transcripts make it easy to add regression assertions for failure paths and ensure both transports handle backend outages in the same way, explicitly ensuring the flow is documented as: Social service returns `UNAVAILABLE` → Game Logic responds with an application error carrying `ErrorDetail`(code=`UNAVAILABLE`) → Game Session translates that into `ERROR SAY_NOT_DELIVERED ...` for Telnet and `"command": "ERROR SAY_NOT_DELIVERED", "error": {"code":"UNAVAILABLE", ...}` for WebSocket.

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
  - broader `SHOUT` semantics with configurable propagation across area, region, map, or continent boundaries;
  - guild, party, or other channel-backed speech that shares infrastructure with room speech without inheriting the same delivery rules;
  - game-defined speech variants that change formatting, moderation policy, or routing without requiring one-off pipelines.
