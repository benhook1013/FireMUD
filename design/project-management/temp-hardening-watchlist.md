# Temp Hardening Watchlist

Temporary tracking for still-live audit items that need implementation or follow-up validation.

## Fix Now

- [x] Entity-management `ListCharactersByAccount` is tenant-scoped in both request and service path.
- [x] `game-design-service` publish flows no longer hold DB transactions open across S3 export and gRPC notification work.
- [x] `game-session-service` session start/stop flows no longer hold DB transactions open across saga and Redis side effects.

## Fix Soon

- [x] `social-groups-service` chat history Redis writes are now deferred until after DB commit.
- [x] `social-groups-service` account-level friendships now carry tenant scope in storage/schema and service mapping.

## Refactor / Hygiene

- [x] Replace direct `new ObjectMapper()` construction in runtime services with injected shared Jackson infrastructure.
- [x] Replace direct `new JwtUtil(...)` construction in `FirstPartyConnectContextService` with injected/shared JWT infrastructure.

## Already Addressed Elsewhere

- [x] Gateway header spoofing and replay handling hardening.
- [x] Redis session-context indexing no longer uses naive multi-key writes.
- [x] Some `social-groups-service` moderation/reporting side effects already moved to `afterCommit`.
