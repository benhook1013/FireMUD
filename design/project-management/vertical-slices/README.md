# Vertical Slice Design Docs

This directory tracks vertical slice plans for major end-to-end features in the FireMUD platform.

Each document describes a narrowly scoped slice of functionality, covering user experience, architecture, testing strategy, and incremental rollout steps so the feature can be delivered and validated in stages.

## Documents

- [00-slice-ideas.md](./00-slice-ideas.md) – Brainstormed candidate slices and prioritization notes.
- [01-task-list-telnet-to-gameplay-vertical-slice.md](./01-task-list-telnet-to-gameplay-vertical-slice.md) – Initial Telnet-to-gameplay pipeline, from TCP proxy through core services.
- [02-task-list-login-and-session-vertical-slice.md](./02-task-list-login-and-session-vertical-slice.md) – Player login, session management, and related smoke tests.
- [02.1-task-list-login-session-hardening-vertical-slice.md](./02.1-task-list-login-session-hardening-vertical-slice.md) – Bounded follow-up slice to reduce `dev-isolated` reliance and harden the current login/session runtime path.
- [02.2-task-list-gameplay-admission-ux-vertical-slice.md](./02.2-task-list-gameplay-admission-ux-vertical-slice.md) – Follow-up slice to simplify the player-facing `LOGIN` / `PLAY` flow and align command-stage behavior with MUD expectations.
- [02.3-task-list-reconnect-and-session-recovery-vertical-slice.md](./02.3-task-list-reconnect-and-session-recovery-vertical-slice.md) – In-progress reconnect/recovery follow-up; core recovery, transcript restore, and edge classification are live, while non-edge invisibility and first-party parity remain open.
- [03-task-list-data-driven-look-vertical-slice.md](./03-task-list-data-driven-look-vertical-slice.md) – Data-driven `LOOK` command slice, including canonical transcripts and instrumentation.
- [04-task-list-chat-and-social-vertical-slice.md](./04-task-list-chat-and-social-vertical-slice.md) – Chat and social features (`SAY`, guilds, and related flows) as a coordinated slice.
- [04.1-task-list-shared-communication-infrastructure-vertical-slice.md](./04.1-task-list-shared-communication-infrastructure-vertical-slice.md) – Shared communication infrastructure follow-up that turns the initial room-speech path into the canonical model for later communication actions.
- [04.2-task-list-whisper-vertical-slice.md](./04.2-task-list-whisper-vertical-slice.md) – Baseline `whisper` slice, including canonical prose and target/observer transcript fixtures, on top of the shared communication model.
- [04.3-task-list-tell-vertical-slice.md](./04.3-task-list-tell-vertical-slice.md) – Baseline `tell` slice, including canonical sender/target transcript fixtures, on top of the shared communication model.
- [04.4-task-list-communication-observers-and-interceptors-vertical-slice.md](./04.4-task-list-communication-observers-and-interceptors-vertical-slice.md) – Observer/interceptor follow-up slice for eavesdropping, spying, and similar communication mechanics.
- [05-task-list-movement-vertical-slice.md](./05-task-list-movement-vertical-slice.md) – Movement-focused slice covering exit validation, authoritative location changes, and automatic post-move `LOOK` refresh.

When adding new vertical slices, follow the same naming convention (`NN-description-vertical-slice.md`) and include sections for scope, architecture notes, test coverage, and rollout plan.
