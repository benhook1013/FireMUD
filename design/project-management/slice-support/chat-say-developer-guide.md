# SAY Slice Developer Workflow

This quick guide shows example commands for both WebSocket and Telnet clients so you can reproduce the canonical communication transcripts used by the regression suites.

## WebSocket Example

1. Start the services needed for the cross-service regression (Account, World, Entity, Social, Game Logic, Redis, Postgres, Game Session).
1. Connect to the Game Session WebSocket at `/ws/game`. The normal flow is `WORLDS` (optional), `LOGIN`, `PLAY`, then communication commands. Typed attach metadata is not part of the public protocol. In production deployments, Spring Cloud Gateway strips spoofable session and tenant headers from public clients; any future smart-client attach hints must remain hidden proxy or MCP metadata only.
1. Send `LOGIN demo@example.com swordfish`.
1. Once you receive `OK LOGIN ...`, send `SAY Hello travelers`.
1. Expect the canonical sender response:

```text
You say, "Hello travelers"
```

1. The communication regression suites also cover `WHISPER Sora Keep quiet` -> `You whisper to Sora, "Keep quiet"` and `TELL Sora Meet me at the forge` -> `You tell Sora, "Meet me at the forge"`.

## Telnet Example

1. Connect via Telnet to the TCP proxy port.
1. Issue `LOGIN demo@example.com swordfish`, then `PLAY demo`. Typed attach metadata is not part of the Telnet flow.
1. After the `OK LOGIN` acknowledgment, send `SAY Hello travelers`.
1. Compare the Telnet transcript to the canonical response above and confirm the Social stub recorded `SendMessage` with the expected content and `chatType`.

## Running the Automation

- Execute `./gradlew crossServiceTest` to run both the Telnet (`TelnetGatewayGameSessionAccountCrossServiceIntegrationTest`) and WebSocket (`CommunicationWebSocketCrossServiceTest`) flows together. This command starts the shared fixtures and verifies `gamesession.command.say.*`, `gamesession.command.whisper.*`, and `gamesession.command.tell.*` metrics, canonical transcripts, and social webhook calls.
- Manual runs may reuse the stub suite described in `design/project-management/slice-support/look-cross-service-tests.md` (replace `LOOK` commands with the sequence above) so the instrumentation notes stay in sync.
