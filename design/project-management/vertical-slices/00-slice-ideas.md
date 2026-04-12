# Vertical Slice Ideas Index

## Beyond MVP - Future Enhancements

- **Advanced Reconnection Replay** - Extend the reconnect cache to replay buffered commands or cached room/global context so reconnecting clients are dropped back in without losing actions.
- **Dynamic Room Scripts/Triggers** - Let rooms surface scripted events or ambient messages after a `LOOK`, proving Game Logic can orchestrate scripting or event services alongside the base description.
- **Social Channels & NPC Responses** - Expand SAY to feed NPC dialogue or group channels, showcasing richer text patterns and multi-service orchestration (Social, Entity, Group services).
- **Shout and Communication Scope Settings** - Add `shout` as a future built-in only after the game-settings/configuration model can express topology-dependent propagation such as region-wide versus map-wide delivery when regions are absent or disabled.

Note: After choosing the next slice, add a corresponding numbered task list file, update this index, and reconcile any duplicated items in the existing per-service status docs and design docs.

## Promoted Into Numbered Slice Families

- `08` Game Design Publishing and Runtime Activation now captures the publish/version/asset/launch control-plane domain that previously existed mostly as architecture-only planning.
- `09` Multi-Tenancy, Realm Routing, and Runtime Boundaries now captures the tenant/realm/runtime-boundary domain that previously existed mostly as architecture and adjacent admission/reconnect notes.
- `10` Scripting, Automation, and Runtime Orchestration now captures the scripting runtime/control-plane/execution domain that previously existed mostly as fragmented architecture and adjacent runtime slices.
