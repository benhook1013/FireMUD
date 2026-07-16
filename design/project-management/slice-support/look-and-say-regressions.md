# LOOK and Communication Cross-Service Regression Suites

This document centralizes the cross-service regression plans for the `LOOK` and foundational communication text commands so WebSocket and Telnet experiences stay aligned.

## LOOK Command Regression Flow

- Start Game Session, Game Logic, World Management, Entity Management, and the TCP proxy/Gateway together.
- Replay the canonical `LOGIN` + `LOOK` sequence over both WebSocket and Telnet, using the transcript defined in `design/project-management/slice-support/look-cross-service-tests.md`.
- Assert that:
  - Response payloads match the canonical transcript for both transports (modulo framing).
  - `gamesession.command.look.*` metrics/logs are emitted as described in `design/project-management/slice-support/look-instrumentation.md`.
- Run these flows via the `crossServiceTest` Gradle target when available so they execute as part of CI and local verification.

## Communication Command Regression Flow

- Start the same cross-service stack and connect at least two clients (WebSocket or Telnet) to the same room, plus any NPC echo participants.
- Issue `SAY`, `WHISPER`, and `TELL` from one client and verify that:
  - the initiating player receives the canonical actor transcript;
  - downstream services record deterministic type and recipient metadata;
    - `gamesession.command.say.*`, `gamesession.command.whisper.*`, and `gamesession.command.tell.*` metrics are emitted as described in `design/project-management/implementation-tracking/player-experience-commands-and-communication.md`.
- Capture both success and failure-mode transcripts (for example, Social/Groups unavailable) and keep them in sync with the Chat & SAY capability record so Telnet and WebSocket behave consistently.

## Gradle Integration

- Prefer running these suites through `./gradlew crossServiceTest` once the harness is wired so both LOOK and SAY paths run together.
- When adding new regressions, update this document, the underlying transcripts, and the associated Gradle tasks so the documentation and automation stay aligned.
