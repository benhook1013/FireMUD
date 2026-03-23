# TCP Proxy Service Status

## Current Coverage

- Telnet ingress, session envelope handling, WebSocket bridging, buffering, and local dev echo flows are implemented.
- The proxy participates in the current Telnet-backed `LOGIN`, `LOOK`, and `SAY` gameplay slices.
- Cross-service harnesses and lighter gateway-stub-based testing patterns exist for the proxy path.

## Current Role In The Platform

- Acts as the Telnet/MUD-client edge bridge into the shared gameplay ingress path.
- Preserves transport-specific concerns like Telnet framing and session-envelope handling while deferring gameplay semantics to downstream services.
- Supports the parity goal between Telnet and first-party WebSocket gameplay clients.

## Partial / Stubbed / Deferred Areas

- Production-hardening items like full Proxy->Gateway mTLS, PROXY protocol support, richer Telnet option/MCP support, and deeper abuse detection are still open.
- One older cross-service account/game-session Telnet flow test is explicitly stale and disabled.
- The service is strong enough for current slices, but not yet at final edge-hardening maturity.

## Planning Notes

- Use gameplay vertical slices for parity work and separate platform-hardening phases for edge/network maturity.
