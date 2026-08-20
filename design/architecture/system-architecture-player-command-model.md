# Player Command Model

This document defines FireMUD's canonical player-command model. It is the architecture source of truth for standard commands, command stages, command capability policy, and game-authored command extension. Service protocol documents describe transport framing and service-local execution; they do not redefine player command semantics.

## Implementation Notes

`HISTORY [count]` is live through the shared parser, registry, dispatch, capability, persistence, and presentation path. It reads only safe, accepted gameplay commands under the resolved tenant/game/character identity, applies the effective bounded retention policy, and never records its own display invocation. History persistence runs independently and remains best-effort, so it cannot change the outcome of the player command it observes. Retention uses a durable bounded-pass cursor, avoiding replica-loss and starvation of earlier scopes.

Standard optional availability is one typed `commandCapabilities` policy, with operator defaults and persisted tenant/game overrides for `SOCIAL`, `PRESENCE`, `INVENTORY`, and `COMMAND_HISTORY`. Game Session checks that policy after stage validation and before dispatch; `HELP` filters the same capability families; Game Logic repeats the `SOCIAL` check at its gRPC ingress. Command-history retention is separate from availability and contains only its effective entry bound.

Typed, data-defined command execution effects are now live for the first release-admitted `APPLY_ACTION_STATE` declaration. Built-ins still contain transitional handler routing until their declarations converge on the shared effect engine, and additional authored effect kinds remain unavailable. Run documentation checks through `dev-tools/validation/run-locked-gradle.sh linkCheck lintMarkdown`.

## Command Model

Player commands are transport-independent. Telnet, generic WebSocket, first-party web, and future smart clients normalize player intent onto the same command model before execution.

Every command definition carries:

- a canonical command id and accepted aliases;
- its allowed player stage;
- its action category and optional semantic tags;
- its explicit accepted-command-history policy;
- its command capability and semantic owner;
- whether it is a direct read/session operation or a durable gameplay action;
- zero or more typed execution-effect declarations when it changes gameplay state;
- its help/discovery metadata.

The runtime command registry implements this model. It does not define the product contract independently of this document.

## Typed Execution Effects

Action category and tags are descriptive policy metadata. They answer questions such as whether activity is meaningful, whether a command is combat-related, or how output should be presented. They must not select a concrete gameplay mutation merely because a command has a broad tag such as `COMBAT`.

Gameplay-changing commands instead declare one or more typed execution effects. An effect declaration has a registered `effectKind`, a schema-validated payload, targeting and authorization requirements, replay/idempotency semantics, and any ordering or atomicity requirements. Direct reads, session operations, and presentation-only commands declare no gameplay effects.

For example, the seeded `BLOCK` command is a gameplay command with the `COMBAT` tag, but its concrete behavior is a separate action-state effect declaration equivalent to `APPLY_ACTION_STATE` with the typed `blocking` payload and bounded duration. `ATTACK`, `PARRY`, and `TAUNT` may all be combat-tagged without inheriting that blocking effect.

The first registered declaration is `APPLY_ACTION_STATE` schema version `1`. It requires `targeting: SELF`, `replayPolicy: EFFECT_IDEMPOTENT`, and a payload with an identifier `conditionKey`, integer `durationSeconds` from `1` through `3600`, and an `effectPayload.modifiers` array. Each modifier uses one registered operation (`ADD`, `MULTIPLY`, `CLAMP_MIN`, `CLAMP_MAX`, `GRANT_FLAG`, or `GRANT_CONDITION`), an identifier `target_key`, numeric `value`, optional identifier scope fields, and optional integer priority. This data contract is the only authorable action-state behavior; it cannot embed arbitrary DML or Java.

The generic runtime owns only the effect engine:

- validate declarations and payloads against registered effect schemas;
- authorize, persist, order, replay, and execute declared effects through the durable effect ledger;
- fail closed when a published declaration uses an unknown or unsupported effect kind or schema version.

It does not execute arbitrary DML text, Java snippets, or unvalidated payloads. New behavior is made available by registering a safe effect kind and schema in the platform, then declaring version-scoped effect instances as Game Design data.

The CMD-02 extension boundary is deliberately bounded: release-pinned declarations may use only registered typed predicates and effects, with Game Logic resolving bounded target plans from owner-provided facts and Entity Management owning actor mutation, costs, cooldowns, and effect state. Game Session remains the semantic tick-admission boundary. Automation and plugins may request the same registered commands through their sandbox, but cannot bypass these owners; routine internal gameplay actions do not require a per-action signature. This Automation/plugin sandbox extension is target-only: the current Automation handoff does not implement or prove the CMD-02 extension, and its gap remains tracked in [Automation and Scheduler Runtime](../project-management/implementation-tracking/automation-and-scheduler-runtime.md#durable-work-and-runtime-activation). See [ADR 0112](./decisions/adr-0112-typed-bounded-gameplay-effect-extension.md).

Seeded platform commands and tenant/game-authored commands use the same declaration shape. Built-ins are canonical seed data, not permanently privileged Java-only command-to-handler mappings. A command may compose multiple declared effects only when its effect schema defines their ordering and transactional boundary explicitly.

### Versioned Declaration Lifecycle

Game Design persists each command definition as a typed `COMMAND_DEFINITION` revision payload with one stable logical command identity. The payload contains command aliases, stage, category, tags, explicit `historyRecordable` policy, capability requirements, help metadata, and its typed execution-effect declarations. Revisions are version-scoped DML inputs; publish validates them, freezes an immutable command-definition artifact and digest for the version, and rejects ambiguous aliases, invalid history policy, invalid effect payloads, or unsupported effect schemas.

At runtime, Game Session resolves only the declaration artifact pinned to the player's admitted tenant/game version and, where applicable, its approved script-patch version. The durable command and effect ledgers retain that version identity and the declared effect identity for replay. A runtime must fail closed rather than reinterpret a command against a newer definition or silently fall back to process-local configuration when the admitted artifact is unavailable or incompatible.

For the first live effect path, accepted authored ingress persists the admitted release-bundle id, version id, canonical command id, immutable declared-effect JSON, and originating `{tenantId, gameInstanceId, characterId}` on the durable gameplay command. Execution selects that snapshot rather than a later live registry read, requires the current resolved session to retain the originating gameplay identity, accepts only one supported `APPLY_ACTION_STATE` v1 declaration, and uses the durable effect replay record before applying the downstream actor condition. A changed, missing, malformed, or unsupported identity or snapshot fails closed before replay lookup or script publication.

Seeded platform and authored command definitions enter the same version-scoped data lifecycle. Application configuration is not an authored-command source, parser fallback, help fallback, or execution authority.

## Player Stages

FireMUD recognizes three player stages:

- **Pre-login**: no authenticated account context exists.
- **Lobby**: an account is authenticated but no character is actively playing.
- **Gameplay**: an admitted tenant/game, game instance, and character context exists.

A command may be valid in more than one stage only when its contract explicitly says so. Wrong-stage input returns stage-aware guidance rather than leaking into gameplay execution.

## Standard Command Catalog

The standard catalog is grouped by command family. Detailed domain behavior remains in its owning architecture documentation; this table defines availability, stage, aliases, and ownership. `Any` means every player stage. Gameplay-only commands require an admitted gameplay context.

| Family | Canonical commands | Stage | Availability | Semantic owner |
| --- | --- | --- | --- | --- |
| Session lifecycle | `LOGIN` (`LOGON` alias), `PLAY`, `LOGOUT` (`LOGOFF` and `QUIT` aliases) | `LOGIN` pre-login, `PLAY` lobby, `LOGOUT` any stage | Mandatory platform | Account Service and Game Session |
| Discovery and help | `WORLDS`; `REALMS`; `CHARS`; `HELP` | `WORLDS` and `HELP` any; `REALMS` and `CHARS` lobby or gameplay | Mandatory platform | Account Service, Game Session, and Game Design for authored help |
| Gameplay foundation | `LOOK` (`L`); `QUICKLOOK` (`QLOOK`); `MOVE` (`GO`, cardinal direction names, and `N`/`S`/`E`/`W`/`U`/`D`) | Gameplay | Mandatory for a tenant/game using the playable text-MUD profile | Game Logic, World Management, and Game Session rendering |
| Platform utilities | `STATUS` (`STAT`); `AFK` (`BRB`) | Gameplay | Universal platform utilities | Game Session |
| Social and presence | `SAY`, `WHISPER`, `TELL`, `WHO`, `FRIENDS` | Gameplay | Optional tenant/game capabilities | Game Logic, Social & Groups, and Game Session |
| Inventory and equipment | `INVENTORY`, `EQUIPMENT`, `CONTAINER`, `GET`, `DROP`, `PUT`, `TAKE`, `WEAR`, `REMOVE` | Gameplay | Optional tenant/game capability | Entity Management, Game Logic, and Game Session |
| Command history | `HISTORY [count]` | Gameplay | Optional tenant/game capability | Game Session command-history surface |
| Game-authored actions | Tenant/game-defined commands | Declared by the action definition | Tenant/game-defined | Game Design declaration with Game Session ingress and Game Logic execution |

Disabled optional capabilities are not advertised by `HELP` and return `FEATURE_UNAVAILABLE` when their command is invoked. The policy is resolved from the same operator-default then tenant/game-override chain for every ingress, so an alternate alias or direct service call cannot bypass it.

## Reserved Names And Extensions

Platform-reserved commands and aliases cannot be shadowed by a game-authored action. This includes session lifecycle, discovery/help, gameplay-foundation, and platform-utility commands, plus every standard optional-capability command even when that capability is disabled for one tenant/game.

Game-authored actions must:

- declare a canonical id, aliases, stage, action category, tags, capability requirements, typed execution effects where applicable, execution discipline, and help metadata;
- reject collisions with reserved names and aliases;
- use the same stage and output rules as standard commands;
- remain tenant/game-scoped and never alter another tenant/game's command namespace.

Games may extend standard behavior only through explicitly documented extension points. They do not replace platform session, authentication, stage-gating, or command-history safety rules.

## Command History

`HISTORY [count]` is a command-input history feature, not a screen transcript reader. It returns the caller's safe, accepted prior commands for the current tenant/game and character binding. Malformed, unknown, and failed input is not retained. Authentication secrets, OTPs, tokens, and other sensitive input are excluded or redacted before persistence and display. Every command definition explicitly declares `historyRecordable`; `HISTORY` itself is not recordable, so reading history never creates another history row.

The platform default is `10` entries and the platform maximum is `20`. A tenant/game may configure its own effective default and maximum within those platform limits. A supplied `count` returns the newest requested subset, bounded by the effective maximum.

Its storage and retention are independent of the bounded durable semantic reconnect context. That context is not client command-input history, a delivery acknowledgement, or a complete Player Transcript Archive and Export; it is a bounded semantic recent-context presentation after reconnect. See [Input, Output, and Presentation](./system-architecture-input-output-and-presentation.md#separate-history-features) and [ADR 0134](./decisions/adr-0134-bounded-durable-semantic-reconnect-context.md).

The local output consequence is that domain services return typed semantic outcomes; Game Session maps them to compact, versioned `PlayerOutput` envelopes plus deterministic text and prompt projections. First-party WebSocket responses and recipient pushes deliver the structured envelope; Telnet and generic text WebSocket deliver its deterministic text projection. Transport writers only deliver the projection assigned to their branch. [ADR 0135](./decisions/adr-0135-compact-versioned-player-output-and-late-rendering.md) and [Input, Output, and Presentation](./system-architecture-input-output-and-presentation.md#rendering-ownership) own the output boundary; [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants) owns transport delivery.

## Related Contracts

- [Game Session Protocols](./microservices/game-session-service/protocols.md) defines text transport framing, stage-aware response format, and protocol examples.
- [Input, Output, and Presentation](./system-architecture-input-output-and-presentation.md) defines structured player output, bounded durable semantic reconnect context, command-history separation, and future archive/export behavior.
- [Reconnection Strategy](./system-architecture-reconnection.md) defines reconnect restoration from bounded semantic recent context.
- [Game Customization](./system-architecture-game-customization.md) and Game Design architecture documents define tenant/game-authored behavior and content ownership.
