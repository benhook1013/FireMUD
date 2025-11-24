# TCP Proxy Service

The complete design can be found in:
[📄 TCP Proxy Service Design](../../design/architecture/microservices/tcp-proxy-service/README.md)

## Running locally with the echo loop

Use the bundled developer echo target to validate the Telnet -> WebSocket bridge without running the gateway:

1. Start the service: `./gradlew :services:tcp-proxy-service:bootRun` (defaults to the `dev` profile for local runs only).
2. The dev profile disables gRPC TLS by default. Enable it with `GRPC_SERVER_TLS_ENABLED=true` if you want to use the sample certificates in `src/main/resources/certs`.
3. For non-dev profiles (including production), TLS and mutual auth remain enabled unless explicitly disabled via environment variables.
4. Point the bridge at the local echo (default): `GATEWAY_WS_URL=ws://localhost:8080/dev/echo`
5. Connect from a Telnet/MUD client: `telnet localhost 2323`
6. Type any text. The proxy logs the input at INFO and echoes the same text back over the Telnet session.

When pointing at a real gateway, override `GATEWAY_WS_URL` with its WebSocket endpoint.
