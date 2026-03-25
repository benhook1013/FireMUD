# LOOK Smoke Test Transcripts

This folder stores canonical transcripts for the `LOOK` smoke tests across different transports.

Each log file contains both the commands issued by a tester and the responses returned by the system, making it easy to diff behavior over time or compare manual runs to automated regression outputs.

## Files

- [look-ws-sample.log](./look-ws-sample.log) – Sample WebSocket transcript for the `LOGIN` + `LOOK` flow.
- [look-telnet-sample.log](./look-telnet-sample.log) – Sample Telnet transcript exercising the same vertical slice through the TCP proxy and gateway.

See the main [LOOK Smoke Tests](../../slice-support/look-smoke-tests.md) document for instructions on how to reproduce these transcripts and how they relate to the cross-service regression tests.
