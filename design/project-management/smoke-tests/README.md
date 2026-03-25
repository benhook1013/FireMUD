# Smoke Test Artifacts

This directory captures artifacts from manual smoke tests that validate critical end-to-end experiences before or alongside automated regression suites.

Currently it focuses on the `LOOK` command vertical slice, providing canonical Telnet and WebSocket transcripts that should match automated cross-service tests.

## Subdirectories

- [look/](./look/) – Sample `LOOK` transcripts for WebSocket and Telnet flows, used as references when running or updating smoke scripts.

For the step-by-step instructions that produce these artifacts, see [LOOK Smoke Tests](../slice-support/look-smoke-tests.md) and the related vertical slice design in `../vertical-slices/03-task-list-data-driven-look-vertical-slice.md`.
