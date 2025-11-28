# Chat & SAY Vertical Slice Task List

After `LOOK` flows through the new Game Logic + World + Entity path, the next smallest playable slice expands the text command fabric with the `SAY` chat path that lets players speak to others in the same room, touching Game Logic aggregation, Game Session command parsing, and the cross-service regression suites so we can assert both WebSocket and Telnet experiences stay in sync.

## 1. Protocol, UX, and Design Alignment for SAY

- [ ] Update the Minimal Text Command Protocol section so `SAY` (and aliases like `YELL`/`WHISPER`) document the required arguments, the canonical response shape (`OK SAY`, speaker annotations, delivered-to list), and how edge cases (empty message, overly long text) report `ERROR INVALID_ARGUMENT`.
- [ ] Decide and document the order of appearance for in-room chat lines, speaker attribution, and how co-located listeners see the `SAY` payload (e.g., prefixed with nick vs. `PlayerName says ...`). Capture at least one Telnet and one WebSocket transcript showing `SAY` reaching two clients plus a nearby NPC echo.
- [ ] Add a short subsection to the Game Session and Game Logic design docs describing how `SAY` requests flow through Game Session → Game Logic → Social/Group services (or a stub for now) and how we guard the pathway with authentication/session context.
- [ ] Confirm the design docs reiterate `SAY` requires the same authenticated session guard already pulled into `LOOK` and that unauthenticated clients receive `ERROR NOT_AUTHENTICATED`.

## 2. Game Logic Service: Chat Aggregation

- [ ] Introduce a Game Logic gRPC entry for chat, e.g., `BroadcastSay`, that accepts tenant/session/player context plus the message text and returns `Ok`/`Error` with optional delivery metadata (list of recipient IDs or locations).
- [ ] Implement the handler to validate message length, call the Social/Group facades (or stubbed in-memory broadcaster), and emit failure statuses when the service refuses (e.g., `PERMISSION_DENIED` for silenced players).
- [ ] Add unit tests covering successful `SAY` (single recipient + multiple recipients), message validation failures, and propagation of backend errors (Social service unavailable).
- [ ] Document the new chat API in the Game Logic design doc with its responsibilities, especially how it differs from future channel/combat output formats.

## 3. Game Session Service: Wiring Text SAY to Game Logic

- [ ] Extend the `TextCommandInterpreter` / `LookCommandHandler` neighborhood so `SAY` (and `YELL`/`WHISPER`) text commands route to a new `SayCommandHandler` that translates tokens+session context into `BroadcastSay` gRPC calls while the interpreter enforces the authenticated session guard.
- [ ] Map Game Logic chat errors (`ERR_ROOM_SILENCED`, `ERR_SOCIAL_UNAVAILABLE`, etc.) into the text protocol (`ERROR SAY_NOT_DELIVERED ...`), preserve `OK SAY` when delivery succeeds, and emit `gamesession.command.say.invocations`/`gamesession.command.say.failures` metrics/logs tagged by tenant and error codes.
- [ ] Add unit/integration tests for `SayCommandHandler` using stubbed Game Logic clients to cover success and error branches, and verify the interpreter still handles aliases (`YELL`/`WHISPER`).

## 4. Cross-Service Chat Regression Tests

- [ ] Extend the WebSocket cross-service suite so a second WebSocket client (or Telnet proxy replay) joins the room, a `SAY` command is issued, and all participants observe the canonical transcript plus `gamesession.command.say.*` metrics (reuse a shared fixture for the transcript).
- [ ] Add a Telnet variant that runs `SESSION` + `LOGIN` + `SAY`, verifying the Telnet transcript matches the WebSocket output up to framing differences and that `ERROR SAY_NOT_DELIVERED` appears exactly once when the Game Logic backend rejects the message.
- [ ] Capture the canonical SAY transcript fixture (similar to LOOK) so both WebSocket and Telnet suites assert the same reference output.
- [ ] Instrument these flows (via log capture/metrics) to assert the chat command traversed Game Session → Game Logic and triggered Social/Group service calls, ensuring `gamesession.command.say.invocations`/`failures` counters move for both success and failure paths.
- [ ] Wire the chat regression suites into the existing `crossServiceTest` targets and mention the new tests in the README/test docs so they can be run locally and in CI.

## 5. Developer Workflows and Instrumentation

- [ ] Create a WebSocket + Telnet example script (or documented sequence) that demonstrates `LOGIN` + `SAY` against the sample world and references the canonical transcript used by the regression suites.
- [ ] Update logging/monitoring docs (look instrumentation, logging & admin sections) to mention the new `gamesession.command.say.*` metrics.
- [ ] Add a short "Implementation status" note to the Game Session/Game Logic/Social design docs so folks know what of this slice is live, stubbed, or deferred (e.g., channel filters, listening area heuristics).

---

Note: After completing tasks in this checklist, reconcile any overlapping items in the existing per-service task lists and design docs so the architecture docs reflect the new chat slice instead of duplicating details.
