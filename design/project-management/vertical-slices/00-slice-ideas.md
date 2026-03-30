# Vertical Slice Ideas Index

This zero-indexed overview sits at the top of the design/project-management/vertical-slices folder. When a slice is selected for work, add a full task list file and update this index so the team can quickly see what's already live, what follow-up slices exist, and what's queued next.

0. **Login and Session Hardening** - Follow-up slice to reduce `dev-isolated` dependence, re-enable representative integration coverage, and make the existing login/session path more trustworthy without reopening the original login feature scope (planned in `02.1-task-list-login-session-hardening-vertical-slice.md`).
1. **Directional Movement (MOVE/GO)** - Use the current World snapshots and movement primitives to validate exits, update player location, and stream a fresh `LOOK` result so movement becomes part of the real gameplay loop (planned in `05-task-list-movement-vertical-slice.md`).
2. **Inventory, Containers, and Equipment** - Extend the playable loop with hidden inventory containers, room-attached ground containers, first-class equipment bindings, game-configurable slot/body-layout rules, richer inventory queries, and an auditable transfer trail (planned in `06-task-list-inventory-containers-equipment-vertical-slice.md`).
3. **Gameplay Admission UX Alignment** - Align the login/lobby/player-facing command flow with a simpler MUD-like experience: public `WORLDS` browsing, `LOGIN`, and `PLAY <world> [character]` as the normal happy path, with `SESSION` demoted to advanced-client metadata (planned in `02.2-task-list-gameplay-admission-ux-vertical-slice.md`).
4. **Reconnect and Session Recovery Semantics** - Bring the implemented reconnect/takeover behavior into line with the richer explicit recovery model in the design docs, including edge restarts, fresh connect tokens, and clearer resume outcomes (planned in `02.3-task-list-reconnect-and-session-recovery-vertical-slice.md`).
5. **Shared Communication Infrastructure** - Refactor the initial room-speech path into a reusable communication system that all later communication actions can use, while keeping `say` as the first fully live mode (planned in `04.1-task-list-shared-communication-infrastructure-vertical-slice.md`).
6. **Whisper Communication** - Implement the first true target-directed in-room communication mode with default metadata-only observer outcomes (planned in `04.2-task-list-whisper-vertical-slice.md`).
7. **Tell Communication** - Implement the first direct non-room-scoped communication mode as a live-only gameplay tell rather than mail (planned in `04.3-task-list-tell-vertical-slice.md`).
8. **Communication Observers & Interceptors** - Add the first real eavesdropping/spy-style communication mechanic on top of the shared communication model (planned in `04.4-task-list-communication-observers-and-interceptors-vertical-slice.md`).

---

## Beyond MVP - Future Enhancements

- **Advanced Reconnection Replay** - Extend the reconnect cache to replay buffered commands or cached room/global context so reconnecting clients are dropped back in without losing actions.
- **Dynamic Room Scripts/Triggers** - Let rooms surface scripted events or ambient messages after a `LOOK`, proving Game Logic can orchestrate scripting or event services alongside the base description.
- **Social Channels & NPC Responses** - Expand SAY to feed NPC dialogue or group channels, showcasing richer text patterns and multi-service orchestration (Social, Entity, Group services).
- **Shout and Communication Scope Settings** - Add `shout` as a future built-in only after the game-settings/configuration model can express topology-dependent propagation such as region-wide versus map-wide delivery when regions are absent or disabled.

Note: After choosing the next slice, add a corresponding numbered task list file, update this index, and reconcile any duplicated items in the existing per-service status docs and design docs.
