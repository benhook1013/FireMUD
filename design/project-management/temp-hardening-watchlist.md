# Temp Hardening Watchlist

Temporary tracking for still-live audit items that need implementation or follow-up validation.

## Fix Now

- [ ] Entity-management `ListCharactersByAccount` leaks cross-tenant character enumeration and exposes `tenant_id` on a tenant-agnostic request/contract.
- [ ] `game-design-service` publish flows hold DB transactions open across S3 export and gRPC notification work.
- [ ] `game-session-service` session start/stop flows hold DB transactions open across saga and Redis side effects.

## Fix Soon

- [ ] `social-groups-service` chat history Redis writes are non-atomic and can diverge from the surrounding DB transaction.
- [ ] `social-groups-service` account-level friendships have no tenant scope in storage/schema even though the API is tenant-scoped.

## Refactor / Hygiene

- [ ] Replace direct `new ObjectMapper()` construction in runtime services with injected shared Jackson infrastructure.
- [ ] Replace direct `new JwtUtil(...)` construction in `FirstPartyConnectContextService` with injected/shared JWT infrastructure.

## Already Addressed Elsewhere

- [x] Gateway header spoofing and replay handling hardening.
- [x] Redis session-context indexing no longer uses naive multi-key writes.
- [x] Some `social-groups-service` moderation/reporting side effects already moved to `afterCommit`.
