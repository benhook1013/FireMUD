# AI Observations

Append-only notes for recurring friction, surprising behavior, environment issues, inefficient patterns, and code smells discovered during AI work.

Only keep entries whose lesson still matters after the immediate task is done. Do not use this file as a bug log for ordinary fixes that were completed in the same piece of work.

Entry format:

- `YYYY-MM-DD`: short title
  - Context: where it appeared
  - Observation: what was surprising or wasteful
  - Expected pattern: what should happen instead

## 2026-04-05

- `2026-04-05`: Missing common CLI tools create repeated waste
  - Context: WSL archive inspection work fell back to `jar` because `unzip` was missing.
  - Observation: repeated missing-tool workarounds are a process failure, not an efficient fallback.
  - Expected pattern: install common utilities once and use them directly instead of paying repeated Python or ad hoc workaround cost.

- `2026-04-05`: Spring gRPC transport config is easy to misconfigure silently
  - Context: the repo mixed Spring gRPC `1.0.2` config under `spring.grpc.server.*` with older top-level `grpc.*` server TLS blocks.
  - Observation: transport settings can look valid in YAML while being ignored by the actual framework path.
  - Expected pattern: keep one canonical transport configuration model and back it with an explicit config guard in CI.
