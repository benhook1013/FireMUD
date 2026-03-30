# SAY Slice Developer Workflow

This quick guide shows example commands for both WebSocket and Telnet clients so you can reproduce the canonical communication transcripts used by the regression suites.

## WebSocket Example

1. Start the services needed for the cross-service regression (Account, World, Entity, Social, Game Logic, Redis, Postgres, Game Session).
1. Connect to the Game Session WebSocket at `/ws/game`. Advanced clients or test harnesses that resume an existing session created via REST (`POST /sessions`) may first send `SESSION <sessionId>` as an in-band line; typical browser clients never need to send `SESSION` and can rely on the server to create or bind a session on `LOGIN`, matching the Telnet flow. In production deployments, Spring Cloud Gateway strips spoofable session/tenant headers from public clients; header-based session hints are reserved for the authenticated TCP Proxy → Gateway path.
1. Send `LOGIN demo@example.com swordfish`.
1. Once you receive `OK LOGIN ...`, send `SAY Hello travelers`.
1. Expect the canonical sender response:

```text
You say, "Hello travelers"
```

1. The communication regression suites also cover `WHISPER Sora Keep quiet` -> `You whisper to Sora, "Keep quiet"` and `TELL Sora Meet me at the forge` -> `You tell Sora, "Meet me at the forge"`.

## Telnet Example

1. Connect via Telnet to the TCP proxy port.
1. Issue `LOGIN demo@example.com swordfish`. For advanced Telnet clients or tests that need to attach to an existing session created via REST (`POST /sessions`), optionally send `SESSION <sessionId> <tenantId>` first, then `LOGIN demo@example.com swordfish`.
1. After the `OK LOGIN` acknowledgment, send `SAY Hello travelers`.
1. Compare the Telnet transcript to the canonical response above and confirm the Social stub recorded `SendMessage` with the expected content and `chatType`.

## Running the Automation

- Execute `./gradlew crossServiceTest` to run both the Telnet (`TelnetGatewayGameSessionAccountCrossServiceIntegrationTest`) and WebSocket (`CommunicationWebSocketCrossServiceTest`) flows together. This command starts the shared fixtures and verifies `gamesession.command.say.*`, `gamesession.command.whisper.*`, and `gamesession.command.tell.*` metrics, canonical transcripts, and social webhook calls.
- Manual runs may reuse the stub suite described in `design/project-management/slice-support/look-cross-service-tests.md` (replace `LOOK` commands with the sequence above) so the instrumentation notes stay in sync.
