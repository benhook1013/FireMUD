# ADR 0145: Plain-Text Gameplay and Deferred Classic Client Extensions

## Status

Accepted

## Implementation Status

This decision is not implemented. Plain-text gameplay remains the baseline, while classic-client extension research, Game Session semantic ownership, and exact interoperability proof remain outstanding.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `MCP-01`
- Decision date: 2026-07-20
- Decision key: `MCP-01`
- Primary capability: `PO-2.3` client protocol negotiation and structured protocol extensions
- Affected capabilities: `PO-2.2`, `EA-1.2`, `PO-2.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of universal gameplay compatibility, first-party structured output, classic-client interoperability, protocol ownership, authentication semantics, operational limits, and support-proof requirements

## Context

FireMUD must remain playable through simple line-oriented clients while supporting a richer first-party WebSocket experience. Those two needs do not require one universal structured extension protocol. The platform already has a bounded `PlayerOutput` seam for first-party structured output, while classic MUD clients differ in their support and interpretation of MCP, GMCP, Telnet options, and client-specific packages.

Earlier documentation named MCP 2.1, `mcp-negotiate`, `mcp-cord`, and FireMUD-specific packages as supported. The implementation does not establish that claim. TCP Proxy currently recognizes MCP-looking markers and can emit a greeting when a disabled-by-default flag is enabled, but marker recognition and a greeting are not compliant negotiation, package interoperability, or end-to-end client proof. The documents also divided negotiation and semantic state between TCP Proxy and Game Session, creating contradictory ownership and the risk of two state machines disagreeing on packages, cords, multiline tags, or reconnect behavior.

MCP authentication keys add another ambiguity. They are cleartext protocol correlation values, not credentials. Treating one as identity, authorization, gameplay-session, or attach authority would bypass the canonical `LOGIN` and `PLAY` boundaries.

## Decision

### Plain Text Is the Universal Gameplay Contract

The canonical universal gameplay protocol remains line-oriented plain text. Every supported gameplay command and server outcome has a deterministic text representation that works without a structured extension. Authentication, admission, session control, reconnect, and gameplay correctness never depend on MCP, GMCP, a FireMUD package, or any other optional classic-client extension.

The first-party WebSocket surface uses the explicitly versioned structured `PlayerOutput` contract defined by [ADR 0135](./adr-0135-compact-versioned-player-output-and-late-rendering.md). That client-specific projection retains a deterministic plain-text projection but does not make its structured envelope the universal classic-client wire protocol and does not depend on MCP compatibility.

### Classic-Client Extension Support Is Deferred

Classic-client extension support is experimental, disabled, unadvertised, and unimplemented. The currently named MCP 2.1 handshake, `mcp-negotiate`, `mcp-cord`, and FireMUD-specific packages are research candidates rather than supported contracts.

No deployment may advertise MCP, GMCP, another classic-client extension, or a FireMUD package until current-client interoperability research selects one bounded adapter and that exact adapter has end-to-end proof. Documentation, configuration, marker recognition, a server greeting, parser unit tests, or forwarding opaque messages alone do not satisfy that gate.

The research must compare MCP, GMCP, and any other credible bounded alternative against concrete current clients. It must identify target client and version coverage, wire and negotiation behavior, failure and fallback behavior, security implications, package/version semantics, maintenance cost, and how semantic `PlayerOutput` data maps without making gameplay depend on the extension. A follow-up architecture update records the selected adapter or the decision to support none.

### One Semantic Owner If MCP Is Selected

If later research selects MCP, Game Session is the sole owner of:

- the server greeting and per-connection negotiation state;
- protocol-version and package-version selection;
- semantic parsing and validation;
- package advertisement and activation;
- authentication-key correlation;
- cord lifecycle and identifiers;
- multiline data-tag lifecycle and reassembly;
- FireMUD package schemas and mapping from `PlayerOutput`;
- extension failure, fallback, and reconnect behavior.

TCP Proxy remains an opaque transport and safety boundary. It may enforce generic line-size limits and an opaque marker-line rate limit before forwarding, but it does not emit an MCP greeting, decide whether negotiation succeeded, parse packages, validate authentication keys, count active cords or data tags, reassemble multiline values, or maintain a duplicate semantic state machine. Recognizing a reserved marker for bounded rate protection is not protocol negotiation or package support.

### Authentication Keys Are Correlation Only

An MCP authentication key, if MCP is selected, is only a per-connection framing correlation value used to associate MCP messages with the negotiated stream. It is never evidence of account identity, authorization, entitlement, gameplay-session ownership, character control, reconnect authority, or attach authority.

All identity and gameplay authority continue through the canonical transport trust, `LOGIN`, `PLAY`, connect-context, session-binding, and owner-enforcement contracts. Any future extension-carried attach hint is advisory input to Game Session and cannot bypass or weaken those checks.

### Negotiation Is Fresh and Gameplay-Optional

Extension negotiation and all package-local state are scoped to one client transport connection. Reconnect starts with no negotiated protocol version, packages, cords, tags, or correlation key from the prior connection. Neither TCP Proxy, Gateway, Game Session recovery, nor Redis gameplay state restores that extension state.

Clients must remain fully playable when an extension is absent, rejected, incompatible, rate-limited, or lost on reconnect. Fresh state output may be projected after ordinary `LOGIN` and `PLAY`, but prior extension bytes or semantic channel state are never replayed onto the new connection.

### Compatibility Claims Are Exact and Evidence-Gated

FireMUD advertises only explicitly proven stable package/version pairs. Advertising one pair is a compatibility promise only for that exact pair and its documented client matrix; it is not blanket compatibility with MCP 2.1, every MCP or GMCP client, all packages, or future FireMUD extensions.

Experimental FireMUD package names, schemas, refresh behavior, and operational limits may change or be removed while unadvertised. Generic edge limits and later package-specific safety limits protect deployments but do not create a blanket protocol-compatibility promise.

## Consequences

- A plain Telnet or generic text WebSocket client remains the compatibility baseline and never needs an extension to play.
- The first-party browser can evolve through a versioned `PlayerOutput` schema without forcing classic clients to adopt the same transport contract.
- FireMUD makes no current MCP 2.1, `mcp-negotiate`, `mcp-cord`, GMCP, or FireMUD-package support promise.
- Selecting a classic-client adapter later requires research and end-to-end interoperability proof rather than promoting current proxy recognition code into a contract.
- If MCP is selected, semantic ownership and failure handling are concentrated in Game Session; TCP Proxy stays stateless with respect to negotiation, packages, cords, and tags.
- Extension loss can reduce presentation richness but cannot break authentication, admission, reconnect, commands, or essential output.

## Alternatives Considered

### Treat Current MCP Marker and Greeting Handling as Supported MCP 2.1

This would turn a partial proxy heuristic into a compatibility promise without compliant negotiation, semantic package ownership, current-client testing, or end-to-end proof. It is rejected.

### Commit to MCP Before Comparing Current Clients

MCP is already named in the repository and has useful line-oriented properties, but current classic clients may provide better or broader interoperability through GMCP or another bounded mechanism. The protocol choice remains deferred until research measures the clients FireMUD intends to support.

### Put Negotiation and Resource State in TCP Proxy

The proxy is close to the Telnet transport and can cheaply recognize marker lines, but owning greetings, packages, cords, or tags there would split presentation semantics from Game Session and duplicate state across the bridge. Only generic line-size and opaque marker-rate protection remain at the proxy.

### Make Structured Extension Messages Canonical

This could simplify rich-client output at the cost of requiring classic clients and making gameplay depend on optional negotiation. Plain text remains universal, while first-party structured output uses its own versioned contract.

## Implementation and Proof Obligations

Current implementation evidence is insufficient for any classic-client extension support claim. TCP Proxy has disabled-by-default MCP-looking marker recognition, a greeting path, and focused tests. Game Session does not own or prove a compliant MCP or GMCP negotiation, semantic parser, package registry, correlation-key validation, cord lifecycle, data-tag reassembly, or stable FireMUD package mapping. There is no current-client compatibility matrix or end-to-end proof that a supported client negotiates an advertised package and receives semantically equivalent text and structured output.

The existing proxy marker/greeting seam is an implementation gap against the selected ownership boundary, not partial proof of compliant MCP negotiation. A later implementation must remove semantic negotiation from TCP Proxy, implement the selected adapter in Game Session, and keep the adapter disabled and unadvertised until proof is complete.

Proof for a selected adapter must cover the exact target client versions; successful, unsupported-version, malformed, unknown-package, timeout, and reconnect negotiation paths; package/version advertisement; correlation-key mismatch behavior without treating the key as authority; line and marker-rate limits; multiline and channel state if the selected protocol has them; fallback to complete plain-text gameplay; semantic parity with `PlayerOutput`; and end-to-end operation through the public Telnet path. Only package/version pairs covered by that evidence may be advertised as stable.

The versioned first-party `PlayerOutput` contract remains a separate proof obligation under [ADR 0135](./adr-0135-compact-versioned-player-output-and-late-rendering.md). It does not become proven merely because a classic-client adapter is selected, and classic-client interoperability does not become proven by the first-party WebSocket implementation.

## Reversibility and Revisit Triggers

Revisit the protocol choice when current-client research identifies a concrete target matrix and compares MCP, GMCP, and credible alternatives. Revisit package advertisement only after Game Session ownership and public-path end-to-end proof exist for exact stable package/version pairs. Revisit the plain-text universal baseline only through a separate consequential decision that addresses classic-client access, self-hosted compatibility, accessibility, failure fallback, and migration.

## Required Documentation Alignment

- `design/architecture/system-architecture-mud-client-protocol.md`
- `design/architecture/system-architecture-protocol-bridging.md`
- `design/architecture/microservices/tcp-proxy-service/README.md`
- `design/architecture/microservices/tcp-proxy-service/protocols.md`
