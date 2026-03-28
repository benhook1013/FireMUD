# Vertical Slice Ideas Index

This zero-indexed overview sits at the top of the design/project-management/vertical-slices folder. When a slice is selected for work, add a full task list file and update this index so the team can quickly see what's already live, what follow-up slices exist, and what's queued next.

0. **Login and Session Hardening** - Follow-up slice to reduce `dev-isolated` dependence, re-enable representative integration coverage, and make the existing login/session path more trustworthy without reopening the original login feature scope (planned in `02.1-task-list-login-session-hardening-vertical-slice.md`).
1. **Directional Movement (MOVE/GO)** - Use the current World snapshots and movement primitives to validate exits, update player location, and stream a fresh `LOOK` result so movement becomes part of the real gameplay loop (planned in `05-task-list-movement-vertical-slice.md`).

---

## Beyond MVP - Future Enhancements

- **Advanced Reconnection Replay** - Extend the reconnect cache to replay buffered commands or cached room/global context so reconnecting clients are dropped back in without losing actions.
- **Dynamic Room Scripts/Triggers** - Let rooms surface scripted events or ambient messages after a `LOOK`, proving Game Logic can orchestrate scripting or event services alongside the base description.
- **Social Channels & NPC Responses** - Expand SAY to feed NPC dialogue or group channels, showcasing richer text patterns and multi-service orchestration (Social, Entity, Group services).
- **Speech Modes & Propagation** - Split room speech from communication semantics so `WHISPER`, `TELL`, `SHOUT`, guild/group channels, and game-configured speech types can define explicit audience scope and propagation rules such as room, area, region, map, or continent delivery.
- **Speech Scope & Propagation** - Evolve the current `SAY`-centric room chat slice into explicit communication modes (`say`, `whisper`, `tell`, `shout`, channel/system variants) with audience/propagation rules such as same-room, directed target, nearby area, map, region, or continent scope.

Note: After choosing the next slice, add a corresponding numbered task list file, update this index, and reconcile any duplicated items in the existing per-service status docs and design docs.
