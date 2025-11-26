# TCP Proxy Service

The complete design can be found in:
[📄 TCP Proxy Service Design](../../design/architecture/microservices/tcp-proxy-service/README.md)

This README is only a stub for reference. Do not include design details here.

## Dev Echo Path

To validate the Telnet-to-WebSocket pipeline without bringing up the full gateway stack, run the proxy with the `dev` Spring profile so it wires to the lightweight echo handler:

1. Start the service with `./gradlew :tcp-proxy-service:bootRun -Dspring.profiles.active=dev` (use `gradlew.bat` on Windows or launch the `dev` profile from your IDE).
2. The dev profile binds the `DevEchoWebSocketHandler` to `ws://localhost:8080/dev/echo`, and the proxy's `GATEWAY_WS_URL` override in `application-dev.yml` ensures the Telnet bridge targets that endpoint.
3. Connect locally via Telnet (`telnet localhost 2323` by default), type a line, and it should be echoed back immediately, proving the Telnet client can reach `/dev/echo`.
4. With the proxy running in the `dev` profile, you can also run `./dev-echo-loop.sh` from this directory (requires `python`/`python3`) to send a test line over Telnet and verify the echo automatically before using a manual Telnet client.
