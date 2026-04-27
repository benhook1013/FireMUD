# Game Session Service Protocols

## Minimal Text Command Protocol

Telnet and WebSocket clients share a minimal line-based command protocol that powers the initial MVP gameplay set. Clients send ASCII lines terminated by `\n`; the first token is the command name, case-insensitive, and the rest of the line is command-specific arguments. Empty lines are ignored.

The canonical player-facing paths are:

- Telnet via TCP Proxy and Gateway
- first-party web via `/ws/game/**` through Gateway

Direct generic WebSocket access to Game Session remains useful as an internal/test and advanced-client seam, but it is not intended to be the primary product-facing client path. Real end-to-end client-path verification should prefer Gateway or TCP Proxy rather than relying only on direct Game Session WebSocket coverage.

At the protocol level, commands are split into two groups:

- **System commands** – session and connectivity operations fully owned by Game Session, such as `LOGIN`, `LOGON`, `PING`, and simple state/introspection queries that do not touch gameplay rules.
- **Gameplay commands** – in-world actions such as `LOOK`, communication actions like `SAY`, `WHISPER`, and `TELL`, movement, and combat. Game Session validates session state and authorization, normalizes input, and enqueues the action for Game Logic Service; it does not re-implement gameplay mechanics here.

The player-facing protocol is also stage-aware:

- **Connected, not logged in** – players can browse public worlds and get help, but they are not yet authenticated. The normal human flow is `WORLDS` then `LOGIN`.
- **Logged in, not yet playing** – players can issue `PLAY` directly or use lobby helper commands such as `REALMS` and `CHARS` if they need to disambiguate selection.
- **In game** – gameplay commands such as `LOOK`, `SAY`, and movement are available.

The normal happy path for a human player should therefore be:

```text
LOGIN <username> <password> [otp]
PLAY <world> [realm] [character]
```

`WORLDS`, `REALMS`, and `CHARS` are important helper commands, but they are not intended to be mandatory ceremony before ordinary gameplay entry.

| Command | Purpose | Example |
| ------- | ------- | ------- |
| `LOGIN <username> <password> [otp]` | Authenticates a session and binds it to an account on credential-bearing transports; append an OTP when two-factor auth is enabled. First-party `/ws/game/**` may instead use bare `LOGIN` after bootstrap/connect-token validation. | `LOGIN demo@example.com swordfish 123456` |
| `LOGON <username> <password> [otp]` | Exact alias for `LOGIN`; Telnet users often prefer the shorter name when typing from prompts. | `LOGON demo@example.com swordfish` |
| `WORLDS` | Lists worlds visible to the caller. Before `LOGIN`, this is a public browse/discovery command intended to let players explore the platform before signing up or logging in. After `LOGIN`, it may also include caller-specific membership or entitlement context. | `WORLDS` |
| `REALMS <world>` | Lists visible realms for a world, where `<world>` is a world slug or a menu index from `WORLDS`. The default public production realm may be visible before membership exists; additional realms require explicit grants. | `REALMS demo` |
| `CHARS <world> [realm]` | Lists characters for a world and optional realm from the authoritative character store, filtered to `{accountId, tenantId, gameInstanceId}` ownership. | `CHARS demo production` |
| `PLAY <world> [realm] [character]` | Binds the authenticated connection to a world, optional realm, and optional character after `LOGIN`, enforcing tenant authorization, public-admission rules, realm routing, and entitlements. Players may omit `[realm]` or `[character]` when the resolved choice is unambiguous; if the request is ambiguous, the service should return a selection-oriented response instead of treating that ambiguity as a gameplay error. For credential-bearing text clients joining the default public production realm without an existing membership row, `PLAY` creates the caller's `player` membership atomically through Account Service. First-party bootstrap clients normally complete that same membership creation during `POST /auth/connect-token` before socket admission. | `PLAY demo production Sora` |
| `LOOK` | Requests the current room snapshot aggregated from Game Logic plus World and Entity services. | `LOOK` |
| `INVENTORY` / `INV HERE` | Lists carried items or the current room-ground item holder. The command is rendered by Game Session, but item state is read through Game Logic and Entity Management. | `INV HERE` |
| `GET <item>` / `DROP <item>` | Moves a visible room-ground item into carried inventory, or a carried item into the current room. Game Session forwards the raw selector and quantity to Game Logic; Game Logic resolves names, visible refs, container refs, and stack refs before Entity Management mutates state. | `GET torch1` |
| `EQUIPMENT` / `WEAR <item>` / `REMOVE <item>` | Lists equipped items and binds or unbinds a carried item through Game Logic and Entity Management equipment validation. | `WEAR sword1` |
| `BLOCK` / `GUARD` | Applies the short-lived `blocking` action state through the durable gameplay-command path. Game Session enqueues the action, Game Logic routes the actor-state mutation, and Entity Management owns the active condition row and expiry. | `BLOCK` |
| `SAY <text>` | Standard room-local communication action. Targets the caller's current room and uses the shared communication model to resolve listeners and any observer/interceptor views. | `SAY Hello travelers` |
| `WHISPER <character> <text>` | Standard directed in-room communication action. Targets one nearby character in the current room; baseline default is full content for sender and target, with observer handling controlled by communication-type and target rules. | `WHISPER Sora The forge smells of brimstone` |
| `TELL <character> <text>` | Standard direct communication action. Targets one character directly, outside room scope by default, while still flowing through the shared communication model and Game Logic. | `TELL Sora Meet me at the forge` |

Selector rules for `PLAY` match the lobby helpers: `<world>` accepts a stable world slug or a menu index from `WORLDS`, `[realm]` accepts a realm slug or a menu index from `REALMS`, and `[character]` is an optional name or index when the resolved realm exposes exactly one visible character choice. If `PLAY <world>` or `PLAY <world> <character>` is ambiguous, the response should guide the player toward `REALMS`, `CHARS`, or a more specific `PLAY` form rather than failing with a low-level backend-flavored error.

## Login and Play Flow

Telnet and WebSocket clients share the line-based syntax, but transport context determines which `LOGIN` form is valid:

- For Telnet and generic WebSocket clients, bare `LOGIN` or `LOGON` is intended to start a prompt flow, while `LOGIN <username> <password> [otp]` performs an immediate authentication attempt.
- For first-party `/ws/game/**` sessions that already carry a validated Gateway connect context, bare `LOGIN` completes gameplay authentication from the pre-established bootstrap identity instead of prompting for credentials. This bootstrap identity must not quietly reintroduce gameplay binding into `LOGIN`; `PLAY` remains the sole gameplay-admission and gameplay-scope binding step.
- OTP values on credential-bearing logins are passed through verbatim to Account Service so two-factor accounts get the same behavior across transports.
- The same `OK <COMMAND>` and `ERROR <CODE> <message>` response format applies to all transports so clients can react consistently.

Prompt-based exchanges are planned but not implemented in this slice for Telnet and non-bootstrap clients. On those transports, bare `LOGIN` currently returns `ERROR PROMPT_LOGIN_UNSUPPORTED Prompt-based login is not implemented yet; send LOGIN <username> <password>.` First-party `/ws/game/**` sessions with a validated connect context are the exception: bare `LOGIN` consumes the bootstrap-backed context and must not ask the browser to resend credentials.

After `LOGIN` succeeds, the normal player-facing expectation is `PLAY <world> [realm] [character]`. `REALMS` and `CHARS` remain available as lobby helper commands when the player's choice is ambiguous or when they want to browse. `PLAY` is the gameplay-admission and gameplay-binding step; it is not merely a continuation of authentication. This step binds the authenticated connection to a world-scoped gameplay session and enforces tenant authorization, realm routing, public-admission rules, and entitlements.

Handshake failures such as HTTP `403` `CONNECT_TOKEN_REJECTED` or `POLICY_DENY` happen before the gameplay protocol is established and therefore are not emitted as text-protocol `ERROR <CODE>` frames. The command examples below begin only after a socket is already open and the line-based gameplay protocol is active.

For first-party `/ws/game/**` sessions, `PLAY` scope checks, including `tenantId` and `gameInstanceId`, must use the gateway-signed connect context carried in `X-Firemud-Connect-Context` and validated by Game Session rather than raw forwarded headers. Missing, invalid, expired, or replayed context where connect-token validation was required must fail admission with `CONNECT_CONTEXT_INVALID`. Mismatched validated scope fails with `CONNECT_SCOPE_MISMATCH`.

Canonical first-party `PLAY` scope errors on `/ws/game/**`:

- `CONNECT_CONTEXT_INVALID` – required gateway-signed connect context is missing or failed validation because of signature, expiry, replay, or key-verification failure.
- `CONNECT_SCOPE_MISMATCH` – validated connect context does not match the requested `{tenantId, gameInstanceId}` scope.

If a gameplay session already exists for the selected `{tenantId, gameInstanceId, characterId}` and is still resumable, meaning its TTL, current membership authority, and current revocation state are all valid, `PLAY` resumes it and rebinds the new socket to the existing session. On successful resume, Game Session also rebinds the session to a fresh backend token for subsequent internal calls rather than depending on the previous token to remain valid. If no resumable session exists but ordinary admission is still allowed, `PLAY` should fall back automatically to a fresh gameplay entry rather than returning a player-chore error that just asks the user to repeat the same command. Even after reconnect, the client must still send an explicit `PLAY` so the platform never guesses which tenant or character to resume.

If a client attempts gameplay commands before `LOGIN` succeeds, the service should return a stage-aware response such as `ERROR LOGIN_REQUIRED Use LOGIN <username> <password>`. If a client is logged in but has not yet completed `PLAY`, the service should return a stage-aware response such as `ERROR PLAY_REQUIRED Use PLAY <world> [realm] [character]`. These are menu/progression mistakes, not gameplay-mechanics failures.

### Login and world-selection examples

Illustrative world-selection transcript showing public browsing plus slug and index equivalence:

```text
WORLDS
OK WORLDS
1) Demo World (demo)
2) Builder Sandbox (sandbox)

LOGIN demo@example.com swordfish
OK LOGIN Logged in as demo@example.com

REALMS 1
OK REALMS
1) Live Realm (production)
2) Playtest Dock (playtest-docks)

CHARS 1 production
OK CHARS
1) Emberline
2) Sora

CHARS demo production
OK CHARS
1) Emberline
2) Sora

PLAY 1 production 2
OK PLAY Entered world: Demo World / Live Realm as Sora
```

The same resolution rules apply to `PLAY demo production 2` or `PLAY 1 1 Sora`: menu indices and stable world/realm slugs are equivalent player-facing selectors for the same canonical `{tenantId, gameInstanceId, characterId}` target.

The Account Service returns canonical `AUTH_*` error codes such as `AUTH_INVALID_CREDENTIALS`, `AUTH_OTP_REQUIRED`, `AUTH_ACCOUNT_LOCKED`, and `AUTH_UPSTREAM_FAILURE`. Game Session translates them into protocol-level responses such as `ERROR INVALID_CREDENTIALS` and `ERROR OTP_REQUIRED` so Telnet and WebSocket clients can rely on stable error semantics while the human-readable message remains flexible.

Additional Game Session-specific login failures cover parsing and session-state issues before the Account Service call:

- `PROMPT_LOGIN_UNSUPPORTED` – prompt-based `LOGIN`/`LOGON` exchanges are planned but not implemented yet on non-bootstrap transports, so those clients must send `LOGIN <username> <password>`.
- `INVALID_ACCOUNT` – Account Service returned an account identifier that could not be parsed into the expected format.
- `ACCOUNT_MISMATCH` – bootstrap-backed `LOGIN` resolved to an account different from the validated connect-context subject, or the authenticated account is otherwise not permitted to attach to the requested game instance or tenant context.
- `SESSION_NOT_FOUND` – the supplied game instance identifier has no corresponding `GameInstance`.
- `INVALID_ARGUMENT` – session ID parsing or other validation failed before the handler reached gameplay state.
- `PLAY_REQUIRED` – a gameplay command that requires admitted gameplay scope was sent before `PLAY` completed successfully.
- `CONNECT_CONTEXT_INVALID` – required gateway-signed connect context is missing or failed validation.
- `CONNECT_SCOPE_MISMATCH` – validated connect context does not match the requested world scope.

Planned prompt-flow transcript:

```text
LOGIN
OK LOGIN Enter username:
demo@example.com
OK LOGIN Enter password:
swordfish
OK LOGIN Logged in as demo@example.com

WORLDS
OK WORLDS
1) Demo World (demo)

PLAY demo
OK PLAY Entered world: Demo World / Live Realm
```

The transcript above shows the intended prompt flow. In the current implementation the same exchange is represented by a single `LOGIN <username> <password>` call because the prompt-driven handler still returns `ERROR PROMPT_LOGIN_UNSUPPORTED ...`.

Telnet success, using the normal simple player-facing path:

```text
WORLDS
OK WORLDS
1) Demo World (demo)

LOGIN demo@example.com swordfish
OK LOGIN Logged in as demo@example.com

PLAY demo
OK PLAY Entered world: Demo World
```

First-party `/ws/game/**` successful bootstrap-backed login and world entry:

```text
LOGIN
OK LOGIN Logged in
PLAY demo
OK PLAY Entered world: Demo World
```

First-party `/ws/game/**` account-mismatch example:

```text
LOGIN
ERROR ACCOUNT_MISMATCH Bootstrap identity does not match the validated session context
```

Failure examples:

```text
LOGIN demo@example.com wrongpass
ERROR INVALID_CREDENTIALS Invalid username or password
```

```text
LOGIN demo@example.com swordfish
ERROR ACCOUNT_LOCKED Account locked after repeated failures
```

### Plaintext Telnet pre-login warning

For Telnet clients connected over plaintext TCP, Game Session includes a landing-menu security warning before login whenever the propagated transport metadata says `transportSecurity=PLAINTEXT_TELNET`. This warning is suppressed for TLS Telnet and web clients and is intended to be visible without changing normal gameplay flow.

A typical banner is:

> `WARNING: You are connected over plaintext Telnet. Your credentials and gameplay traffic may be visible on the network. For better security, please use the TLS Telnet port advertised by the server or the FireMUD web client instead.`

## LOOK and SAY Behavior

`LOOK` is treated as a fully data-driven gameplay command. Game Session enforces authentication, forwards it to Game Logic, which fetches room metadata from World Management and visible entities from Entity Management before the response is rendered over Telnet or WebSocket.

The canonical text renderer should preserve a classic MUD feel:

- room title first;
- then one composed descriptive block that includes room prose plus visible occupants and room-ground items as separate sentences in the same paragraph/block;
- then exits;
- then the normal prompt, which remains the place for player health/status rather than embedding that data into the `LOOK` body.

The standard `QUICKLOOK` command reuses the same underlying room-view structure as `LOOK` but skips the room-description prose, making it suitable for rapid redraws that still show visible occupants, room-ground items, exits, and the normal prompt/status line.

Prompt outputs now also carry a first minimal structured status field list alongside their rendered classic text. Text clients still receive the traditional prompt line, while first-party web and later richer clients can consume the same prompt payload without scraping transcript text.

Later game-defined prompt composition should plug in ahead of rendering: upstream gameplay/status providers add structured fields, the prompt pipeline selects and orders fields according to player/game layout policy, and the Game Session renderer continues turning that payload into the final client-facing prompt for the active surface.

The text protocol remains the canonical wire format for Telnet and generic text WebSocket clients, but it should not be treated as the deepest platform abstraction. FireMUD should preserve structured gameplay views, communication results, prompt/status snapshots, and command errors until the latest practical rendering step so player settings such as color mode and `BRIEF`, plus first-party web and future MCP-aware clients, can apply presentation policy without rewriting gameplay logic. See [Input, Output, and Presentation](../../system-architecture-input-output-and-presentation.md).

The first live communication modes now emit canonical actor prose directly for the initiating player. After a successful command the server responds with text such as:

```text
You say, "Hello travelers"
You whisper to Sora, "Keep quiet"
You tell Sora, "Meet me at the forge"
```

Explicit type, recipient, and delivery metadata still exists on the shared downstream communication path for deterministic tests, logging, and later fanout behavior, but that metadata is no longer exposed as the canonical user-facing success transcript.

This structured payload should not be treated as the final platform abstraction for all communication. The longer-term model should be:

- a communication intent emitted by the actor,
- a game-configured communication type definition,
- one or more targets/scopes such as room, area, region, direct target, guild, or account,
- recipient resolution owned by those targets/scopes,
- and per-recipient presentation for emitters, ordinary listeners, and observer/interceptor roles.

All communication actions should enter through Game Logic. Game Logic resolves gameplay context, communication type, target/scope, and gameplay interception/perception rules, then dispatches to Social & Groups or other owning services as needed for membership checks, moderation, persistence, and delivery fanout.

For in-world communication, the command should usually target the room/area/etc. rather than precomputing the final recipient list in the sender path. That keeps room-local speech extensible for eavesdropping, spy skills, magical listening, and other target-owned delivery rules.

The first standard built-ins should be understood as:

- `say` -> current room target
- `whisper` -> direct target in the current room
- `tell` -> direct target outside room scope by default

`shout` should be treated as a future built-in, but not implemented until the game-settings model can describe whether its propagation should be region-wide, map-wide, or otherwise topology-dependent.

The built-in communication parser enforces that `SAY`, `WHISPER`, and `TELL` include the required message and target fields for their mode. `WHISPER` and `TELL` now flow through the same shared communication path as `SAY` instead of acting as room-speech aliases. Submitting an empty payload or exceeding the configured message limit, currently 512 characters, yields `ERROR INVALID_ARGUMENT Message text must be 1-512 characters long`.

Canonical baseline prose for the built-in communication modes is:

- `say` sender view: `You say, "Hello travelers"`
- `whisper` sender view: `You whisper to Sora, "Keep quiet"`
- `whisper` target view: `Emberline whispers to you, "Keep quiet"`
- `whisper` metadata-only observer view: `Emberline whispers something to Sora.`
- `tell` sender view: `You tell Sora, "Meet me at the forge"`
- `tell` target view: `Emberline tells you, "Meet me at the forge"`

Baseline failure mapping for the target-directed modes is:

- invalid or missing room target for `whisper` -> `ERROR COMMUNICATION_NOT_DELIVERED Target not present in room: <name>`
- unresolved or unavailable direct target for `tell` -> `ERROR INVALID_ARGUMENT Target is not available: <name>` or `ERROR INVALID_ARGUMENT Character not found: <name>`
- muted or silenced sender -> `ERROR COMMUNICATION_NOT_DELIVERED silenced`
- generic downstream failure -> `ERROR COMMUNICATION_NOT_DELIVERED <backend message>`

### LOOK transcripts

Telnet `LOOK` example:

```text
PLAY demo
OK PLAY Entered world: Demo World

LOOK
OK LOOK
Candle-lit Antechamber
You stand in a basalt chamber warmed by the brazier near the western wall. Stalactites drip along the northern wall while a faint draft carries the smell of damp earth from the lower tunnels. Torches flicker in alcoves, casting motion into the shadowy archway to the north. A kobold scout stands alert near the eastern balustrade. Sora leans against the southern pillar. A dented brass lantern lies on the flagstones near the brazier.

Exits: north, east
```

WebSocket `LOOK` example:

```text
PLAY demo
OK PLAY Entered world: Demo World

LOOK
OK LOOK
Crafting Hall of Ember
A vaulted hall lined with anvils and hanging banners rings with the rhythm of hammer blows. Sparks drift upward from the forges while metalworkers shout over the din, and the far wall is dominated by the etched sigil of the Ember Guild. Master Smith Torga wipes soot from his shoulders beside the nearest forge. Sora waits near the south stair, waving to a passing engineer. A crate of fresh horseshoes sits open near the western ovens.

Exits: south, west
```

### Stage-aware command handling

The protocol should behave like a menu-driven MUD front door rather than treating all non-system input as premature gameplay:

- Before `LOGIN`, valid commands are things such as `WORLDS`, `LOGIN`, `LOGON`, `HELP`, and `QUIT`.
- After `LOGIN` but before `PLAY`, valid commands are things such as `PLAY`, `WORLDS`, `REALMS`, `CHARS`, `HELP`, and `QUIT`.
- After `PLAY`, gameplay commands such as `LOOK`, `SAY`, and movement are admitted.

This means wrong-stage input should produce stage-helpful guidance:

- pre-login gameplay-like input -> `ERROR LOGIN_REQUIRED ...`
- post-login, pre-`PLAY` gameplay-like input -> `ERROR PLAY_REQUIRED ...`

The protocol should not frame these cases primarily as backend or world-state failures.

### LOOK request flow

1. Game Session validates that the caller has completed `LOGIN` and `PLAY` and has a valid Redis-backed gameplay session context. If the caller is still in the login/menu stages, it returns a stage-aware `LOGIN_REQUIRED` or `PLAY_REQUIRED` error rather than a generic gameplay-auth failure.
2. Authenticated `LOOK` commands call Game Logic's `ResolveLook`, passing `tenantId`, `gameInstanceId`, `sessionId`, `characterId`, and `roomInstanceId`.
3. Game Logic returns a structured `LookResult`, which Game Session renders into the `OK LOOK` text response and emits `gamesession.command.look.*` metrics/logs.
4. Reconnecting Telnet or WebSocket clients do not receive buffered command replay. Instead, after successful `LOGIN` and `PLAY`, Game Session may replay the bounded per-player transcript/screen buffer and then emits a fresh authoritative `LOOK` so current room state wins over any stale context. New buffer entries preserve structured `PlayerOutput` replay metadata alongside rendered protocol text; first-party web clients receive typed `transcript_entry` replay events when that metadata exists, while older text-only buffer entries and classic clients continue to use transcript chunks/plain text.

### LOOK error mapping and metrics

`LOOK` maps downstream failures into protocol-level errors so clients see a stable failure surface:

- `ERROR ROOM_NOT_FOUND`
- `ERROR WORLD_UNAVAILABLE`
- `ERROR ENTITY_UNAVAILABLE`
- `ERROR LOOK_UNAVAILABLE`
- `ERROR UNEXPECTED`

Metrics `gamesession.command.look.invocations` and `gamesession.command.look.failures` are tagged with `tenantId` and, when applicable, `error` so operators can correlate client-visible failures with the underlying cause quickly.

### Communication request flow

1. Game Session validates the same admitted gameplay session context leveraged by `LOOK`; callers still in the login/menu stages receive stage-aware `LOGIN_REQUIRED` or `PLAY_REQUIRED` guidance rather than a generic protocol-auth failure.
2. Authenticated `SAY`, `WHISPER`, and `TELL` commands route through `CommunicationCommandHandler`, which packages `tenantId`, `gameInstanceId`, `sessionId`, `characterId`, `speakerName`, `roomInstanceId`, normalized text, and target metadata into a `SendCommunication` gRPC request to Game Logic.
3. Game Logic resolves the communication type and target/scope, validates message constraints, and produces the baseline delivery metadata:
   - `say` targets the current room;
   - `whisper` targets one character in the current room;
   - `tell` targets one online character directly outside room scope by default.
4. Game Logic forwards the normalized communication to Social & Groups Service with explicit type and recipient metadata for delivery logging, history, moderation, and downstream fanout where applicable.
5. Backend failures propagate protocol-mapped errors such as `ERROR COMMUNICATION_NOT_DELIVERED`, while pre-flight stage failures are surfaced as `LOGIN_REQUIRED` or `PLAY_REQUIRED` before the communication request is attempted.

## Response Format

- System commands such as `LOGIN`, `LOGON`, `PING`, and lightweight state queries are allowed to produce synchronous responses without enqueuing gameplay actions. Their side effects stay limited to session binding, health checks, or read-only projections.
- Gameplay commands such as `LOOK`, `SAY`, movement, `BLOCK`, and later combat are tick-driven actions. Game Session validates and normalizes them, emits enqueue metadata, and must not perform gameplay state mutations outside the tick executor.
- If the interpreter produces both immediate text and enqueue metadata and the enqueue step fails, for example because of a Redis outage, Game Session surfaces a single `ERROR` response and does not report success followed by a dropped action.
- Every response is plain text. The first line is either `OK <COMMAND>` or `ERROR <CODE> <message>`.
- Success responses may include additional lines describing the outcome.
- A blank line terminates the response block so multiple responses can be streamed back-to-back without ambiguity.
- Asynchronous world events use the same rules but are prefixed with `EVENT <TYPE>` to distinguish them from direct command responses.
- Unknown commands return `ERROR UNKNOWN_COMMAND <rawLine>`.

Prompt/status remains a separate output class from transcript lines and gameplay views even when plain-text clients receive it on the same socket. Prompt emission should be coalesced, reconnect transcript replay should exclude prompt lines, and first-party or MCP-aware clients may consume prompt/status as structured state rather than main-transcript text. See [Reconnection Strategy](../../system-architecture-reconnection.md#client-reconnection-behaviour) and [Input, Output, and Presentation](../../system-architecture-input-output-and-presentation.md).

Examples:

```text
LOGIN demo@example.com swordfish
OK LOGIN Logged in as demo@example.com

PLAY demo
OK PLAY Entered world: Demo World

LOOK
OK LOOK
Room: Candle-lit Antechamber (Room Instance ID: R-1021)
Short: You stand in a basalt chamber warmed by a single brazier.
Long: Stalactites drip along the northern wall while a faint draft carries the smell of damp earth from the lower tunnels.
Exits: NORTH (arched passage toward the cavern mouth), EAST (narrow fissure descending toward the forges).
Entities:
- NPC "Kobold Scout" (alert, leaning on the eastern balustrade)
- Player "Sora" (half-hidden in the shadowed niche)

SAY Hello travelers
You say, "Hello travelers"

WHISPER Sora Keep quiet
You whisper to Sora, "Keep quiet"

TELL Sora Meet me at the forge
You tell Sora, "Meet me at the forge"

DANCE
ERROR UNKNOWN_COMMAND DANCE
```
