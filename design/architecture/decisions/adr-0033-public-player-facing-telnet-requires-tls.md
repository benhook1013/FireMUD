# ADR 0033: Public Player-Facing Telnet Requires TLS

## Status

Accepted

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `PO-2.2` WebSocket, Telnet, TCP proxy, and protocol bridging
- Affected capabilities: `SF-1.3`, `AA-1.3`, `PO-2.1`, `AA-2.1`, `EA-3.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `SEC-03`

## Context

FireMUD supports classic line-oriented Telnet clients through TCP Proxy Service. The previous design was contradictory: some sources made public plaintext Telnet a permanent production requirement, while the authoritative security policy told player-facing deployments to avoid it until an unspecified transport-admission contract existed.

The implemented text-client authentication path accepts a password or verified-email login code in-band. TCP Proxy hardening, PROXY protocol, connection limits, sanitization, warnings, and network filtering protect availability and abuse boundaries, but none provide confidentiality or integrity for credentials or gameplay traffic on the public network. A TOTP value sent over the same plaintext connection can also be intercepted and raced; adding a factor does not turn Telnet into a protected channel.

TCP Proxy can apply TLS to its listener, and a dedicated public Telnet edge may terminate TLS before forwarding through a restricted internal listener. Public plaintext compatibility would therefore preserve access for TLS-incapable clients at the cost of an avoidable account and session exposure.

## Decision

### Public Transport Boundary

- Every public player-facing Telnet endpoint in hobby/self-hosted, staging, and production environments requires TLS from the client to the public Telnet termination point.
- Each public endpoint selects exactly one of these mutually exclusive modes:
  - **Edge termination plus internal PROXY mode** – A dedicated Telnet edge proxy terminates client TLS and forwards plaintext Telnet with a trusted PROXY header to the internal-only `TCP_PROXY_PROXY_PROTOCOL_PORT`. TCP Proxy TLS is disabled on that listener.
  - **Direct TCP Proxy TLS mode** – The client connects to a TCP Proxy TLS listener with `TCP_PROXY_TLS_ENABLED=true`, and TCP Proxy presents the Telnet certificate and terminates TLS. No preceding TLS-terminating edge or PROXY header is used on that path; the listener observes the TCP peer address.
- Never combine edge TLS termination with TCP Proxy TLS on the same endpoint, and never expose the raw or PROXY-protocol listeners publicly.
- The TCP Proxy raw listener is never exposed directly to the public Internet in a player-facing environment.
- Plaintext Telnet remains supported for local development, automated protocol proof, and explicitly private-network use. Those paths do not qualify as player-facing production evidence and retain the pre-login plaintext warning when real credentials could be entered.
- TLS-incapable clients cannot connect directly to a supported public FireMUD deployment. A user may employ a trusted local TLS wrapper, but FireMUD does not weaken the server boundary to accommodate that client.

### Authentication Factors

- FireMUD does not add a TOTP requirement specifically for Telnet transport admission.
- Password and verified-email login-code modes remain the ordinary gameplay factors established by ADR 0021, but public text-client credential carriage occurs only over the protected TLS boundary.
- Stronger factors or reauthentication for creator, billing, moderation, administrative, and operator actions remain separate control-plane decisions and are not performed over ordinary gameplay commands merely because the player connected through Telnet.

### Deferred Legacy Admission

FireMUD does not build a short-lived plaintext Telnet login-ticket system now. Such a ticket could reduce durable credential disclosure but would add issuance, atomic consumption, recovery, support, and copy/paste friction while leaving gameplay content and commands exposed to observation and tampering. Public plaintext admission requires a new explicit decision rather than being enabled by configuration or documentation alone.

## Consequences

- The public transport rule is simple and testable: HTTPS/WSS and Telnet-over-TLS are protected; public raw Telnet is unsupported.
- Durable account credentials and gameplay traffic are no longer deliberately exposed on a supported public path.
- Classic clients without TLS support require a client upgrade or trusted local wrapper and lose direct public compatibility.
- Ordinary players do not inherit TOTP enrollment, recovery, accessibility, and reconnect friction for a factor that would not repair the transport.
- TLS adds a handshake at connection establishment and modest stream-encryption cost, but no extra per-command authorization, datastore lookup, or gameplay hot-path protocol.
- Raw Telnet remains useful for bounded development and test scenarios without becoming an alternate production security model.

## Alternatives Considered

### Keep Public Plaintext With Warnings And Edge Hardening

This maximizes classic-client compatibility and reuses the existing raw path. Warnings, throttling, filtering, and PROXY protocol do not prevent passive credential capture, session observation, or active command tampering, so this is not accepted as a production-qualified boundary.

### Require TOTP On Plaintext Telnet

TOTP reduces some later password-replay risk but can be intercepted and raced on the same connection. It adds enrollment, recovery, support, and reconnect burden while the entire gameplay session remains unprotected.

### Issue A Short-Lived Single-Use Gameplay Ticket Over HTTPS

This avoids sending a durable password and can preserve more TLS-incapable client compatibility. It still permits active interception and session disclosure, requires a second secure client or copy/paste workflow, and adds ticket issuance and replay state. It remains a future option only if demonstrated product demand justifies a separate decision.

## Implementation and Proof Obligations

- Ensure player-facing manifests expose only a TLS-protected public Telnet port and keep raw/PROXY-protocol listeners internal-only.
- Select and record one public TLS mode per endpoint; reject configurations that enable both edge termination and TCP Proxy TLS for the same path.
- For edge termination plus internal PROXY, prove the edge forwards the expected PROXY version/header to the restricted listener and that malformed, missing, or untrusted headers fail closed. For direct TCP Proxy TLS, prove the TCP Proxy certificate/handshake and peer-address behavior without a PROXY header.
- In either mode, prove TCP Proxy establishes the internal Proxy -> Gateway WebSocket mTLS bridge and preserves the same `LOGIN -> PLAY -> LOOK` protocol flow as browser gameplay.
- Prove the public endpoint completes a valid TLS handshake, presents the expected certificate chain, rejects plaintext, and reaches `LOGIN -> PLAY -> LOOK` through the canonical bridge.
- Keep local and private raw-Telnet tests distinct from player-facing transport evidence.
- Align configuration documentation with the actual listener model; do not advertise a separate `TCP_PROXY_TLS_PORT` unless a distinct listener using that setting exists.
- Preserve the plaintext landing warning on any permitted raw listener without adding a transport-specific TOTP field to the ordinary authentication contract.
- Record certificate lifecycle and rotation proof before claiming player-facing Telnet readiness.

## Required Documentation Alignment

- `design/architecture/system-architecture-security.md`
- `design/architecture/system-architecture-authentication.md`
- `design/architecture/system-architecture-protocol-bridging.md`
- `design/architecture/microservices/tcp-proxy-service/README.md`
- `design/architecture/microservices/tcp-proxy-service/configuration.md`
- `design/project-management/implementation-tracking/platform-operations-and-delivery.md`

## Reversibility and Revisit Triggers

The raw protocol implementation remains available, so this policy is technically reversible. Revisit only if measured demand from TLS-incapable public clients materially affects the product and a proposed admission design explicitly addresses credential exposure, active interception, session confidentiality, operational support, and production proof. Public plaintext must not reappear as an undocumented operator toggle.
