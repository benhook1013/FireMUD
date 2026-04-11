# AI Observations

Append-only notes for recurring friction, surprising behavior, environment issues, inefficient patterns, code smells, and "this should be shaped better" patterns discovered during AI work.

Only keep entries whose lesson still matters after the immediate task is done. Do not use this file as a bug log for ordinary fixes that were completed in the same piece of work. Prefer logging reusable observations that suggest a better repo rule, CI guard, design refinement, or shared implementation pattern.

Entry format:

- `YYYY-MM-DD`: short title
  - Context: where it appeared
  - Observation: what was surprising or wasteful
  - Expected pattern: what should happen instead

- `2026-04-10`: Avoid parallel Gradle invocations against generated proto trees
  - Context: concurrent AI-triggered Gradle runs while validating `account-service` and `game-session-service` after `common-security` changes
  - Observation: overlapping builds against the same workspace produced bogus `javac` read errors in `services/common-security/build/generated/sources/proto/...` and misleading downstream compile failures that disappeared on a serial rerun
  - Expected pattern: run Gradle validations serially when they share the same repo checkout and generated-source directories, especially around protobuf generation and multi-module compile tasks
- `2026-04-11`: Do not hide player-facing authorization behind admin-only interceptors
  - Context: implementing the first account-scoped friend presence path in `social-groups-service`
  - Observation: the previous REST auth shape relied on an admin-only JWT interceptor, which accidentally disguised the fact that player-facing endpoints had no explicit ownership guard and the gRPC side had no equivalent auth interceptor at all
  - Expected pattern: interceptors should authenticate and establish caller context, while controllers and RPC services enforce explicit tenant/account access rules at the endpoint boundary
- `2026-04-11`: Do not start variant-aware stack work before a canonical stack-family substrate exists
  - Context: attempting the next `06.3.2` authored stackability pass in `entity-management-service`
  - Observation: a `DEFINITION_AND_VARIANT` compatibility mode is meaningless if the only authored identity available is the item definition id itself, because adding a variant flag on the same definition does not create a second merge boundary that differs from plain definition-level fingerprinting
  - Expected pattern: variant-aware stack compatibility should only be implemented once there is a canonical stack-family or equivalent per-source variant substrate that can actually distinguish compatible definitions beyond `item_id`
