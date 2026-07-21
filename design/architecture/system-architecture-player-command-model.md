# Player Command Model

This document defines FireMUD's canonical player-command model. It is the architecture source of truth for standard commands, command stages, command capability policy, and game-authored command extension. Service protocol documents describe transport framing and service-local execution; they do not redefine player command semantics.

## Implementation Notes

`HISTORY [count]` is live through the shared parser, registry, dispatch, capability, persistence, and presentation path. It reads only safe, accepted gameplay commands under the resolved tenant/game/character identity, applies the effective bounded retention policy, and never records its own display invocation. History persistence runs independently and remains best-effort, so it cannot change the outcome of the player command it observes. Retention uses a durable bounded-pass cursor, avoiding replica-loss and starvation of earlier scopes.

Standard optional availability is implemented as one typed `commandCapabilities` policy, with operator defaults and persisted tenant/game overrides for `SOCIAL`, `PRESENCE`, `INVENTORY`, and `COMMAND_HISTORY`. Game Session checks that policy after stage validation and before dispatch; `HELP` filters the same capability families; Game Logic repeats the `SOCIAL` check at its gRPC ingress. The target hybrid release-pinned extension registry, authored requirement validation, alternate-ingress enforcement, history maximum age, generation fencing, and purge behavior from [ADR 0170](decisions/adr-0170-release-pinned-command-capabilities-and-private-history.md) remain implementation gaps.

Typed, data-defined command execution effects are now live for the first release-admitted `APPLY_ACTION_STATE` declaration. Built-ins still contain transitional handler routing until their declarations converge on the shared effect engine, and additional authored effect kinds remain unavailable. Run documentation checks through `dev-tools/validation/run-locked-gradle.sh linkCheck lintMarkdown`.

## Command Model

Player commands are transport-independent. Telnet, generic WebSocket, first-party web, and future smart clients normalize player intent onto the same command model before execution.

Game Session is the one semantic admission boundary. A text adapter tokenizes the line protocol and a future structured adapter may submit an already typed command intention, but neither bypasses the pinned registry, stage, capability, history, idempotency, and durable-admission rules. Structured clients do not need to stringify typed input merely to make Game Session parse it again.

Normalization identifies the declared command and validates its transport-independent input shape; it does not move domain facts into Game Session. Game Logic and the owning domain services still resolve authoritative targets, inventory, location, permissions, effects, and mutation preconditions from the normalized intention.

Registry resolution distinguishes three states:

- no gameplay artifact applies yet, so the stage's verified platform/menu declarations are authoritative;
- the admitted release supplies a verified artifact, including an intentionally empty authored set;
- an admitted artifact is required but missing, corrupt, mismatched, colliding, or unsupported, which fails closed with a bounded player-visible error.

The third state must never silently fall back to process-local built-ins. Verified registries may be cached under their immutable release identity; correctness must not depend on a database read for every command. A downstream service does not retain a second production raw-text parser or reinterpret the command against another registry.

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

This is also the command extensibility boundary. A future bounded declarative plan grammar may compose registered predicates and effect primitives, but each new fact source or mutation primitive still requires an explicit typed platform contract and owning-service enforcement. Sandboxed scripts and plugins may request the same registered commands through the Automation pathway; they are not invoked as arbitrary code inside the hot gameplay process and cannot bypass Game Logic target resolution or domain-owner mutation checks. Foundational hot-path invariants remain typed platform behavior rather than creator-defined scripts.

Routine gameplay relies on authenticated service transport, scoped execution context, immutable declaration identity, and owner-side validation. It does not require a separately signed authorization artifact for every internal action. Separately classified account, operator, billing, or real-currency operations may impose stronger authorization or step-up requirements at their owning boundaries without charging that cost to every gameplay effect.

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

Platform-owned capability keys remain typed and may be mandatory or non-disableable. An immutable game release or plugin may declare bounded stable namespaced extension keys with owner, description, default, settings eligibility, and gated entry points. The release manifest pins the applicable registry; publication rejects invalid, duplicate, reserved, or unresolved requirements, and runtime fails closed on unknown or stale required keys. Settings can override only declared configurable keys and cannot weaken platform invariants.

Command definitions carry a bounded set of requirements from that registry. Admission, `HELP`, Automation, and any direct owning-service entry point enforce the same effective requirements. Tags such as `COMBAT` remain descriptive and do not become capabilities implicitly.

## Reserved Names And Extensions

Platform-reserved commands and aliases cannot be shadowed by a game-authored action. This includes session lifecycle, discovery/help, gameplay-foundation, and platform-utility commands, plus every standard optional-capability command even when that capability is disabled for one tenant/game.

Game-authored actions must:

- declare a canonical id, aliases, stage, action category, tags, capability requirements, typed execution effects where applicable, execution discipline, and help metadata;
- reject collisions with reserved names and aliases;
- use the same stage and output rules as standard commands;
- remain tenant/game-scoped and never alter another tenant/game's command namespace.

Games may extend standard behavior only through explicitly documented extension points. They do not replace platform session, authentication, stage-gating, or command-history safety rules.

## Command History

`HISTORY [count]` is a command-input history feature, not a screen transcript reader. It returns the caller's safe, accepted prior commands for the current tenant/game and character binding. Malformed, unknown, and failed input is not retained. Authentication secrets, OTPs, tokens, and other sensitive input are excluded or redacted before persistence and display. Every platform command explicitly declares `historyRecordable`; authored and plugin commands default to non-recordable. A command whose arguments can contain secrets needs a bounded safe projection/redaction contract before it may be recordable. `HISTORY` itself is not recordable, so reading history never creates another history row.

The platform default is `10` entries and the platform maximum is `20`. A tenant/game may configure its own effective default and maximum within those platform limits. A supplied `count` returns the newest requested subset, bounded by the effective maximum. Retention also has a non-zero maximum age under a platform hard limit so low activity cannot preserve raw input indefinitely.

Disabling command history stops capture and display immediately, advances the affected scope's history policy generation, and schedules asynchronous deletion of older entries. Re-enabling begins empty in the new generation and cannot resurrect pre-disable rows while cleanup finishes. Account/privacy erasure can purge history independently.

Its storage and retention are independent of the durable resume transcript. See [Input, Output, and Presentation](./system-architecture-input-output-and-presentation.md#separate-history-features).

## Related Contracts

- [Game Session Protocols](./microservices/game-session-service/protocols.md) defines text transport framing, stage-aware response format, and protocol examples.
- [Input, Output, and Presentation](./system-architecture-input-output-and-presentation.md) defines structured player output, durable resume transcripts, command-history separation, and future archive/export behavior.
- [Reconnection Strategy](./system-architecture-reconnection.md) defines reconnect restoration from the durable resume transcript.
- [Game Customization](./system-architecture-game-customization.md) and Game Design architecture documents define tenant/game-authored behavior and content ownership.
