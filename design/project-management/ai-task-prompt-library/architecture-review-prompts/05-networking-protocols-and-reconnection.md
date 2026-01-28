# Architecture Review Prompt: Networking, Protocols, and Reconnection

Read the following documents:

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
- Do not summarize intended behavior or praise what is already clear.
- Only identify problems, contradictions, or gaps: unclear ownership between the TCP proxy, gateway, and backend services; missing or inconsistent protocol guarantees such as ordering, backpressure, or idempotency; weak reconnection semantics; under-specified degraded modes; or operational edge cases not covered, such as partial disconnects, slow clients, or load spikes.
- For each issue, reference the specific document or documents involved and propose concrete, actionable improvements, such as clarified responsibilities, more precise protocol contracts, explicit reconnection and state-recovery rules, or additional degraded-mode and failure-handling scenarios.
