# Mud Client Protocol and Classic-Client Extensions

This document defines the current boundary for optional classic-client semantic extensions. It does not make MCP, GMCP, Telnet options, or a FireMUD-specific package a supported gameplay contract. The accepted decision is [MCP-01](./decisions/adr-0145-plain-text-gameplay-and-deferred-classic-client-extensions.md).

## Normative Target Contract

Line-oriented plain text is FireMUD's universal gameplay protocol. Every supported command and server outcome has a deterministic text representation that works for first-party WebSocket clients, Telnet clients, and clients using the TCP Proxy bridge. Authentication, admission, session control, reconnect, command correctness, and essential output never depend on a structured classic-client extension.

The first-party WebSocket surface may use the explicitly versioned structured `PlayerOutput` projection owned by [Input, Output, and Presentation](./system-architecture-input-output-and-presentation.md). That projection retains a deterministic plain-text representation; its structured envelope is a first-party client projection, not the universal classic-client wire protocol and not an MCP contract.

## Deferred Classic-Client Extension Support

Classic-client semantic extension support is experimental, disabled, unadvertised, and unimplemented. MCP 2.1, `mcp-negotiate`, `mcp-cord`, GMCP, Telnet option packages, and FireMUD-specific package names are research candidates rather than supported contracts. Existing marker recognition, a server greeting, configuration, or parser tests do not establish interoperability or an advertised compatibility promise. The verified current TCP Proxy seam still recognizes MCP-looking markers and, when `TCP_PROXY_MCP_ENABLED` is enabled, emits an `#$#mcp version:2.1` greeting; this is implementation drift, not supported MCP. Supported deployments must keep that path disabled, unadvertised, and fail closed until an accepted Game Session-owned adapter has end-to-end proof.

Before any extension is enabled or advertised, research must compare MCP, GMCP, and other credible bounded alternatives against the exact current-client and version matrix FireMUD intends to support. The comparison must cover wire and negotiation behavior, failure/fallback behavior, security implications, package/version semantics, maintenance cost, and mapping from semantic `PlayerOutput` without making gameplay depend on the extension. A follow-up architecture update records the selected adapter or the decision to support none. No extension schema is defined here until that decision and proof exist.

## Semantic Ownership If an Extension Is Selected

If later research selects MCP or another classic-client semantic adapter, Game Session is the sole owner of its per-connection semantic state: greeting and negotiation, version/package selection, parsing and validation, advertisement and activation, correlation-key handling, any package/cord/data-tag lifecycle, mapping from `PlayerOutput`, failure/fallback, and reconnect behavior. Game Session must keep the adapter optional to gameplay and must preserve the universal plain-text projection.

TCP Proxy remains a generic bounded transport and safety bridge. It may enforce generic line-size, buffering, connection, and opaque marker-rate limits before forwarding, but it does not emit semantic greetings, decide negotiation success, parse packages, validate semantic keys, count/reassemble package resources, or maintain a duplicate semantic state machine. Accepted opaque input that exceeds a generic limit or cannot be forwarded under backpressure receives an explicit bounded error/close; the edge does not keep a connection open after silently discarding it. Neither TCP Proxy nor Gateway interprets extension semantics. TCP Proxy must not promise Telnet parity for an independent semantic protocol or translate that protocol. Gateway remains the API/gameplay ingress and edge close-translation authority; it does not become a semantic-extension owner.

If an authentication/correlation key exists in a future adapter, it is only a per-connection framing correlation value. It is never evidence of account identity, authorization, entitlement, gameplay-session ownership, character control, reconnect authority, or attach authority. Identity and gameplay authority continue through transport trust and the canonical `LOGIN`, `PLAY`, connect-context, session-binding, and owner-enforcement contracts.

## Fresh Connections and Optional Semantics

Negotiation and package-local state are scoped to one client transport connection. A reconnect starts without a prior version, package activation, correlation key, cord, or data-tag state. Neither TCP Proxy, Gateway, Game Session recovery, nor Redis gameplay state restores extension bytes or semantic state. A client remains fully playable when an extension is absent, rejected, incompatible, rate-limited, malformed, or lost on reconnect.

Fresh transport behavior follows [Reconnection Strategy](./system-architecture-reconnection.md) and [Protocol Bridging](./system-architecture-protocol-bridging.md):

- The target new Telnet lobby sequence is exactly `WORLDS -> LOGIN -> REALMS -> conditional JOIN -> conditional CHARS/creation -> PLAY -> fresh output`. Current unavailable realm, character, and join steps remain disabled, unadvertised, and fail closed; the TCP Proxy does not reuse browser bootstrap state or prior transport-local scope.
- A first-party browser reconnect uses a fresh `/ws/game/**` connection, the owner-defined connect-token/bootstrap checks, bare `LOGIN`, and `PLAY`; it never prompts the browser to replay Telnet credentials after bootstrap is restored.
- No client input, raw bytes, WebSocket frames, unsent output, or prior transport stream is replayed across a client-facing reconnect. A retained semantic recent-context window, when the current binding remains eligible, is a newly projected orientation aid rather than transport-byte replay or delivery acknowledgement.
- After successful fresh `LOGIN` and `PLAY`, Game Session emits a mandatory fresh authoritative `LOOK` from current state. Retained context, a prompt, or any optional structured projection is additional and never replaces `LOOK`.
- Prompt behavior remains governed by the effective `firemud.presentation.prompt.enabled` and `firemud.presentation.prompt.emit-after-reconnect-restore` settings; a reconnect emits one current prompt only when both are enabled, otherwise zero. Current composition and proof remain partial as recorded in [Input, Output, and Presentation](./system-architecture-input-output-and-presentation.md#implemented-status).
- Gameplay localization remains owned by Game Session's presentation contract: exact-locale, explicitly stored base-language, then source-locale fallback. A classic-client extension does not choose a provider or translate authored content/player speech on the gameplay hot path.
- Gateway's close taxonomy remains canonical (`logout`, `session_replaced`, `service_restart`, `idle_timeout`, `policy_violation`, `internal_error`, `backend_unavailable`), with TCP Proxy preserving the authenticated top-level outcome as its Telnet token. A close reports connection/session lifecycle, not command commit status.

## Implementation and Proof Status

Current implementation evidence is insufficient for any classic-client extension support claim. TCP Proxy currently recognizes MCP-looking markers and, when `TCP_PROXY_MCP_ENABLED` is enabled, can emit the `#$#mcp version:2.1` greeting, but that is implementation drift against this ownership boundary, not compliant negotiation or package proof. The seam must remain disabled by default and unadvertised, and it must fail closed until a Game Session-owned adapter is accepted and proven. There is no accepted current-client compatibility matrix or end-to-end proof for a stable extension/version pair.

The existing first-party `PlayerOutput` seam and text renderer remain separate proof obligations. A generic browser JSON consumer does not prove semantic `PlayerOutput` consumption, and a future classic-client adapter does not prove browser structured-output compatibility. Focused proof for the selected future adapter would need exact client/version coverage, successful and rejected negotiation, malformed/unknown/timeout paths, correlation mismatch without authority use, resource and line limits, reconnect reset, plain-text fallback, semantic parity, and public Telnet operation. Until that evidence exists, documentation and deployment must not advertise the adapter.

## Related Documentation

- [Protocol Bridging](./system-architecture-protocol-bridging.md)
- [TCP Proxy Service](./microservices/tcp-proxy-service/README.md)
- [TCP Proxy protocols](./microservices/tcp-proxy-service/protocols.md)
- [Input, Output, and Presentation](./system-architecture-input-output-and-presentation.md)
- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Gateway Architecture](./system-architecture-gateway.md)
- [MCP-01 decision](./decisions/adr-0145-plain-text-gameplay-and-deferred-classic-client-extensions.md)
