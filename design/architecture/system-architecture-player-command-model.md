# Player Command Model

This document defines FireMUD's canonical player-command model. It is the architecture source of truth for standard commands, command stages, command capability policy, and game-authored command extension. Service protocol documents describe transport framing and service-local execution; they do not redefine player command semantics.

## Command Model

Player commands are transport-independent. Telnet, generic WebSocket, first-party web, and future smart clients normalize player intent onto the same command model before execution.

Every command definition carries:

- a canonical command id and accepted aliases;
- its allowed player stage;
- its action category and optional semantic tags;
- its command capability and semantic owner;
- whether it is a direct read/session operation or a durable gameplay action;
- its help/discovery metadata.

The runtime command registry implements this model. It does not define the product contract independently of this document.

## Player Stages

FireMUD recognizes three player stages:

- **Pre-login**: no authenticated account context exists.
- **Lobby**: an account is authenticated but no character is actively playing.
- **Gameplay**: an admitted tenant/game, game instance, and character context exists.

A command may be valid in more than one stage only when its contract explicitly says so. Wrong-stage input returns stage-aware guidance rather than leaking into gameplay execution.

## Standard Command Catalog

The standard catalog is grouped by command family. Detailed domain behavior remains in its owning architecture documentation; this table defines availability, stage, and ownership.

| Family | Canonical commands | Stage | Availability | Semantic owner |
| --- | --- | --- | --- | --- |
| Session lifecycle | `LOGIN`, `PLAY`, `LOGOUT` | Command-specific pre-login, lobby, or gameplay stage | Mandatory platform | Account Service and Game Session |
| Discovery and help | `WORLDS`, `REALMS`, `CHARS`, `HELP` | Pre-login or lobby as applicable; `HELP` is stage-aware | Mandatory platform | Account Service, Game Session, and Game Design for authored help |
| Gameplay foundation | `LOOK`, `QUICKLOOK`, movement | Gameplay | Mandatory for a tenant/game using the playable text-MUD profile | Game Logic, World Management, and Game Session rendering |
| Platform utilities | `STATUS`, `AFK` | Gameplay | Universal platform utilities | Game Session |
| Social and presence | `SAY`, `WHISPER`, `TELL`, `WHO`, `FRIENDS` | Gameplay | Optional tenant/game capabilities | Game Logic, Social & Groups, and Game Session |
| Inventory and equipment | `INVENTORY`, `EQUIPMENT`, `CONTAINER`, `GET`, `DROP`, `PUT`, `TAKE`, `WEAR`, `REMOVE` | Gameplay | Optional tenant/game capability | Entity Management, Game Logic, and Game Session |
| Command history | `HISTORY [count]` | Gameplay | Future optional tenant/game capability | Game Session command-history surface |
| Game-authored actions | Tenant/game-defined commands | Declared by the action definition | Tenant/game-defined | Game Design declaration with Game Session ingress and Game Logic execution |

Disabled optional capabilities are not advertised by `HELP` and return a bounded feature-unavailable result when invoked. They are not silently repurposed or made available through an alternate alias.

## Reserved Names And Extensions

Platform-reserved commands and aliases cannot be shadowed by a game-authored action. This includes session lifecycle, discovery/help, gameplay-foundation, and platform-utility commands, plus every standard optional-capability command even when that capability is disabled for one tenant/game.

Game-authored actions must:

- declare a canonical id, aliases, stage, action category, tags, capability requirements, execution discipline, and help metadata;
- reject collisions with reserved names and aliases;
- use the same stage and output rules as standard commands;
- remain tenant/game-scoped and never alter another tenant/game's command namespace.

Games may extend standard behavior only through explicitly documented extension points. They do not replace platform session, authentication, stage-gating, or command-history safety rules.

## Command History

`HISTORY [count]` is a future command-input history feature, not a screen transcript reader. It returns the caller's eligible prior command inputs, subject to the effective tenant/game default and maximum count. Authentication secrets, OTPs, tokens, and other sensitive input are excluded or redacted before persistence and display.

Its storage and retention are independent of the durable resume transcript. See [Input, Output, and Presentation](./system-architecture-input-output-and-presentation.md#separate-history-features).

## Related Contracts

- [Game Session Protocols](./microservices/game-session-service/protocols.md) defines text transport framing, stage-aware response format, and protocol examples.
- [Input, Output, and Presentation](./system-architecture-input-output-and-presentation.md) defines structured player output, durable resume transcripts, command-history separation, and future archive/export behavior.
- [Reconnection Strategy](./system-architecture-reconnection.md) defines reconnect restoration from the durable resume transcript.
- [Game Customization](./system-architecture-game-customization.md) and Game Design architecture documents define tenant/game-authored behavior and content ownership.
