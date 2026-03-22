# Game Session Service Protocols

## Minimal Text Command Protocol

Telnet and WebSocket clients share a minimal line-based command protocol that powers the initial MVP gameplay set. Clients send ASCII lines terminated by `\n`; the first token is the command name, case-insensitive, and the rest of the line is command-specific arguments. Empty lines are ignored.

At the protocol level, commands are split into two groups:

- **System commands** – session and connectivity operations fully owned by Game Session, such as `LOGIN`, `LOGON`, `PING`, and simple state/introspection queries that do not touch gameplay rules.
- **Gameplay commands** – in-world actions such as `LOOK`, `SAY`, `YELL`, `WHISPER`, movement, and combat. Game Session validates session state and authorization, normalizes input, and enqueues the action for Game Logic Service; it does not re-implement gameplay mechanics here.

| Command | Purpose | Example |
| ------- | ------- | ------- |
| `LOGIN <username> <password> [otp]` | Authenticates a session and binds it to an account on credential-bearing transports; append an OTP when two-factor auth is enabled. First-party `/ws/game/**` may instead use bare `LOGIN` after bootstrap/connect-token validation. | `LOGIN demo@example.com swordfish 123456` |
| `LOGON <username> <password> [otp]` | Exact alias for `LOGIN`; Telnet users often prefer the shorter name when typing from prompts. | `LOGON demo@example.com swordfish` |
| `WORLDS` | Lists worlds the authenticated account can enter from Account Service membership, public-production discovery, and entitlement state. Brand-new authenticated accounts may still see the default public production realm. | `WORLDS` |
| `REALMS <world>` | Lists visible realms for a world, where `<world>` is a world slug or a menu index from `WORLDS`. The default public production realm may be visible before membership exists; additional realms require explicit grants. | `REALMS demo` |
| `CHARS <world> [realm]` | Lists characters for a world and optional realm from the authoritative character store, filtered to `{accountId, tenantId, gameInstanceId}` ownership. | `CHARS demo production` |
| `PLAY <world> [realm] [character]` | Binds the authenticated connection to a world, realm, and character after `LOGIN`, enforcing tenant authorization, public-admission rules, realm routing, and entitlements. For the default public production realm, the first successful `PLAY` creates the caller's `player` membership atomically via Account Service. | `PLAY demo production 1` |
| `LOOK` | Requests the current room snapshot aggregated from Game Logic plus World and Entity services. | `LOOK` |
| `SAY <text>` | Broadcasts chat text to everyone in the same room. | `SAY Hello travelers` |
| `YELL <text>` | Alias for `SAY` rendered with higher emphasis while still delivering to the current room. | `YELL Hear me, comrades` |
| `WHISPER <player> <text>` | Directed chat that points at a single nearby player. | `WHISPER Sora The forge smells of brimstone` |

Selector rules for `PLAY` match the lobby helpers: `<world>` accepts a stable world slug or a menu index from `WORLDS`, `[realm]` accepts a realm slug or a menu index from `REALMS`, and `[character]` is an optional name or index when the resolved realm exposes exactly one visible character choice.

## Login and Play Flow

Telnet and WebSocket clients share the line-based syntax, but transport context determines which `LOGIN` form is valid:

- For Telnet and generic WebSocket clients, bare `LOGIN` or `LOGON` is intended to start a prompt flow, while `LOGIN <username> <password> [otp]` performs an immediate authentication attempt.
- For first-party `/ws/game/**` sessions that already carry a validated Gateway connect context, bare `LOGIN` completes gameplay authentication from the pre-established bootstrap identity instead of prompting for credentials.
- OTP values on credential-bearing logins are passed through verbatim to Account Service so two-factor accounts get the same behavior across transports.
- The same `OK <COMMAND>` and `ERROR <CODE> <message>` response format applies to all transports so clients can react consistently.

Prompt-based exchanges are planned but not implemented in this slice for Telnet and non-bootstrap clients. On those transports, bare `LOGIN` currently returns `ERROR PROMPT_LOGIN_UNSUPPORTED Prompt-based login is not implemented yet; send LOGIN <username> <password>.` First-party `/ws/game/**` sessions with a validated connect context are the exception: bare `LOGIN` consumes the bootstrap-backed context and must not ask the browser to resend credentials.

After `LOGIN` succeeds, clients must complete realm-aware lobby selection with `WORLDS`, optional `REALMS`, optional `CHARS`, and then `PLAY <world> [realm] [character]` before gameplay commands such as `LOOK` or `SAY`. This play step binds the authenticated connection to a world-scoped gameplay session and enforces tenant authorization, realm routing, public-admission rules, and entitlements.

Handshake failures such as HTTP `403` `CONNECT_TOKEN_REJECTED` or `POLICY_DENY` happen before the gameplay protocol is established and therefore are not emitted as text-protocol `ERROR <CODE>` frames. The command examples below begin only after a socket is already open and the line-based gameplay protocol is active.

For first-party `/ws/game/**` sessions, `PLAY` scope checks, including `tenantId` and `gameInstanceId`, must use the gateway-signed connect context carried in `X-Firemud-Connect-Context` and validated by Game Session rather than raw forwarded headers. Missing, invalid, expired, or replayed context where connect-token validation was required must fail admission with `CONNECT_CONTEXT_INVALID`. Mismatched validated scope fails with `CONNECT_SCOPE_MISMATCH`.

Canonical first-party `PLAY` scope errors on `/ws/game/**`:

- `CONNECT_CONTEXT_INVALID` – required gateway-signed connect context is missing or failed validation because of signature, expiry, replay, or key-verification failure.
- `CONNECT_SCOPE_MISMATCH` – validated connect context does not match the requested `{tenantId, gameInstanceId}` scope.

If a gameplay session already exists for the selected `{tenantId, gameInstanceId, characterId}` and is still resumable, meaning its TTL, current membership authority, and current revocation state are all valid, `PLAY` resumes it and rebinds the new socket to the existing session. On successful resume, Game Session also rebinds the session to a fresh backend token for subsequent internal calls rather than depending on the previous token to remain valid. If no resumable session exists, `PLAY` creates a new gameplay session binding. Even after reconnect, the client must still send an explicit `PLAY` so the platform never guesses which tenant or character to resume.

If a client attempts gameplay commands before selecting a world, the service returns `ERROR WORLD_NOT_SELECTED Use WORLDS/PLAY first` so clients can recover deterministically.

### Login and world-selection examples

Illustrative world-selection transcript showing slug and index equivalence:

```text
WORLDS
OK WORLDS
1) Demo World (demo)
2) Builder Sandbox (sandbox)

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
- `WORLD_NOT_SELECTED` – a gameplay command that requires admitted world scope was sent before `PLAY` completed successfully.
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
OK PLAY Entered world: Demo World
```

The transcript above shows the intended prompt flow. In the current implementation the same exchange is represented by a single `LOGIN <username> <password>` call because the prompt-driven handler still returns `ERROR PROMPT_LOGIN_UNSUPPORTED ...`.

Telnet success, using the current implementation form:

```text
LOGIN demo@example.com swordfish
OK LOGIN Logged in as demo@example.com

WORLDS
OK WORLDS
1) Demo World (demo)

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

`SAY`, `YELL`, and `WHISPER` emit a shared success payload so Telnet and WebSocket clients can render the same transcript. After a successful chat command the server responds with:

```text
OK SAY
Speaker: Emberline
Delivered-To: Emberline, Sora, Kobold Scout
Message: Hello travelers
```

`Speaker` annotations let clients highlight who originated the message while `Delivered-To` lists the recipients that observed the chat frame. In production gameplay, the `Delivered-To` list is scoped to recipients visible to the speaking player and may be redacted or disabled behind feature flags; its primary purpose is deterministic tests and debugging.
The `Message` line echoes the trimmed chat text so transport implementations can either render the structured payload directly or stitch it into transport-specific narration.

Chat parsing enforces that `SAY` and `YELL` include at least one non-whitespace character and that `WHISPER` provides both an existing player identifier and the message text. Submitting an empty payload or exceeding the configured message limit, currently 512 characters, yields `ERROR INVALID_ARGUMENT Message text must be 1-512 characters long`. A missing whisper target or text returns the same `ERROR INVALID_ARGUMENT` guidance so clients can keep their parsers simple.

### LOOK transcripts

Telnet `LOOK` example:

```text
PLAY demo
OK PLAY Entered world: Demo World

LOOK
OK LOOK
Room: Candle-lit Antechamber (Room Instance ID: R-1021)
Short: You stand in a basalt chamber warmed by the brazier near the western wall.
Long: Stalactites drip along the northern wall while a faint draft carries the smell of damp earth from the lower tunnels. Torches flicker in alcoves, casting motion into the shadowy archway to the north.
Exits: NORTH (arched passage leading toward the cavern mouth), EAST (narrow fissure descending toward the forges).
Entities:
- NPC "Kobold Scout" (alert, checking the eastern balustrade)
- Player "Sora" (leaning against the southern pillar)
```

WebSocket `LOOK` example:

```text
PLAY demo
OK PLAY Entered world: Demo World

LOOK
OK LOOK
Room: Crafting Hall of Ember (Room Instance ID: R-2045)
Short: A vaulted hall lined with anvils and hanging banners.
Long: Sparks drift upward from the forges while metalworkers shout over the rhythm of hammers; the far wall is dominated by the etched sigil of the Ember Guild.
Exits: SOUTH (wide stair toward the guild atrium), WEST (narrow corridor past the glazing ovens).
Entities:
- NPC "Master Smith Torga" (wiping soot from his shoulders)
- Player "Sora" (now near the south stair, waving to a passing engineer)
```

### LOOK request flow

1. Game Session validates the Redis-backed session context created by a successful `LOGIN` or `LOGON`. If the guard fails, it immediately returns `ERROR NOT_AUTHENTICATED`.
2. Authenticated `LOOK` commands call Game Logic's `ResolveLook`, passing `tenantId`, `gameInstanceId`, `sessionId`, `playerId`, and `roomInstanceId`.
3. Game Logic returns a structured `LookResult`, which Game Session renders into the `OK LOOK` text response, emits `gamesession.command.look.*` metrics/logs, and caches per session so reconnections can replay it quickly.
4. Reconnecting Telnet or WebSocket clients receive the cached snapshot before buffered commands replay. If the snapshot is missing or stale, Game Session reruns `ResolveLook`.

### LOOK error mapping and metrics

`LOOK` maps downstream failures into protocol-level errors so clients see a stable failure surface:

- `ERROR ROOM_NOT_FOUND`
- `ERROR WORLD_UNAVAILABLE`
- `ERROR ENTITY_UNAVAILABLE`
- `ERROR LOOK_UNAVAILABLE`
- `ERROR UNEXPECTED`

Metrics `gamesession.command.look.invocations` and `gamesession.command.look.failures` are tagged with `tenantId` and, when applicable, `error` so operators can correlate client-visible failures with the underlying cause quickly.

### SAY request flow

1. Game Session validates the same Redis-backed session context leveraged by `LOOK`; unauthenticated inputs are rejected with `ERROR NOT_AUTHENTICATED`.
2. Authenticated `SAY`/`YELL`/`WHISPER` commands route through `SayCommandHandler`, which packages `tenantId`, `gameInstanceId`, `sessionId`, `playerId`, `roomInstanceId`, normalized text, and alias metadata into a `BroadcastSay` gRPC request to Game Logic.
3. Game Logic evaluates room visibility, enforces message constraints, and forwards the payload, or a stubbed notification, to Social & Groups Service for delivery and logging.
4. Backend failures propagate protocol-mapped errors such as `ERROR SAY_NOT_DELIVERED` while `ERROR NOT_AUTHENTICATED` remains the consistent pre-flight guard.

## Response Format

- System commands such as `LOGIN`, `LOGON`, `PING`, and lightweight state queries are allowed to produce synchronous responses without enqueuing gameplay actions. Their side effects stay limited to session binding, health checks, or read-only projections.
- Gameplay commands such as `LOOK`, `SAY`, movement, and combat are tick-driven actions. Game Session validates and normalizes them, emits enqueue metadata, and must not perform gameplay state mutations outside the tick executor.
- If the interpreter produces both immediate text and enqueue metadata and the enqueue step fails, for example because of a Redis outage, Game Session surfaces a single `ERROR` response and does not report success followed by a dropped action.
- Every response is plain text. The first line is either `OK <COMMAND>` or `ERROR <CODE> <message>`.
- Success responses may include additional lines describing the outcome.
- A blank line terminates the response block so multiple responses can be streamed back-to-back without ambiguity.
- Asynchronous world events use the same rules but are prefixed with `EVENT <TYPE>` to distinguish them from direct command responses.
- Unknown commands return `ERROR UNKNOWN_COMMAND <rawLine>`.

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
OK SAY
Speaker: Emberline
Delivered-To: Emberline, Sora, Kobold Scout
Message: Hello travelers
A kobold says: Stay sharp.

DANCE
ERROR UNKNOWN_COMMAND DANCE
```
