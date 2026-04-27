# Slice Support Docs

This directory contains slice-specific implementation support material that is useful while a slice is being built, debugged, or stabilized.

These docs are not the primary planning index. Use [`../task-list.md`](../task-list.md) and [`../vertical-slices/`](../vertical-slices/) for active planning, and use service architecture docs for long-lived canonical behavior once a slice is fully absorbed into the permanent design set.

Current contents include:

- [`chat-say-developer-guide.md`](./chat-say-developer-guide.md) – Canonical WebSocket and Telnet workflow for reproducing the `SAY` transcript.
- [`look-and-say-regressions.md`](./look-and-say-regressions.md) – Shared LOOK/SAY regression checklist and expected assertions.
- [`look-cross-service-tests.md`](./look-cross-service-tests.md) – Cross-service LOOK test wiring and automation notes.
- [`look-instrumentation.md`](./look-instrumentation.md) – Metrics, logs, and tracing references for the LOOK/SAY work.
- [`look-smoke-tests.md`](./look-smoke-tests.md) – Manual smoke steps and expected outputs for the LOOK slice.
- [`playtesting-feedback.md`](./playtesting-feedback.md) – Playtesting and feedback collection support material.
- [`slice-completion-proof-checklist.md`](./slice-completion-proof-checklist.md) – Required verification checklist before a slice is marked complete.
