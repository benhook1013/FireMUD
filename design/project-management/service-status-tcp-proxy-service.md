# TCP Proxy Service Status

## Current Coverage

- Telnet ingress, hidden bootstrap metadata, WebSocket bridging, and buffering are implemented on the canonical gameplay ingress path.
- The proxy participates in the current Telnet-backed `WORLDS`, `LOGIN`, `PLAY`, `LOOK`, and shared communication gameplay slices.
- Cross-service harnesses and lighter gateway-stub-based testing patterns exist for the proxy path.

## Current Role In The Platform

- Acts as the Telnet/MUD-client edge bridge into the shared gameplay ingress path.
- Preserves transport-specific concerns like Telnet framing, hidden bootstrap metadata, and future MCP-based attach hints while deferring gameplay semantics to downstream services.
- Supports the parity goal between Telnet and first-party WebSocket gameplay clients.

## Partial / Stubbed / Deferred Areas

- Production-hardening items like full Proxy->Gateway mTLS, PROXY protocol support, richer Telnet option/MCP support, and deeper abuse detection are still open.
- The current Telnet parity path is covered by active cross-service tests; remaining edge-test work is about deeper hardening, not reviving stale disabled scaffolding.
- The service is strong enough for current slices, but not yet at final edge-hardening maturity.

## Planning Notes

- Use gameplay vertical slices for parity work and separate platform-hardening phases for edge/network maturity.
