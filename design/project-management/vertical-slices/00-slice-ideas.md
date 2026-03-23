# Vertical Slice Ideas Index

This zero-indexed overview sits at the top of the design/project-management/vertical-slices folder. When a slice is selected for work, add a full task list file (01, 02, 03, ...) and update this index so the team can quickly see what's already live and what's queued.

0. **Data-Driven LOOK** - Already completed; the slice replaced the previous hard-coded `LOOK` flow with data from Game Logic > World > Entity and added reconnect caching/metrics (see `03-task-list-data-driven-look-vertical-slice.md`).
1. **Chat & SAY / Social Layer** - Outline the Game Logic broadcast path plus client wiring, metrics, and cross-service regression tests so players can speech the room and the pipeline captures delivery success/failure (drafted in `04-task-list-chat-and-social-vertical-slice.md`).
2. **Directional Movement (MOVE/GO)** - Use the freshly built World snapshots to validate exits, update player location in Game Logic, and stream a fresh `LOOK` result so movement is fully data-driven.

---

## Beyond MVP - Future Enhancements

- **Advanced Reconnection Replay** - Extend the reconnect cache to replay buffered commands or cached room/global context so reconnecting clients are dropped back in without losing actions.
- **Dynamic Room Scripts/Triggers** - Let rooms surface scripted events or ambient messages after a `LOOK`, proving Game Logic can orchestrate scripting or event services alongside the base description.
- **Social Channels & NPC Responses** - Expand SAY to feed NPC dialogue or group channels, showcasing richer text patterns and multi-service orchestration (Social, Entity, Group services).

Note: After choosing the next slice, add a corresponding numbered task list file, update this index, and reconcile any duplicated items in the existing per-service status docs and design docs.
