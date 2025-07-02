# TCP Proxy Service

## Overview

Bridges legacy Telnet clients into the platform by converting raw TCP traffic into WebSocket connections for the Spring Cloud Gateway.

## Architecture / Design Notes

- Lightweight custom service separate from Spring Boot.
- Buffers incoming input during brief disconnects and clears it on connection loss.

## Key Features

- **Telnet Compatibility** — accepts standard MUD clients over TCP.
- **WebSocket Bridging** — forwards all traffic to the gateway via WebSocket.
- **Connection Buffering** — temporarily queues input to handle latency.

## Dependencies

- **Internal:** Spring Cloud Gateway, Game Session Service.
- **External:** None, runs as a standalone proxy.

> See [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for details on how Telnet connections are integrated into the platform.

## Future Enhancements

- Connection throttling and rate limits.
- Support for SSL/TLS termination if required.
