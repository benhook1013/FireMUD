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
