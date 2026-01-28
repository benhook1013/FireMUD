# Architecture Review Prompt: Per-Service Deep Dive Template

Use this template to review any individual microservice in the context of the overall architecture.

First, gather and read:

- The main architecture overview documents you need for context, such as:
  - `design/architecture/system-architecture-overview.md`
  - `design/architecture/system-context-diagram.md`
  - `design/architecture/service-responsibility-matrix.md`
- The service-specific docs for the target service, for example:
  - `design/architecture/microservices/<service-name>/README.md`
  - Any additional design docs under `design/architecture/microservices/<service-name>/`
  - Any related system-architecture docs that describe this service’s protocols, data, or operations.

Then:

- Review the chosen service’s responsibilities, interfaces, and data flows in the context of the whole system.
- Do not summarize the service or describe what is already working well.
- Only identify problems, contradictions, or gaps: unclear or leaking boundaries with neighboring services, responsibilities that conflict with the global responsibility matrix, underspecified failure or retry behavior, ambiguous data ownership or lifecycle, or missing observability and operational hooks.
- For each issue, reference the specific document or documents involved and propose concrete, actionable improvements, such as clearer contracts, responsibility shifts between services, additional diagrams or flow descriptions, or more precise error and lifecycle handling.
