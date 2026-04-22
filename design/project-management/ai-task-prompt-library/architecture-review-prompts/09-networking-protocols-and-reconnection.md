# Architecture Review Prompt: Networking, Protocols, and Reconnection

Read the following documents. Follow references and read nearby related files as required when a listed document points to a canonical contract or when a closely related file is needed to resolve a contradiction or missing implementation-critical rule. Do not recursively traverse unrelated networking docs.

- `design/architecture/microservices/tcp-proxy-service/README.md`
- `design/architecture/microservices/spring-cloud-gateway/README.md`
- `design/architecture/system-architecture-gateway.md`
- `design/architecture/system-architecture-mud-client-protocol.md`
- `design/architecture/system-architecture-protocol-bridging.md`
- `design/architecture/system-architecture-reconnection.md`
- `design/architecture/system-architecture-telnet-degraded-runbook.md`
- `design/architecture/system-architecture-grpc.md`

Then:

- Review networking and client connectivity as a unified design: TCP and WebSocket entry points, gateway behavior, protocol translation, and reconnection flows across these documents.
- Check each finding against any already created slices or implementation that are clearly relevant, in case the issue has already been resolved in code or tracking and the design now needs to import that decision back into the docs.
- Do not summarize intended behavior or praise what is already clear.
- Focus on issues that would force different implementations of admission, transport guarantees, reconnection, or degraded-mode behavior.
- If slices, protos, or current implementation already resolve the seam but the design docs are stale, classify the issue as "import resolved decision back into design" rather than as an unresolved architecture blocker.
- Do not let non-blocking protocol polish and low-probability edge cases crowd out blockers. Once blockers are cleared, list the highest-value non-blocking improvements if they would materially improve the design or operational safety.
- Return at most 5 issues, ordered by severity.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If no implementation-blocking issues remain, say `No implementation-blocking issues found.` and include a `Suggested follow-ups` section with any worthwhile non-blocking improvements, ordered by leverage and not capped at 3.
- Stop once you have either identified the remaining blockers or captured the worthwhile non-blocking follow-ups.
