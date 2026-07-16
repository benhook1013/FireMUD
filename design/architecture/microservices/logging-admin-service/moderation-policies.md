# Moderation Policies

This file outlines recommended moderation rules for hosted FireMUD games.
Operators can adapt these policies based on community needs while maintaining a safe environment for players. Automated enforcement, moderation dashboards, and detection tooling support these policies alongside manual bans via the `ApplyModerationAction` API.

For details on moderation tooling see the [Logging & Admin Service overview](./README.md), [API contracts](./api-contracts.md), and [runtime model](./runtime-and-data.md).

## Core Policies

1. **Zero Tolerance for Hate Speech** – racial, sexual, or other discriminatory language results in immediate bans. Automated detection identifies violations and enforces bans.
2. **Profanity Filtering** – common swear words are automatically masked by the Social & Groups Service. Chat profanity events are reported to the Logging & Admin Service for audit purposes, and word lists can be customized per tenant. See the [Social & Groups Service design](../social-groups-service/README.md) for filter details.
3. **Harassment and Threats** – direct or implied threats trigger temporary suspensions or account deletion. Automated detection and configurable suspension durations ensure consistent enforcement.
4. **Spam Prevention** – repeated unsolicited messages trigger rate limiting and chat mutes through automated detection.
5. **Cheating and Exploits** – using automation or bugs for unfair advantage results in account sanctions through detection tooling and automatic enforcement.

## Profanity Filters

The Social & Groups Service integrates a configurable word list. When detected, profanity is replaced with `*` characters before being routed to other players or persisted in logs.
Operators can customize the word list per tenant, and the filter flags attempts to bypass restrictions with misspellings or Unicode look-alikes.

## Enforcement Workflow

1. Offending logs or reports are flagged in the Logging & Admin Service dashboards. These dashboards are described in [Analytics Dashboards](./analytics-dashboards.md).
2. Moderators review the context and determine the severity.
3. Actions are recorded via `ApplyModerationAction` gRPC calls (see [`logging_admin_service.proto`](../../../../protos/logging-admin/v1/logging_admin_service.proto)). Logging & Admin persists the moderation action and evaluates policy; it does not delete accounts or terminate sessions as a side effect of recording the action.
4. The owning runtime enforces the applicable policy at its authoritative boundary: Game Session enforces gameplay admission, Social & Groups enforces chat-send policy, and Account owns account security-state transitions and player notification. A broader cross-service suspension, recovery, appeal, and notification workflow remains separate Account-owned product work.

## Appeals

Players may appeal bans through a web form provided by the Account Service.
Moderators should document evidence and keep responses timely.
