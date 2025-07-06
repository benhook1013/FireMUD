# 🛡️ Moderation Policies

This file outlines recommended moderation rules for hosted FireMUD games. Operators can adapt these policies based on community needs while maintaining a safe environment for players.

## Core Policies

1. **Zero Tolerance for Hate Speech** – racial, sexual, or other discriminatory language results in immediate bans.
2. **Profanity Filtering** – common swear words are automatically masked by the Social & Groups Service.
3. **Harassment and Threats** – direct or implied threats lead to temporary suspensions or account deletion.
4. **Spam Prevention** – repeated unsolicited messages trigger rate limiting and potential chat mutes.
5. **Cheating and Exploits** – using automation or bugs for unfair advantage results in account sanctions.

## Profanity Filters

The Social & Groups Service integrates a configurable word list. When detected, profanity is replaced with `*` characters before being routed to other players or persisted in logs. Operators can customize the word list per tenant. Bypassing the filter with misspellings or Unicode look-alikes is considered a violation.

## Enforcement Workflow

1. Offending logs or reports are flagged in the Logging & Admin Service dashboards.
2. Moderators review the context and determine the severity.
3. Actions are recorded via `ApplyModerationAction` gRPC calls and stored in the `moderation_action` table.
4. Notifications are sent to affected players with reason and duration.

## Appeals

Players may appeal bans through a web form provided by the Account Service. Moderators should document evidence and keep responses timely.

