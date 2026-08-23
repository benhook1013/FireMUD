# TCP Proxy Service Protocols

## Normative Target Contract

The target Telnet flow is optional non-discovery `HELP`, then initial mandatory anonymous `WORLDS` discovery -> credential-bearing `LOGIN` -> authenticated `REALMS` -> conditional `JOIN` -> conditional `CHARS`/character creation -> `PLAY`. After `LOGIN`, `WORLDS` remains available as authenticated pre-tenant/account-scoped discovery and may provide caller-specific visibility context; [Authentication](../../system-architecture-authentication.md#login-and-session-flow) owns its route classification, visibility, and admission semantics. `HELP` never replaces `WORLDS`; `REALMS` and `CHARS` are never anonymous discovery. Plain text is the universal gameplay protocol. The proxy forwards player-facing selectors only; Game Session resolves the target and derives `playableStateScope` server-side from the exact realm catalog/pointer snapshot. No Telnet command or join request carries `playableStateScope`, storage keys, `tenantId`, `gameInstanceId`, or `PlayerExecutionContext` as caller authority. Optional classic-client semantic negotiation is deferred under [MCP-01](../../decisions/adr-0145-plain-text-gameplay-and-deferred-classic-client-extensions.md); TCP Proxy remains a generic bounded bridge and does not promise Telnet parity for or translate an independent semantic protocol.

## Implementation Status

The admission rules are owned by [Authentication](../../system-architecture-authentication.md#login-and-session-flow). Current proxy-routed admission is fail-closed: connect-token issuance and text `PLAY` proceed only when the fresh current Account response reports `membershipExists=true` and `gameplayAdmissionAllowed=true`. That response exposes `membershipExists`, `gameplayAdmissionAllowed`, `membershipVersion`, and `evaluatedAt`, but no lifecycle-state field: missing membership is represented by `membershipExists=false`; for an existing membership, the current response cannot distinguish lifecycle states, so the only available admission signal for an existing non-admitting case is `gameplayAdmissionAllowed=false`. Based on those available fields, only an otherwise eligible public-production request with `membershipExists=false` may return `JOIN_REQUIRED`; an existing response with `gameplayAdmissionAllowed=false` retains its established denial and is not classified as `INACTIVE`. Current text `PLAY` returns `WORLD_ACCESS_DENIED` for missing or non-admitting membership on a non-public target and enforces the applicable current realm grant. Connect-token issuance uses the same membership evidence but does not yet validate that non-public grant and retains its current Account-owned rejection mapping; it must not be described as lifecycle-aware or grant-complete. In target state, the reconnect/admission evidence is the authoritative fresh atomic Account membership snapshot, which carries `membershipLifecycleState`, `membershipAuthorityGeneration`, and independent `membershipVersion`; eligible missing or `INACTIVE` public-production membership returns `JOIN_REQUIRED`, and only an existing `ACTIVE` public-production member skips `JOIN`, while missing or non-`ACTIVE` membership for a non-public target returns `NON_PUBLIC_ENROLLMENT_REQUIRED`. In target state, every fresh admission requires applicable entitlement; public production requires a fresh positive entitlement result, while private/playtest additionally requires existing caller-bound `ACTIVE` membership plus the exact current Account-owned realm grant and does not consume the public-production enrollment predicate `allowPublicJoin`. The only last-known-good exception is unchanged public-production binding continuity during an entitlement-only outage after current membership, routing, public-policy, and other resume predicates pass. Reachable invalid or stale pointer evidence is `ADMISSION_POINTER_UNAVAILABLE`; an unreachable or timed-out Account or routing authority is `AUTH_UNAVAILABLE`. Target-only `NON_PUBLIC_ENROLLMENT_REQUIRED` and `REALM_ACCESS_DENIED` are not current behavior. The target flow below must not be read as proof that explicit join is complete across all clients.
The standalone `session_replaced` and `service_restart` preservation and bridge classification described in the target flow are target-only. Current close recognition, fallback, and shutdown classification remain the handler behavior recorded in [Operations Implementation Status](./operations.md#implementation-status).
The Telnet credential form is `LOGIN <email> [secret]`. `LOGIN <email>` starts the email challenge, while `LOGIN <email> <secret>` supplies the secret immediately.
The current text `PLAY` path checks the fresh Account response's `membershipExists` and `gameplayAdmissionAllowed` fields plus the realm grant where applicable; the current connect-token path uses the same membership fields but the private/playtest grant check remains an implementation and proof gap. These local gaps do not change the Authentication-owned admission outcomes.
The discovery-snapshot shortcut is restricted to the first-party token-backed WebSocket path. Direct text/Telnet clients currently use the abbreviated fail-closed `LOGIN` -> `PLAY` -> `LOOK` path for otherwise admitting existing members; missing eligible public-production membership returns non-actionable `JOIN_REQUIRED`, and missing or non-admitting private/playtest membership returns `WORLD_ACCESS_DENIED`. The target `WORLDS` -> `LOGIN` -> `REALMS` -> conditional `JOIN` -> conditional `CHARS`/character creation -> `PLAY` flow is documented separately and is not current behavior. The current proxy still bootstraps hidden default routing and does not enforce `WORLDS` as a prerequisite.

## Cross-Path Connectivity Contract

The following are canonical contracts across Telnet and WebSocket paths:

- Target Telnet discovery-first without typed attach hints follows optional non-discovery `HELP`, mandatory fresh `WORLDS` discovery -> credential-bearing `LOGIN` -> authenticated `REALMS` -> the Authentication-owned conditional `JOIN` when required -> conditional `CHARS`/character creation when no valid selected character exists -> `PLAY`. In the current runtime, explicit `JOIN` is unavailable, hidden default routing is bootstrapped, and missing public-production membership does not create membership. Direct text does not use a first-party WebSocket discovery snapshot.
- Proxy -> Gateway WebSocket hop is mTLS-authenticated in player-facing environments under [Security](../../system-architecture-security.md#tls-termination--internal-encryption).
- Proxy bridge-availability circuit breaker uses deterministic open/half-open/closed admission behavior during sustained upstream unreachability.

## Recommended Telnet Client Flows

These flows describe how Telnet traffic is forwarded into the shared login/session pipeline. `LOGIN` / `LOGON` / `JOIN` / `PLAY` semantics are canonical in [Authentication](../../system-architecture-authentication.md#login-and-session-flow), while multi-client continuity is canonical in [Session Behavior](../../system-architecture-session-behavior.md#multi-client-behavior-and-session-takeover). The target flow requires `WORLDS` before `LOGIN`; optional `HELP` is non-discovery and does not replace it. The target flow includes explicit `JOIN`; current Telnet clients cannot perform `JOIN` and fail closed with non-actionable `JOIN_REQUIRED` only when an otherwise eligible public-production response reports `membershipExists=false`. An existing response with `gameplayAdmissionAllowed=false` retains its established denial. In target behavior, an `INACTIVE` existing public-production membership also follows `JOIN_REQUIRED`; only an existing `ACTIVE` member skips `JOIN`. The missing connect-token membership-authority-generation reread is a separate gap.

- **Target canonical player flow**
  - Connect to the TCP Proxy Service.
  - Perform fresh public-world discovery with `WORLDS`. This direct text flow does not consume a first-party WebSocket discovery snapshot.
  - Use `LOGIN <email> [secret]`: `LOGIN <email>` starts the email challenge, while `LOGIN <email> <secret>` authenticates immediately. Complete the applicable secret or verified-email-code response before continuing, then perform authenticated `REALMS` discovery as needed.
  - For a public-production target, follow [Authentication](../../system-architecture-authentication.md#direct-text-realms-to-join-scope-normative) for the target-only conditional `JOIN`; current text reconnect stops with non-actionable `JOIN_REQUIRED` when the current response reports `membershipExists=false` for an eligible public-production request and performs no membership mutation. Target behavior also returns `JOIN_REQUIRED` for `INACTIVE` membership; only an existing `ACTIVE` public-production member skips `JOIN`. Private/playtest targets do not use public `JOIN`; current missing or non-admitting membership returns `WORLD_ACCESS_DENIED`, while target admission requires existing `ACTIVE` membership plus the exact current realm grant rather than `allowPublicJoin`.
  - If no valid selected character exists after the conditional membership step, use `CHARS <world> [realm]` or the character-creation flow to select or create it; then enter gameplay with `PLAY <world> [realm] [character]`.
  - The Telnet wire never carries `connectScopeId`, `PlayerExecutionContext`, `playableStateScope`, or storage keys. Game Session retains any target-bound scope locally and derives `playableStateScope` for the character query from the exact server-side realm snapshot; Account separately validates the authenticated caller context, and the proxy only forwards the player-facing selectors and commands.
  - Send gameplay commands (`LOOK`, `SAY`, movement, and so on) as normal.
  - After Telnet-control filtering, the proxy forwards each resulting sanitized gameplay line unchanged to Spring Cloud Gateway; unsupported controls and subnegotiation never leak into the gameplay line. The Game Session Service creates or binds the gameplay session exactly as it does for native WebSocket clients.
  - **Future smart-client flow**
    - No typed `SESSION` gameplay line or proxy-owned semantic extension is part of the current contract. If a later adapter is selected, Game Session owns its semantics and any attach hint remains advisory input that cannot replace the normal human-facing `WORLDS` -> `LOGIN` -> `REALMS` -> Authentication-owned conditional `JOIN` -> conditional `CHARS`/character creation -> `PLAY` flow.

## Advanced Multi-Connection Scenarios

Advanced Telnet tools may open more than one window or pane for the same account. The proxy forwards traffic for every TCP connection independently; visible takeover behavior follows [Session Behavior](../../system-architecture-session-behavior.md#multi-client-behavior-and-session-takeover).

- **Two Telnet windows**
  - Window A connects, performs fresh `WORLDS` discovery, issues `LOGIN`, performs authenticated `REALMS` discovery, follows the target-only conditional `JOIN` step if it is first public-production entry, conditionally completes `CHARS`/character creation when no valid selected character exists, and enters gameplay with `PLAY`.
  - Window B connects and repeats `WORLDS`, `LOGIN`, and authenticated `REALMS` discovery for the same character, follows `JOIN` only if required by the Authentication flow, conditionally completes `CHARS`/character creation when no valid selected character exists, then issues `PLAY`. Game Session applies the canonical takeover and the old window is disconnected.
  - If Window A reconnects and logs in again, it in turn takes over from Window B. There is no concurrent split control even though the proxy forwards traffic from both TCP connections.

## Data Flow

- TCP connections are accepted on a dedicated port and proxied to Spring Cloud Gateway using a lightweight WebSocket bridge.
- For each Telnet socket, the proxy bootstraps the hidden default gameplay instance and tenant metadata needed to open the bridge and includes that context in the authenticated Proxy -> Gateway handshake headers.
- Incoming bytes are queued and forwarded to the gateway in order.
- If the proxy cannot establish the WebSocket bridge because gameplay upstream is unavailable, it fail-closes the Telnet socket with `backend_unavailable`.
- User-facing failure messaging may use a clear bounded message such as `Gateway link unavailable; please reconnect.` so clients are not left with a silent close.
- If bridge establishment fails because trust or policy checks fail, the proxy fail-closes with `policy_violation`, not `backend_unavailable`.
- Example: the proxy attempts the authenticated bridge handshake with its hidden bootstrap context and Gateway rejects it due to certificate validation or policy deny. Because gameplay admission never succeeded, the Telnet connection closes as `policy_violation`, not `backend_unavailable`.
- If the WebSocket bridge drops after the Telnet connection is established, the proxy closes the Telnet socket immediately according to the established-session bridge state machine; it does not keep the client TCP socket open while attempting a hidden gameplay-bridge reattach. The resulting new Telnet connection follows fresh discovery and admission. A fresh gameplay binding requires fresh entitlement and the applicable membership/grant predicates; an entitlement-only outage on this path remains `ENTITLEMENT_UNAVAILABLE`, and a new Telnet transport does not receive the last-known-good exception. Only unchanged public-production binding continuity during an entitlement-only outage, while the Gateway/Game Session edge remains established and all other resume predicates pass, may use bounded last-known-good entitlement.
- During sustained Gateway gameplay unreachability, proxy admission uses a bridge-availability circuit-breaker model so new Telnet sockets are rejected quickly with `backend_unavailable`. Admission resumes only after `TCP_PROXY_GATEWAY_CIRCUIT_RECOVERY_SUCCESS_COUNT` consecutive successful probe bridge establishments.
- If upstream backpressure causes the Telnet -> Gateway buffered-line ceiling to be exceeded while upstream is still reachable, the proxy closes the Telnet connection with `policy_violation`, emits `edge_backpressure` context in structured logs and metrics, and increments `tcpproxy.telnet.discarded{reason="gateway_buffer_full"}`. If upstream is already unreachable, `backend_unavailable` takes precedence.
- Target-only lifecycle behavior preserves every valid authenticated Gateway top-level close as the same Telnet token: `logout`, `session_replaced`, `service_restart`, `idle_timeout`, `policy_violation`, `internal_error`, or `backend_unavailable`. For target bridge-only operational attribution, clean authenticated `1012/service_restart` uses `bridge_shutdown_class=planned_drain`; every other valid authenticated top-level close uses `valid_upstream_close`; and only absent, invalid, or otherwise unattributable close metadata uses `unattributed_failure`. Only absent or invalid top-level close metadata maps to observation-specific `backend_unavailable`. Optional subreasons never redefine the lifecycle class. Current handler mapping remains the [Operations Implementation Status](./operations.md#implementation-status).
- After Telnet-control filtering, every resulting sanitized gameplay command, including `LOGIN`, `JOIN` when required, and `PLAY`, is forwarded unchanged over the WebSocket bridge so Spring Cloud Gateway and Game Session see the same gameplay line as native WebSocket clients. Admission semantics remain owned by [Authentication](../../system-architecture-authentication.md#login-and-session-flow).

## Bridge State Machine (Established Telnet Sessions)

For already-established Telnet sessions, the proxy uses an explicit per-connection bridge state machine:

- `healthy` – upstream bridge established and forwarding.
- `close_due_to_valid_upstream` – target behavior is to preserve the same Telnet token when the authenticated bridge supplies any valid Gateway top-level close. Clean authenticated `1012/service_restart` uses `planned_drain`; every other valid authenticated top-level close uses `valid_upstream_close`. Standalone `session_replaced` and `service_restart` preservation/classification remain target-only; current handler mapping is recorded in [Operations Implementation Status](./operations.md#implementation-status).
- `close_due_to_unattributed_failure` – if the established upstream bridge terminates with absent or invalid top-level close metadata, close immediately with `backend_unavailable` and record `bridge_shutdown_class=unattributed_failure`.
- `close_due_to_edge_backpressure` – if queued lines exceed `TCP_PROXY_GATEWAY_MAX_BUFFERED_LINES` while upstream is reachable, close with `policy_violation` and record `edge_backpressure` context.

This state machine is distinct from the proxy-wide open/half-open/closed admission breaker and defines deterministic behavior for active Telnet sockets during upstream loss. Hidden bridge reattachment behind an already-open client TCP socket is not part of the design; after bridge loss, the client opens a fresh socket and follows fresh discovery/admission. A new binding is strict fresh-entitlement admission, while an exact same-binding reconnect on that new transport remains strict and returns `ENTITLEMENT_UNAVAILABLE` when fresh entitlement is unavailable; only an unchanged public-production binding continued on the established edge can use that narrow exception.

Actual client-visible edge loss requires a fresh TCP transport and the complete owner-defined [fresh direct-text discovery and admission flow](../../system-architecture-authentication.md#login-and-session-flow); no Telnet input, prior output bytes, WebSocket frames, or optional semantic-extension state is replayed. A close token reports lifecycle only and never proves whether an in-flight command committed. The current-only `GetGameplayCommandStatus` lookup uses `{tenantId, gameInstanceId, commandId}`; this is a local transport/client consequence, not the complete target identity. Target reconciliation and replay use the complete canonical [Command-Handoff Identity](../../system-architecture-scripting-normative-contract-tables.md#command-handoff-identity-target-state) from its owning contract, which this TCP Proxy document does not duplicate.

## Telnet Disconnect Line Format

When the proxy can write a final player-visible disconnect line before closing the socket, it uses this exact line format:

```text
DISCONNECT <reason-token> <human-message>\n
```

`<reason-token>` is one non-whitespace token from the unified disconnect taxonomy (`logout`, `session_replaced`, `service_restart`, `idle_timeout`, `policy_violation`, `internal_error`, or `backend_unavailable`). When bounded subreason context is available, it may be appended to the reason token as `;subreason=<value>`, for example `policy_violation;subreason=edge_backpressure`; subreason is optional and never lifecycle authority. `<human-message>` is advisory display text for people and must not be parsed for retry policy.

Examples:

```text
DISCONNECT backend_unavailable Gateway link dropped; please reconnect\n
DISCONNECT session_replaced This connection was replaced by another controller\n
DISCONNECT service_restart Gateway maintenance; please reconnect\n
DISCONNECT policy_violation;subreason=edge_backpressure Gameplay connection closed due to policy violation\n
```

The proxy writes this line best-effort and then closes the TCP connection. If the transport drops before the line is received, clients must treat the event as abnormal transport loss and use the reconnection policy in [Reconnection Strategy](../../system-architecture-reconnection.md).

## Transport Metadata and Semantic Extension Boundary

Typed `SESSION` gameplay lines are no longer part of the Telnet contract. TCP Proxy may carry server-owned bridge metadata needed to establish the trusted Proxy -> Gateway hop, but it does not define or interpret an independent semantic gameplay protocol. Plain text remains universal. If a future classic-client adapter is selected, Game Session owns its greeting, negotiation, package semantics, correlation, mapping, and per-connection state as defined by [MCP-01](../../decisions/adr-0145-plain-text-gameplay-and-deferred-classic-client-extensions.md); TCP Proxy remains an opaque bounded bridge.

Current rules:

- current Telnet runtime uses hidden default routing and the abbreviated `LOGIN` -> `PLAY` -> `LOOK` path for existing members. It fails closed with non-actionable `JOIN_REQUIRED` when an otherwise eligible public-production response reports `membershipExists=false`; the current adapter cannot distinguish `INACTIVE` from another existing non-admitting result, and an existing `gameplayAdmissionAllowed=false` response retains its established denial. Target behavior requires `JOIN_REQUIRED` for `INACTIVE` and skips `JOIN` only for an existing `ACTIVE` public-production member. Current private/playtest text `PLAY` maps missing or non-admitting membership to `WORLD_ACCESS_DENIED` and checks the current realm grant; the full `WORLDS` -> `LOGIN` -> `REALMS` -> conditional `JOIN` -> conditional `CHARS`/character creation -> `PLAY` flow remains target-only;
- the proxy bootstraps hidden default gameplay instance and tenant metadata for the connection and, when configured coherently, forwards the server-owned `X-World-Slug` / `X-Realm-Slug` / positive `X-Pointer-Version` routing bundle only across the authenticated Proxy -> Gateway hop; Gateway validates the all-or-none bundle and forwards it as advisory Game Session context without replacing direct `LOGIN` / `PLAY` admission;
- current proxy behavior forwards `WORLDS` but also bootstraps hidden default routing and does not enforce the discovery command, so the fresh-discovery requirement remains a target gap;
- typed attach hints and semantic extension negotiation do not exist on the player-facing wire contract;
- any future adapter-carried attach hint must remain advisory and must not bypass either the current abbreviated `LOGIN` -> `PLAY` -> `LOOK` fail-closed path or the target `WORLDS` -> `LOGIN` -> `REALMS` -> conditional `JOIN` -> conditional `CHARS`/character creation -> `PLAY` flow; only existing `ACTIVE` public-production members skip target `JOIN`.
- PROXY protocol trust follows [Security](../../system-architecture-security.md#telnet-command-handling-and-controls): on the internal-only PROXY listener, the edge-to-TCP Proxy channel must be authenticated and cryptographically protected before the recovered address can drive per-IP connection caps, rate limits, abuse controls, or admission decisions. Malformed or truncated PROXY headers remain a hard failure, and the canonical discard signal is `tcpproxy.telnet.discarded{reason="proxy_protocol"}`.
- PROXY parsing must never be enabled on the public Telnet listener. Accepting PROXY headers directly from the Internet would allow client-IP spoofing.
- Public Telnet must use either edge TLS termination with the authenticated internal PROXY listener or direct TCP Proxy TLS without a PROXY header; the two modes are mutually exclusive. Both modes continue through the same Proxy -> Gateway WebSocket bridge. Current Telnet runtime does not implement the target full sequence because explicit `JOIN` is unavailable, hidden default routing is bootstrapped instead, and otherwise admitting existing members may use the abbreviated current `LOGIN` -> `PLAY` -> `LOOK` path; the target sequence is owned by [Authentication](../../system-architecture-authentication.md#login-and-session-flow).

Metrics give observability into each Telnet connection while keeping Prometheus label cardinality bounded:

- `tcpproxy.connection.events{type="connect"|"disconnect"}`
- `tcpproxy.connection.duration`
- `grpc_app_error_total{service="tcp-proxy-service",code="<code>"}` for the supplementary caller-side normalization count
- `grpc_app_error_total{service="game-session-service",code="<code>"}` for the canonical producer application-error count

Detailed identifiers such as `gameInstanceId` and client IP are captured in structured logs and tracing context, not Prometheus label values.

`tenantId` is intentionally omitted from canonical proxy metrics. Even in small deployments, tenant-specific diagnosis should come from logs, traces, and control-plane reads or from an explicitly separate non-canonical diagnostic export; operators should not mutate the shared Prometheus metric contract by adding raw `tenantId` labels to the standard proxy meters.

## Telnet Command Handling

The proxy sanitizes incoming bytes and allows only a safe subset of Telnet protocol commands. Optional classic-client semantic extensions are deferred and, if selected later, must use a line-oriented or otherwise sanitization-compatible opaque representation through this bounded bridge under Game Session ownership; the proxy does not negotiate or parse them. Unsupported Telnet options and subnegotiation blocks remain consumed/discarded. A separately accepted transport change could explicitly permit selected frames through under generic limits without TCP Proxy semantic parsing, but no such exception is part of the current contract.

After Telnet-control filtering, each resulting sanitized gameplay line is forwarded unchanged over the TCP Proxy → Gateway WebSocket bridge; future semantic-extension data remains opaque and unparsed only when its representation survives that filtering, and generic limits still produce an explicit close rather than a keep-open discard.

Allowed Telnet commands:

| Command / Option | Byte | Purpose |
| --- | --- | --- |
| `SE` | `240` | Terminates subnegotiation blocks. |
| `NOP` | `241` | Ignored; kept for compatibility. |
| `GA` | `249` | May be sent by some legacy clients; ignored by the server. |
| `WILL` | `251` | Negotiation: client proposes enabling an option. |
| `WONT` | `252` | Negotiation: client refuses to enable an option. |
| `DO` | `253` | Negotiation: client requests that the server enable an option. |
| `DONT` | `254` | Negotiation: client requests that the server disable an option. |

Options outside this subset are silently discarded by the sanitization layer. For Telnet subnegotiation (`IAC SB ... IAC SE`), the proxy consumes the entire subnegotiation block up to the matching `SE` and does not surface any of its bytes as gameplay text when the option is unsupported.
Malformed or truncated subnegotiations may increment diagnostic counters, but they must not leak partial control bytes into the line-oriented gameplay stream. A future adapter cannot rely on unsupported option or subnegotiation carriage unless a separately accepted transport change explicitly permits selected frames through under generic limits without TCP Proxy semantic parsing.

Hard abuse signals include:

- line-length floods or repeated lines exceeding `TCP_PROXY_MAX_LINE_BYTES`; and
- excessive connection churn from the same IP that collides with global connection-limit policy.

Diagnostic-only signals include unknown or malformed opaque extension/control lines.

### Compatibility Notes

- Classic MUD clients that rely only on standard text I/O and basic Telnet negotiation are expected to work without special configuration.
- Clients that require Telnet options outside this explicit basic subset are unsupported and have no compatibility guarantee; advanced options are not best-effort features.
- Classic clients should rely on plain-text gameplay. No MCP, GMCP, or FireMUD package/version is currently advertised or supported; a future adapter must be explicitly selected, owned by Game Session, and proven against an exact client matrix before advertisement.

## Generic Transport Limits and Deferred Semantics

The TCP Proxy Service currently applies only the generic `TCP_PROXY_MAX_LINE_BYTES`, buffered-line, connection, idle, and per-connection/per-IP abuse limits to all transported input. It has no implemented marker-rate guard, MCP budget enforcement, or marker-specific metric; the `TCP_PROXY_MCP_*` configuration names and the disabled-by-default MCP greeting seam are implementation drift, not an active protocol or compatibility contract. Target startup rejects `TCP_PROXY_MCP_ENABLED=true` in shared, player-facing, and production-like profiles and permits the legacy greeting seam only in explicit local/development/test profiles. Current startup does not yet enforce that profile restriction, so deployments must keep the flag false outside those explicit development profiles and any contrary emission is unsupported drift. A future generic opaque-marker rate guard may be added only with explicit configuration, metric, and focused proof, and would remain a transport safety control rather than semantic negotiation. Gameplay text lines are never silently discarded while the connection remains open; a hard transport limit closes with the canonical policy or backend outcome. A later semantic adapter must define any package-local limits in Game Session and preserve plain-text fallback.
