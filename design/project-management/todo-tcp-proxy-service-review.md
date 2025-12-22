# TCP Proxy Service – Review TODOs

This temporary checklist tracks design/doc changes identified during the TCP Proxy Service review so they are not lost to summarisation or context limits. Items can be migrated into permanent task lists once resolved.

## Raw Telnet Security & UX

- [ ] Document a per-connection flag/marker for **plaintext Telnet** vs **TLS Telnet** that is propagated from the TCP Proxy through Gateway to Game Session.
- [ ] Specify that plaintext Telnet connections show a **landing menu security warning** advising players to use the TLS Telnet port or web client instead.
- [ ] Introduce a **deployment-wide setting** (default: enabled) that requires two-factor authentication (2FA) for logins over plaintext Telnet.
- [ ] Define a per-account setting (checkbox in the web portal, option in the Telnet account setup flow) that controls whether an account is allowed to log in over plaintext Telnet; this flag defaults to **off** and includes a clear risk explanation.
- [ ] Update **Authentication & Authorization**, **Security Architecture**, **Protocol Bridging**, **TCP Proxy Service design**, and **Account Service design** to reflect the plaintext Telnet 2FA requirement, per-account flag, and landing-menu warning.
- [ ] Add the new deployment-wide setting to **Environment Variables & Secrets Management** with recommended defaults for dev vs production.

## IP Headers & Trust Model

- [ ] Clarify in architecture docs that Spring Cloud Gateway strips or ignores `X-Client-IP` from public traffic and only trusts the value when it originates from the TCP Proxy path, combining it with `X-Forwarded-For` as documented.
- [ ] Ensure the TCP Proxy design and Security Architecture describe a consistent, end-to-end trust model for client IPs across Telnet and WebSocket flows.

## NotifyDisconnect Semantics

- [ ] Tighten documentation for `NotifyDisconnect` delivery guarantees (retry/backoff behaviour, maximum buffering, and failure modes) so operators know what to expect when the Game Session Service is unavailable.
- [ ] Document the recommended idempotency key shape and consumption rules in the Game Session and Reconnection docs to keep implementations aligned.

## TLS / Certificate Configuration

- [ ] Make it explicit that the `FIREMUD_GRPC_*` certificate paths are intentionally reused by the TCP Proxy’s WebSocket client in the current design, and note that future iterations may introduce dedicated gateway WebSocket TLS variables if separate credentials are required.

## Telnet Option Whitelist & MCP

- [ ] Add a short table of **allowed Telnet options/commands** and a “Compatibility Notes” section in the TCP Proxy design so client authors understand what is supported and what is intentionally ignored.
- [ ] Clarify **current vs target-state MCP support** (which MCP packages and flows are implemented today, which are planned) and add a brief “Client Expectations” note about experimental behaviour and fallback to plain Telnet.

