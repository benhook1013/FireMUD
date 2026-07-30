# Social & Groups Service API Contracts

This document defines the Social & Groups Service REST and gRPC surfaces, chat-delivery APIs, and voice-token endpoint contract.

An OpenAPI specification for the REST endpoints is available at `src/main/resources/openapi.yaml` in the service repository.

## Implementation Status

The friend-presence slice currently implements non-pageable friend-roster and presence reads; it does not create snapshots, continuations, or paginated bulk pages. Snapshot-bound continuation, current relationship/block revalidation, bounded paginated bulk reads, and the failure-precedence contract below remain target behavior; this document does not claim that the current implementation or existing tests prove those target obligations.

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
- `ListFriendPresence` – returns bounded account-scoped friend presence, including canonical world/realm labels and conservative last-seen facts only when policy allows them. Player-facing responses never include disconnect reason or disposition. The target-state projection reads raw current/recent presence facts from Game Session and current profile visibility through bounded, chunked bulk reads, with each Account or Game Session chunk limited to 100 account IDs and each page limited to 100 subjects; missing, malformed, unknown, or legacy Account policy is evaluated as internal `PRIVATE` before any field projection, and that policy value is never serialized. The current implementation remains non-pageable as described above.
- `SendMail` – stores asynchronous player mail for later retrieval

### Friend Presence Privacy Contract

- Game Session owns raw live/recent gameplay-presence facts. Social & Groups is the only player-facing cross-game friend projection and obtains those facts through an internal caller-restricted bounded API.
- `WHO` remains an in-world current-instance roster. Profile presence privacy does not make a physically present player invisible there; game-defined invisibility or perception mechanics are a separate gameplay concern.
- `FRIENDS_ONLY` is the new-profile default and applies only to mutually accepted friend relationships. For every policy, including `FRIENDS_ONLY`, the service derives `viewerAccountId` from authenticated caller context and authorizes against that viewer's accepted-friend snapshot; a caller-supplied identity or friendship membership alone is never authority. It exposes online/offline state to mutual friends. A world/realm label is included only when the viewer can independently discover that location; otherwise location and hidden realm identity remain private.
- `PRIVATE` is an internal evaluation result only. `PRIVATE`, missing, unknown, legacy, or malformed policy expose no online/offline state, last-seen timestamp, character, location, activity, disconnect disposition, policy label, or policy value. Policy-service unavailability exposes none of those fields and follows the uniform chunk/page redaction rule below. A candidate that remains currently authorized but has a stricter or `PRIVATE` policy remains in snapshot order and serializes solely as the existing redacted/no-presence-entry shape; an ordinary per-subject policy failure never rejects the page solely for that subject or exposes why it was redacted. A candidate that is no longer mutually accepted or is blocked in either direction is omitted during current-state revalidation.
- `PUBLIC` is a disclosure policy, not an authorization grant. Every policy uses the same authenticated-caller rule: derive `viewerAccountId` from caller context and authorize that viewer for the requested surface and each returned subject. `ListFriendPresence` is limited to subjects in the viewer's mutual accepted-friend snapshot; any single-subject lookup must name one exact subject and pass the same viewer/subject authorization check. A request-supplied viewer ID or a subject's `PUBLIC` value never grants access by itself.
- The presence surface never provides an anonymous or arbitrary-account directory, wildcard or prefix search, online counts, presence-sorted results, or pagination outside the authorized friend snapshot. Non-public does not by itself mean redacted: a mutually accepted friend may see online/offline state for a `FRIENDS_ONLY` subject. `PRIVATE`, missing, unknown, legacy, malformed, or subject-locally unauthorized policy evaluation uses the same redacted/no-presence-entry shape; candidates that current revalidation finds non-mutual or blocked are omitted. Neither outcome can be used to enumerate account existence or privacy state.
- Player-facing presence never exposes disconnect reason or disposition. Those facts remain available only to authorized diagnostic/support surfaces.
- `HIDDEN_STAFF` and all other unknown or legacy visibility values are treated as an internal `PRIVATE` evaluation for gameplay-field disclosure per subject: the subject remains in the authorized snapshot, all gameplay fields are redacted, and the legacy value is never serialized or exposed through player-facing rosters, filters, or summaries. `HIDDEN_STAFF` is not part of the player-facing policy vocabulary or selectable by account holders. Global or support roles do not automatically hide a gameplay actor, and support diagnostics remain separate from player presence.

### Target-State Snapshot, Chunking, Pagination, and Failure Precedence

The following rules define the target-state continuation contract; they do not describe the current non-pageable implementation identified in the implementation status above.

- Request-level authorization is evaluated before a friend snapshot is created. Invalid caller identity, an unauthorized requested surface, caller/snapshot mismatch, or Account failure to establish the caller's authoritative accepted-friend snapshot rejects the whole request with no entries or continuation token. Once request-level authorization succeeds, Account relationship/block-state revalidation remains request-fatal on a continuation; it is never converted into subject omission or policy redaction. A candidate that is no longer mutually accepted or is blocked in either direction is omitted during successful revalidation.
- Profile-policy reads have a separate failure class. After the request or continuation has passed Account authorization and relationship/block-state checks, an Account policy-read transport/chunk failure applies private-by-failure redaction uniformly to every subject in the page; successful policy chunks are not disclosed alongside a failed chunk. A missing, unknown, legacy, or malformed policy value is a subject-local `PRIVATE` result, not a request failure. This precedence is based on the operation performed, even when an implementation batches authorization, relationship, and policy data in one backend call.
- Whenever pagination can return a continuation, including when all backend reads fit in one chunk, the service creates an opaque, short-lived snapshot from the authorized friend set. The snapshot fixes the subject set and deterministic friend-ordinal/account-ID order and binds the viewer, filters, page size, and expiry; continuation tokens must use that same snapshot without rebuilding, reordering, duplicating, or skipping subjects. The platform caps snapshot subjects at 10,000, page size at 100, and candidates scanned per continuation at 1,000; lower tenant settings may narrow those caps. Snapshot creation above the effective subject cap or a page-size request above the effective page cap is rejected with no entries or continuation token. Every continuation walks strictly after `lastScanned` in that immutable order and authoritatively re-reads current mutual accepted-friend status, both-direction block state, and profile policy for every candidate before disclosure; it advances `lastScanned` after every candidate examined, consumes and omits candidates found no longer mutual or blocked, and emits only currently eligible subjects, using a redacted shape when policy requires it and advancing `lastEmitted` only for emitted subjects. This prevents duplicates or skips. If the effective candidate scan cap is reached before filling a page or establishing snapshot exhaustion, the continuation returns all entries accumulated during that scan plus a continuation advanced through `lastScanned`; entries are empty only when no subject was emitted during that scan. Every Account and Game Session chunk is at most 100 account IDs. An expired snapshot returns a `page-expired` error with no entries or continuation token. Game Session raw-presence transport or chunk-shape failure and any snapshot-integrity failure reject the whole page with no partial entries or continuation token; the client must restart from a new snapshot. Expiry retains the `page-expired` error rather than the scan-cap response.

Failure precedence is therefore: request shape, snapshot integrity, caller authorization, Account relationship/block-state failure, or Game Session raw-presence transport/chunk-shape failure rejects the request/page with no entries or continuation; reaching the scan cap before page fill or exhaustion returns the accumulated entries and a continuation advanced through `lastScanned`, with an empty page only when no subject was emitted during that scan; successful exhaustion returns accumulated entries without a continuation, with an empty page only when no subject was emitted; a successful relationship check that finds a subject no longer eligible consumes and omits that subject; policy transport failure redacts the whole page; and a missing, malformed, unknown, or legacy policy value redacts only the affected subject. The service never uses a policy-read failure to override a prior request-fatal authorization, integrity, or raw-presence failure.

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
