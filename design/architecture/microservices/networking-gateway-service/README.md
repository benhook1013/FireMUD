# Networking & Gateway Service

## Overview
This service exposes WebSocket and HTTP endpoints for all clients. It routes requests through Spring Cloud Gateway and ensures Telnet connections are bridged via the TCP proxy.

## Architecture / Design Notes
- Maintains persistent WebSocket sessions and supports raw TCP through a proxy.
- Event-driven updates synchronize game state across connected players.
- Includes a fallback mechanism so players with unstable connections can rejoin seamlessly.

## Key Features
- Central API gateway and authentication point.
- Real-time state synchronization for multiplayer actions.
- Reconnection support for dropped clients.

## Dependencies
- **Internal:** Game Session Service and other microservices over gRPC.
- **External:** Spring Cloud Gateway infrastructure.

## Future Enhancements
- Connection metrics and throttling.
- Horizontal scaling for high concurrency.
