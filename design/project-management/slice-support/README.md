# Capability Support Docs

This directory contains capability-specific implementation support material for reproducing, debugging, and stabilizing cross-service behavior.

These docs are not the primary planning index. Use [`../implementation-tracking/README.md`](../implementation-tracking/README.md) for current domain status and use the linked architecture docs for long-lived canonical behavior. Update the owning domain tracker when support work changes the recorded implementation boundary or remaining work.

Current contents include:

- [`chat-say-developer-guide.md`](./chat-say-developer-guide.md) – Canonical WebSocket and Telnet workflow for reproducing the `SAY` transcript.
- [`look-and-say-regressions.md`](./look-and-say-regressions.md) – Shared LOOK/SAY regression checklist and expected assertions.
- [`look-cross-service-tests.md`](./look-cross-service-tests.md) – Cross-service LOOK test wiring and automation notes.
- [`look-instrumentation.md`](./look-instrumentation.md) – Metrics, logs, and tracing references for the LOOK/SAY work.
- [`look-smoke-tests.md`](./look-smoke-tests.md) – Manual smoke steps and expected outputs for the LOOK capability.
- [`playtesting-feedback.md`](./playtesting-feedback.md) – Playtesting and feedback collection support material.
