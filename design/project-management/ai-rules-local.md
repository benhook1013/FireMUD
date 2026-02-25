# FireMUD AI Rules (Local)

These rules are FireMUD-specific and override generic migration-heavy guidance.

## Initial Development Mode (Authoritative)

FireMUD is in initial development. Optimize for direct convergence to a clean canonical state, not backward compatibility.

- Treat old schemas, contracts, and legacy routes as replaceable.
- Prefer direct replacement over phased migration.
- Breaking changes are allowed across DB schema, Redis keys, protocol fields, route shapes, and internal APIs.
- Do not add migration scaffolding unless explicitly requested: no dual-read or dual-write paths, no deprecation windows, no compatibility matrices, and no temporary rollout-only flags.
- When a contract changes, update all call sites, tests, and docs in the same change and remove obsolete paths.

## Documentation in Initial Development

- Document one canonical current behavior.
- Remove obsolete legacy or transitional guidance instead of preserving it.
- Do not add phased rollout or compatibility narratives unless explicitly requested.

## Architecture and Operations Assumptions

- Assume one prod-like topology across dev, hobby self-hosted, and production.
- Optimize for a single-admin operator model.
- Prefer simple automation and one-shot tools over multi-step manual runbooks.
- Add new operator-facing controls only when necessary and not derivable from existing system state.

## FireMUD Runtime and API Invariants

- gRPC application-level failures must be returned as `ErrorDetail` in normal responses, not thrown as transport errors.
- For gRPC application errors, log warnings, increment `grpc.app_error` tagged with error code, and tag spans.
- Use `onError()` only for transport or infrastructure failures.
- When invoking Gradle repeatedly from AI workflows, prefer the default daemon behavior; only use `--no-daemon` for daemon debugging.

## Formatting Rules Source

- Follow [AI Formatting Rules](./ai-formatting-rules.md) for markdown formatting conventions.
