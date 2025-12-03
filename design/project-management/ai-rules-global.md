# All-Project AI Rules

## 1. General Code Rules

- Provide complete, functional code unless code-only is requested
- Avoid deprecated or unstable features
- Respect existing architecture; avoid new patterns or tech unless necessary, and remove old code if replaced
- Mark unknown APIs or behavior as uncertain; don’t guess
- Restart servers after changes; kill related ones first
- Reuse existing code and patterns before writing new logic
- Prefer simple solutions; avoid duplication by checking for existing logic
- Never mock or stub data in dev or prod—only in tests

## 2. Style and Formatting

- Follow consistent, idiomatic formatting for the language (indentation, casing, etc.)
- Prefer explicit over implicit behavior
- Include boilerplate unless minimal examples are requested
- Keep code clean, modular, and well organized
- Avoid manual line wrapping; let lines flow naturally so content displays correctly in editors and
  on GitHub.
- Refactor files over 300 lines

## 3. Validation and Error Handling

- Warn if assumptions are made (e.g., if inputs are presumed valid)
- Write code with all environments in mind: dev, test, and prod
- Ensure valid syntax
- Include basic error handling (e.g., try/catch, null checks)
- Log errors with enough context for debugging
- gRPC services must return `ErrorDetail` fields on logical failures instead of throwing transport exceptions. Wrap response observers or use an interceptor to log warnings, increment a `grpc.app_error` metric with the error code, and tag tracing spans. Use `onError()` only for transport or infrastructure issues.

## 4. Comments and Explanation

- Include comments unless code-only is requested
- Explain both what the code does and why
- Write for future contributors, new developers, and your future self
- Explain design choices and limitations when relevant
- Use clear, friendly, technically accurate style

## 5. Performance and Security

- Avoid inefficient or unsafe practices (e.g., unbounded recursion, SQL injection)
- Use safe defaults (e.g., prepared statements, escaped inputs)
- Follow best practices for auth and security (OAuth2, JWT, RBAC, validation, rate limiting)

## 6. Refactoring and Review

- When reviewing or refactoring code:
  - Identify bugs, anti-patterns, and improvements
  - Note strange or nonstandard implementations and suggest better alternatives in your output, but do not apply fixes unless explicitly requested
  - Do not change functionality unless requested
  - Avoid touching unrelated code
  - Update or remove outdated comments

## 7. Multi-file Contexts

- When editing multiple files:
  - Reference filenames explicitly
  - Keep cross-file dependencies clear and minimal

## 8. Project Structure

- Use standard directory layout for the tech stack
- Avoid one-off scripts unless reused

## 9. Architecture & Documentation Behavior

- When editing architecture docs (for example, reconnection, protocol bridging, versioning, or procedural generation), describe the **target-state behavior as if fully implemented** in the main body. Avoid sprinkling “planned” or “will be implemented” caveats throughout flow descriptions.
- If implementation is partial, capture that status in a dedicated section (for example, “Implemented Status” or “Implementation Notes”) near the top or bottom of the document, and keep that section up to date as behavior changes.
- Service-level docs (per-microservice READMEs) may also include implementation status sections, but the architectural narrative should remain clean and present-tense.

## 10. Testing & Observability

- Provide unit tests for new logic and integration tests for features that touch databases or external services.
- Verify coverage with JaCoCo by running `./gradlew check` before committing.
- Instrument new code with Micrometer metrics and OpenTelemetry tracing following `design/architecture/system-architecture-logging-monitoring.md`.
- Use `LoggingInterceptor`, `MetricsInterceptor`, and `TracingInterceptor` from the shared library when adding new gRPC endpoints so logs, metrics, and spans are recorded consistently.
- When editing `.proto` files or gRPC APIs, follow the conventions in `design/architecture/system-architecture-grpc.md` for schema layout, versioning, and error-handling rules.

## 11. Tooling Available to AI

- The development environment includes the GitHub CLI (`gh`) configured for this repository.
- AI tools may use `gh` to inspect and manage pull requests (for example, listing PRs, viewing diffs, and editing descriptions) when explicitly asked.
- Do not create, merge, or close pull requests with `gh` unless a human contributor requests that action for a specific task.
- When working on a feature branch that has (or will have) an open PR, always keep a brief, accurate PR summary up to date as part of the change:
  - Maintain the current summary in the PR body and, when a local `pr-summary.md` file exists, keep that file in sync with the implemented changes.
  - When the user explicitly asks to refresh the PR description, prefer using `gh pr edit --body-file pr-summary.md` to apply the local summary to the PR; if no summary file exists, update the PR body directly via `gh` instead.
  - When creating or editing PR bodies, always provide Markdown via a file (for example `--body-file`) or stdin with real newlines; avoid passing a single shell string with literal `\n` escapes so formatting renders correctly on GitHub.
