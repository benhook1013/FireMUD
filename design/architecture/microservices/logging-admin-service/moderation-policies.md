# 🛡️ Moderation Policies

This file outlines recommended moderation rules for hosted FireMUD games.
Operators can adapt these policies based on community needs while maintaining a safe environment
for players.

## Core Policies

1. **Zero Tolerance for Hate Speech** – racial, sexual, or other discriminatory language results in
   immediate bans. (TODO: Not yet implemented)
2. **Profanity Filtering** – common swear words are automatically masked by the
   Social & Groups Service.
3. **Harassment and Threats** – direct or implied threats lead to temporary suspensions or account
   deletion. (TODO: Not yet implemented)
4. **Spam Prevention** – repeated unsolicited messages trigger rate limiting and
   potential chat mutes.
   (TODO: Not yet implemented)
5. **Cheating and Exploits** – using automation or bugs for unfair advantage results in account
   sanctions. (TODO: Not yet implemented)

## Profanity Filters

The Social & Groups Service integrates a configurable word list. When detected,
profanity is replaced with `*` characters before being routed to other players or persisted in logs.
Operators can customize the word list per tenant (TODO: Not yet implemented).
Bypassing the filter with misspellings or Unicode look-alikes is considered a violation.

## Enforcement Workflow

1. Offending logs or reports are flagged in the Logging & Admin Service dashboards. (TODO: Not yet
   implemented)
2. Moderators review the context and determine the severity. (TODO: Not yet implemented)
3. Actions are recorded via `ApplyModerationAction` gRPC calls and stored in the
   `moderation_action` table.
4. Notifications are sent to affected players with reason and duration. (TODO: Not yet implemented)

## Appeals

Players may appeal bans through a web form provided by the Account Service. (TODO: Not yet
implemented)
Moderators should document evidence and keep responses timely.
