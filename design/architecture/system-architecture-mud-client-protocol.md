# Classic MUD Client Extensions (MCP Research)

This document defines FireMUD's classic-client extension boundary. [ADR 0145](./decisions/adr-0145-plain-text-gameplay-and-deferred-classic-client-extensions.md) is canonical: MCP, GMCP, and other classic-client adapters are research candidates, not currently supported protocols.

## Canonical Client Protocols

Line-oriented plain text is the universal gameplay protocol for Telnet and generic text WebSocket clients. Authentication, `LOGIN`, `PLAY`, commands, essential output, disconnect handling, and reconnect remain complete without a structured extension.

The first-party WebSocket client uses the explicitly versioned structured `PlayerOutput` contract defined by [ADR 0131](./decisions/adr-0131-compact-versioned-player-output-and-late-rendering.md). That bounded projection retains deterministic text parity but is separate from classic-client extension compatibility.

## Current Extension Status

Classic-client extensions are experimental, disabled, unadvertised, and unimplemented. In particular, FireMUD does not currently claim support for:

- MCP 2.1;
- `mcp-negotiate` or `mcp-cord`;
- GMCP;
- FireMUD status, map, chat, notification, attach-hint, or other packages;
- compatibility with any named MCP- or GMCP-capable client.

TCP Proxy contains disabled-by-default marker recognition and a greeting path. Those are implementation residue and partial wire recognition, not compliant MCP negotiation, package support, or interoperability proof. The flag must remain disabled and the platform must not advertise MCP while the research and proof gates below remain open.

## Research and Enablement Gate

Before enabling any classic-client extension, current-client interoperability research must compare MCP, GMCP, and any credible bounded alternative against concrete client versions FireMUD intends to support. The research must cover:

- actual wire, negotiation, quoting, multiline, and reconnect behavior where applicable;
- target client and version coverage;
- complete plain-text fallback behavior;
- security and resource-exhaustion characteristics;
- package and version compatibility semantics;
- mapping from semantic `PlayerOutput` without moving gameplay authority into the adapter;
- implementation and long-term maintenance cost.

A follow-up architecture update must select one bounded adapter or explicitly select none. Selection alone is insufficient: the adapter remains disabled and unadvertised until end-to-end proof succeeds through the public Telnet path with the exact target clients and package/version pairs.

## Ownership If MCP Is Selected

If the research selects MCP, Game Session is the sole owner of the MCP greeting, negotiation state, semantic parser, version selection, package registry and advertisement, package activation, authentication-key correlation, cord lifecycle, multiline data-tag lifecycle and reassembly, FireMUD package schemas, and mapping from `PlayerOutput`.

TCP Proxy remains an opaque line-framing and transport-safety boundary. It may enforce the generic line-size limit and a per-connection rate limit for lines matching the reserved MCP marker, then forward allowed lines unchanged. It must not:

- emit an MCP greeting;
- decide that MCP negotiation succeeded or failed;
- parse or validate packages or message arguments;
- validate authentication keys;
- count or track cords or data tags;
- reassemble multiline values;
- maintain extension semantic state that duplicates Game Session.

Marker recognition for a generic rate budget is not MCP negotiation. Unknown-package, malformed-message, cord, and data-tag behavior are semantic concerns for Game Session only after MCP has been selected and implemented.

## Authentication and Attach Authority

An MCP `authentication-key`, if MCP is selected, is a per-connection framing correlation value only. It is not an account credential, bearer token, connect token, session identifier, entitlement, character-control grant, or attach authority.

All identity and gameplay authority continue through the canonical transport trust, `LOGIN`, `PLAY`, signed connect context where applicable, gameplay session binding, and owner-local enforcement contracts. A future extension-carried attach hint may be considered only as advisory input to Game Session and can never bypass or weaken those checks.

## Reconnection and Failure Fallback

Any selected extension negotiates fresh on every client transport connection. A reconnect has no inherited protocol version, authentication key, packages, cords, multiline tags, or other extension state. TCP Proxy, Gateway, Redis gameplay state, and Game Session recovery never reattach the new transport to the old extension state.

Clients remain fully playable when extension negotiation is absent, rejected, incompatible, rate-limited, or lost. After ordinary `LOGIN` and `PLAY`, Game Session may generate fresh current-state output for the new connection, but no edge component replays extension bytes or package-local state from the old connection.

## Compatibility and Operational Limits

FireMUD advertises only exact package/version pairs that have a stable documented contract and end-to-end evidence against the target client matrix. Advertising one proven pair does not promise blanket MCP 2.1 compatibility, every package, every client, or compatibility with experimental FireMUD packages.

Experimental package names, schemas, refresh behavior, and limits may change or disappear while unadvertised. Generic line-size and marker-rate limits, plus any later Game Session package-specific limits, are deployment safety controls rather than blanket compatibility guarantees. Ordinary gameplay must not depend on an extension line surviving an extension-specific safety budget.

## Implementation and Proof Gaps

No compliant classic-client extension is implemented. Current proxy marker/greeting recognition does not prove negotiation. Game Session lacks the selected adapter's negotiation, semantic parser, package/version registry, correlation handling, channel or multiline state, and `PlayerOutput` mapping. No current-client matrix or public-path end-to-end extension proof exists.

A selected adapter may be described as supported only after focused and end-to-end evidence covers exact client versions, negotiation success and failure, unsupported versions, malformed and unknown messages, correlation mismatch, reconnect, safety limits, plain-text fallback, package/version advertisement, and semantic parity with the deterministic text projection.

## Related Documentation

- [ADR 0145: Plain-Text Gameplay and Deferred Classic Client Extensions](./decisions/adr-0145-plain-text-gameplay-and-deferred-classic-client-extensions.md)
- [ADR 0131: Compact Versioned Player Output and Late Rendering](./decisions/adr-0131-compact-versioned-player-output-and-late-rendering.md)
- [Protocol Bridging](./system-architecture-protocol-bridging.md)
- [TCP Proxy Service](./microservices/tcp-proxy-service/README.md)
- [Game Session Protocols](./microservices/game-session-service/protocols.md)
