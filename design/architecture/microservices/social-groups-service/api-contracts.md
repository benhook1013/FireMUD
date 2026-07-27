# Social & Groups Service API Contracts

This document defines the Social & Groups Service REST and gRPC surfaces, chat-delivery APIs, and voice-token endpoint contract.

An OpenAPI specification for the REST endpoints is available at `src/main/resources/openapi.yaml` in the service repository.

## REST APIs

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/ping` | Basic health check returning `"pong"` |
| `POST` | `/friends` | Create a friend link |
| `GET` | `/friends/presence` | List bounded cross-game friend presence for one account |
| `POST` | `/mail` | Send an asynchronous in-game mail message; mail retrieval endpoints are also available |
| `POST` | `/guilds` | Create a guild |
| `POST` | `/guilds/storage` | Add an item to guild storage |
| `POST` | `/guilds/alliances` | Create a guild alliance |
| `POST` | `/guilds/members` | Add a guild member |
| `POST` | `/guilds/members/role` | Update a guild member's role |
| `POST` | `/guilds/members/remove` | Remove a guild member |
| `POST` | `/chat` | Send a chat message filtered for profanity |
| `POST` | `/voice/token` | Issue a temporary WebRTC token for voice chat; the gateway relays media between participants |

Example health check:

```bash
curl http://localhost:8080/ping
```

Example chat request:

```bash
curl -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"tenant-abc","senderAccountId":100,"content":"hello"}'
```

Example voice-token request:

```bash
curl -X POST http://localhost:8080/voice/token \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"tenant-abc","accountId":100,"channelId":"guild-10"}'
```

## gRPC APIs

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in `social_groups_service.proto`
- `SendMessage` – publishes a chat message to an in-game channel or player
- `CreateGuild` – establishes a new guild with an owner account
- `AddFriend` – adds a friend relationship at the game or account level
- `ListFriendPresence` – returns bounded account-scoped friend presence, including canonical world/realm labels and conservative last-seen facts only when policy allows them. Player-facing responses never include disconnect reason or disposition. Social & Groups reads raw current/recent presence facts from Game Session and current profile visibility in one bounded Account Service bulk read; missing or unavailable Account policy defaults to `PRIVATE` before any field projection.
- `SendMail` – stores asynchronous player mail for later retrieval

### Friend Presence Privacy Contract

- Game Session owns raw live/recent gameplay-presence facts. Social & Groups is the only player-facing cross-game friend projection and obtains those facts through an internal caller-restricted bounded API.
- `WHO` remains an in-world current-instance roster. Profile presence privacy does not make a physically present player invisible there; game-defined invisibility or perception mechanics are a separate gameplay concern.
- `FRIENDS_ONLY` is the new-profile default and applies only to mutually accepted friend relationships. It exposes online/offline state to mutual friends. A world/realm label is included only when the viewer can independently discover that location; otherwise location and hidden realm identity remain private.
- `PRIVATE`, blocked relationships, missing policy, malformed policy, and policy-service unavailability expose no online/offline state, last-seen timestamp, character, location, activity, disconnect disposition, or policy label. The friendship record may remain visible, but its presence projection is absent.
- `PUBLIC` is a disclosure policy, not an authorization grant. Every request must derive `viewerAccountId` from authenticated caller context and authorize that viewer for the requested surface and each returned subject. `ListFriendPresence` is limited to subjects in the viewer's mutual accepted-friend snapshot; any single-subject lookup must name one exact subject and pass the same viewer/subject authorization check. A request-supplied viewer ID or a subject's `PUBLIC` value never grants access by itself.
- The presence surface never provides an anonymous or arbitrary-account directory, wildcard or prefix search, online counts, presence-sorted results, or pagination outside the authorized friend snapshot. Denied, missing, blocked, and non-public subjects use the same redacted/no-entry shape so the surface cannot be used to enumerate account existence or privacy state.
- Player-facing presence never exposes disconnect reason or disposition. Those facts remain available only to authorized diagnostic/support surfaces.
- `HIDDEN_STAFF` and all unknown or legacy visibility values are unsupported player-facing values and fail closed as `PRIVATE` before projection. Global or support roles do not automatically hide a gameplay actor, and support diagnostics remain separate from player presence.
- Friend lists larger than one raw-presence or policy batch use an opaque, short-lived snapshot established from the authorized friend set and its bounded presence/policy read epoch. The snapshot binds the viewer, subject set, filters, page size, and deterministic friend-ordinal/account-ID order; continuation tokens must use that same snapshot and must not re-query, reorder, duplicate, or skip subjects. Every Account and Game Session chunk is at most 100 account IDs. An expired snapshot returns a page-expired error with no entries or continuation token. A raw-presence, authorization, or chunk-shape/transport failure fails the whole page with no partial entries or continuation token; the client must restart from a new snapshot. A defined unavailable Account policy result instead yields a complete page with private redaction for all affected subjects, never a mixed success/failure page.

```bash
grpcurl -plaintext localhost:6565 social_groups.v1.SocialGroupsService/Ping
```

## Delivery Semantics

- In-game communication actions such as `say`, `whisper`, `tell`, guild chat, and mail originate in the Game Logic Service and incorporate context from the World Management and Entity Management services where needed.
- The Game Logic Service invokes this service to deliver messages, run profanity checks, persist history, and log communications for audit and moderation.
- Voice chat is an optional feature layered on a lightweight WebRTC gateway; the service issues temporary tokens via `/voice/token` and records voice activity for moderation.
- The current gameplay-connected `say` slice should be treated as the first implemented communication action only. It proves the cross-service path, but it is not the final abstraction for all communication.
- Future gameplay communication should preserve a distinction between:
  - the speech act (`SAY`, `WHISPER`, `SHOUT`, `TELL`, system narration, or game-defined variants),
  - the delivery scope or target object (directed target, room, local area, region, map, continent, guild/group, account-directed, and other configured channels),
  - recipient-resolution rules owned by that target, including observer/interceptor handling such as eavesdropping or spy mechanics,
  - and per-recipient presentation/rendering metadata.
- Later slices may allow world-topology-aware propagation rules such as area-local whispers, map-wide shouts, or continent-scoped announcements. Social & Groups should accept those semantics through explicit delivery metadata rather than inferring them from a room-chat alias alone.
- The preferred target-state is a configurable communication envelope where a communication intent names a type definition plus one or more targets/scopes. Social & Groups should receive explicit resolved delivery metadata and presentation directives from the gameplay orchestration layer rather than re-deriving spatial context from a verb name alone.
- Even when Social & Groups owns durable history, moderation, membership checks, or fanout for a communication type, the action should still enter through Game Logic so gameplay abilities, items, and perception/interception rules can participate consistently across all communication modes.
