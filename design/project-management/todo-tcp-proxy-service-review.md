# TCP Proxy Service Design Review Tasks

This document tracks documentation-only review items for the TCP Proxy Service
and its surrounding architecture docs. It is scoped to **design and docs** only;
implementation work remains in `task-list-tcp-proxy-service.md` and related
vertical-slice task lists.

## Status Legend

- [ ] Pending
- [x] Completed

---

## A. Telnet SESSION Optionality & Examples

- [ ] A1 – Update `design/developer-workflows/login-session-smoke-tests.md` to:
  - Show a **baseline Telnet flow without `SESSION`** (plain `LOGIN` + `LOOK`) as the first example.
  - Keep the existing `SESSION` example but explicitly label it as an **advanced attach-to-session** variant.
  - Add a short note that `SESSION` is always optional and Telnet clients may rely on `LOGIN` only, matching WebSocket clients.
- [ ] A2 – In `design/architecture/system-architecture-protocol-bridging.md`, add a one-sentence reminder in the Telnet flow section that `SESSION` is optional and advanced-only, and link back to the TCP Proxy design’s **Telnet Session Envelope & Event Metrics** section.
- [ ] A3 – In `design/architecture/user-journeys.md` under **7. Player Login and Gameplay**, add a short sentence clarifying that Telnet clients may connect with `LOGIN` only and that `SESSION` is an optional optimization for advanced tooling.

## B. Telnet Session Envelope & Malformed Envelope Budget

- [ ] B1 – In `design/architecture/microservices/tcp-proxy-service/README.md` under **Telnet Session Envelope & Event Metrics**, explicitly describe how `TCP_PROXY_MAX_MALFORMED_ENVELOPES` interacts with malformed `SESSION` lines:
  - Individual malformed envelopes are silently ignored (no client error) but increment a per-connection counter.
  - When the counter exceeds `TCP_PROXY_MAX_MALFORMED_ENVELOPES`, the proxy closes the connection as abusive and increments the appropriate metrics.
- [ ] B2 – Add a brief cross-reference from the **Environment Variables** section of the same README back to the envelope rules so operators understand that tuning `TCP_PROXY_MAX_MALFORMED_ENVELOPES` changes when the proxy closes abusive connections.

## C. Proxy → Gateway WebSocket mTLS Target-State vs Current Behavior

- [ ] C1 – In `design/architecture/system-architecture-overview.md` and `design/architecture/system-architecture-protocol-bridging.md`, update the description of Proxy → Gateway `wss://` mutual TLS to clearly label it as **target-state** and refer readers to the TCP Proxy **Implementation Status** table for the current behavior.
- [ ] C2 – In `design/architecture/microservices/tcp-proxy-service/README.md`, add a short “Current default” note near the WebSocket mTLS section spelling out how the proxy behaves today (for example, `ws://` or `wss://` without client certs) until the “Wire mutual TLS for Proxy → Gateway WebSocket traffic” task is completed.
- [ ] C3 – In `design/project-management/task-list-tcp-proxy-service.md`, extend the existing mTLS-related checkbox item with a brief acceptance criteria summary (for example, “Gateway link uses `wss://` with client certificates, TLS validation failures are surfaced via `tcpproxy.gateway.handshake.failures{reason=...}`, and architecture docs are updated to remove ‘target-state’ qualifiers”).

## D. TLS Surface Clarification (Telnet vs mTLS vs gRPC)

- [ ] D1 – In `design/architecture/microservices/tcp-proxy-service/README.md` **Environment Variables** table, group or annotate TLS-related variables by surface:
  - Telnet listener TLS: `TCP_PROXY_TLS_ENABLED`, `TCP_PROXY_TLS_CERT`, `TCP_PROXY_TLS_KEY`.
  - Proxy → Gateway WebSocket mTLS: `GATEWAY_WS_URL` plus `FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH`, `FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH`, `FIREMUD_GATEWAY_WS_CA_CERT_PATH`.
  - Internal gRPC server mTLS: the same `FIREMUD_GRPC_*` variables where applicable.
- [ ] D2 – Add a one-sentence clarification in both the TCP Proxy design and `design/architecture/system-architecture-protocol-bridging.md` that **Telnet-over-TLS** certs are independent from the **Proxy → Gateway mTLS** certs; they may reuse the same files, but are conceptually different trust surfaces.

## E. Plaintext Telnet Security – Single Canonical Reference

- [ ] E1 – Choose a single canonical home for plaintext Telnet security rules (preferably the TCP Proxy design or `design/architecture/system-architecture-security.md`) and:
  - Summarize the invariants: 2FA required, explicit account opt-in, plaintext sessions tagged and warned, enforcement owned by Game Session / auth layer.
  - Mark this as the “authoritative” section for plaintext Telnet rules.
- [ ] E2 – In `design/architecture/system-architecture-protocol-bridging.md`, trim the full plaintext Telnet explanation down to a short summary and a link to the canonical section chosen in E1, to avoid duplicated long-form descriptions.

## F. NotifyDisconnect Semantics & Layering

- [ ] F1 – In the `NotifyDisconnect` / event integration portion of `design/architecture/microservices/tcp-proxy-service/README.md`, add a bolded invariant that **Game Session must always be able to detect disconnects without this event; `NotifyDisconnect` is only an optimization hint**.
- [ ] F2 – In `design/architecture/system-architecture-reconnection.md`, extend the TCP Proxy layer bullet list or nearby text to restate that `NotifyDisconnect` is best-effort and never the sole source of truth for session liveness.

## G. Environment Tuning: Dev vs Prod Recommendations

- [ ] G1 – In `design/architecture/microservices/tcp-proxy-service/README.md` near **Environment Variables** / **Tuning TCP Proxy for Different Environments**, add explicit “Recommended dev defaults” and “Minimum viable prod hardening” bullets:
  - Dev: small connection caps, generous malformed envelope budget, permissive TLS assumptions, explicit note about `ws://` vs `wss://`.
  - Prod: non-zero caps sized to player counts, stricter malformed-envelope budget, strong recommendation for `wss://` with mTLS to Gateway.
- [ ] G2 – From `design/architecture/system-architecture-protocol-bridging.md`, add a short pointer in the Telnet flow section that environment-specific tuning guidance for the proxy lives in the TCP Proxy design’s tuning section.

## H. MCP vs Abuse Heuristics

- [ ] H1 – In `design/architecture/system-architecture-mud-client-protocol.md`, add a short “Interaction with abuse heuristics” note clarifying that MCP control lines are treated as application-level text and that abuse detection operates at the Telnet control/envelope level; unknown MCP packages or messages are not treated as abuse by default.
- [ ] H2 – In the **Security** / abuse-detection portion of `design/architecture/microservices/tcp-proxy-service/README.md`, explicitly list which signals are considered hard abuse (for example, line-length floods, repeated malformed `SESSION`, connection churn) vs diagnostic-only signals, and note that MCP parsing errors fall into the diagnostic-only bucket.

## I. Canonical Specs & Cross-Doc Duplication

- [ ] I1 – Near the top of `design/architecture/microservices/tcp-proxy-service/README.md`, add a small “Canonical Specs” note listing:
  - `SESSION` + `LOGIN` semantics and header propagation (Telnet Session Envelope & Event Metrics).
  - `NotifyDisconnect` event semantics.
  - Proxy metrics naming and label cardinality rules.
- [ ] I2 – In `design/architecture/system-architecture-gateway.md` and `design/architecture/system-architecture-protocol-bridging.md`, trim any restated, detailed Telnet envelope rules down to short summaries and point back to the TCP Proxy design’s canonical sections for exact protocol details.

## J. Miscellaneous Cleanups & Encoding Artifacts

- [ ] J1 – As documentation edits are made above, clean up any stray encoding artifacts (for example `ƒÅ'`, `ƒ?"`, or other mojibake) in the touched docs:
  - `design/architecture/system-architecture-gateway.md`
  - `design/architecture/system-architecture-protocol-bridging.md`
  - `design/architecture/system-architecture-reconnection.md`
  - `design/architecture/system-architecture-mud-client-protocol.md`
  - `design/architecture/user-journeys.md`
  - `design/architecture/microservices/tcp-proxy-service/README.md`
- [ ] J2 – If additional minor inconsistencies or typos are discovered while implementing these tasks, fix them and add a brief note to this list (as a completed sub-item) to keep an audit trail of doc-only cleanups.

## K. Telnet Client IP Preservation (HAProxy + PROXY protocol)

- [x] K1 – Document HAProxy Telnet edge proxy + PROXY protocol as the preferred production approach for preserving client IPs without relying on `externalTrafficPolicy: Local` (`design/architecture/infrastructure/deployment-environments.md`, `design/architecture/system-architecture-security.md`, `design/architecture/system-architecture-protocol-bridging.md`, `design/architecture/microservices/tcp-proxy-service/README.md`, `design/architecture/system-architecture-gateway.md`)

## L. Header Trust Model (Gateway Canonicalization)

- [x] L1 – Add a dedicated “Header Trust Model” section to `design/architecture/system-architecture-gateway.md` defining `X-Proxy-*` inputs, strip/drop rules, and gateway output rules; align TCP Proxy, Protocol Bridging, and related docs to use `X-Proxy-Client-IP` / `X-Proxy-Session-Id` / `X-Proxy-Tenant-Id` / `X-Proxy-Connection-Id` as inputs rather than trusting `X-Client-IP` or `X-Session-Id` from upstream

## M. Gateway Handshake Failure Reason Enum

- [ ] M1 – Decide how to standardize `tcpproxy.gateway.handshake.failures{reason="..."}`:
  - Prefer a small bounded enum for `reason` (no exception messages/classes).
  - Reuse an existing `firemud-common` error/code enum only if it cleanly fits TLS/WebSocket handshake failures; otherwise define a proxy-local enum and document the mapping rules.
