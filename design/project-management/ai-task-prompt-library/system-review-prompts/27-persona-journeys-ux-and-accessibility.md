# Persona Journeys, UX, And Accessibility Review

Use this prompt to review whether player, creator, and operator journeys are coherent, understandable, recoverable, and honestly represented across target and implemented surfaces.

Apply the [shared review contract](./00-shared-review-contract.md).
Apply the [orchestrated review workstream contract](./02-orchestrated-review-workstream-contract.md).

## Orchestrated Execution

A full invocation is an orchestrated review workstream: the invoking main thread takes primary ownership and delegates bounded evidence lanes for:

- player journeys, tracing first-attempt success and failure or recovery paths with accessibility;
- creator journeys, tracing first-attempt success and failure or recovery paths with accessibility;
- operator journeys, tracing first-attempt success and failure or recovery paths with accessibility; and
- an intentional cross-cutting pass for cross-client language, state, accessibility, target/current status, and proof consistency.

The primary thread reconciles shared journeys and ensures API or tracker existence is not treated as proof of a usable experience.

## Starting Sources

- `design/product/requirements.md`
- `design/product/user-journeys/overview.md`
- `design/product/user-journeys/players.md`
- `design/product/user-journeys/creators.md`
- `design/product/user-journeys/operators.md`
- `design/architecture/system-architecture-frontend.md`
- `design/architecture/system-architecture-input-output-and-presentation.md`
- `design/architecture/system-architecture-player-command-model.md`
- `design/architecture/system-architecture-protocol-bridging.md`
- `design/user-guides/game-creator-guide.md`
- `design/developer-workflows/player-playtest-checklist.md`
- current client, API, command, tracker, test, and smoke evidence for the journeys reviewed

## Review

For each persona, trace first-attempt success and important failure or recovery paths. Check:

- discoverability, onboarding, authentication, navigation, state transitions, confirmation, and error recovery;
- whether user-visible language matches the underlying product and technical state;
- loading, empty, stale, unavailable, validation, permission, conflict, retry, cancellation, and partial-completion states;
- consistency across browser, WebSocket, Telnet, creator tools, operator tools, and documented command surfaces;
- accessibility of structured output, color use, keyboard interaction, prompts, focus, screen-reader meaning, and non-visual alternatives;
- reconnect, takeover, moderation, billing, data export or deletion, publication, rollback, incident, and restore journeys where relevant;
- whether target-only applications or flows are clearly distinguished from current implementation; and
- whether tests, smoke checks, and manual playtests prove the claimed observable boundary.

Do not treat API existence or an implementation tracker row as proof of a usable journey. Keep optional polish separate from missing behavior that prevents a coherent first experience.

## Output

Provide:

1. a player, creator, and operator journey coverage table;
2. missing, contradictory, misleading, or unrecoverable journey steps;
3. accessibility and cross-client consistency findings;
4. target/current/proof disagreements; and
5. the review state required by the shared contract.
