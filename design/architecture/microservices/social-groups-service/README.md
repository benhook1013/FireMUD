# Social & Groups Service

## Overview

Provides chat, guild, and social networking features across games. Enables players to form groups and communicate in real time.

## Architecture / Design Notes

- Uses WebSocket channels for chat delivery.
- Stores guild and friend relationships in PostgreSQL.

## Key Features

- Global and guild chat rooms.
- Guild creation and membership management.
- Cross-game friend lists.

## Dependencies

- **Internal:** Account Service for user identities.
- **External:** PostgreSQL for social data.

## Future Enhancements

- Rich moderation tools for chat.
- Optional voice chat integration.
