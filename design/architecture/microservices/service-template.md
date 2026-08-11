# {{ Service Name }}

## Overview

{{ Briefly describe the service's main purpose. }}

### Responsibilities

{{ Bullet list of core responsibilities. }}

## Architecture / Design Notes

- {{ Key architecture choice or pattern (e.g., gRPC, REST). }}
- {{ State management approach or other design considerations. }}
- Describe the service’s ingress and egress surfaces using the canonical top-level terms: `player traffic plane`, `external admin/creator API plane`, and `infrastructure management plane` where applicable.
- If the service exposes an edge-routable admin route, state whether it is a read-only contract, an explicitly documented bypass-safe workflow, or internal-only by default.
- If the service marks an edge-routable write as bypass-safe, document the route shape and method, why it is domain-local, why it avoids Logging & Admin-owned policy and cross-domain orchestration, and what audit behavior it emits.
- If the service participates in the canonical room-read contract, link to the [causal-read fence owner contract](../system-architecture-identifier-glossary.md#cross-service-causal-read-fence-identity) and document only the service-local request/response behavior and proof status. State that current `worldSnapshotId`/`entitySnapshotId` values are deterministic same-scope markers with no mutation-freshness proof; target behavior serves the requested same-scope/epoch floor and returns actual component versions, with local rejection/retry for behind-floor or mixed-scope/epoch responses. Do not duplicate the owner’s floor or composite-identity contract.

## Key Features

- **{{ Feature 1 }}** — {{ One-liner description of the feature. }}
- **{{ Feature 2 }}** — {{ One-liner description of the feature. }}
- **{{ Feature 3 }}** — {{ One-liner description of the feature. }}

### Data Model

{{ Brief description of important tables or storage schemas. }}

### Readiness and Liveness

- `liveness` is {{ local process health meaning; keep this local-only and do not fail it only because downstream dependencies are degraded. }}
- `readiness` is {{ safe admission meaning for the service's current exposed traffic contract. }}
- Readiness is {{ local-only or dependency-aware }} for this service because {{ short reason tied to the current contract. }}
- While unready, new traffic should {{ be refused, receive an explicit unavailable/startup response, or remain unrouted by orchestration }} rather than relying on retries or delayed convergence to hide startup races.
- If readiness uses synthetic canaries, document the reserved probe identifiers and confirm they are bounded, side-effect free, and clearly separated from real gameplay or operator state.

### gRPC APIs

- `{{ Method }}` – {{ Brief description. }}

### REST APIs

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/ping` | Health check |

### Traffic Surface Classification

- `player traffic plane`: {{ Describe any player-facing HTTP/WebSocket/Telnet surface, or state "none". }}
- `external admin/creator API plane`: {{ Describe any Gateway-routed operator/creator HTTP(S) surface, or state "none". }}
- `infrastructure management plane`: {{ Describe any internal Gateway management or infrastructure-control interaction, or state "none". }}

## Dependencies

- **Internal:** {{ Other FireMUD services this one relies on. }}
- **External:** {{ Databases, caches, or third-party systems. }}

> See [**Gateway Architecture**](../system-architecture-gateway.md), [**Deployment Environments**](../infrastructure/deployment-environments.md), and [**Protocol Bridging**](../system-architecture-protocol-bridging.md) for details on shared infrastructure components.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health/readiness` and `/actuator/health/liveness` probes. Do not treat plain `/actuator/health` as an orchestration contract. See [Deployment Environments](../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../system-architecture-logging-monitoring.md) pipeline.
The OpenTelemetry collector endpoint can be overridden via `OTEL_ENDPOINT` (see [Environment Variables & Secrets Management](../infrastructure/environment-and-secrets.md)).

## Environment Variables

{{ Important environment variables and what they control. }}

## Proto Files

{{ Location of the protobuf definitions and how to regenerate stubs. }}

## Additional Details

### REST & gRPC Endpoints

- `GET /ping` – health check.
- `{{ RPC Method }}` – {{ brief description }}

## Related Documentation

- {{ Links to other design docs that expand on this service. }}
