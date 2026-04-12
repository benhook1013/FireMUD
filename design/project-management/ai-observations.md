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

- `2026-04-12`: Do not collapse gameplay target dimensions into a two-slot command payload
  - Context: implementing `09.1` bootstrap discovery and server-resolved connect scope in `account-service` while checking how the current `PLAY` path in `game-session-service` consumes world selection
  - Observation: the current text-command selection seam effectively treats gameplay target selection as `world + optional secondary`, which makes later realm-aware routing awkward and encourages temporary config-backed shortcuts because `world`, `realm`, and `character` are not modeled as first-class dimensions end-to-end
  - Expected pattern: canonical routing-sensitive command payloads should preserve the full selection structure they need for the target architecture instead of compressing multiple future dimensions into one optional slot

- `2026-04-12`: Localized player-output variants need explicit message keys or updated bundles
  - Context: updating `PLAY` guidance strings in `game-session-service` to become realm-aware after the new `09.1` bootstrap/connect-scope work
  - Observation: changing Java-side error text alone did not change rendered localized output because `TextPlayerOutputRenderer` prefers `messageKey` templates from `presentation-messages*.properties`, so reusing a generic key for a more specific guidance path silently produced stale or wrong user-facing text
  - Expected pattern: when a command path introduces a new user-visible guidance variant, add a distinct message key and update the locale bundles in the same change instead of assuming the raw fallback message will be rendered

- `2026-04-12`: Runtime tenant admission cannot stay encoded as `accounts.tenant_id`
  - Context: tracing `09.2` public-production onboarding from `PLAY` and connect-token issuance back into `account-service`
  - Observation: the current runtime-membership path still answers "can this account play in tenant X?" by comparing `accounts.tenant_id` to the requested tenant, which makes first-join onboarding, multi-realm discovery, and future multi-tenant routing look implemented when they are really resting on a single-tenant shortcut
  - Expected pattern: gameplay admission should read a dedicated membership/grant substrate and use explicit writer boundaries like `EnsurePublicProductionPlayerMembership(...)` rather than treating account ownership fields as the long-term runtime authority
