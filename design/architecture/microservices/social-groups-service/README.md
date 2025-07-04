# Social & Groups Service

## Overview

Provides chat, guild, and social networking features across games. Enables players to form groups and communicate in real time.

## Architecture / Design Notes

- Uses WebSocket channels for chat delivery.
- Stores guild and friend relationships in PostgreSQL.
- Integrates with the Logging & Admin Service for moderation events.

## Key Features

- Global and guild chat rooms.
- Private messaging and presence indicators.
- Guild creation and membership management.
- Friend lists scoped both to individual games and to overall accounts.
- In-game social chat plus account-to-account direct messaging.

### Chat Pipeline

- Messages are published to Redis streams and fanned out to WebSocket channels
  through the gateway.
- Guild and direct messages share a common persistence model for history.

### gRPC/REST APIs

- `SendMessage` – publishes a chat message to an in-game channel or player.
- `SendAccountMessage` – delivers a direct message between account holders.
- `CreateGuild` – establishes a new guild with an owner account.
- `AddFriend` – adds a friend relationship at the game or account level.

## Dependencies

- **Internal:** Account Service for user identities.
- **External:** PostgreSQL for social data.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for
details on shared infrastructure components.

## Proto Files

The social APIs are defined in
[../../../../protos/social-groups/v1](../../../../protos/social-groups/v1).
Regenerate the service stubs with `./gradlew generateProto` whenever the proto
files change.

## 📚 Related Documentation

- [System Architecture Overview](../system-architecture-overview.md)
- [Service Responsibility Matrix](../service-responsibility-matrix.md)
- [User Journeys](../user-journeys.md)

## Future Enhancements

- Rich moderation tools for chat.
- Optional voice chat integration.
