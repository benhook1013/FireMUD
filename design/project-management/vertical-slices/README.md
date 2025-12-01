# Vertical Slice Design Docs

This directory tracks vertical slice plans for major end-to-end features in the FireMUD platform.

Each document describes a narrowly scoped slice of functionality, covering user experience, architecture, testing strategy, and incremental rollout steps so the feature can be delivered and validated in stages.

## Documents

- [00-slice-ideas.md](./00-slice-ideas.md) – Brainstormed candidate slices and prioritization notes.
- [01-task-list-telnet-to-gameplay-vertical-slice.md](./01-task-list-telnet-to-gameplay-vertical-slice.md) – Initial Telnet-to-gameplay pipeline, from TCP proxy through core services.
- [02-task-list-login-and-session-vertical-slice.md](./02-task-list-login-and-session-vertical-slice.md) – Player login, session management, and related smoke tests.
- [03-task-list-data-driven-look-vertical-slice.md](./03-task-list-data-driven-look-vertical-slice.md) – Data-driven `LOOK` command slice, including canonical transcripts and instrumentation.
- [04-task-list-chat-and-social-vertical-slice.md](./04-task-list-chat-and-social-vertical-slice.md) – Chat and social features (`SAY`, guilds, and related flows) as a coordinated slice.

When adding new vertical slices, follow the same naming convention (`NN-description-vertical-slice.md`) and include sections for scope, architecture notes, test coverage, and rollout plan.
