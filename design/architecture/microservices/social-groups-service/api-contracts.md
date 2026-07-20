# Social & Groups Service API Contracts

This document defines the Social & Groups Service REST and gRPC surfaces, chat-delivery APIs, and voice-token endpoint contract.

An OpenAPI specification for the REST endpoints is available at `src/main/resources/openapi.yaml` in the service repository.

## REST APIs

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/ping` | Basic health check returning `"pong"` |
| `POST` | `/friends/requests` | Request a tenant-local or account-global friendship; account-global requests omit tenant scope |
| `POST` | `/friends/requests/{requestId}/accept` | Accept a pending friendship request as its target |
| `POST` | `/friends/requests/{requestId}/reject` | Reject a pending friendship request as its target |
| `DELETE` | `/friends/{relationshipId}` | Remove an accepted relationship as either participant |
| `PUT` | `/blocks/{accountId}` | Create a directional block in the declared global or tenant-local scope |
| `DELETE` | `/blocks/{accountId}` | Remove the caller's directional block in the declared scope |
| `GET` | `/friends/presence` | List bounded cross-game friend presence for one account |
| `POST` | `/mail` | Send an asynchronous in-game mail message; mail retrieval endpoints are also available |
| `POST` | `/guilds` | Create a guild |
| `POST` | `/guilds/{guildId}/container-binding` | Bind the Social-owned guild ACL to an Entity-owned container; item mutation remains an Entity operation |
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
- `CreateGuild` – establishes a new guild or group with an owner and one declared `ACCOUNT` or `CHARACTER` membership-subject type
- Relationship lifecycle operations – request, accept, reject, remove, block, and unblock tenant-local or genuinely tenant-free account-global relationships; no operation lets one caller create an accepted friendship unilaterally
- `ListFriendPresence` – returns bounded account-scoped friend presence, including canonical world/realm labels, conservative last-seen facts, and recent disconnect disposition when policy allows it. Social & Groups reads raw current/recent presence facts from Game Session and current profile visibility in one bounded Account Service bulk read; missing or unavailable Account policy defaults to `PRIVATE` before any field projection.
- `SendMail` – stores asynchronous player mail for later retrieval

### Relationship, Group, and Value Authority

- Account-global relationships use tenant-free account-pair identity. Tenant-local relationships are separate tenant-qualified records; callers must select the scope explicitly.
- A group with `ACCOUNT` membership identifies members by `accountId`. A group with `CHARACTER` membership identifies them by `{playableStateNamespaceId, characterId}` within the tenant. Membership, role, ownership, audience, and ACL operations reject identifiers of the wrong type.
- Account supplies account identity/status and profile-visibility policy. Game Session supplies raw presence and owns connected transports. These are inputs to a Social projection, not Social mutation APIs.
- Social authorizes its guild ACL and exposes the binding to an Entity-owned container, but has no API that creates item-name/quantity or currency rows. Item and currency owners perform deposits, withdrawals, and escrow.
- Mail without world-specific semantics enters Social directly. Attached value is represented by stable owner-controlled escrow references. World-specific mail rules may enter Game Logic, but ordinary private mail is not exposed to tenant-authored scripts.
- Ordinary users own friendship consent and block transitions. Tenant operators have only explicitly authorized moderation/support operations and cannot call an override that fabricates an accepted friendship.
- The current OpenAPI, proto, schema, and authorization implementation do not yet prove this target lifecycle, typed membership, or value-transfer boundary and must converge before the surfaces are claimed implemented.

### Friend Presence Privacy Contract

- Game Session owns raw live/recent gameplay-presence facts. Social & Groups is the only player-facing cross-game friend projection and obtains those facts through an internal caller-restricted bounded API.
- `WHO` remains an in-world current-instance roster. Profile presence privacy does not make a physically present player invisible there; game-defined invisibility or perception mechanics are a separate gameplay concern.
- `FRIENDS_ONLY` is the new-profile default and applies only to mutually accepted friend relationships. It may expose online/offline state and a world/realm label only when the viewer can independently discover that target. Otherwise it reports online with location private and discloses no hidden realm identity.
- `PRIVATE`, blocked relationships, missing policy, malformed policy, and policy-service unavailability expose no online/offline state, last-seen timestamp, character, location, activity, disconnect disposition, or policy label. The friendship record may remain visible, but its presence projection is absent.
- `PUBLIC` permits deliberate authenticated presence lookup only through a separately authorized bounded social surface. It never creates a globally enumerable online-player directory.
- Player-facing presence never exposes disconnect reason or disposition. Those facts remain available only to authorized diagnostic/support surfaces.
- `HIDDEN_STAFF` is not a player-facing visibility value. Global or support roles do not automatically hide a gameplay actor, and support diagnostics remain separate from player presence.
- Friend lists larger than one raw-presence or policy batch are paginated/chunked under the same snapshot and redaction rules; implementations must not fail open, truncate silently, or exceed the 100-account downstream bound.

```bash
grpcurl -plaintext localhost:6565 social_groups.v1.SocialGroupsService/Ping
```

## Delivery Semantics

- Communication uses the explicit ingress classes in [ADR 0134](../../decisions/adr-0134-explicit-communication-classes-and-owner-delivery.md).
- World/gameplay communication such as `say`, nearby `whisper`, gameplay `tell`, and future topology-aware `shout` enters Game Logic so world and entity context, game rules, perception, and interception can participate. Game Logic supplies a bounded resolved communication plan; Social & Groups applies its moderation, history, and relevant social-audience responsibilities; Game Session owns final connected-gameplay transport delivery.
- Account messaging, ordinary guild/group channels, mail, and browser social interactions enter Social & Groups directly after authentication, membership, privacy, and moderation checks. An in-game command may adapt to these APIs without making the operation a Game Logic action. Tenant-authored DSL cannot reclassify private platform communication for script inspection.
- Existing online `tell` may remain a gameplay action when abilities or world rules apply, but its standard type has no observer path. Interception requires a deliberately distinct published communication type and mechanic. Account direct messages and mail remain social operations even if presented through an in-game client.
- Operator and platform-system communication enters through the service that owns the originating authorization and audit contract and uses typed handoffs to the applicable audience, history, and transport owners.
- Voice chat is an optional feature layered on a lightweight WebRTC gateway; the service issues temporary tokens via `/voice/token` and records voice activity for moderation.
- The current gameplay-connected `say` slice should be treated as the first implemented communication action only. It proves the cross-service path, but it is not the final abstraction for all communication.
- Future gameplay communication should preserve a distinction between:
  - the speech act (`SAY`, `WHISPER`, `SHOUT`, `TELL`, system narration, or game-defined variants),
  - the delivery scope or target object (directed target, room, local area, region, map, continent, guild/group, account-directed, and other configured channels),
  - recipient-resolution rules owned by that target, including observer/interceptor handling such as eavesdropping or spy mechanics,
  - and per-recipient presentation/rendering metadata.
- Later slices may allow world-topology-aware propagation rules such as area-local whispers, map-wide shouts, or continent-scoped announcements. Social & Groups should accept those semantics through explicit delivery metadata rather than inferring them from a room-chat alias alone.
- The preferred target-state is a configurable communication envelope where a communication intent names a type definition plus one or more targets/scopes. Social & Groups should receive explicit resolved delivery metadata and presentation directives from the gameplay orchestration layer rather than re-deriving spatial context from a verb name alone.
- Only world/gameplay communication must enter Game Logic. Private platform and ordinary social-channel communication remains independent of Game Logic availability and tenant-authored gameplay semantics.
- Only world/gameplay communication is eligible for gameplay observation. A published communication type version declares a small closed set of permitted observer-view classes and the exact safe metadata fields for each non-full view. Gameplay `TELL` has no observer path by default, and `WHISPER` observation requires an explicit authored mechanic. Partial content is introduced only with a concrete typed mechanic, never through a free-form observer policy.
- Game Logic supplies bounded candidate-specific authorized views from authoritative topology, capabilities, senses, and mechanics. Missing, stale, contradictory, or oversized resolution fails without over-delivery. Social & Groups applies social authorization, moderation, history, retention, and delivery-state constraints but does not infer spatial observers or broaden the view; Game Session performs connected-client delivery.
- `SHOUT` has no platform-global scope and remains unimplemented until a game or default profile declares a named bounded topology scope. Area-wide in one profile and map-wide in another are both valid. Operator fanout caps apply, and large permitted audiences use stable bounded/chunkable delivery with explicit outcomes, diagnostics, and metrics rather than silent truncation. See [ADR 0137](../../decisions/adr-0137-closed-observer-views-and-profile-scoped-shout.md).

## History, Evidence, and Acknowledgement

Communication storage follows [ADR 0136](../../decisions/adr-0136-communication-type-specific-history-and-retention.md). Every supported communication type declares durable player-history behavior, finite moderation or safety-evidence behavior, retention, access, export, erasure or tombstones, and acknowledgement semantics. A generic send endpoint must not silently assign indefinite persistence to a type whose contract is transient.

- Mail, account direct messages, and channels promising scrollback are durably committed before the API returns the corresponding durable-acceptance outcome.
- World speech is live by default and does not expose permanent player history unless its type explicitly promises a bounded history surface.
- Safety evidence is a separately authorized finite record, not an implicit player-history extension. Logging & Admin cases and audit records remain separate from Social communication evidence.
- A content-free idempotency receipt may survive content expiry and return the previous outcome for an identical retry; it cannot reconstruct content or audience.
- Cache/Rate-Limit Redis is a rebuildable history projection and fanout mechanism only. Its loss cannot erase promised durable history or authorize an otherwise forbidden replay.
- History and export return no more than the semantic view originally authorized for that recipient. Metadata-only, redacted, or partial observers cannot retrieve full content through another endpoint or a later cache refill.
- Semantic admission, durable-history acceptance, evidence capture, transport attempt, and end-user delivery are distinct typed outcomes unless the communication type explicitly requires one before another.

The current REST, gRPC, persistence, and retrieval surfaces do not yet prove these distinctions. Complete implementation requires type-versioned storage declarations, lifecycle and erasure handling, recipient-view-bound reads, and typed acknowledgement outcomes rather than interpreting one successful `SendMessage` response as every form of persistence and delivery.
