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
- Cross-game friend lists.

## Dependencies

- **Internal:** Account Service for user identities.
- **External:** PostgreSQL for social data.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for
details on shared infrastructure components.

## 📚 Related Documentation

- [System Architecture Overview](../system-architecture-overview.md)

## Future Enhancements

- Rich moderation tools for chat.
- Optional voice chat integration.
