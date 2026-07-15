# Player Access and Session

## Current Status

The current player-access and session boundary is implemented across Account, Common Security, TCP Proxy, Gateway, Game Session, and the gameplay runtime services. The live model is account-first bootstrap followed by explicitly authorized gameplay admission, shared Telnet/WebSocket text handling, deliberate lifecycle separation between fresh entry, resume, takeover, and logout, and bounded continuity through the Gateway bridge. Focused automated proofs exist for the principal paths; manual end-to-end QA and a small number of broader continuity/validation follow-ups remain.

## Implementation Record Index

Use this index to locate the current domain capability. The detailed evidence preserves every allocated legacy source line and is intentionally kept in the same document for comparison.

| Capability and ownership focus | Source-declared status | Source range | Evidence |
| --- | --- | --- | --- |
| [Telnet to Gameplay Vertical Slice Task List](../vertical-slices/01-task-list-telnet-to-gameplay-vertical-slice.md) - Telnet-to-gameplay session pipeline | parts of this slice are implemented and under active refinement; where behavior is not yet live, this document still describes the target-state flow, with imple | 1-99 | [source evidence](#source-01-task-list-telnet-to-gameplay-vertical-slice-1-99) |
| [Login and Session Vertical Slice Task List](../vertical-slices/02-task-list-login-and-session-vertical-slice.md) - Login and session lifecycle | core flows and several tests are implemented; this document captures the target-state behaviour, while individual task checkboxes and design docs indicate what | 1-69 | [source evidence](#source-02-task-list-login-and-session-vertical-slice-1-69) |
| [Login and Session Hardening Vertical Slice Task List](../vertical-slices/02.1-task-list-login-session-hardening-vertical-slice.md) - Canonical login/session runtime hardening | completed for this PR. The core login/session runtime is now covered by real integration and ingress tests, the stale shortcut smoke path has been removed, the | 1-59 | [source evidence](#source-02-1-task-list-login-session-hardening-vertical-slice-1-59) |
| [Email OTP and Text-Client Auth Options Vertical Slice](../vertical-slices/02.1.1-task-list-email-otp-and-text-auth-options-vertical-slice.md) - Account and text-client authentication | Account Service challenge lifecycle, password/email-OTP policy, and account-settings mode selection, plus Game Session `LOGIN <email>` challenge initiation, are | 1-251 | [source evidence](#source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251) |
| [Logout and Session Termination Vertical Slice](../vertical-slices/02.1.2-task-list-logout-and-session-termination-vertical-slice.md) - Deliberate logout and session termination | complete at the current bounded boundary; broader reconnect-proof depth and richer client UX remain future work | 1-185 | [source evidence](#source-02-1-2-task-list-logout-and-session-termination-vertical-slice-1-185) |
| [Global Account and Tenant Authorization Convergence Vertical Slice](../vertical-slices/02.1.6-task-list-global-account-and-tenant-authorization-convergence-vertical-slice.md) - Global account and tenant authorization | complete at the current boundary | 1-81 | [source evidence](#source-02-1-6-task-list-global-account-and-tenant-authorization-convergence-vertical-slice-1-81) |
| [Auth, Session, and Routing Guardrail Follow-Through Vertical Slice Task List](../vertical-slices/02.1.7-task-list-auth-session-and-routing-guardrail-follow-through-vertical-slice.md) - Auth and session guardrail follow-through | implementation-complete at the current bounded seam | 1-807 | [source evidence](#source-02-1-7-task-list-auth-session-and-routing-guardrail-follow-through-vertical-slice-1-807) |
| [02.1.7.1 Task List: Auth Entry Negative-Path Parity Vertical Slice](../vertical-slices/02.1.7.1-task-list-auth-entry-negative-path-parity-vertical-slice.md) - Auth entry negative-path parity | complete | 1-96 | [source evidence](#source-02-1-7-1-task-list-auth-entry-negative-path-parity-vertical-slice-1-96) |
| [02.1.7.2 Task List: Malformed JWT and Claim-Shape Parity Vertical Slice](../vertical-slices/02.1.7.2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice.md) - Malformed token and claim parity | complete | 1-105 | [source evidence](#source-02-1-7-2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice-1-105) |
| [02.1.7.3 Task List: Positive Identity and Routing-Bundle Guardrails Vertical Slice](../vertical-slices/02.1.7.3-task-list-positive-identity-and-routing-bundle-guardrails-vertical-slice.md) - Positive identity and routing guardrails | complete | 1-87 | [source evidence](#source-02-1-7-3-task-list-positive-identity-and-routing-bundle-guardrails-vertical-slice-1-87) |
| [Session Start Admission Ordering and IP-Limit Safety Vertical Slice](../vertical-slices/02.2.1-task-list-session-start-admission-ordering-and-ip-limit-safety-vertical-slice.md) - Session-start admission ordering | complete | 1-64 | [source evidence](#source-02-2-1-task-list-session-start-admission-ordering-and-ip-limit-safety-vertical-slice-1-64) |
| [Reconnect and Session Recovery Semantics Vertical Slice Task List](../vertical-slices/02.3-task-list-reconnect-and-session-recovery-vertical-slice.md) - Reconnect and session recovery | baseline live; gameplay identity keying, same-session resume preservation, runtime membership and entitlement checks in `PLAY`, canonical reconnect docs, WebSoc | 1-161 | [source evidence](#source-02-3-task-list-reconnect-and-session-recovery-vertical-slice-1-161) |
| [First-Party Reconnect Parity Vertical Slice Task List](../vertical-slices/02.4-task-list-first-party-reconnect-parity-vertical-slice.md) - First-party reconnect authority | baseline live; manual QA still pending | 1-55 | [source evidence](#source-02-4-task-list-first-party-reconnect-parity-vertical-slice-1-55) |
| [Non-Edge Failover Invisibility Vertical Slice Task List](../vertical-slices/02.5-task-list-non-edge-failover-invisibility-vertical-slice.md) - Shared reconnect continuity state | baseline live; manual QA remains | 1-45 | [source evidence](#source-02-5-task-list-non-edge-failover-invisibility-vertical-slice-1-45) |
| [Live Backend Rebind Invisibility Vertical Slice Task List](../vertical-slices/02.6-task-list-live-backend-rebind-invisibility-vertical-slice.md) - Live backend rebind continuity | baseline live for the Gateway-owned gameplay WebSocket bridge, stable edge transport session ids, and focused upstream rebind proof; the focused Game Session pr | 1-38 | [source evidence](#source-02-6-task-list-live-backend-rebind-invisibility-vertical-slice-1-38) |
| [Game Session Process Restart Invisibility Vertical Slice Task List](../vertical-slices/02.7-task-list-process-restart-invisibility-vertical-slice.md) - Game Session restart continuity | baseline live for the focused same-JVM process-bounce proof; Game Logic continuity continues in `02.8-task-list-game-logic-restart-invisibility-vertical-slice.m | 1-33 | [source evidence](#source-02-7-task-list-process-restart-invisibility-vertical-slice-1-33) |
| [Game Logic Restart Invisibility Vertical Slice Task List](../vertical-slices/02.8-task-list-game-logic-restart-invisibility-vertical-slice.md) - Game Logic restart continuity | baseline live for the focused post-restart command proof; manual QA remains | 1-34 | [source evidence](#source-02-8-task-list-game-logic-restart-invisibility-vertical-slice-1-34) |
| [Public-Production Admission and Membership Creation Vertical Slice](../vertical-slices/09.2-task-list-public-production-admission-and-membership-creation-vertical-slice.md) - Audited primary runtime or service owner | complete at the current bounded boundary | 1-68 | [source evidence](#source-09-2-task-list-public-production-admission-and-membership-creation-vertical-slice-1-68) |

## Canonical Design Sources

- [Authentication and authorization](../../architecture/system-architecture-authentication.md) defines global account identity, tenant membership and authorization, access grants, credentials, and gameplay admission.
- [Reconnection](../../architecture/system-architecture-reconnection.md) defines reconnect eligibility, transcript replay, takeover, and the bounded continuity envelope.
- [Game Session protocols](../../architecture/microservices/game-session-service/protocols.md) defines the login, play, session, and control-plane protocol boundaries.
- [Redis architecture](../../architecture/system-architecture-redis.md) defines session keys and gameplay binding persistence.
- [Account Service](../../architecture/microservices/account-service/README.md) owns account identity, credential policy, membership, and non-public realm grants.
- [Game Session Service](../../architecture/microservices/game-session-service/README.md) owns text-command lifecycle, session context, gameplay binding, and reconnect redraw orchestration.
- [TCP Proxy protocols](../../architecture/microservices/tcp-proxy-service/protocols.md) and [Spring Cloud Gateway client behavior](../../architecture/microservices/spring-cloud-gateway/client-behavior.md) define transport handoff and client-visible retry behavior.
- [TCP Proxy Service](../../architecture/microservices/tcp-proxy-service/README.md) and [Spring Cloud Gateway](../../architecture/microservices/spring-cloud-gateway/README.md) own trusted transport ingress and the stable edge bridge.

## Consolidated Implementation Record

### Account, Tenant, and Realm Authority

Platform accounts are global identities. Tenant membership, tenant-scoped authorization, purchased/runtime entitlements, and non-public realm grants are distinct Account-owned authorities. The canonical tenant roles are `player`, `designer`, `tenantAdmin`, and `moderator`; global elevated roles such as `god`/platform admin are handled separately. Runtime consumers reuse Account and Common Security authority rather than deriving local equivalents.

`POST /auth/player-bootstrap` authenticates the account before requiring gameplay membership. Discovery, connect-token issuance, and `PLAY` then evaluate the selected tenant/realm, current admission pointer, entitlement availability, membership or explicit grant, and runtime target. Only the visible default public production realm uses public admission. Non-production or non-public realms require explicit Account-owned grant state and do not inherit public-production admission.

`EnsurePublicProductionPlayerMembership(accountId, tenantId, worldSlug, realmSlug, requestId)` is the sole first-join membership writer for the current public-production path. It is idempotent and race-safe, treats `requestId` as the replay key, makes successful membership immediately visible to runtime reads, emits durable audit logging, and returns `requestId` plus `replayed` so retries are distinguishable. Failed attempts do not commit. The same boundary is used by first-party connect-token issuance and text-client `PLAY`; it is not a hidden bootstrap-only side effect.

Account export, deletion, recovery, password reset, username reminder, email verification, and profile operations are account-scoped. `ExportTenantData` is the deliberately narrower tenant-scoped export route and does not redefine account export or deletion. Account deletion observes active subscription/billing preconditions without collapsing global account ownership into tenant ownership.

### Text Protocol and Credential Policy

Telnet and generic WebSocket clients share one Game Session line-oriented parser/interpreter and gameplay queue. The parser trims input, accepts case-insensitive command names, handles empty or malformed lines, and maps unknown commands to `UNKNOWN`; Telnet performs no separate gameplay parsing. The current canonical flow is optional `WORLDS`, `LOGIN`, `PLAY`, and gameplay commands such as `LOOK` and `SAY`, with built-in `LOGOUT`. `LOGON` remains an accepted login spelling where the protocol documents it. The old typed `SESSION` attach flow is not part of the canonical initial-development protocol; future smart-client metadata, if revived, is transport metadata rather than a player command and is never authoritative.

TCP Proxy bootstrap metadata is proxy-owned hidden transport context, not a player-visible attach command. `TelnetSessionContext` and `TelnetServerHandler` validate the envelope before forwarding it, record connect events with session/tenant/client identity, reconnect with bounded backoff, preserve ordered buffered input, and close once `MAX_BUFFER_DEPTH` is exhausted while metering discarded input. Telnet logging redacts `LOGIN` arguments. `NotifyDisconnect` validates proxy/session identity, maps a proxy connection to the authenticated session where possible, records suspension for unexpected loss, and returns application-level `ErrorDetail` results while metering duplicate, missing-context, transport-failure, and application-error classes.

`LOGIN` is available in parameterized text form as `LOGIN <email> <secret>` and in challenge-initiation form as `LOGIN <email>`. The supplied secret is one opaque value forwarded to Account Service. Account Service interprets it from the account's enabled modes rather than guessing from its shape: live policy tries a valid active email code before password fallback when both are enabled, and rejects modes that do not permit email OTP. Successful Account authentication is required before Game Session creates authenticated session state or accepts gameplay commands. Failures map to canonical `ErrorDetail` and text `ERROR <CODE> <message>` responses, including invalid credentials, locked accounts, upstream unavailability, and unauthenticated gameplay.

The live account mode set is `PASSWORD` and `EMAIL_OTP`, exposed through global account-settings reads and replacements. New accounts default to `PASSWORD,EMAIL_OTP`; the requested set must be nonempty and supported, and enabling `EMAIL_OTP` requires a verified email. Email challenges are account-specific, short-lived, hashed, single-use, invalidated when replaced, resend-rate-limited, and bounded for failed attempts. Generic login challenge responses remain neutral for account-enumeration resistance. Signup-time mode selection, authenticator-app TOTP enrollment, and any dormant TOTP credential/field are not live. Secrets and OTP values are redacted from transport logs. Prompt/help text describes the optional-secret grammar without making text clients a separate authentication system.

The maintained integration path uses the real Account/Redis/GameInstance/downstream-service topology. Historical optimistic-success, dependency-light, proxy-only echo, GHCR placeholder, and fake-session shortcuts are not part of normal smoke or integration workflows; any remaining developer stub must be explicit and narrowly scoped.

### Admission and Gameplay Binding

Trusted proxy, first-party, bootstrap, and connect-token inputs fail closed before they can establish or preserve routing state. Signed actor identity requires positive, matching `sub` and `accountId` claims. Gameplay routing requires a complete positive bundle of `tenantId`, `gameInstanceId`, `worldSlug`, `realmSlug`, and `pointerVersion`; blank slugs, partial bundles, stale pointers, mismatched world/realm targets, and malformed claims are rejected before routing reuse or persistence.

Game Session owns the canonical admitted-runtime normalization through `GameplayAdmissionPointerSnapshots`. `LoginCommandHandler` and `SessionRoutingNormalizationService` validate strict runtime identity while remaining scope-agnostic about `playableStateScope`; command-routing-authoritative seams such as `CommandServiceImpl` enforce scope-aware durable routing metadata. Game Logic and Entity Management receive a complete-or-absent admitted routing bundle and fail rather than consuming a partial one. Room identifiers are opaque text routing identities such as `R-*`, not positive numeric IDs.

First-party selector state is also complete-or-absent. A missing registry entry combined with a partial persisted selector fails as `CONNECT_CONTEXT_INVALID` rather than degrading into ordinary realm selection; malformed or incomplete bootstrap/connect bundles are rejected before route preservation. Gateway trust-boundary failures and handshake scope failures remain distinct (`403`/trust rejection versus `CONNECT_SCOPE_MISMATCH`), while invalid first-party signed context is `CONNECT_CONTEXT_INVALID` or `CONNECT_TOKEN_REJECTED` according to the owning seam.

`PLAY` is the gameplay-binding step after authentication or verified first-party bootstrap identity. It checks current runtime membership and entitlement, explicit grant where required, current admission-pointer authority, and runtime-target coherence. The public-production membership writer above is invoked when a visible public realm lacks a gameplay-admission membership. First-party `PLAY` uses the same runtime authority as generic reconnect; noncanonical operator/bootstrap session-start paths must not become a parallel gameplay-admission model.

The canonical gRPC `GameSessionGrpcService.startSession` path preflights IP admission before replacing an existing session. Replacement transfers/reserves the IP slot safely, leaves the existing session alive until the new session and reservation succeed, and on failed admission stops only the tentative session. The shared `GameInstanceService.startSession` default is non-destructive; replacement is explicit. Once replacement is admitted, failure to tear down the old session is warning/cleanup follow-up rather than grounds to revoke the newly accepted session. REST `/sessions` is explicitly noncanonical operator/bootstrap behavior.

### Session Lifecycle and Logout

Redis-backed session context carries the authenticated account/tenant identity and gameplay binding, including character and game-instance context. Gameplay uniqueness is keyed by `{tenantId, gameInstanceId, characterId}`. A second valid login for the same gameplay identity takes over the old binding with an explicit reason; an unexpected transport loss can preserve the underlying session and queues for resume. Resume and takeover emit structured identity-bearing logs and bounded metrics such as `gamesession.session.resume`, `gamesession.session.takeover`, `gamesession.session.resume_denied`, and `gamesession.session.fresh_entry_fallback`.

The lifecycle classes are deliberately distinct:

- Unexpected disconnect may mark the session suspended/reconnect-eligible, preserve bounded replay state, and allow later `PLAY` to request recovery.
- Takeover invalidates the losing gameplay binding and does not leave it in control; close/reason classification identifies takeover.
- Deliberate `LOGOUT` is termination, not suspension. It deletes authenticated/gameplay session context, clears first-party connect context registration, removes gameplay presence, clears reconnect eligibility, invokes the canonical World Management termination seam and downstream Entity cleanup, returns one canonical result, and closes the transport with a deliberate logout reason. It does not delete bounded durable `resume_transcript_entry`/screen-buffer rows. Logout before login is a bounded in-band failure.

Replay projection is separate from retained transcript storage. Generic fresh `LOGIN` plus `PLAY` after logout is not reconnect and does not replay the retained output; unexpected transport loss remains reconnect-eligible. The current first-party connect-context path still reads and replays retained context after a post-logout `LOGIN` plus `PLAY`, which has not yet converged with the target rule that deliberate logout is not a recovery replay path. Partial gameplay shells are not authoritative: `hasGameplayBinding` may identify a shell requiring cleanup/normalization, but full gameplay identity requires positive game-instance and character identity, and gameplay-region readiness additionally requires a room. Logout cleanup and reconnect replay therefore skip partial identity shells rather than trusting them.

### Reconnect and Continuity

For generic Telnet and WebSocket, reconnect uses current identity and current Account runtime membership/entitlement authority, not only a prior backend token. Revoked membership, removed realm access, unavailable tenant gameplay, stale runtime target, and failed authority reads fail closed as appropriate. Stale, expired, or partially missing resumable state falls through to invisible fresh entry when current `PLAY` admission succeeds; backend authority unavailability remains retryable temporary-unavailable behavior. A fresh `LOOK` redraw is the current-state authority after successful resume or fresh fallback.

The reconnect transcript is a bounded durable per-player buffer keyed by gameplay identity; Redis may cache it but is not the authoritative gameplay state. Prompts are a separate output class and are not included by default. Received-but-unprocessed commands and unsent transient output are not durably replayed. The continuity contract is therefore bounded stall/redraw and current-state reconstruction, not arbitrary command replay or transport-byte replay.

First-party `/ws/game/**` has a separate but implemented authority path. Account exposes player bootstrap and connect-token issuance; Gateway requires a fresh connect token on non-proxy handshakes, emits deterministic failure classes for missing, expired, replayed, invalid-context, and scope-mismatch tokens, and forwards a signed `X-Firemud-Connect-Context` with `X-Firemud-Connection-Mode: first_party_web`. Game Session validates that context, lets bare first-party `LOGIN` consume verified identity rather than re-entering credential login, and keeps `PLAY` as the binding step. Successful first-party reconnect restores bounded screen buffer then emits fresh `LOOK`.

Gateway owns the downstream `/ws/game/**` socket and a stable edge transport session id. Its custom bridge can rebind an upstream Game Session socket after bounded loss without immediately dropping the player socket, reusing that edge id. Focused proofs cover abrupt upstream loss, a Game Session-like process bounce with isolated Reactor resources, and post-restart Game Logic commands (`LOOK`, movement-derived room context, and communication inputs) against shared state. Edge-visible retry taxonomy is reserved for actual edge-route loss or exhausted rebind windows; this does not guarantee in-flight command or byte replay.

Shared coordination state now carries reconnect/connect-context tracking, disconnect deduplication, gameplay-identity screen-buffer access, and the bounded session information needed for same-type takeover. The actual in-process socket remains local. Close classification is deterministic: planned drain, takeover/logout, and explicit internal error retain their classes; missing close metadata falls back to `backend_unavailable`; duplicate and missing disconnect hints are separately metered. A replacement backend may reconstruct from shared state, but visible continuity still stops at the bounded stall/redraw envelope.

### Fail-Closed Boundary Convergence

The auth/session/routing guardrail family is complete at its current bounded seams and is now a standing regression boundary, not a claim that every future ingress path has been audited. Shared helpers and direct adopters cover:

- `JwtClaims`, `SessionClaims`, session attestation, current-account access, elevated gameplay-role classification, and downstream gRPC bearer creation in Common Security;
- bootstrap/connect-scope/runtime membership/grant/entitlement claim readers, account-subject coherence, replay readers/writers, and account REST/gRPC request readers in Account Service;
- `GameplayAdmissionPointerSnapshots`, `ControlPlaneRequestParser`, `SessionIdParsing`, positive-ID readers, gameplay-character parsing, partial-shell classification, IP counter parsing, and command/control-plane routing normalization in Game Session;
- signed first-party handshake/connect-context checks and `TrustedTcpProxyIdentity` at Gateway, plus `TelnetRoutingBundle` and hidden bootstrap envelope handling at TCP Proxy;
- the corresponding positive-ID, room-scope, actor-scope, replay-payload, current-account, routing-bundle, and REST error-envelope readers in World Management, Entity Management, Social Groups, Automation, Logging & Admin, Game Design, and Game Logic.

The canonical rules are: numeric identifiers that are authoritative in a seam must be present, well-formed, and positive; opaque room ids remain textual; malformed or contradictory JWT/signed-token claims reject before identity comparison; partial routing bundles are absent or rejected, never completed by fallback; malformed persisted replay state is ignored/rejected or terminalizes only its affected durable work item; and request validation occurs before replay lookup when malformed shape could otherwise be masked by a stored effect. Malformed persisted Automation tenant identity terminalizes only that work item rather than aborting the batch. Unexpected parser/runtime bugs are not mislabeled as auth failures.

This includes positive readers for account, tenant, session, game-instance, character, version, item/container, payment, report, formation, and control-plane identities across the participating services; current-account claims that are present but malformed count as authenticated caller context and therefore fail tenant access rather than silently becoming anonymous; and invalid session JWTs used only for presence classification consistently degrade to the ordinary `PLAYER` presence role. Automation schedule due-point identities reject fractional JSON numbers as `invalid_built_in_payload` before handler, pin, quota, or durable-queue work. Runtime-version, work-item, tenant, and gameplay routing readers reject malformed/non-positive values before lookups or mutation. Entity replay-backed mutations validate expiry, required text, optional container identity, and positive quantity before replay execution so a replay hit cannot hide malformed request shape.

Replay boundaries validate both read and write sides. Account connect-token and public-production membership replays require valid success/created flags, nonblank required text, and payload identity matching cache-key inputs; otherwise the replay is treated as unavailable rather than as authoritative success. Prepared upgrade and remote-control-plane replays likewise compare request/execution payloads before reuse. Shared control-plane response readers reject malformed or non-positive returned ids instead of projecting them into apparently valid DTOs.

Equivalent HTTP, WebSocket, and gRPC failures use stable application semantics. gRPC application failures return canonical `ErrorDetail` payloads rather than transport errors; transport `onError()` remains for infrastructure failures. Shared REST exception handling produces `ApiResponse<ErrorDetail>` envelopes and field-specific `INVALID_ARGUMENT` responses instead of framework-default JSON. These seams log application warnings, meter `grpc.app_error` where applicable, and preserve span/error-code classification.

## Validation and Proof

The evidence records focused unit, controller, gRPC, WebSocket, cross-service, and restart/rebind proofs for text parsing and Telnet parity; real Account-backed login and authenticated `PLAY`; takeover, resume, logout, and replay cleanup; public-production membership idempotency; first-party handshake/reconnect; session-start IP ordering; malformed claim and positive-ID rejection; replay identity/payload mismatch; canonical REST/gRPC error envelopes; and Gateway upstream/process restart continuity. The Telnet cross-service harness uses a lightweight Gateway stub only as a test fixture and has a successful `TelnetGatewayGameSessionCrossServiceIntegrationTest`; the maintained runtime path is not the stub.

The focused admission proofs cover fresh acceptance, same-IP replacement reservation transfer, rejection without destroying the old session, and the non-destructive operator path. Telnet proofs cover envelope parsing, event metadata, reconnect backoff, ordered input replay, buffer exhaustion, sensitive logging, and Telnet/WebSocket `LOOK` parity. Game Session gRPC proofs cover `Ping`, `NotifyDisconnect`, session lifecycle/control-plane parsing, application-error payloads, and `grpc.app_error` classification. First-party proofs cover fresh-token enforcement, bare verified-identity login, `PLAY` scope binding, replay/expiry/context/scope failures, screen-buffer restore, and fresh redraw.

Recorded validation includes `spotlessApply`, touched-service full checks, `linkCheck lintMarkdown`, and `bash dev-tools/verify-fresh-bootstrap.sh`; the fresh-bootstrap proof succeeded with both WebSocket and Telnet smoke flows, and Markdown checks passed. The recorded guardrail validation also reports `:common-security:check -PfullCheck` passing. A June 2026 `:game-session-service:check -PfullCheck` attempt was disrupted by overlapping-run test-result corruption and a then-failing `MultiplayerLoadProofCrossServiceTest` assertion. Later July 2026 test artifacts supersede that blocker: Game Session unit, integration, cross-service, multiplayer-load, and Checkstyle results are green. This consolidation did not rerun the aggregate task.

## Active Gaps

- Signup-time login-mode selection and authenticator-app TOTP enrollment/management are not implemented; the live credential contract remains password plus email OTP. Richer MFA policy for elevated users is also future work.
- Manual QA remains for generic Telnet/WebSocket reconnect, takeover and failed resume, first-party reconnect, shared-state replacement, abrupt upstream rebind, Game Session restart, and Game Logic restart while the edge client remains connected.
- The first-party post-logout path still replays retained transcript context even though generic fresh login does not; that replay-projection mismatch must converge without treating durable transcript retention as reconnect eligibility.
- Broader discovery parity (`WORLDS`/`REALMS`/`CHARS`), richer admission-pointer/discovery behavior, and any later public/non-public realm product expansion remain outside the current bounded implementation.
- Account switching, multi-session controls, global sign-out, and richer first-party logout UX are not implemented.
- The continuity boundary intentionally excludes generalized chaos testing, arbitrary command replay, transport-byte replay, and reconstruction of queue/tick ownership if Game Logic later owns durable execution.
- The guardrail family should reopen only for a new concrete auth/session/routing regression; it is not a completed audit of every future service ingress.

## To Discuss

No competing target state is currently recorded. Future design discussion is required before changing credential modes or introducing the shared OTP/TOTP enrollment model, changing Account-owned admission authority or the account-versus-tenant ownership split, expanding reconnect guarantees beyond bounded redraw, or making non-edge restart continuity depend on durable queue/tick ownership. Future smart-client attach metadata must remain non-authoritative if revived.

## Service and Contract Map

| Owner | Current responsibility | Primary contract boundary |
| --- | --- | --- |
| Account Service | Global accounts, credentials/challenges, memberships, canonical tenant roles, entitlements, non-public realm grants, account lifecycle, and public-production first join | Account REST/gRPC APIs; bootstrap/connect-token APIs; runtime membership/grant/entitlement reads; `EnsurePublicProductionPlayerMembership` |
| Common Security | JWT/signed-token claims, session attestation, current-account and tenant access, gameplay-elevated roles, and downstream caller identity | `JwtClaims`, `SessionClaims`, session-context helpers, attestation and shared authorization checks |
| TCP Proxy | Telnet transport, hidden bootstrap envelope, reconnect buffering, disconnect notification, and Telnet close taxonomy | Telnet bridge; `TelnetRoutingBundle`; Game Session `NotifyDisconnect` |
| Spring Cloud Gateway | Trusted proxy/first-party handshake validation, signed connect context, stable edge socket/session id, upstream gameplay bridge and rebind | `/ws/game/**`; `TrustedTcpProxyIdentity`; connect-token and first-party context headers |
| Game Session | Text parser/interpreter, login/play/logout, Redis session context, takeover/resume, gameplay presence, admission normalization, command/control-plane routing, and redraw | Text-command protocol; WebSocket ingress; Game Session gRPC/control plane; Account and World/Entity calls |
| World and Entity Management | Current room/runtime authority, gameplay admission dependencies, runtime teardown, actor/item/room reads, and presence cleanup | Gameplay-attested gRPC/REST seams; World termination and Entity cleanup called by Game Session |
| Social Groups and Automation | Authenticated gameplay-adjacent communication/presence and runtime script/event admission using the same identity/routing guardrails | Positive-ID/current-account readers; gameplay routing bundles; canonical REST/gRPC error envelopes |
| Logging & Admin | Authenticated operator/control-plane reads, mutation/remediation actor identity, audit and response projection | Control-plane client readers; `ApiResponse<ErrorDetail>` REST boundary |
| Game Logic | Current-state command aggregation after recovery; currently stateless `LOOK`, movement-derived room context, and communication fan-out inputs | Post-restart gameplay command paths backed by shared world/session state |

The unchanged appendix below is the audit source for every allocation and exact validation command; it is not required to discover the current behavior summarized above.

## Source Evidence

The following records are the unchanged line-preserving transposition used as the audit backstop for the consolidated record above. Heading depth is shifted by three levels and same-directory Markdown links are rebased only so the combined tracker remains valid and navigable.

### source-01-task-list-telnet-to-gameplay-vertical-slice-1-99

#### Telnet to Gameplay Vertical Slice Task List - Telnet-to-gameplay session pipeline (source lines 1-99)

##### Preserved Source Text: source-01-task-list-telnet-to-gameplay-vertical-slice-1-99

<!-- migration-source path="design/project-management/vertical-slices/01-task-list-telnet-to-gameplay-vertical-slice.md" lines="1-99" sha256="dc114792d4b874656767b65eb2c220e41b1cd641be40d04a293832851f881433" heading-offset="3" -->
#### source-01-task-list-telnet-to-gameplay-vertical-slice-1-99: Telnet to Gameplay Vertical Slice Task List

##### source-01-task-list-telnet-to-gameplay-vertical-slice-1-99: Goal and Status

Goal: describe the end-to-end Telnet → Gateway → Game Session pipeline as a playable, testable slice, including envelopes, reconnection, and a minimal text command protocol. Status: parts of this slice are implemented and under active refinement; where behavior is not yet live, this document still describes the target-state flow, with implementation details tracked in the relevant service design docs and tests.

This checklist focuses on turning the Telnet TCP Proxy + Gateway + Game Session path into a playable, testable vertical slice. Each task is intentionally scoped so it can be handed to Codex (or a developer) as a single, self-contained chunk of work.

##### source-01-task-list-telnet-to-gameplay-vertical-slice-1-99: 1. Historical Local Telnet Loop

- [x] Historical note: this slice originally used a local echo loop before the full Gateway -> Game Session path was reliable. That shortcut has since been removed in favor of the canonical real-stack smoke scripts.
- [x] Historical note: the old proxy-only echo helper and tests were deleted when `dev` was normalized to the real local topology.

##### source-01-task-list-telnet-to-gameplay-vertical-slice-1-99: 2. Telnet Session Envelope and Event Metrics

- [x] Document the Telnet bridge bootstrap metadata model in the TCP Proxy design so the transport can pass trusted proxy-owned context without exposing typed attach commands to players.
- [x] Add focused unit tests for `TelnetSessionContext` covering valid envelopes (space-separated) and invalid/malformed cases, asserting sessionId/tenantId handling and log behaviour.
- [x] Add a Spring Boot test for `TelnetServerHandler` that opens a Netty channel, triggers the hidden bootstrap path with a normal command, and asserts that `TcpProxyEventService.recordConnectEvent` is invoked with the expected sessionId, tenantId, and client IP.

##### source-01-task-list-telnet-to-gameplay-vertical-slice-1-99: 3. Reconnection and Buffered Input Behaviour

- [x] Add unit or component tests for `TelnetServerHandler` that simulate a dropped WebSocket connection (e.g., triggering `onClose`/`onError`) and verify that reconnect backoff, reconnect counter metrics, and buffer preservation behave as designed.
- [x] Add a test that populates the buffer with several commands, forces a reconnect, and verifies `pushBufferedInputAsync` calls `TcpProxyEventService.pushBufferedInput` with the correct sessionId, tenantId, and ordered command list.
- [x] Add tests around the buffer depth limit (`MAX_BUFFER_DEPTH`) to ensure the handler closes the Telnet connection and increments the discarded command counter when the buffer is exhausted.

##### source-01-task-list-telnet-to-gameplay-vertical-slice-1-99: 4. Game Session gRPC TcpProxyService Implementation

- [x] Scaffold a `TcpProxyServiceImpl` gRPC server in `services/game-session-service` implementing the `TcpProxyService` proto (`NotifyDisconnect`) and register it with the existing gRPC server configuration.
- [x] Implement `NotifyDisconnect` in `TcpProxyServiceImpl` to validate inputs, map `proxyConnectionId` to the authenticated session when available, and mark the appropriate session as disconnected/suspended in Redis using the existing session repository or service layer, returning an `ErrorDetail` code of `OK` on success.
- [x] Add unit tests for `TcpProxyServiceImpl` covering happy paths and validation failures for `NotifyDisconnect`, ensuring `ErrorDetail` codes and `grpc.app_error` metrics are set correctly.

##### source-01-task-list-telnet-to-gameplay-vertical-slice-1-99: 5. Telnet → Gateway → Game Session Cross-Service Flow

- [x] Add a cross-service integration test (in `services/tcp-proxy-service` or a shared test module) that starts tcp-proxy-service, Spring Cloud Gateway, and game-session-service together using Testcontainers or Spring Boot test harnesses.
- [x] In that test, open a Telnet socket, send a simple command, and assert that the command arrives at the Game Session command queue (or an observable stub) and that an expected response can be read back over the Telnet connection.
- [x] Ensure this cross-service test is wired into Gradle (e.g., via a dedicated `crossServiceTest` or naming convention) and is documented so it can be run locally and in CI (run via `./gradlew :tcp-proxy-service:test --tests net.firedevops.firemud.TelnetGatewayGameSessionCrossServiceIntegrationTest`).

##### source-01-task-list-telnet-to-gameplay-vertical-slice-1-99: 6. Minimal Text Command Protocol and Gameplay Slice

###### source-01-task-list-telnet-to-gameplay-vertical-slice-1-99: 6.1 Protocol definition and docs

Link to the [Minimal Text Command Protocol](../../architecture/microservices/game-session-service/README.md#minimal-text-command-protocol) section, which defines the initial MVP gameplay command set shared by Telnet and WebSocket clients in this vertical slice.

- [x] Add a "Minimal Text Command Protocol" section to `design/architecture/microservices/game-session-service/README.md` describing a line-based command protocol for Telnet/WebSocket clients (for example `LOGIN <user> <password>`, `LOOK`, `SAY <text>`), including at least one concrete example per command.
- [x] In that section, define the expected response format for commands (plain text lines, how errors are reported, behavior for unknown commands, and how multiple responses are separated).
- [x] Update this vertical slice doc (`design/project-management/task-list-telnet-to-gameplay-vertical-slice.md`) to link to the new protocol section and explicitly call it the initial MVP command set for gameplay.

###### source-01-task-list-telnet-to-gameplay-vertical-slice-1-99: 6.2 Command model and parser in game-session-service

- [x] Introduce a minimal text command model in `services/game-session-service` (for example a `TextCommand` record with fields like `type`, `args`, `rawLine`, plus a `CommandType` enum including `LOGIN`, `LOOK`, `SAY`, `UNKNOWN`).
- [x] Implement a `TextCommandParser` (or similar) in `services/game-session-service` that takes a raw text line and returns a `TextCommand`, handling trimming, case-insensitive command names, and falling back to `UNKNOWN` for unrecognized commands.
- [x] Add unit tests for `TextCommandParser` covering valid commands, extra whitespace, empty lines, malformed input, and the `UNKNOWN` command path.

###### source-01-task-list-telnet-to-gameplay-vertical-slice-1-99: 6.3 Interpreter and dispatch into existing tick/command flow

- [x] Add a minimal `TextCommandInterpreter` (or equivalent service) in `services/game-session-service` that takes a `TextCommand` and enqueues the appropriate internal command into the existing tick/command queue (reusing the same enqueue logic used for current WebSocket/game commands).
- [x] Wire the WebSocket/Game Session entry point to call `TextCommandParser` + `TextCommandInterpreter` for each incoming text line so that text commands follow the same tick-based processing path as any other gameplay command.
- [x] Add tests (unit or small Spring test) that simulate a WebSocket message containing a text line and assert that the correct internal command is enqueued for a given session/tenant.

###### source-01-task-list-telnet-to-gameplay-vertical-slice-1-99: 6.4 Minimal LOOK gameplay command

- [x] Implement a minimal `LOOK` command handler in `services/game-session-service` that produces a static or test-seeded room description string (it can ignore real world state for this slice as long as the output is deterministic).
- [x] Connect the `LOOK` handler into the interpreter so that a parsed `LOOK` `TextCommand` results in the handler being invoked and its output being sent back to the client via the existing outbound messaging mechanism.
- [x] Add tests within `services/game-session-service` that exercise the `LOOK` command end-to-end inside the service (without Telnet or Gateway), asserting that a `LOOK` input results in the expected response text being produced.

###### source-01-task-list-telnet-to-gameplay-vertical-slice-1-99: 6.5 Telnet and WebSocket parity for LOOK

- [x] Ensure the Telnet path (TCP Proxy ? Gateway ? Game Session) forwards raw text command lines into the same `TextCommandParser` / interpreter pipeline used by direct WebSocket clients, with no Telnet-specific gameplay command parsing.
- [x] Add an integration test that exercises `LOOK` over a direct WebSocket connection using a lightweight Gateway stub and asserts the response text equals the Telnet response by reusing the same deterministic constant.
- [x] Add an integration test that exercises `LOOK` over a direct WebSocket connection through Spring Cloud Gateway (no Telnet) and asserts the response text matches the Telnet path exercised by the cross-service test in section 5 (for example by sharing a helper that asserts the `LOOK` response string is identical); implemented by `services/spring-cloud-gateway/src/test/java/integration/net/firedevops/firemud/GatewayLookCommandIntegrationTest.java`, which spins up a lightweight stubbed route and compares the payload to `LookCommandConstants.LOOK_RESPONSE`.

##### source-01-task-list-telnet-to-gameplay-vertical-slice-1-99: 7. Additional Infrastructure Tasks

- [x] Document the production WebSocket bridge between the TCP proxy and Spring Cloud Gateway (expected `GATEWAY_WS_URL` and Gateway route path such as `/ws/game/**`) so the Telnet and web client paths are explicitly aligned and easy to configure.
- [x] Ensure the `game-session-service` Gradle configuration generates and compiles gRPC stubs from `protos/game-session/v1/game_session_service.proto` into the module, and add a short note in the Game Session design docs describing where the generated stubs are used.
- [x] Implement a minimal `GameSessionService` gRPC server in `game-session-service` based on the `game_session.v1` proto (at least the `Ping` RPC), reusing existing service-layer logic where possible.
- [x] Add tests that exercise the `GameSessionService` gRPC `Ping` endpoint and verify it returns a successful `ErrorDetail` code and message.
- [x] Historical note: this slice originally added a dependency-light smoke path before the full local stack was reliable. That shortcut has since been removed in favor of the canonical real-stack smoke scripts.
- [x] Historical note: the associated Game Session documentation was later cleaned up so it no longer presents a dedicated dependency-light mode as the normal local workflow.

##### source-01-task-list-telnet-to-gameplay-vertical-slice-1-99: 8. Cross-Service Test Stabilization Follow-Up

Work on the tcp-proxy cross-service test has drifted: the current `TelnetGatewayGameSessionCrossServiceIntegrationTest` is burdened with ad-hoc bean overrides (mocked gRPC runners, custom route builders, Redis template stubs, etc.) and still fails to compile because `ReactiveRedisTemplate` is not on the test classpath. Before resuming, carve out a clean plan to simplify this area:

- [x] Remove the reactive Redis references (and other recent hacks) from `TelnetGatewayGameSessionCrossServiceIntegrationTest` so the file compiles again with the original dependencies — verified in `services/tcp-proxy-service/src/test/java/crossservice/net/firedevops/firemud/TelnetGatewayGameSessionCrossServiceIntegrationTest.java` where the imports/config now exclude Redis and mocked route builders (`@EnableAutoConfiguration` excludes `GRpcAutoConfiguration` / `GatewayRedisAutoConfiguration` only for the stub contexts).
- [x] Build a lightweight gateway stub app (either inline or as a separate `GatewayStubApplication`) that only exposes the `/ws/game` WebSocket route and requires no Redis/JWT/gRPC configuration — implemented in `services/tcp-proxy-service/src/test/java/crossservice/net/firedevops/firemud/stub/GatewayStubApplication.java`, which proxies `/ws/game/**` traffic via `ReactorNettyWebSocketClient`.
- [x] Update the cross-service test to launch just the stub gateway + the existing game-session stub, wiring the ports through `@DynamicPropertySource` without extra bean or component-scan overrides — `TelnetGatewayGameSessionCrossServiceIntegrationTest` now spins up only `GameSessionStubApplication` + `GatewayStubApplication` and registers `GATEWAY_WS_URL`/`TCP_PROXY_PORT` via `@DynamicPropertySource`.
- [x] Re-run `./gradlew :tcp-proxy-service:test --tests crossservice.net.firedevops.firemud.TelnetGatewayGameSessionCrossServiceIntegrationTest` and log any remaining failures as follow-up items (e.g., LOOK handler expectations) rather than piling on mocks — command executed successfully (latest run in this workspace, see shell history) with no remaining failures.
- [x] Document the new helper stub and wiring approach in this file and the per-service status summaries once it's stable, so future slices can reuse the simplified pattern — this checklist plus the TCP Proxy and Spring Cloud Gateway service-status docs now include references to the stub/test harness where relevant.

---

Note: After completing tasks in this checklist, go back and update the existing per-service status documents (such as `design/project-management/service-status-tcp-proxy-service.md`, `design/project-management/service-status-game-session-service.md`, and `design/project-management/service-status-spring-cloud-gateway.md`) and the relevant design docs so duplicated items are reconciled and the architecture documentation reflects the completed vertical slice.

<!--
Prompt for Codex to generate the next vertical slice task list after these items are done:

"Context: We just completed the Telnet to Gameplay vertical slice described in design/project-management/vertical-slices/01-task-list-telnet-to-gameplay-vertical-slice.md. Please inspect the current code and design docs, then propose a new markdown task list file under design/project-management/ focused on the next smallest playable/demo slice that follows this flow deeper into the system (e.g., richer gameplay commands, reconnection edge cases, or related services). Each task should be small enough to hand to Codex as a single chunk, and the file should end with a note reminding us to reconcile any duplicated items in existing per-service status docs and design docs."
-->
<!-- /migration-source -->

### source-02-task-list-login-and-session-vertical-slice-1-69

#### Login and Session Vertical Slice Task List - Login and session lifecycle (source lines 1-69)

##### Preserved Source Text: source-02-task-list-login-and-session-vertical-slice-1-69

<!-- migration-source path="design/project-management/vertical-slices/02-task-list-login-and-session-vertical-slice.md" lines="1-69" sha256="f13e4f32e5d3d6f898b6c86ce3d7e296d19ad3d995bcdaef00e33225b0f24531" heading-offset="3" -->
#### source-02-task-list-login-and-session-vertical-slice-1-69: Login and Session Vertical Slice Task List

##### source-02-task-list-login-and-session-vertical-slice-1-69: Goal and Status

Goal: define a cohesive login and session-management slice that layers authenticated flows, reconnection behaviour, and cross-service tests on top of the Telnet to gameplay pipeline. Status: core flows and several tests are implemented; this document captures the target-state behaviour, while individual task checkboxes and design docs indicate what is currently live vs. stubbed or deferred.

This checklist builds on the **Telnet to Gameplay** slice by wiring the `LOGIN` text command end-to-end through Game Session and Account services, enforcing authenticated sessions for gameplay commands, and exercising basic session resumption behaviour. As before, each task should be small enough to hand to Codex (or a developer) as a single, self-contained chunk of work.

##### source-02-task-list-login-and-session-vertical-slice-1-69: 1. Minimal LOGIN Protocol Behaviour and Docs

- [x] Review the [Minimal Text Command Protocol](../../architecture/microservices/game-session-service/README.md#minimal-text-command-protocol) and the [Authentication & Authorization](../../architecture/system-architecture-authentication.md#login-and-session-flow) docs to confirm the intended `LOGIN` / `LOGON` semantics (prompt-based vs parameterized logins, one supplied secret, error codes such as `INVALID_CREDENTIALS` and `ACCOUNT_LOCKED`).
- [x] Update the Game Session Service design doc so the `Minimal Text Command Protocol` section explicitly documents `LOGIN` and `LOGON` behaviour for both Telnet and WebSocket clients, including at least one success and one failure transcript that show the `OK LOGIN` / `ERROR <CODE>` response format.
- [x] Add a short subsection under the Authentication & Authorization doc describing how plain-text `LOGIN` commands map onto the Account Service `/auth/login` API (or gRPC equivalent), including how OTP values are forwarded when present.
- [x] Ensure docs clearly state that once this slice is complete, gameplay commands such as `LOOK` and `SAY` require an authenticated session, except in explicitly documented dev/test bypass modes.

##### source-02-task-list-login-and-session-vertical-slice-1-69: 2. Game Session LOGIN Command Handling

- [x] Implement a dedicated login handler in `services/game-session-service` (for example `LoginCommandHandler` or a focused method on `TextCommandInterpreter`) that processes `TextCommandType.LOGIN` / `LOGON`, distinguishes between prompt-based and parameterized forms, and produces a structured result that includes success/failure and optional response text.
- [x] On successful login, create or update a Redis-backed session context record storing at least `accountId`, `tenantId`, `characterId`, and a reference to the active `GameInstance`, using key conventions consistent with the [Redis Architecture](../../architecture/system-architecture-redis.md#session-keys-and-gameplay-binding).
- [x] Integrate the login handler into the existing WebSocket entry point so that `LOGIN` commands received over `/ws/game/**` flow through the same `TextCommandParser` / interpreter pipeline as gameplay commands, returning `OK LOGIN ...` responses or `ERROR <CODE> <message>` for failures.
- [x] Enforce an "authenticated session required" check before processing gameplay commands in the Game Session Service (e.g., `LOOK`, `SAY`) so that unauthenticated clients receive `ERROR NOT_AUTHENTICATED` until `LOGIN` succeeds, with a configuration flag allowing this guard to be disabled for local development if needed.
- [x] Add unit tests for the login handler and authentication guard covering at least: successful parameterized login, invalid credentials, missing arguments triggering a "prompt-mode not yet implemented" placeholder, repeated `LOGIN` attempts, and the `NOT_AUTHENTICATED` path for `LOOK`.

##### source-02-task-list-login-and-session-vertical-slice-1-69: 3. Account Service Integration for LOGIN

- [x] Implement a Game Session Service client for the Account Service login API (REST or gRPC, per the current Account Service design), including username, one supplied secret, and any tenant/game selection identifiers required for session binding.
- [x] Map Account Service responses into a small internal DTO (e.g., `LoginResult`) containing `accountId`, allowed tenant/game identifiers, and the issued JWT (if applicable), and store the JWT and identity details in Redis alongside the gameplay session entry as described in the Authentication & Authorization docs.
- [x] Ensure Game Session converts Account Service failures into appropriate `ErrorDetail` codes (e.g., `INVALID_CREDENTIALS`, `ACCOUNT_LOCKED`, `UPSTREAM_FAILURE`) and that these are surfaced in both the gRPC response and the text `ERROR <CODE> <message>` line returned to clients.
- [x] Add an integration test that starts Game Session Service with a lightweight Account Service stub (Spring Boot test configuration or Testcontainers-based stub), issues a `LOGIN` command over a direct WebSocket connection, and asserts that the stub receives the expected login request and that the client sees the correct `OK LOGIN ...` or `ERROR ...` text.
- [x] Document the dependency on the Account Service in `services/game-session-service/README.md`, including example `grpcurl` or REST calls that demonstrate the login path in isolation.

##### source-02-task-list-login-and-session-vertical-slice-1-69: 4. Session Resumption and Takeover Basics

- [x] Extend session persistence so that Game Session can look up an existing session by `accountId` and `characterId` on `LOGIN`, and perform session takeover when a second client logs in as the same character, in line with the [Multi-Client Behaviour and Session Takeover](../../architecture/system-architecture-authentication.md#multi-client-behavior-and-session-takeover) rules.
- [x] Implement basic session resumption behaviour so that when a previously connected client disconnects and later sends `LOGIN` again with valid credentials for an existing Redis session, Game Session reuses the existing tick/command queues instead of starting a fresh game instance.
- [x] Ensure that session takeover and resumption paths emit Micrometer metrics (for example `gamesession.session.takeover` and `gamesession.session.resume`) and structured logs including `tenantId`, `accountId`, and `characterId` to support debugging.
- [x] Add focused tests (unit or Spring Boot integration) that simulate two WebSocket connections using the same character: the first performs `LOGIN` and `LOOK`, the second performs `LOGIN` and is granted control while the first receives a disconnect or error, and subsequent `LOOK` calls continue to operate on the same underlying game state.
- [x] Update the [Reconnection Strategy](../../architecture/system-architecture-reconnection.md) doc with a short "implemented status" note describing which parts of the reconnect flow are now live (e.g., session takeover and basic resume after TCP/WebSocket loss) and which remain future work.

##### source-02-task-list-login-and-session-vertical-slice-1-69: 5. Telnet and WebSocket LOGIN Parity

The canonical Telnet admission semantics now live in the TCP Proxy and authentication docs as `WORLDS` (optional), `LOGIN`, and `PLAY`, with hidden proxy or MCP metadata reserved for future smart-client hints only. This slice focuses on verifying that Telnet and WebSocket clients share the same login pipeline and observed behaviour rather than redefining the protocol.

- [x] Confirm that Telnet connections sending normal login flow lines are forwarded by the TCP Proxy Service into the same WebSocket `/ws/game/**` route and Game Session login pipeline used by direct WebSocket clients, with no Telnet-specific gameplay parsing.
- [x] Add a cross-service integration test (reusing the lightweight gateway and game-session stubs where appropriate) that starts TCP Proxy Service, Spring Cloud Gateway, Game Session Service, and an Account Service stub together, performs the normal browse/login/play/look flow over a Telnet socket, and asserts that the observed responses match those from a direct WebSocket client hitting Gateway.
- [x] Verify that `TelnetServerHandler` continues to redact `LOGIN` arguments in logs while still forwarding the full command to Game Session, and add tests that assert logging behaviour for sensitive vs non-sensitive commands.
- [x] Document the Telnet `LOGIN` flow in both the TCP Proxy Service design doc and the Spring Cloud Gateway design doc, making it clear that Telnet and WebSocket clients share the same authentication path and that the gateway route (`/ws/game/**`) is the single entry point for gameplay login.

##### source-02-task-list-login-and-session-vertical-slice-1-69: 6. Developer Workflows and Smoke Tests

- [x] Add or update a smoke test script (alongside existing ones) that demonstrates a full `LOGIN` + `LOOK` flow over a direct WebSocket connection to Game Session Service, using sample credentials and clearly marking any required Account Service/dev environment setup.
- [x] Add a second smoke test or documented telnet or curl sequence that exercises the normal `WORLDS` + `LOGIN` + `PLAY` + `LOOK` flow through TCP Proxy Service and Spring Cloud Gateway. Use the same credentials and assert that the responses match the direct WebSocket flow.
- [x] Update the relevant per-service status docs so Game Session, Account, TCP Proxy, and Spring Cloud Gateway summarize the current login/session slice without duplicating the detailed task list.

---

##### source-02-task-list-login-and-session-vertical-slice-1-69: 7. Dev Mode Stubs and Real-Service Rollout

- [x] Historical note: this slice originally introduced temporary dependency-light session and game-instance stubs to keep `LOGIN` runnable before the real local stack existed. Those shortcuts have since been removed in favor of the canonical Redis/Postgres/downstream-service path.
- [x] Historical note: tests that once depended on those shortcuts were either rewritten against the real infrastructure-backed path or removed when they stopped representing the maintained runtime.
- [x] Historical note: the associated developer documentation was later cleaned up so the canonical local guidance now points at the real stack and smoke scripts rather than temporary stubbed services.

Note: After completing tasks in this checklist, go back and update the existing per-service status documents (such as `design/project-management/service-status-game-session-service.md`, `design/project-management/service-status-account-service.md`, `design/project-management/service-status-tcp-proxy-service.md`, and `design/project-management/service-status-spring-cloud-gateway.md`) and the relevant design docs so duplicated items are reconciled and the architecture documentation reflects the completed vertical slice.

<!--
Prompt for Codex to generate the next vertical slice task list after these items are done:

"Context: We just completed the Login and Session vertical slice described in design/project-management/vertical-slices/02-task-list-login-and-session-vertical-slice.md. Please inspect the current code and design docs, then propose a new markdown task list file under design/project-management/ focused on the next smallest playable/demo slice that follows this flow deeper into the system (for example, data-driven LOOK that integrates World and Entity services, the SAY/chat path through Social & Groups, or more advanced reconnection edge cases). Each task should be small enough to hand to Codex as a single chunk, and the file should end with a note reminding us to reconcile any duplicated items in existing per-service status docs and design docs."
-->
<!-- /migration-source -->

### source-02-1-task-list-login-session-hardening-vertical-slice-1-59

#### Login and Session Hardening Vertical Slice Task List - Canonical login/session runtime hardening (source lines 1-59)

##### Preserved Source Text: source-02-1-task-list-login-session-hardening-vertical-slice-1-59

<!-- migration-source path="design/project-management/vertical-slices/02.1-task-list-login-session-hardening-vertical-slice.md" lines="1-59" sha256="6f496560b0dd3c19e071e4c2a4236d70b3322c0ee234d01877c21a4b5a07af81" heading-offset="3" -->
#### source-02-1-task-list-login-session-hardening-vertical-slice-1-59: Login and Session Hardening Vertical Slice Task List

##### source-02-1-task-list-login-session-hardening-vertical-slice-1-59: Goal and Status

Goal: turn the currently playable login and session path into a more trustworthy runtime path by removing the old dependency-light shortcuts, re-enabling real integration coverage, and tightening the current Account/Redis/GameInstance-backed flow without reopening the scope of the original login slice. Status: completed for this PR. The core login/session runtime is now covered by real integration and ingress tests, the stale shortcut smoke path has been removed, the obsolete GHCR-based disabled cross-service scaffolding has been removed, and the app-smoke placeholders across the supporting services have been converted into real local application integration tests.

This checklist is a bounded follow-up to the **Login and Session** slice. The original slice delivered working `LOGIN` / `PLAY` / authenticated gameplay entry, but the runtime still relied on too much dependency-light scaffolding and left several integration tests disabled. This slice is about making the existing path less fake, not about introducing new player-facing commands.

##### source-02-1-task-list-login-session-hardening-vertical-slice-1-59: 1. Scope Lock and Design Alignment

- [x] Re-read the current login/session flow in the [Game Session Service protocols](../../architecture/microservices/game-session-service/protocols.md#login-and-play-flow), [Authentication & Authorization](../../architecture/system-architecture-authentication.md), and [Redis Architecture](../../architecture/system-architecture-redis.md) docs to confirm the intended target state for gameplay session binding, takeover, and resume.
- [x] Add a short subsection to the Game Session Service design docs that explicitly distinguishes the currently live Account/Redis/GameInstance-backed path from the old dependency-light shortcut path, and states that this slice is intended to remove the latter from normal integration coverage.
- [x] Confirm which parts of the login/session runtime are expected to remain developer-only shortcuts after this slice, and document them clearly instead of leaving broad hidden escape hatches in the canonical local path.

##### source-02-1-task-list-login-session-hardening-vertical-slice-1-59: 2. Account Service Path: Remove Stubby Success Cases

- [x] Audit `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/client/AccountClient.java` and the surrounding authentication path to identify where the old dependency-light mode returned optimistic success tokens instead of exercising a realistic failure or upstream call.
- [x] Replace the most misleading optimistic-success shortcuts with either a real Account Service-backed call path or a narrowly scoped test/dev stub that is explicit in configuration and impossible to confuse with normal integration behavior.
- [x] Ensure readiness and login code paths fail deterministically with documented `ErrorDetail` / text-protocol errors when Account Service is unavailable, rather than silently succeeding because of a broad local shortcut mode.
- [x] Add targeted unit/integration tests around the Account client/authentication path covering real upstream success, upstream unavailability, and the remaining explicitly documented developer-only stub mode.

##### source-02-1-task-list-login-session-hardening-vertical-slice-1-59: 3. Session Context and Game Instance Realism

- [x] Audit the temporary in-memory session-context and fake game-instance usages in Game Session and classify which call sites can now be switched to the real Redis/GameInstance-backed path for integration and cross-service tests.
- [x] Reduce the number of call sites that depend on in-memory session context or fake game-instance records during normal integration flows, preferring the real `RedisSessionContextService` and `GameInstanceServiceImpl` where the surrounding infrastructure now exists.
- [x] Tighten takeover/resume behavior so the runtime path used by `LOGIN` / `PLAY` / subsequent `LOOK` and `SAY` commands is the same path used by integration tests, rather than one path for production intent and another for test execution.
- [x] Add focused tests proving session takeover/resume still works after these changes, especially for the same `{tenantId, gameInstanceId, characterId}` identity rebinding path.

##### source-02-1-task-list-login-session-hardening-vertical-slice-1-59: 4. Re-enable or Replace Disabled Login/Session Tests

- [x] Revisit the disabled Game Session integration tests called out in the original slice, including `GameSessionLoginIntegrationTest`, `GameSessionWebSocketHandlerIntegrationTest`, the former dependency-light smoke test, and `SessionResumptionFlowTest`, and decide which should be re-enabled, rewritten, or intentionally deleted.
- [x] Re-enable the tests that now have real infrastructure-backed equivalents, using Testcontainers or the existing harness setup instead of broad local shortcut assumptions.
- [x] Replace stale or misleading tests with slimmer focused coverage when the original fixture shape is no longer representative of the current protocol or runtime.
- [x] Ensure every intentionally still-disabled test has an accurate, current reason; do not leave stale `@Disabled` messages referring to already-completed work.

##### source-02-1-task-list-login-session-hardening-vertical-slice-1-59: 5. Telnet / Gateway / Game Session Cross-Service Session Confidence

- [x] Revisit `TelnetGatewayGameSessionAccountCrossServiceIntegrationTest` and determine whether it should be re-enabled directly, rewritten into a narrower current-protocol test, or split into separate WebSocket and Telnet cross-service cases.
- [x] Add or refresh at least one cross-service flow that proves the current login/session path works through the real ingress chain (TCP Proxy -> Gateway -> Game Session -> Account) without leaning on optimistic local shortcut behavior.
- [x] Include one takeover/resume or reconnect-oriented regression in this cross-service coverage so the session semantics are tested as part of the real ingress path rather than only in service-local tests.

##### source-02-1-task-list-login-session-hardening-vertical-slice-1-59: 6. Developer Workflows and Documentation Cleanup

- [x] Update the Game Session README/design docs and any related developer workflow notes so local experimentation guidance clearly separates "quick dev-only stubs" from "real integration path" commands and expectations.
- [x] Update any smoke scripts or manual test sequences that still implicitly assume a dependency-light shortcut mode is the primary way to exercise login/session behavior.
- [x] Refresh the affected service-status docs so Game Session, Account, TCP Proxy, and Spring Cloud Gateway summarize the hardened current state instead of repeating stale caveats from the original slice.

##### source-02-1-task-list-login-session-hardening-vertical-slice-1-59: 7. Final QA Checklist

- [x] Run the relevant Game Session, TCP Proxy, and cross-service test targets for the updated login/session path and confirm they pass without relying on optimistic local shortcuts.
- [x] Manually verify one baseline WebSocket and one baseline Telnet `LOGIN` / `PLAY` / `LOOK` path against the real integration-backed flow.
- [x] Confirm the old dependency-light behavior has been removed from the maintained local and smoke workflows rather than left as a hidden dependency of the main slice.

---

##### source-02-1-task-list-login-session-hardening-vertical-slice-1-59: Deferred Follow-Up

- A future slice can add new modern cross-service tests for specific inter-service contracts when those contracts justify dedicated end-to-end coverage.
- This slice intentionally removed stale disabled GHCR-based placeholder tests instead of reviving them in a misleading form.
<!-- /migration-source -->

### source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251

#### Email OTP and Text-Client Auth Options Vertical Slice - Account and text-client authentication (source lines 1-251)

##### Preserved Source Text: source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251

<!-- migration-source path="design/project-management/vertical-slices/02.1.1-task-list-email-otp-and-text-auth-options-vertical-slice.md" lines="1-251" sha256="ec838e79760669c399666d19416d6aeb65a0890e39b8e2dc4fbef74e14d8c8a1" heading-offset="3" -->
#### source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251: Email OTP and Text-Client Auth Options Vertical Slice

##### source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251: Goal and Status

Goal: define the canonical text-client authentication model around account-selected login modes, a shared account-level OTP secret, and a text-first `LOGIN <email> [secret]` flow that supports password, emailed OTP, and later authenticator-app TOTP without fragmenting the auth system. Status: Account Service challenge lifecycle, password/email-OTP policy, and account-settings mode selection, plus Game Session `LOGIN <email>` challenge initiation, are live; signup mode selection and authenticator-app TOTP enrollment remain pending.

##### source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251: Checklist

- [ ] Define target-state behavior and scope.
- [ ] Implement the slice end-to-end.
- [ ] Verify and close any follow-ups.

##### source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251: Implementation Notes

Account Service owns neutral email-login OTP challenge initiation, persists one short-lived hashed code per account with resend cooldown and bounded failed attempts, and verifies a single-use code into the existing authenticated session/token result.

Account Service persists the primary login mechanism set as `login_auth_modes`. Existing and newly created accounts default to the compatible mixed set `PASSWORD,EMAIL_OTP`. Account Service is authoritative for interpreting the supplied second secret: it tries a valid active email code first, falls back to password only when that mode is allowed, and only counts a failed email-code attempt when neither allowed mechanism authenticates. Code issue and direct verification also reject accounts without the email-OTP mode.

Current Game Session command adoption remains incomplete:

- `LOGIN <email> <secret>` already forwards the opaque secret to Account Service and therefore receives the new policy;
- `LOGIN <email>` requests an emailed challenge and returns the generic code-sent response without authenticating or enqueueing gameplay work;
- prompts/help now describe the optional secret grammar;
- signup mode selection and authenticator-app TOTP enrollment remain later work.

The dormant legacy TOTP field, separate REST/gRPC `otp` parameter, and three-argument text-login form were removed during the current convergence. No persisted authenticator-app secret remains before a deliberate enrollment design exists; the active account contract remains one supplied secret interpreted only as an enabled password or verified-email login code.

So this document remains the target-state auth design, with the Account Service policy portion now implemented.

The account-settings API now exposes the persisted `PASSWORD` and `EMAIL_OTP` mode set through global-account `GET` and `PUT /accounts/{accountId}/login-auth-modes` reads and replacements. The requested set must be nonempty and contain only supported modes. Enabling `EMAIL_OTP` requires the account email to be verified, so a player cannot choose an email-only login path that cannot issue a challenge. Account creation retains the mixed default; explicit signup selection remains later work.

This slice is a follow-up to the existing login/session work. FireMUD should not treat text clients as a degraded copy of browser login. It needs one deliberate text-first auth model that stays compact in Telnet and generic WebSocket clients while still allowing stronger security modes for the users who want them.

##### source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251: Why This Slice Exists

Text clients and Telnet clients have different constraints than browser UI:

- no native clickable auth UI;
- no strong expectation of password-manager integration;
- awkward multi-step secret entry flows;
- strong value in minimizing sensitive text entry over plain command lines;
- strong value in one-command login for users who authenticate frequently.

At the same time, different users want different tradeoffs:

- some want passwordless email login;
- some still want password convenience;
- some will later want authenticator-app TOTP because it is faster than waiting for email;
- some higher-security users may want password plus TOTP in richer GUI flows.

So the auth model should be account-driven, not one hardcoded login style for everyone.

##### source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251: Target State

- Each account chooses its allowed login mechanism set at signup or in account settings.
- Supported modes should converge on:
  - password;
  - email OTP;
  - TOTP;
  - optionally stronger combined modes later where appropriate.
- The OTP model should use one account-level OTP secret.
- Emailed OTP codes and authenticator-app TOTP should be two surfaces over the same underlying OTP model, not two unrelated systems.
- The canonical text-client command surface should be:
  - `LOGIN <email>`
  - `LOGIN <email> <secret>`
- `LOGIN <email>` should trigger an emailed OTP challenge when the account mode allows it.
- `LOGIN <email> <secret>` should attempt the account's allowed fast-path auth methods in policy order:
  - OTP/TOTP first when the account allows OTP-style auth;
  - password fallback only when the account allows password auth.
- Game Session should only create the authenticated gameplay/session context after Account Service verifies the supplied credential or challenge.
- Stronger MFA should remain possible, but richer MFA UX should prefer first-party web or GUI flows rather than forcing Telnet users through maximum-friction paths by default.

##### source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251: Scope

- Define the account-level login-mode model for text clients.
- Define the shared OTP-secret model used by both emailed OTP and future TOTP app integration.
- Define the canonical text command shapes for:
  - `LOGIN <email>`
  - `LOGIN <email> <secret>`
- Define how Account Service decides whether the second argument is evaluated as OTP/TOTP, password, or both based on account mode.
- Define the neutral user-facing behavior for generic login and the more direct delivery-failure behavior allowed during explicit signup/verification flows.
- Define minimum challenge expiry, resend, invalidation, and retry expectations.

##### source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251: Out of Scope

- Browser-first OAuth or social login flows.
- Full web account-management UX.
- Full signup flow design beyond the auth implications recorded here.
- Full authenticator-app enrollment implementation.
- Full admin/creator MFA policy implementation.

##### source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251: Account-Level Auth Model

Auth mechanism should be an account property, selected during signup and later adjustable through account settings.

Planned account-level options:

- password only;
- email OTP only;
- TOTP only;
- mixed password + OTP/TOTP where explicitly allowed later.

The important product rule is that text-client auth mode is not globally fixed. A user who never wants a password should be able to remain passwordless, while a user who prefers the convenience of `LOGIN <email> <password>` should still be able to keep that mode if the product allows it.

##### source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251: Shared OTP Secret Model

The system should use one account-level OTP secret.

That one secret powers:

- emailed OTP codes;
- future authenticator-app TOTP codes.

This is preferable to building two unrelated systems because:

- the verification semantics stay unified;
- account auth policy stays simpler;
- later TOTP app support becomes an exposure of an existing account OTP capability rather than a parallel migration;
- text login can remain compact.

So the intended model is:

- one authoritative account OTP secret;
- email delivery is one presentation/delivery path for OTP use;
- authenticator-app TOTP is another presentation/delivery path for the same account capability.

##### source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251: Canonical Text-Client Command Flow

The text protocol should support two primary shapes.

###### source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251: 1. Challenge initiation

Player enters:

- `LOGIN player@example.com`

If the account mode allows emailed OTP, Game Session asks Account Service to create and send an email challenge.

The player receives a neutral response such as:

- `OK LOGIN_CODE If that account is allowed to log in, a code has been sent. Use OTP <code> or LOGIN <email> <code>.`

This keeps the text flow compact while still preserving anti-enumeration behavior for generic login.

###### source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251: 2. One-command fast path

Player enters:

- `LOGIN player@example.com 482193`
- or `LOGIN player@example.com swordfish`

Game Session forwards the supplied secret to Account Service, and Account Service evaluates it using the account's allowed auth modes:

- for TOTP-only or OTP-style accounts, treat it as OTP/TOTP;
- for password-only accounts, treat it as password;
- for mixed accounts, try OTP/TOTP first, then password fallback.

This keeps TOTP and password users on a one-command login path, while email-OTP users still have the compact two-step fallback.

##### source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251: Command Semantics and Policy Order

The second argument to `LOGIN <email> <secret>` should not be guessed globally. It should be interpreted according to the account's configured auth modes.

Recommended behavior:

- TOTP-only account:
  - `LOGIN <email> <secret>` tries OTP/TOTP only.
- password-only account:
  - `LOGIN <email> <secret>` tries password only.
- mixed password/TOTP account:
  - try OTP/TOTP first;
  - if it does not match, fall back to password.

This avoids weird ambiguity from simplistic heuristics like "six digits means OTP".

##### source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251: Login vs Signup / Verification Error Policy

Generic login and active verification/signup flows should not share exactly the same error messaging policy.

###### source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251: Generic login

For plain login:

- keep account-enumeration resistance;
- return neutral responses for challenge initiation;
- do not leak whether the email is registered, allowed, or absent through casual login responses.

###### source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251: Signup / explicit verification flow

When the user is already in an explicit signup or email-verification flow, the product may surface email delivery failure more directly, because the player is in a bounded account-creation/verification state and needs actionable feedback.

So the same OTP engine can support two different user-facing policies:

- generic login: neutral;
- explicit signup/verification: more direct delivery feedback when necessary.

##### source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251: Challenge Lifecycle Expectations

The first implementation should follow these rules:

- OTP challenges are account-specific;
- only one active challenge should exist per account and relevant login scope at a time;
- issuing a new challenge invalidates the old one;
- challenges are single-use;
- challenges expire quickly;
- resend attempts are rate-limited;
- verification attempts are bounded.

Recommended defaults:

- expiry around 5 to 10 minutes;
- resend cooldown around 30 to 60 seconds;
- bounded invalid-attempt cap per active challenge.

##### source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251: Stronger MFA Direction

Stronger MFA still matters, especially for platform admins or game creators.

But the preferred product direction is:

- baseline text-client usability first;
- richer MFA friction primarily enforced in web or first-party GUI flows where the UX is much better;
- Telnet/TCP should not carry unnecessary complexity by default unless an operator deliberately wants that tradeoff.

Planned follow-up directions:

- expose the existing account OTP secret for authenticator-app TOTP enrollment;
- support terminal-friendly QR bootstrap where the client can render it reliably;
- keep a manual secret fallback;
- allow stronger MFA policy for elevated users or richer client surfaces.

##### source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251: Testing Expectations

- unit coverage for:
  - `LOGIN <email>`
  - `LOGIN <email> <secret>`
  - mode-aware auth evaluation
- integration coverage for Game Session <-> Account Service:
  - challenge creation;
  - challenge verification;
  - password fallback where allowed;
  - TOTP acceptance once exposed
- logging tests proving supplied secrets and OTP codes remain redacted
- explicit tests for neutral login responses vs more direct signup/verification delivery feedback once those flows exist

##### source-02-1-1-task-list-email-otp-and-text-auth-options-vertical-slice-1-251: Follow-On

- explicit signup/account-verification slice aligned to this auth model;
- authenticator-app enrollment and management;
- optional admin/creator stronger MFA policy;
- richer web/first-party handoff flows built on the same account auth modes.
<!-- /migration-source -->

### source-02-1-2-task-list-logout-and-session-termination-vertical-slice-1-185

#### Logout and Session Termination Vertical Slice - Deliberate logout and session termination (source lines 1-185)

##### Preserved Source Text: source-02-1-2-task-list-logout-and-session-termination-vertical-slice-1-185

<!-- migration-source path="design/project-management/vertical-slices/02.1.2-task-list-logout-and-session-termination-vertical-slice.md" lines="1-185" sha256="c8cdcbd4058096f3cadf2fb1fff86db4a8455f4ae1a911e4b166d0d11f0fafa1" heading-offset="3" -->
#### source-02-1-2-task-list-logout-and-session-termination-vertical-slice-1-185: Logout and Session Termination Vertical Slice

##### source-02-1-2-task-list-logout-and-session-termination-vertical-slice-1-185: Goal and Status

Goal: add one canonical deliberate logout flow so `LOGOUT` cleanly ends the player's authenticated and gameplay-bound session state, instead of relying only on disconnect recovery, TTL expiry, or operator-side session stop paths. Status: complete at the current bounded boundary; broader reconnect-proof depth and richer client UX remain future work.

##### source-02-1-2-task-list-logout-and-session-termination-vertical-slice-1-185: Implementation Notes

Current branch state:

- built-in `LOGOUT` command handling exists in Game Session and is dispatched through the shared session-command family alongside `LOGIN` and `PLAY`;
- successful logout now deletes session context, clears first-party connect context registration, removes gameplay presence, clears replay buffer state for gameplay-bound sessions, and closes the transport with a deliberate `LOGOUT` close reason;
- successful logout/stop now also drives the canonical World Management termination seam instead of relying on a ping-only session stop path, so gameplay-bound shutdown now closes admission before World/Entity runtime cleanup converges, including World-owned runtime row deletion before `TERMINATED`;
- replacement-session takeover now uses that same termination seam instead of locally invalidating the losing session without draining its world runtime;
- logout before login is now a bounded in-band failure instead of an unhandled path;
- focused unit and websocket integration coverage exists for the basic logout path, replay-buffer cleanup, the distinction between later fresh login and reconnect/takeover counters, and first-party logout parity for replay-state clearing.
- focused unit and websocket integration coverage also proves the complementary generic-WebSocket reconnect case: unexpected disconnect keeps replay eligible where logout would clear it.

Still future work above the current bounded implementation:

- fuller proof that unexpected transport-loss recovery remains distinct from deliberate logout across the wider reconnect slices;
- any later first-party client UX affordances beyond the generic transport-close behavior.

This slice is a follow-up to the current login/session and reconnect work. The repo already had reconnect-oriented suspension and buffer replay behavior before this slice, but it did not yet have a player-facing logout command or one explicit target-state distinction between:

- transient disconnect and reconnect recovery;
- gameplay takeover or replacement;
- deliberate logout and session termination.

##### source-02-1-2-task-list-logout-and-session-termination-vertical-slice-1-185: Why This Slice Exists

Today the runtime already does some cleanup and suspension work:

- transport/session registries are cleaned up when a live socket closes;
- TCP disconnect notifications can mark gameplay session state as `SUSPENDED` for reconnect recovery;
- Redis-backed session context entries eventually expire by TTL;
- explicit stop-session control-plane paths can delete persisted session state.

But there is no canonical player-facing logout lifecycle.

That creates the wrong semantics:

- reconnect recovery state risks being treated like a fresh login/session lifecycle;
- transcript or output-buffer replay can conceptually leak across intentional session boundaries;
- `LOGIN` remains overloaded as both "start a new session" and "resume after transport loss";
- players have no explicit command to say "end this session cleanly now."

FireMUD needs one deliberate session-termination model before reconnect and richer auth flows grow further.

##### source-02-1-2-task-list-logout-and-session-termination-vertical-slice-1-185: Scope

- Define a canonical player-facing `LOGOUT` command for Telnet, generic WebSocket, and later first-party clients.
- Define what state `LOGOUT` clears immediately versus what remains available only for reconnect recovery.
- Define the relationship between:
  - session context;
  - gameplay binding;
  - reconnect/suspended state;
  - screen-buffer replay state;
  - transport closure.
- Define the canonical success transcript and transport-close behavior for deliberate logout.
- Define the minimum cleanup behavior needed so a fresh post-logout `LOGIN` is not treated like a reconnect restore.

##### source-02-1-2-task-list-logout-and-session-termination-vertical-slice-1-185: Out of Scope

- Full account-switching UX.
- Rich multi-character lobby/session-management UI.
- Cross-device sign-out of all sessions.
- Advanced operator or admin session-revocation tools beyond what the first logout path needs.

##### source-02-1-2-task-list-logout-and-session-termination-vertical-slice-1-185: Target State

- `LOGOUT` is a built-in stage-aware command available once a player is authenticated.
- Deliberate logout is not the same thing as disconnect recovery.
- A clean logout should:
  - stop the current gameplay session binding deliberately rather than detaching it for later resume;
  - delete Redis-backed authenticated/gameplay session context for that transport session;
  - clear reconnect-oriented replay/screen-buffer state for that logged-out gameplay identity;
  - avoid preserving the current session as a reconnect-eligible suspended state;
  - emit one canonical success response and then close the transport.
- A fresh later `LOGIN` after `LOGOUT` should behave like a new authenticated session, not like reconnect resumption.

##### source-02-1-2-task-list-logout-and-session-termination-vertical-slice-1-185: Canonical Behavior Split

FireMUD should distinguish three lifecycle classes:

###### source-02-1-2-task-list-logout-and-session-termination-vertical-slice-1-185: 1. Unexpected Disconnect

- Socket closes or transport dies unexpectedly.
- Runtime may preserve reconnect-eligible suspended state.
- Screen-buffer replay remains eligible.
- Later `PLAY` on the same underlying session flow may request reconnect redraw.

###### source-02-1-2-task-list-logout-and-session-termination-vertical-slice-1-185: 2. Gameplay Takeover or Replacement

- Another session replaces the old gameplay binding.
- Old gameplay binding is deliberately invalidated.
- The losing session should not retain control of gameplay state.
- Any resulting close reason should be explicit about takeover.

###### source-02-1-2-task-list-logout-and-session-termination-vertical-slice-1-185: 3. Deliberate Logout

- Player explicitly issues `LOGOUT`.
- Session/auth/gameplay state for that session is terminated, not suspended.
- Reconnect replay state should be cleared rather than preserved.
- The active gameplay session is stopped rather than kept resumable.
- A new `LOGIN` starts fresh.

##### source-02-1-2-task-list-logout-and-session-termination-vertical-slice-1-185: First Implementation Boundary

The first narrow implementation should not attempt every possible account-management command.

The current repo has completed this first bounded order:

1. add built-in `LOGOUT` command handling;
2. delete session context and reconnect-eligible buffer/state for that authenticated/gameplay session;
3. stop the active gameplay session rather than detaching it;
4. explicitly avoid the suspend-for-reconnect path on deliberate logout;
5. emit one canonical success/termination response and close the transport;
6. prove fresh `LOGIN` after logout does not replay stale reconnect output.

This keeps the slice focused on lifecycle correctness rather than broader account UX.

##### source-02-1-2-task-list-logout-and-session-termination-vertical-slice-1-185: 1. Design Alignment and Ownership

- [x] Re-read the login/session, reconnect, TCP proxy, Gateway, and Game Session design docs so logout semantics do not conflict with the existing reconnect and takeover model.
- [x] Update the relevant docs so the repo describes one canonical distinction between:
  - reconnect recovery;
  - takeover;
  - deliberate logout.
- [x] Document which service owns each part of logout cleanup:
  - transport close reason;
  - session context deletion;
  - gameplay binding teardown;
  - replay/screen-buffer cleanup.

##### source-02-1-2-task-list-logout-and-session-termination-vertical-slice-1-185: 2. Game Session Service: Built-In `LOGOUT`

- [x] Add a stage-aware built-in `LOGOUT` command in Game Session.
- [x] Ensure `LOGOUT` is available only when authenticated or gameplay-bound state exists, with bounded graceful behavior when used pre-login.
- [x] Delete Redis-backed session context for the current session on successful logout.
- [x] Stop the active gameplay session on successful logout instead of leaving it resumable.
- [x] Ensure logout does not preserve reconnect-restore flags for that session.
- [x] Ensure later `LOGIN` after logout is treated as fresh session establishment, not reconnect continuation.

##### source-02-1-2-task-list-logout-and-session-termination-vertical-slice-1-185: 3. Reconnect and Replay Cleanup

- [x] Define and implement the canonical cleanup for reconnect-oriented state on logout, including at least:
  - screen-buffer replay entries;
  - reconnect restore eligibility;
  - any suspended gameplay session marker used only for transport loss recovery.
- [x] Add focused tests proving that deliberate logout clears replay state and that a fresh websocket login/play path does not replay stale transcript state.
- [ ] Add the remaining focused tests proving that unexpected disconnect still preserves reconnect state where expected.

##### source-02-1-2-task-list-logout-and-session-termination-vertical-slice-1-185: 4. Transport and Close Semantics

- [x] Close the transport after the canonical logout success response instead of returning the client to an in-band pre-login front door state.
- [x] Keep Telnet, generic WebSocket, and later first-party flows aligned on one canonical result even if framing differs.
- [x] Ensure the close reason or final response is deliberate and not confused with gateway restart or takeover.

##### source-02-1-2-task-list-logout-and-session-termination-vertical-slice-1-185: 5. Cross-Service Proof Shape

- [x] Add a bounded websocket proof for:
  - `LOGIN`
  - `PLAY`
  - `LOGOUT`
  - fresh `LOGIN`
  - `PLAY`
  - no stale reconnect replay
- [x] Add a takeover-oriented proof showing that takeover remains distinct from logout.

##### source-02-1-2-task-list-logout-and-session-termination-vertical-slice-1-185: 6. Final QA Checklist

- [x] The repo has one explicit logout lifecycle distinct from reconnect recovery.
- [x] `LOGOUT` deletes authenticated/gameplay session context for the session instead of relying on TTL expiry.
- [x] `LOGOUT` stops the active gameplay session rather than keeping it resumable.
- [x] Deliberate logout clears reconnect-oriented replay state.
- [x] Fresh later login does not replay stale output from the previous logged-out session.

---

##### source-02-1-2-task-list-logout-and-session-termination-vertical-slice-1-185: Follow-On

- Account-switching or multi-session management commands later.
- "Log out all sessions" or operator-driven revocation later.
- GUI-specific logout affordances once the first-party web client grows richer session controls.
<!-- /migration-source -->

### source-02-1-6-task-list-global-account-and-tenant-authorization-convergence-vertical-slice-1-81

#### Global Account and Tenant Authorization Convergence Vertical Slice - Global account and tenant authorization (source lines 1-81)

##### Preserved Source Text: source-02-1-6-task-list-global-account-and-tenant-authorization-convergence-vertical-slice-1-81

<!-- migration-source path="design/project-management/vertical-slices/02.1.6-task-list-global-account-and-tenant-authorization-convergence-vertical-slice.md" lines="1-81" sha256="f5a20f3b541af597e4b40056b7bdd29ecc6abfce8993d2bb2dd1c6aeea3bd708" heading-offset="3" -->
#### source-02-1-6-task-list-global-account-and-tenant-authorization-convergence-vertical-slice-1-81: Global Account and Tenant Authorization Convergence Vertical Slice

##### source-02-1-6-task-list-global-account-and-tenant-authorization-convergence-vertical-slice-1-81: Goal and Status

Goal: converge the live account/auth/runtime model on the documented distinction between one global platform account, per-tenant membership and scoped roles, account-level lifecycle operations, and explicit non-public realm grants. Status: complete at the current boundary.

##### source-02-1-6-task-list-global-account-and-tenant-authorization-convergence-vertical-slice-1-81: Implementation Notes

- `POST /auth/player-bootstrap` now authenticates account identity without first requiring gameplay membership in the selected tenant; tenant gameplay admission still happens later during bootstrap discovery and `POST /auth/connect-token`.
- Account/profile HTTP surfaces in `account-service` now authenticate ordinary callers and enforce explicit current-account-or-tenant-access rules instead of relying on blanket privileged-only HTTP auth.
- Shared auth helpers now treat `tenantAdmin` as the canonical scoped elevated tenant role, and the last live legacy `admin` role dependency has been removed from account auth behavior.
- Account now owns the first concrete non-public realm grant substrate:
  - persisted `account_realm_access_grant`
  - runtime gRPC lookup via `GetRealmAccessGrantForRuntime`
  - operator/runtime write path via `/internal/runtime/realm-access-grants`
- Bootstrap discovery and connect-token issuance now treat hidden/non-public realms as explicit account-grant reads instead of implicit membership-only admission.
- Account export, tenant-scoped export, deletion, password reset, username reminder, and email verification now use account-scoped lifecycle boundaries instead of overloading tenant-keyed export/delete/recovery tokens.
- Closure verification has been re-checked across the live contracts and focused tests:
  - `protos/account/v1/account_service.proto` keeps `ExportAccount` and `DeleteAccount` account-scoped while reserving `ExportTenantData` as the explicit tenant-scoped bounded export route.
  - `services/account-service/src/main/resources/openapi.yaml` exposes account-wide export/delete routes plus the separate `/accounts/{accountId}/tenant-export` boundary instead of tenant-keyed lifecycle shortcuts.
  - `AccountController`, `AccountServiceImpl`, and `AccountGrpcService` keep full export/delete/recovery ownership on the account boundary while still enforcing tenant-scoped access only for the narrower tenant export seam.
  - focused controller, service, and gRPC tests cover account-wide export across tenants, explicit tenant-export access control, and account deletion preconditions.

##### source-02-1-6-task-list-global-account-and-tenant-authorization-convergence-vertical-slice-1-81: Why This Slice Exists

The repo has explicit `account_tenant_membership`, public-production membership creation, realm-aware bootstrap/connect-token flows, and account-scoped lifecycle routes. This slice exists to prevent those seams from drifting back into a tenant-owned account model:

- `POST /auth/player-bootstrap` must continue authenticating global account identity first instead of reintroducing tenant gameplay membership as a login prerequisite;
- scoped tenant authorization and JWT/session claims should stay on the documented `player` / `designer` / `tenantAdmin` / `moderator` split without drifting back to legacy role names;
- account export, delete, password-reset, and email-verification behavior previously drifted toward tenant-keyed ownership even though the architecture treats platform accounts as global;
- non-public realm access grants need to stay Account-owned and must not drift back into implicit membership or visibility shortcuts.

This work needs one bounded home so account identity, membership, tenant authorization, and realm grants do not keep drifting independently across Account, Common Security, and runtime entry flows.

##### source-02-1-6-task-list-global-account-and-tenant-authorization-convergence-vertical-slice-1-81: Scope

- global account bootstrap versus tenant gameplay membership
- canonical tenant-scoped role model and JWT claim semantics
- account-level export/delete/recovery lifecycle behavior
- Account-owned non-public realm grant authority
- runtime reads that distinguish:
  - global account identity
  - tenant membership
  - tenant authorization
  - realm grant access

##### source-02-1-6-task-list-global-account-and-tenant-authorization-convergence-vertical-slice-1-81: Out of Scope

- billing-product redesign beyond the entitlement reads already required by account/runtime flows
- hidden-staff capability bundles and god/admin gameplay behavior
- gameplay-domain service-to-service delegation and attestation, which belong in a separate auth/delegation slice

##### source-02-1-6-task-list-global-account-and-tenant-authorization-convergence-vertical-slice-1-81: Locked Direction

- platform accounts are global identities first, not tenant-owned records with global behavior stapled on afterward.
- tenant membership and tenant authorization are not the same primitive.
- non-public realm access is Account-owned grant state, not an implicit side effect of realm visibility or gameplay membership.
- account export/delete/recovery flows are account-level operations even when they include tenant-scoped consequences; tenant-scoped recovery exports are separate billing-safe routes and do not replace full account export.
- JWT/session claims should expose the documented tenant-role model rather than preserving legacy role names for convenience.
- full account deletion is a global account operation, not a tenant-scoped convenience wrapper.
- full account export is account-wide; narrower tenant-scoped export routes may exist for bounded billing or operator use cases, but they do not redefine the meaning of account export.
- billing or subscription preconditions may block deletion, but they must not collapse the model back into tenant-owned account identity.
- Account owns global account identity, tenant membership, tenant-role authorization, and non-public realm grants; later runtime consumers should reuse those seams rather than inferring equivalent authority locally.

##### source-02-1-6-task-list-global-account-and-tenant-authorization-convergence-vertical-slice-1-81: Acceptance Shape

- `POST /auth/player-bootstrap` establishes account identity without first requiring gameplay membership in the selected tenant.
- public-production membership creation remains explicit and idempotent, while non-public realm entry requires an explicit grant read from Account-owned authority.
- shared auth enforcement no longer treats legacy `admin` as the canonical tenant role name.
- account lifecycle operations no longer require tenant-scoped ownership as a proxy for global account ownership, and account deletion refuses to proceed while the account owns nonterminal tenant subscriptions.
- billing-owned purchased entitlements, pending plan-change metadata, downgrade/cancellation enforcement, quota-bearing runtime entitlement fields, and active-subscription deletion guards are part of the same account lifecycle convergence rather than a separate tenant-role cleanup.
- docs and runtime contracts describe one coherent account/membership/grant model instead of mixed legacy and target-state language.

##### source-02-1-6-task-list-global-account-and-tenant-authorization-convergence-vertical-slice-1-81: Checklist

- [x] Reconcile architecture docs and current service docs around global account identity, tenant roles, and non-public realm grants.
- [x] Replace tenant-bound player bootstrap assumptions with an account-identity-first bootstrap contract.
- [x] Land the canonical tenant-role claim model and remove legacy tenant role-name drift from shared auth enforcement.
- [x] Make account export/delete/recovery flows account-owned rather than tenant-owned, including separate tenant-scoped billing-safe export and active-subscription deletion preconditions.
- [x] Add the first Account-owned non-public realm grant read/write substrate and align connect-token issuance to it.
- [x] Add focused tests covering account bootstrap without preexisting gameplay membership, scoped-role enforcement, account lifecycle boundaries, and non-public realm grant denial.
<!-- /migration-source -->

### source-02-1-7-task-list-auth-session-and-routing-guardrail-follow-through-vertical-slice-1-807

#### Auth, Session, and Routing Guardrail Follow-Through Vertical Slice Task List - Auth and session guardrail follow-through (source lines 1-807)

##### Preserved Source Text: source-02-1-7-task-list-auth-session-and-routing-guardrail-follow-through-vertical-slice-1-807

<!-- migration-source path="design/project-management/vertical-slices/02.1.7-task-list-auth-session-and-routing-guardrail-follow-through-vertical-slice.md" lines="1-807" sha256="7c0f0698ac15ab1db3d769807af6c6ad1c0d4af461b025cf142d8dad2dd92d50" heading-offset="3" -->
#### source-02-1-7-task-list-auth-session-and-routing-guardrail-follow-through-vertical-slice-1-807: Auth, Session, and Routing Guardrail Follow-Through Vertical Slice Task List

##### source-02-1-7-task-list-auth-session-and-routing-guardrail-follow-through-vertical-slice-1-807: Goal and Status

Goal: keep the live auth, session, and routing entry paths fail-closed by landing bounded follow-through guardrails, focused negative-path proof, and canonical helper convergence without reopening the broader login, session, or routing architecture. Status: implementation-complete at the current bounded seam.

This slice is the standing queue for narrow hardening batches such as malformed JWT rejection, blank or inconsistent routing identity rejection, replay payload mismatch guards, and gRPC application-error normalization when those seams are already architecturally decided and only need bounded implementation follow-through.

The point of this slice is not a repo-wide defensive-coding sweep. It exists to let the main thread define one real invariant seam, then complete that seam through a narrow batch of adopters, tests, and docs without turning every hardening pass into an ad hoc scavenger hunt.

##### source-02-1-7-task-list-auth-session-and-routing-guardrail-follow-through-vertical-slice-1-807: Implementation Notes

- 2026-07-13: Continued the bounded Automation positive-identity reader family by converging `PluginRuntimeStateServiceImpl` on canonical positive-ID parsing for the Game Session control-plane `runtimeVersionId`, `ScriptWorkItemServiceImpl` on the same reader for operator-supplied replay work-item IDs, and `ScriptWorkItemExecutionServiceImpl` for persisted work-item tenant identity. Plugin activation now rejects malformed or non-positive runtime-version identity before base-version comparison, release-bundle lookup, or schedule mutation; dead-letter replay rejects malformed/non-positive work-item IDs before repository reads; and a malformed persisted tenant identity terminalizes only its work item before definition lookup instead of aborting the batch. Added focused proof in:
  - `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/PluginRuntimeStateServiceImplTest.java`:
    - `rejectsActivationWhenRuntimeVersionIdIsNonPositiveBeforeReleaseBundleLookup`
  - `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptWorkItemServiceImplTest.java`:
    - `replayRejectsNonPositiveWorkItemIdBeforeRepositoryRead`
  - `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptWorkItemExecutionServiceImplTest.java`:
    - `deadLettersWorkItemWithNonPositiveTenantIdBeforeDefinitionLookup`
- 2026-07-13: Tightened the adjacent schedule due-point identity reader in `ScriptEventIngressServiceImpl`: built-in `onTimerExpire` and `onInterval` payloads now use canonical positive-long parsing instead of truncating arbitrary JSON `Number` values. Fractional due-point identities reject as `invalid_built_in_payload` before handler resolution, pin checks, quotas, or durable queue work. Added focused proof in `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptEventIngressServiceImplTest.java`.
- 2026-06-25: Completed Supertask A/D with shared claim parsing and canonicalized invalid-argument gRPC responses in `AuthTokenInterceptor`, `SessionClaims`, `GameplayHandshakeFilter`, and `GameSessionControlPlaneGrpcService`.
- 2026-06-25: Added focused negative-path proof across auth-session and control-plane seams, including malformed/missing claims, replay identity mismatches, routing/runtime identity failures, and invalid-argument/error-metric assertions.
- 2026-06-25: Completed Supertask B for first-party connect-context reuse by rejecting incomplete routing claims during connect-context parsing, closing invalid first-party websocket bootstrap with `CONNECT_CONTEXT_INVALID`, and classifying incomplete persisted connect context in `PLAY` as `CONNECT_CONTEXT_INVALID` instead of `CONNECT_SCOPE_MISMATCH`.
- 2026-06-29: Added direct negative-path proof in `services/account-service/src/main/java/net/firedevops/firemud/accountservice/service/impl/AccountServiceImpl.java` and `services/account-service/src/test/java/unit/net/firedevops/firemud/accountservice/service/impl/AccountServiceImplTest.java` (`listBootstrapWorldsRejectsNonPositiveBootstrapTokenClaims`, `listBootstrapWorldsRejectsZeroTenantBootstrapTokenClaims`, `issueConnectTokenRejectsNonPositiveConnectScopeClaims`, `issueConnectTokenRejectsBlankWorldSlugConnectScopeClaims`, `issueConnectTokenRejectsZeroPointerVersionInConnectScopeClaims`).
- 2026-07-05: Completed a bounded Supertask B follow-through in `services/common-security`: hardened `GameplaySessionAttestationService` so `requireGameplaySessionMatch` now requires non-positive/blank/malformed numeric claims (`tenantId`, `sessionId`, `accountId`, `characterId`, `gameInstanceId`, `pointerVersion`) to fail-closed as `SESSION_ATTESTATION_INVALID` before identity comparisons. Added focused negative-path tests in `services/common-security/src/test/java/net/firedevops/firemud/common/security/GameplaySessionAttestationServiceTest.java` for zero tenant id, zero pointer version, non-numeric character id, and non-positive expected accountId.
- 2026-07-05: Continued Sequent batch in `services/common-security`: tightened `requireGameplayOrProbeMatch` to use positive-ID semantics for tenant/game-instance/room-instance identity checks, adding focused negative tests for malformed tenant-id claims, non-positive expected game-instance-id, and zero room-instance claims.
- 2026-07-05: For `requireGameplaySessionMatch(...)`, kept `roomInstanceId` as opaque routing identity (string) rather than enforcing positive-id semantics, because current gameplay contracts pass room IDs through `RoomInstanceRef` as text and tests already use `R-*` values. Added focused tests in `services/common-security/src/test/java/net/firedevops/firemud/common/security/GameplaySessionAttestationServiceTest.java` for alphanumeric room match success and room mismatch failure.
- 2026-07-05: Resolved adjacent inconsistency by aligning `requireGameplayOrProbeMatch(...)` to the same textual `roomInstanceId` identity path: room IDs now compare via `requireOptionalEquals` instead of numeric parsing. Added focused probe-path tests for non-numeric room match success (`R-1021`) and mismatched-room failure (`R-1021` vs `abc`).
- 2026-07-05: Bounded batch in `services/game-session-service` converged repeated runtime-target routing validation into `GameplayAdmissionPointerSnapshots.matchesCurrentRuntimeTarget(...)` and adopted it in both `LoginCommandHandler` and `SessionRoutingNormalizationService`. Added focused negative-path tests for non-positive pointer version, blank runtime slugs, non-singular runtime pointer bundles, and exact-match success in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/GameplayAdmissionPointerSnapshotsTest.java`.
- 2026-07-05: Continued bounded batch in `services/game-session-service` by converging the remaining `CommandServiceImpl` inline runtime-target matcher into `GameplayAdmissionPointerSnapshots.matchesCurrentRuntimeTarget(...)` and removing a duplicated local matcher path. Added focused scope-aware pointer snapshot tests to guard `playableStateScope`-aware matching used by command enqueue routing repair.
- 2026-07-05: Continued bounded batch in `services/game-session-service` by converging `GameSessionWebSocketHandler` bootstrap-route identity checks into `GameplayAdmissionPointerSnapshots` (`sameBootstrapRoute` and session-context completeness helper), removing duplicated local comparison logic. Added focused unit proof in `GameplayAdmissionPointerSnapshotsTest` for bundle-completeness mismatch and world/realm drift rejection with case-insensitive slug compatibility retained.
- 2026-07-05: Continued bounded batch in `services/game-session-service` by converging repeated admitted-runtime routing-bundle normalization and fail-closed assertions in `GameLogicClient` and `EntityManagementClient` into `GameplayAdmissionPointerSnapshots.admittedRoutingBundle(...)` plus `hasPartialAdmittedRoutingBundle(...)`. Added focused helper tests proving complete, partial, and absent routing bundle outcomes in `GameplayAdmissionPointerSnapshotsTest`, while preserving existing `IllegalStateException("Incomplete admitted routing bundle...")` failure mode in both clients.
- 2026-07-05: Continued bounded batch in `services/game-session-service` by fail-closing websocket generic bootstrap/connection-read identity handling in `GameSessionWebSocketHandler`; malformed or non-positive `tenantId`/`bootstrapGameInstanceId` now short-circuit bootstrap persistence, and complete inbound routing claims now require exact authority match (`GameplayAdmissionPointerSnapshots.matchesCurrentRuntimeTarget`) before preserving route data. Added focused negative-path tests in `GameSessionWebSocketHandlerTest` for malformed tenant header, non-positive bootstrap game-instance, and mismatched runtime-bundle bootstrap.
- 2026-07-05: Continued bounded batch in `services/spring-cloud-gateway` by extracting a canonical routing-bundle parser shared by trusted TCP-proxy checks and first-party connect-token claims inside `GameplayHandshakeFilter`. Added focused negative tests for zero `pointerVersion` and whitespace-blank trusted proxy `X-World-Slug` path (`CONNECT_TOKEN_REJECTED` and `CONNECT_SCOPE_MISMATCH` respectively), keeping the path bound to auth/session/routing guardrails.
- 2026-07-05: Continued bounded batch in `services/spring-cloud-gateway` by converging trusted-TCP-proxy identity validation for `X-Proxy-Game-Instance-Id` and `X-Proxy-Tenant-Id` into `GameplayHandshakeFilter` helper parsing. Added fail-closed checks so malformed/blank/partial identity pairs are rejected as `CONNECT_SCOPE_MISMATCH`, with focused tests in `GameplayHandshakeFilterTest` for malformed tenant ID, non-positive game-instance ID, and partial proxy identity presence.
- 2026-07-05: Continued bounded batch in `services/spring-cloud-gateway` by lifting trusted-proxy identity validation into `HeaderTrustFilter` (and sharing it via a small helper) so malformed/partial `X-Proxy-Game-Instance-Id` + `X-Proxy-Tenant-Id` pairs are fail-closed at the trust boundary before downstream session routing. Added focused negative-path coverage in `HeaderTrustFilterTest` for malformed tenant ID, non-positive game-instance ID, and partial proxy identity.
- 2026-07-05: Revalidated that same bounded `services/spring-cloud-gateway` batch: `TrustedTcpProxyIdentity` is now canonical for trusted proxy identity checks, used by both `HeaderTrustFilter` and `GameplayHandshakeFilter`, with focused negative-path tests in both seams proving malformed/blank/partial identity rejection (403 for trust-boundary, `CONNECT_SCOPE_MISMATCH` for handshake trust-path) and no widening beyond routing/session entry.
- 2026-07-05: Bounded batch in `services/game-session-service` converged repeated request-text ID parsing in `GameSessionGrpcService` onto shared positive-ID parsing so malformed/non-positive tenant/session/account/game-template values are rejected before routing/auth/session calls continue (`startSession`, `stopSession`, `restartSession`, `enqueueCommand`, `queryState`, `queryAccountPresence`, `getAdmissionPointer`, `toggleFeatureFlag`). Added focused negative tests in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionGrpcServiceTest.java` for malformed/non-positive tenant IDs, owner account IDs, and session IDs.
- 2026-07-05: Bounded batch in `services/game-session-service` converged repeated control-plane request parsing by replacing duplicated `parseTenantId`/`parseGameInstanceId` local parsing with a shared positive-ID parser in `GameSessionControlPlaneGrpcService`. This now enforces malformed and non-positive `tenant_id`/`game_instance_id` rejection (`INVALID_ARGUMENT`) before control-plane service dispatch (`getRuntimeOwnershipStatus`, `setAdmissionPointer`, routing queries, and version-upgrade control paths). Added focused tests in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcServiceTest.java` for malformed tenant IDs, zero tenant IDs, and zero game-instance IDs.
- 2026-07-05: Continued bounded batch in `services/game-session-service` by converging `GameplayCommand` admission parsing/runtime handoff routing-coherence checks in `AutomationGameplayCommandAdmissionSupport` and `DefaultDurableRemoteFollowupExecutionService` into canonical `GameplayAdmissionPointerSnapshots.requireCompleteOrAbsentRoutingBundle(...)` and `normalizeRoutingBundle(...)`. Added focused negative-path proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/AutomationGameplayCommandAdmissionSupportTest.java` for partial routing bundle rejection.
- 2026-07-05: Continued bounded batch in `services/game-session-service` by converging remaining control-plane/runtime-handoff routing-bundle normalization and validation in `GameSessionRemoteControlPlaneService` onto canonical `GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(...)` and `GameplayAdmissionPointerSnapshots.requireCompleteOrAbsentRoutingBundle(...)`, replacing local `normalizeRoutingBundle(...)` and `requireCompleteOrAbsentRoutingBundle(...)` helpers. Added focused negative-path proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcServiceTest.java::listRemoteFollowupsRejectsPartialRoutingFilterWithPointerOnly` for pointer-only partial routing filters.
- 2026-07-05: Continued bounded batch in `services/game-session-service` by converging remaining local command-control-plane routing normalization/checks in `GameSessionCommandControlPlaneService` onto canonical `GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(...)`, removing local duplicate routing tuple helpers. Added focused negative-path proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcServiceTest.java` for partial stored routing bundles:
  - `getGameplayCommandStatusDropsPartialRoutingBundleFromStoredCommand`
  - `getGameplayCommandStatusDropsPartialRoutingBundleFromStoredCommandWhenOnlyPointerVersionPresent`
- 2026-07-05: Continued bounded batch in `services/game-session-service` by converging remaining non-positive `game_instance_id` parsing on the shared `ControlPlaneRequestParser.parsePositiveLong(...)` in command-routing ownership/runtime control-plane seams (`GameSessionControlPlaneGrpcService`, `GameSessionCommandControlPlaneService`, `GameSessionRemoteControlPlaneService`, `GameSessionRuntimeControlPlaneReadService`), adding focused negative-path proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcServiceTest.java` for `getGameplayCommandStatusRejectsZeroGameInstanceId`, `getRuntimeOwnershipStatusRejectsZeroGameInstanceId`, `getGameInstanceRuntimeStateRejectsZeroGameInstanceId`, `listRemoteCommandCoordinatorsRejectsZeroOriginGameInstanceId`, `listRemoteFollowupsRejectsZeroTargetGameInstanceId`, and `scheduleRemoteFollowupRejectsZeroTargetGameInstanceId`.
- 2026-07-05: Continued bounded batch in `services/game-session-service` by converging remaining local command-routing runtime-hand-off routing normalization/checks in `RemoteFollowupRuntimeServiceImpl` onto canonical `GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(...)`, removing local duplicate `RoutingBundle`/normalize helper. Added focused negative-path proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/RemoteFollowupRuntimeServiceImplTest.java`:
  - `scheduleFollowupDropsPartialRoutingBundleWhenOnlyPointerVersionIsProvided`
- 2026-07-05: Continued bounded batch in `services/game-session-service` by converging repeated staging-manifest routing bundle emission in `TickStagingService` to canonical `GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(...)`. Added focused negative-path proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/TickStagingServiceTest.java`:
  - `createBatchDropsPartialRoutingBundleFromGameplayManifestWhenOnlyPointerVersionIsProvided`
- 2026-07-05: Continued bounded batch in `services/game-session-service` by replacing local `CommandServiceImpl` routing-bundle parsing (`RoutingBundle`, `normalizeRoutingBundle`) with canonical `GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(...)` for session enqueue routing repair and bootstrap runtime eligibility checks. Added focused negative-path proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/CommandServiceImplTest.java`:
  - `gameplayCommandRepairsPointerOnlySessionRoutingMetadataFromRuntimeAuthority`
- 2026-07-05: Continued bounded batch in `services/game-session-service` by converging replay-context gameplay identity checks in `RedisMovementEffectIdempotencyService` with shared `SessionContext` helpers (`hasGameplayIdentity`/`sameGameplayIdentity`) and removing duplicated inline gameplay identity checks from `RedisSessionContextService` replay/write/delete paths. Added focused negative-path proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/RedisMovementEffectIdempotencyServiceTest.java`:
  - `applyReturnsConflictWhenGameplayIdentityMismatch`
  - `applyReturnsReplayedContextWhenReplayAlreadyStored`
- 2026-07-05: Continued bounded batch in `services/game-session-service` by converging remaining non-positive `game_instance_id` parsing in `GameSessionOperatorControlPlaneService` (`pauseTicksForScope`, `resumeTicksForScope`) onto shared `ControlPlaneRequestParser.parsePositiveLong(...)`. Added focused negative-path proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcServiceTest.java`:
  - `pauseTicksForScopeRejectsZeroGameInstanceId`
  - `resumeTicksForScopeRejectsZeroGameInstanceId`
- 2026-07-05: Continued bounded batch in `services/game-session-service` by converging remaining local positive-id parsing in `GameSessionAdmissionPointerControlPlaneService` (`listAdmissionPointerAudit`) onto canonical `ControlPlaneRequestParser.parsePositiveLong(...)`, closing the non-positive `tenant_id` gap for the remaining admission-pointer control-plane open path. Added focused negative-path proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcServiceTest.java`:
  - `listAdmissionPointerAuditRejectsZeroTenantId`
- 2026-07-06: Continued bounded batch in `services/game-session-service` by fail-closing incomplete persisted first-party connect selectors in `PlayCommandHandler`. Converged selector completeness onto canonical helpers in `FirstPartyConnectContext` and `SessionContext`, so a missing registry entry plus partial persisted selector now returns `CONNECT_CONTEXT_INVALID` instead of degrading into ordinary realm-selection flow. Added focused negative-path proof in:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/command/text/PlayCommandHandlerTest.java`
    - `firstPartyPlayRejectsIncompletePersistedSelectorWhenRegistryEntryIsMissing`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/SessionContextTest.java`
    - `hasPartialPersistedFirstPartyConnectContextDetectsMissingConnectRequest`
- 2026-07-06: Continued the same first-party selector family in `services/game-session-service` by extracting shared `FirstPartyConnectContextResolution` for `LoginCommandHandler` and `PlayCommandHandler`. Bare `LOGIN` now also fail-closes partial persisted selector state as `CONNECT_CONTEXT_INVALID`, and malformed first-party registry payloads are rejected at the canonical selector-completeness boundary instead of falling through to later scope-comparison failures. Added focused negative-path proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/command/text/LoginCommandHandlerTest.java`:
  - `bareLoginRejectsIncompletePersistedFirstPartyContextWhenRegistryEntryIsMissing`
- 2026-07-06: Continued bounded auth/session routing follow-through in `services/game-session-service` by converging the remaining local `tenantId` / `gameInstanceId` parsing in `TcpProxyServiceImpl` onto `ControlPlaneRequestParser.parsePositiveLong(...)`. `notifyDisconnect` now rejects non-positive proxy bootstrap IDs as `INVALID_ARGUMENT` before repository lookup or suspended-state writes. Added focused negative-path proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/TcpProxyServiceImplTest.java`:
  - `notifyDisconnectRejectsZeroGameInstanceId`
  - `notifyDisconnectRejectsZeroTenantId`
- 2026-07-06: Continued the same command-routing guardrail family in `services/game-session-service` by converging `CommandServiceImpl` session-id validation onto `ControlPlaneRequestParser.parsePositiveLong(...)` and removing the duplicated numeric-only gate in `resolveQueueTarget(...)`. Command enqueue now rejects malformed and non-positive `sessionId` before unverified session lookup, rate limiting, or queue-target resolution. Added focused negative-path proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/CommandServiceImplTest.java`:
  - `enqueueRejectsMalformedSessionIdBeforeSessionLookup`
  - `enqueueRejectsZeroSessionIdBeforeSessionLookup`
- 2026-07-06: Continued bounded disconnect-session guardrails in `services/game-session-service` by converging `TcpProxyServiceImpl` advisory `sessionId` parsing for `recordDisconnected(...)` onto `ControlPlaneRequestParser.parsePositiveLong(...)`. Best-effort disconnect cleanup now ignores zero and negative `sessionId` values instead of projecting bogus transport-loss presence for non-positive sessions. Added focused negative-path proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/TcpProxyServiceImplTest.java`:
  - `disconnectWithoutGameInstanceMetadataIgnoresZeroSessionId`
  - `disconnectWithoutGameInstanceMetadataIgnoresNegativeSessionId`
- 2026-07-06: Continued bounded auth-entry session-id normalization in `services/game-session-service` by preserving precise `sessionId` failure semantics in `LoginCommandHandler`. Parameterized and bare `LOGIN` now distinguish malformed session IDs (`sessionId must be numeric`) from non-positive session IDs (`sessionId must be positive`) while still failing closed before bootstrap lookup or command enqueue. Added focused negative-path proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/command/text/LoginCommandHandlerTest.java`:
  - `invalidSessionIdZeroReturnsInvalidArgument`
  - `invalidSessionIdNegativeReturnsInvalidArgument`
  - `bareLoginInvalidZeroSessionIdReturnsInvalidArgument`
- 2026-07-06: Final bounded `02.1.7` reader-convergence pass in `services/game-session-service` extracted shared `SessionIdParsing` and adopted it in `LoginCommandHandler`, `SessionAuthenticationService`, `SessionRoutingNormalizationService`, and `GameSessionWebSocketHandler`. This keeps malformed vs non-positive `sessionId` semantics canonical at the session-reader boundary instead of repeating local `JwtClaims.requireLong(...)` handling across auth, routing, and websocket entry seams. Added focused proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/SessionIdParsingTest.java`.
- 2026-07-06: Continued bounded routing-identity follow-through in `services/game-session-service` by extracting shared optional positive-id parsing in `PositiveLongParsing` and using it in `GameSessionWebSocketHandler` plus `CommunicationRecipientDeliveryService`. Structured communication recipient views now treat `recipientId` as authoritative when present: malformed or non-positive IDs, and unresolved structured IDs, no longer fall back to `recipientName` lookup. Added focused proof in:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/PositiveLongParsingTest.java`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/CommunicationRecipientDeliveryServiceTest.java`
- 2026-07-06: Continued bounded account-presence routing-reader follow-through in `services/game-session-service` by converging `AccountPresenceQueryServiceImpl`'s duplicated runtime-target match checks for both live `GameplayPresence` and offline `AccountRecentPresenceState` onto canonical `GameplayAdmissionPointerSnapshots.matchesCurrentRuntimeTarget(...)`. Presence query now keeps the same fail-closed incomplete/stale routing behavior while inheriting the canonical case-insensitive world/realm slug comparison already used by sibling auth/session readers. Added focused proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/AccountPresenceQueryServiceImplTest.java`:
  - `queryAccountPresenceTreatsCaseInsensitiveLiveRoutingIdentityAsCurrent`
  - `queryAccountPresenceTreatsCaseInsensitiveRecentRoutingIdentityAsCurrent`
- 2026-07-07: Followed through on the same account-presence routing-reader seam in `services/game-session-service` by converging `AccountPresenceQueryServiceImpl`'s remaining local routing-bundle completeness checks onto canonical `GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(...)`. Live `GameplayPresence` and offline `AccountRecentPresenceState` now reject zero `pointerVersion` and blank routing slugs at the same shared reader boundary before runtime-authority matching, instead of depending on partially duplicated local guards. Added focused negative-path proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/AccountPresenceQueryServiceImplTest.java`:
  - `queryAccountPresenceIgnoresLivePresenceWithZeroPointerVersionAndKeepsOfflineSnapshot`
  - `queryAccountPresenceIgnoresLivePresenceWithBlankWorldSlugAndKeepsOfflineSnapshot`
- 2026-07-07: Continued the adjacent first-party connect-scope route-validation seam in `services/game-session-service` by converging `PlayCommandHandler.validateFirstPartyConnectScope(...)` onto shared `GameplayAdmissionPointerSnapshots.sameBootstrapRoute(...)` logic for first-party selector routing identity. PLAY-side first-party scope validation now shares the same case-insensitive world/realm comparison and fail-closed routing-bundle completeness behavior as sibling bootstrap/session route checks instead of open-coding tenant/game-instance/world/realm/pointer tuple comparison locally. Added focused proof in:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/command/text/PlayCommandHandlerTest.java`
    - `firstPartyPlayRejectsMismatchedConnectScope`
    - `firstPartyPlayRejectsMismatchedWorldSlug`
    - `firstPartyPlayRejectsStalePointerVersion`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/GameplayAdmissionPointerSnapshotsTest.java`
    - `sameBootstrapRouteRejectsFirstPartyConnectContextWhenRoutingIdentityChanges`
    - `sameBootstrapRouteAcceptsFirstPartyConnectContextWithCaseInsensitiveRoutingIdentity`
- 2026-07-07: Continued the deferred Telnet connection-read routing seam in `services/tcp-proxy-service` by tightening `TelnetRoutingBundle.normalize(...)` into the canonical bootstrap-routing reader for hidden proxy defaults and session bootstrap state. `TelnetSessionContext.bootstrap(...)` and `TelnetServerHandler` default gateway bootstrap now fail closed on malformed or non-positive `pointerVersion` instead of treating any non-blank tuple as a complete routing bundle and forwarding stale `X-World-Slug` / `X-Realm-Slug` / `X-Pointer-Version` headers. Added focused proof in:
  - `services/tcp-proxy-service/src/test/java/unit/net/firedevops/firemud/tcpproxy/telnet/TelnetRoutingBundleTest.java`
    - `normalizeReturnsNullWhenPointerVersionIsMalformed`
    - `normalizeReturnsNullWhenPointerVersionIsNonPositive`
  - `services/tcp-proxy-service/src/test/java/unit/net/firedevops/firemud/tcpproxy/telnet/TelnetSessionContextTest.java`
    - `bootstrapDropsRoutingBundleWhenPointerVersionIsNonPositive`
- 2026-07-07: Continued bounded auth/session reader convergence in `services/game-session-service` by replacing `GameSessionGrpcService.queryAccountPresence(...)`'s remaining inline repeated `accountIds` parsing with a canonical positive-id reader. Presence queries now reject malformed and non-positive repeated `accountId` values as `INVALID_ARGUMENT` before `AccountPresenceQueryService` executes, instead of silently dropping bad entries through `Long.parseLong(...).filter(id > 0)`. Added focused proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionGrpcServiceTest.java`:
  - `queryAccountPresenceRejectsMalformedAccountId`
  - `queryAccountPresenceRejectsZeroAccountId`
- 2026-07-07: Completed the same deferred Telnet connection-read routing seam in `services/tcp-proxy-service` by proving the server-side gateway bootstrap path drops non-positive hidden default routing tuples before header emission. Added focused proof in `services/tcp-proxy-service/src/test/java/unit/net/firedevops/firemud/tcpproxy/telnet/TelnetServerHandlerTest.java`:
  - `nonPositiveDefaultRoutingBundleIsDroppedBeforeGatewayBootstrap`
- 2026-07-07: Continued the adjacent sessionless gameplay-identity reader family in `services/game-session-service` by converging `GameplayCharacterIdParser` onto canonical optional positive-id parsing. Automation admission and durable gameplay execution now derive fallback `characterId` from `targetEntityId` through the same `PositiveLongParsing` boundary already used by sibling auth/session readers, instead of maintaining a local `Long.parseLong(...)` + `> 0` helper. Added focused proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameplayCharacterIdParserTest.java`:
  - `parseGameplayCharacterIdReturnsNullForBlankMalformedAndNonPositiveText`
  - `parseGameplayCharacterIdReturnsPositiveCharacterIdFromText`
  - `parseGameplayCharacterIdPrefersExplicitPositiveCharacterId`
  - `parseGameplayCharacterIdFallsBackToTextWhenExplicitCharacterIdIsNonPositive`
- 2026-07-07: Continued the neighboring login auth-response reader seam in `services/game-session-service` by converging `LoginCommandHandler.parseAccountId(...)` onto canonical optional positive-id parsing. LOGIN now treats malformed account-service `accountId` payloads through the same `PositiveLongParsing` boundary already used by sibling auth/session readers instead of maintaining a local `JwtClaims.requireLong(...)` try/catch helper. Added focused proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/command/text/LoginCommandHandlerTest.java`:
  - `invalidAccountIdMalformedReturnsInvalidAccount`
- 2026-07-07: Continued the adjacent Redis gameplay-presence index-reader seam in `services/game-session-service` by converging `RedisGameplayPresenceService`'s session-index parsing onto canonical optional positive-id parsing. Corrupt or non-numeric session-id set members in game-instance and account presence indexes are now pruned fail-closed instead of throwing from raw `Long.parseLong(...)` and aborting the whole presence read. Added focused proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/RedisGameplayPresenceServiceTest.java`:
  - `listConnectedByGameInstancePrunesMalformedSessionIndexEntry`
  - `listConnectedByAccountIdsPrunesMalformedSessionIndexEntry`
- 2026-07-07: Continued the adjacent friend identity/ownership reader seam across `services/game-session-service` and `services/social-groups-service` by converging malformed/non-positive friend account id parsing onto existing canonical positive-id helpers. `FriendsCommandHandler.resolveTarget(...)` now rejects malformed resolved `accountId` values from character lookup through `PositiveLongParsing` instead of trusting raw `Long.parseLong(...)`, and `FriendServiceImpl` now fails closed on malformed/non-positive required presence `accountId` payloads while still nulling invalid visible optional `gameInstanceId` / `characterId` values through shared `RequestIdValidation` instead of raw parsing. Added focused proof in:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/command/text/FriendsCommandHandlerTest.java`
    - `friendsMutationRejectsCharacterTargetWithMalformedResolvedAccountId`
  - `services/social-groups-service/src/test/java/unit/net/firedevops/firemud/socialgroups/service/impl/FriendServiceImplTest.java`
    - `listFriendsRejectsMalformedPresenceAccountId`
    - `listFriendPresenceRejectsMalformedPresenceAccountId`
    - `listFriendPresenceDropsNonPositiveVisibleIds`
- 2026-07-07: Continued the neighboring command-routing ownership reader seam in `services/game-session-service` by converging remaining text-command numeric parsing onto `PositiveLongParsing`. `FriendsCommandHandler` now uses the same canonical positive-id reader for roster `friendAccountId` / `friendLinkId` payloads and numeric friend target tokens, failing closed on malformed returned `friendAccountId` while still treating malformed optional `friendLinkId` as absent instead of raw `Long.parseLong(...)`, and `PlayCommandHandler.resolveCharacterId(...)` now rejects malformed resolved character ids from entity lookup as unavailable identity instead of silently falling back to a different fresh-entry character binding. Added focused proof in:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/command/text/FriendsCommandHandlerTest.java`
    - `friendsListTreatsMalformedFriendLinkIdAsAbsent`
    - `friendsListRejectsMalformedFriendAccountIdPayload`
    - `friendsRemoveByOrdinalRejectsMalformedReturnedFriendAccountId`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/command/text/PlayCommandHandlerTest.java`
    - `playRejectsMalformedResolvedCharacterId`
- 2026-07-06: Continued bounded presence-routing follow-through in `services/game-session-service` by converging gameplay-region binding checks onto canonical `SessionContext.hasGameplayRegionBinding()`. `DefaultGameplayPresenceLifecycleService` and `RedisAccountRecentPresenceService` now agree that partial gameplay shells are not authoritative enough to reuse live region presence, so recent-presence disconnect snapshots fail closed to normalized context routing instead of inheriting stale live `GameplayPresence` world/realm/pointer data. Added focused proof in:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/SessionContextTest.java`
    - `hasGameplayRegionBindingRequiresGameInstanceCharacterAndRoom`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/RedisAccountRecentPresenceServiceTest.java`
    - `recordDisconnectIgnoresLivePresenceWhenContextOnlyRetainsPartialGameplayShell`
- 2026-07-06: Continued the same auth/session routing guardrail family in `services/game-session-service` by converging the repeated scope-agnostic "any gameplay binding present" check onto canonical `SessionContext.hasGameplayBinding()`. `LoginCommandHandler`, `PlayCommandHandler`, and `SessionRoutingNormalizationService` now share the same cleanup/normalization boundary for partial gameplay shells instead of open-coding the same `gameInstanceId` / `characterId` / `roomInstanceId` check. Added focused proof in:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/SessionContextTest.java`
    - `hasGameplayBindingTreatsPartialGameplayShellsAsBound`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/SessionRoutingNormalizationServiceTest.java`
    - `normalizeProjectedContextClearsPartialGameplayShellWhenAdmissionPointerCannotMatch`
- 2026-07-06: Continued the adjacent full-gameplay-identity guardrail seam in `services/game-session-service` by converging repeated `gameInstanceId > 0 && characterId > 0` checks onto canonical `SessionContext.hasGameplayIdentity()`. `LogoutCommandHandler` and `GameSessionWebSocketHandler` screen-buffer/reconnect paths now share the same requirement that partial gameplay shells must not be treated as authoritative gameplay identity for logout-side gameplay cleanup or reconnect buffer replay. Added focused proof in:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/SessionContextTest.java`
    - `hasGameplayIdentityRequiresPositiveGameInstanceAndCharacter`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/command/text/LogoutCommandHandlerTest.java`
    - `logoutSkipsGameplayCleanupForPartialGameplayShell`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/websocket/GameSessionWebSocketHandlerTest.java`
    - `handleMessageDoesNotAppendScreenBufferForPartialGameplayIdentityShell`
    - `handleMessageDoesNotReplayReconnectBufferForPartialGameplayIdentityShell`
- 2026-07-06: Continued bounded auth/session reader convergence in `services/game-session-service` by extracting shared elevated-role classification for gameplay presence into `GameplayPresenceRoleClassifier`. `InMemoryGameplayPresenceService` and `RedisGameplayPresenceService` now share the same JWT claim reader and fail-closed invalid-token behavior, so absent or malformed JWTs degrade consistently to `GameplayPresenceRole.PLAYER` instead of relying on duplicated local parsing. Added focused proof in:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/InMemoryGameplayPresenceServiceTest.java`
    - `classifiesInvalidJwtAsPlayer`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/RedisGameplayPresenceServiceTest.java`
    - `registerConnectedClassifiesInvalidJwtAsPlayer`
- 2026-07-06: Continued bounded signed-token guardrail convergence in `services/account-service` by extracting shared bootstrap/connect-scope token claim parsing in `AccountServiceImpl`. `requireBootstrapContext(...)` and `requireConnectScopeContext(...)` now share one fail-closed reader for blank token rejection, JWT parse failure, and audience validation before seam-specific claim extraction continues. Added focused proof in `services/account-service/src/test/java/unit/net/firedevops/firemud/accountservice/service/impl/AccountServiceImplTest.java`:
  - `listBootstrapWorldsRejectsBootstrapTokenWithoutAudience`
  - `issueConnectTokenRejectsBlankConnectScopeId`
- 2026-07-06: Continued the adjacent runtime-target reader seam in `services/account-service` by extracting canonical admission-pointer/runtime-realm parsing into shared `RuntimeRealmTarget` readers inside `AccountServiceImpl`. `listBootstrapRealms(...)`, `listBootstrapCharacters(...)`, connect-token issuance, public-production membership creation, and admissibility checks now share one fail-closed reader for malformed or non-positive `tenantId` / `gameInstanceId`, non-positive `pointerVersion`, and blank `worldSlug` / `realmSlug` instead of trusting raw `GameplayRealm` / `GameplayAdmissionPointer` fields at each call site. Added focused proof in `services/account-service/src/test/java/unit/net/firedevops/firemud/accountservice/service/impl/AccountServiceImplTest.java`:
  - `listBootstrapRealmsDropsRealmWithMalformedTenantId`
  - `listBootstrapCharactersRejectsAdmissionPointerWithMalformedGameInstanceId`
- 2026-07-06: Continued bounded gameplay-stage reader convergence in `services/game-session-service` by replacing `TextCommandInterpreter`'s duplicated `roomInstanceId` text checks with canonical `SessionContext.hasGameplayRegionBinding()`. Gameplay-stage command admission and `WHEN_GAMEPLAY` prompt eligibility now share the same fail-closed rule that partial gameplay shells are not gameplay-ready unless `gameInstanceId`, `characterId`, and `roomInstanceId` are all present. Added focused proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/command/text/TextCommandInterpreterTest.java`:
  - `gameplayWithRoomOnlyPartialShellStillReturnsPlayRequired`
- 2026-07-06: Continued bounded auth/access claim-reader convergence in `services/game-session-service` by replacing `GameSessionGrpcService`'s local current-account claim parsing with canonical `common-security` `SessionContext.isCurrentAccount(...)`. Start/stop/query ownership checks now share the same fail-closed malformed-account-id behavior already used by sibling auth surfaces instead of maintaining a duplicate `Long.parseLong(...)` path in the gRPC entry service. Added focused proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionGrpcServiceTest.java`:
  - `stopSessionRejectsMalformedCurrentAccountClaim`
- 2026-07-06: Continued the same auth/access current-account convergence family in `services/social-groups-service` by replacing `SocialAccessGuard`'s local current-account claim parsing with canonical `common-security` `SessionContext.isCurrentAccount(...)`. Friend/mail/voice access guards now inherit the same fail-closed malformed-account-id behavior used by other auth surfaces instead of keeping a duplicate `Long.parseLong(...)` branch in the social account guard. Added focused proof in `services/social-groups-service/src/test/java/unit/net/firedevops/firemud/socialgroups/security/SocialAccessGuardTest.java`:
  - `hasAccountAccessRejectsMalformedCurrentAccountClaim`
- 2026-07-06: Continued bounded auth/access request-reader convergence in `services/social-groups-service` by extracting canonical positive-id parsing for `SocialGroupsGrpcService` account-owned entrypoints. Send-message, guild, friend-roster, presence-policy, and mail RPCs now share one fail-closed reader for malformed or non-positive `tenantId`, account-owner ids, and optional target ids (`recipientId`, `guildId`, `cityId`, `recipientAccountId`) instead of open-coding raw `Long.parseLong(...)` / `Long.valueOf(...)` at each call site. Added focused proof in `services/social-groups-service/src/test/java/unit/net/firedevops/firemud/socialgroups/service/impl/SocialGroupsGrpcServiceTest.java`:
  - `sendMessageRejectsZeroRecipientIdBeforeDispatch`
  - `getFriendPresencePolicyRejectsMalformedTenantIdBeforeAccessCheck`
  - `sendMailRejectsZeroRecipientAccountIdBeforeDispatch`
- 2026-07-06: Continued bounded runtime lifecycle request-reader convergence in `services/world-management-service` by extracting canonical positive-id parsing for world-instance lifecycle and upgrade-validation RPCs in `WorldManagementGrpcService`. Prepare/activate/fail/get/terminate world-instance flows and upgrade validation now reject malformed or non-positive `tenantId`, `gameInstanceId`, `gameTemplateId`, `versionId`, `releaseBundleId`, `sourceGameInstanceId`, and `targetVersionId` with field-specific `INVALID_ARGUMENT` errors instead of generic raw `Long.parseLong(...)` handling. Added focused proof in `services/world-management-service/src/test/java/unit/net/firedevops/firemud/worldmanagement/service/impl/WorldManagementGrpcServiceTest.java`:
  - `prepareWorldInstanceRejectsZeroTenantId`
  - `activatePreparedWorldInstanceRejectsMalformedGameInstanceId`
  - `validateWorldUpgradeMappingsRejectsZeroTargetVersionId`
- 2026-07-06: Continued bounded gameplay actor-reader convergence in `services/entity-management-service` by extracting canonical positive-id parsing for `tenantId` and `characterId` in `EntityManagementGrpcService` gameplay actor seams. `queryInventory`, `queryActorState`, `applyActorCondition`, and `listEquipment` now share one fail-closed actor-scope reader, so malformed or non-positive actor ids reject as field-specific `INVALID_ARGUMENT` before inventory, actor-state, replay, or equipment service dispatch instead of relying on repeated raw `Long.parseLong(...)` branches. Added focused proof in `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/service/impl/EntityManagementGrpcServiceTest.java`:
  - `queryInventoryRejectsZeroCharacterIdBeforeInventoryLookup`
  - `queryActorStateRejectsMalformedCharacterIdBeforeActorStateLookup`
  - `applyActorConditionRejectsZeroTenantIdBeforeReplayExecution`
  - `listEquipmentRejectsMalformedCharacterIdBeforeEquipmentLookup`
- 2026-07-06: Continued the adjacent gameplay item/container reader family in `services/entity-management-service` by extending the same canonical positive-id parsing to `itemId`, `itemInstanceId`, `containerInstanceId`, and room-ground tenant reads. `wearEquipment`, `removeEquipment`, `listContainerContents`, `putItemIntoContainer`, `takeItemFromContainer`, `listRoomGroundInventory`, `pickupItemFromRoom`, and `dropItemToRoom` now fail closed on malformed or non-positive item/container ids before equipment, container, inventory, or replay dispatch instead of open-coding more raw `Long.parseLong(...)` branches. Added focused proof in `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/service/impl/EntityManagementGrpcServiceTest.java`:
  - `wearEquipmentRejectsZeroItemIdBeforeMutation`
  - `listContainerContentsRejectsZeroContainerInstanceIdBeforeLookup`
  - `putItemIntoContainerRejectsMalformedItemInstanceIdBeforeMutation`
  - `listRoomGroundInventoryRejectsZeroTenantIdBeforeLookup`
- 2026-07-07: Continued the same replay payload mismatch family in `services/entity-management-service` by hoisting replay-sensitive request parsing ahead of `EntityMutationEffectReplayService.execute(...)`. `applyActorCondition` now parses `expiresAt` before replay lookup, matching the adjacent `pickupItemFromRoom` and `dropItemToRoom` item-instance guardrail so malformed request shape cannot be hidden by a stored replay hit. Added focused proof in `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/service/impl/EntityManagementGrpcServiceTest.java`:
  - `applyActorConditionRejectsMalformedExpiresAtBeforeReplayExecution`
- 2026-07-07: Continued the same replay payload mismatch family in `services/entity-management-service` by hoisting positive-quantity validation ahead of replay lookup for replay-backed inventory/container mutations. `putItemIntoContainer`, `takeItemFromContainer`, `pickupItemFromRoom`, and `dropItemToRoom` now reject non-positive `quantity` before `EntityMutationEffectReplayService.execute(...)`, so replay hits cannot mask `quantity must be positive` request failures. Added focused proof in `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/service/impl/EntityManagementGrpcServiceTest.java`:
  - `putItemIntoContainerRejectsZeroQuantityBeforeReplayLookup`
  - `takeItemFromContainerRejectsZeroQuantityBeforeReplayLookup`
  - `pickupItemFromRoomRejectsZeroQuantityBeforeReplayLookup`
  - `dropItemToRoomRejectsZeroQuantityBeforeReplayLookup`
- 2026-07-07: Continued the same replay payload mismatch family in `services/entity-management-service` by hoisting required-text validation ahead of replay lookup for replay-backed gameplay mutations. `applyActorCondition` now normalizes `conditionKey` and `sourceType`, and `removeEquipment` now normalizes `slot`, before `EntityMutationEffectReplayService.execute(...)`, so replay hits cannot mask downstream `must be specified` failures from actor-condition or equipment services. Added focused proof in `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/service/impl/EntityManagementGrpcServiceTest.java`:
  - `applyActorConditionRejectsBlankConditionKeyBeforeReplayExecution`
  - `applyActorConditionRejectsBlankSourceTypeBeforeReplayExecution`
  - `removeEquipmentRejectsBlankSlotBeforeReplayLookup`
- 2026-07-07: Continued bounded logging-admin control-plane reader convergence by adopting shared positive-ID response parsing in the remaining auth/session/routing-adjacent read surfaces: `GameplayCommandStatusServiceImpl`, `GameSessionPinServiceImpl`, `AdmissionPointerServiceImpl`, and `TickRemediationServiceImpl`. These readers now fail closed when control-plane responses return malformed or non-positive numeric IDs instead of mixing raw `Long.parseLong(...)` acceptance with local mismatch checks. Added focused proof in:
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/GameplayCommandStatusServiceImplTest.java`
    - `getGameplayCommandStatusRejectsZeroSessionIdInResponse`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/GameSessionPinServiceImplTest.java`
    - `getGameSessionPinConvergenceRejectsZeroGameInstanceIdInResponse`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/AdmissionPointerServiceImplTest.java`
    - `getRuntimeStateRejectsZeroControlPlaneGameInstanceId`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/TickRemediationServiceImplTest.java`
    - `getRuntimeOwnershipStatusRejectsZeroGameInstanceIdForRegionScope`
- 2026-07-08: Continued the same replay payload mismatch family in `services/entity-management-service` by hoisting optional positive `containerInstanceId` parsing ahead of `EntityMutationEffectReplayService.execute(...)` in `pickupItemFromRoom` and `dropItemToRoom`. Replay-backed room-ground mutations now reject malformed or non-positive container-instance identity before replay lookup instead of letting stored effect replays acknowledge malformed request shape. Added focused proof in `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/service/impl/EntityManagementGrpcServiceTest.java`:
  - `pickupItemFromRoomRejectsMalformedContainerInstanceIdBeforeReplayLookup`
  - `dropItemToRoomRejectsMalformedContainerInstanceIdBeforeReplayLookup`
- 2026-07-08: Continued bounded REST error-envelope convergence in `services/logging-admin-service`, `services/automation-scripting-service`, `services/entity-management-service`, and `services/social-groups-service` by importing shared `GlobalExceptionHandler` into each Spring Boot application. These REST seams now route malformed enum/body/query binding failures and propagated `ResponseStatusException` app errors through the canonical `ApiResponse<ErrorDetail>` envelope instead of framework-default error JSON. Tightened `services/common-web-support/src/main/java/net/firedevops/firemud/common/GlobalExceptionHandler.java` to normalize `BindException` numeric type mismatches into field-specific `INVALID_ARGUMENT` messages. Added focused proof in:
  - `services/common-web-support/src/test/java/net/firedevops/firemud/common/GlobalExceptionHandlerTest.java`
    - `handleBindExceptionNormalizesNumericTypeMismatch`
    - `handleResponseStatusPreservesCanonicalNotFoundEnvelope`
  - `services/logging-admin-service/src/test/java/integration/net/firedevops/firemud/loggingadmin/LoggingAdminApplicationIntegrationTest.java`
    - `remoteFollowupsRejectMalformedPointerVersionWithInvalidArgumentEnvelope`
  - `services/automation-scripting-service/src/test/java/integration/net/firedevops/firemud/automationscripting/AutomationScriptingServiceApplicationIntegrationTest.java`
    - `adjustReputationRejectsMalformedPlayableStateScopeWithInvalidArgumentEnvelope`
  - `services/entity-management-service/src/test/java/integration/net/firedevops/firemud/entitymanagement/EntityManagementApplicationIntegrationTest.java`
    - `listCharactersRejectsMalformedPlayableStateScopeWithInvalidArgumentEnvelope`
  - `services/social-groups-service/src/test/java/integration/net/firedevops/firemud/socialgroups/SocialGroupsApplicationIntegrationTest.java`
    - `friendRosterRejectsMalformedFilterAsInvalidArgument`
- 2026-07-10: Continued the same REST ingress guardrail family in `services/logging-admin-service` by converging remaining REST body identity fields onto bean-validation positive-id readers. `ToggleFeatureFlagRequest`, `QueryLogsRequest`, `ApplyModerationActionRequest`, `PrepareVersionUpgradeRequest`, `TickRemediationRequest`, and `CreateReportRequest` now reject non-positive tenant/account/session/version ids at the controller boundary instead of relying on later service behavior or persistence. Added focused negative-path proof in:
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/FeatureFlagControllerTest.java`
    - `toggleRejectsZeroTenantIdBeforeDispatch`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/LogQueryControllerTest.java`
    - `queryRejectsZeroTenantIdBeforeDispatch`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/ModerationActionControllerTest.java`
    - `applyRejectsZeroSessionIdBeforeDispatch`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/AdmissionPointerControllerTest.java`
    - `prepareVersionUpgradeRejectsZeroTargetVersionIdBeforeDispatch`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/TickRemediationControllerTest.java`
    - `pauseRejectsZeroTenantIdBeforeDispatch`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/ReportControllerTest.java`
    - `createRejectsZeroTargetAccountIdBeforeDispatch`
- 2026-07-10: Continued the adjacent public account REST guardrail family in `services/account-service` by converging direct body-owned ids onto bean-validation positive-id readers where controllers dispatch raw DTO values. `CreateAccountRequest`, `LoginRequest`, and `AccountIdRequest` now reject non-positive tenant/account ids before account creation, login/bootstrap issuance, or email-verification dispatch instead of relying on later service handling. Added focused negative-path proof in:
  - `services/account-service/src/test/java/unit/net/firedevops/firemud/accountservice/controller/AccountControllerTest.java`
    - `createAccountRejectsZeroTenantIdBeforeDispatch`
  - `services/account-service/src/test/java/unit/net/firedevops/firemud/accountservice/controller/AuthControllerTest.java`
    - `loginRejectsZeroTenantIdBeforeDispatch`
    - `requestEmailVerificationRejectsZeroAccountIdBeforeDispatch`
- 2026-07-10: Continued the remaining privileged REST body-id family in `services/game-session-service` and `services/automation-scripting-service` by converging direct operator/bootstrap request ids onto bean-validation positive-id readers where those controllers still forwarded raw DTO values. `StartSessionRequest` now rejects non-positive tenant/game-template/owner-account ids before session bootstrap dispatch, and `CreateFormationRequest` now rejects non-positive tenant/leader-npc ids before formation creation dispatch. Added focused negative-path proof in:
  - `services/game-session-service/src/test/java/integration/net/firedevops/firemud/gamesession/controller/GameInstanceControllerTest.java`
    - `startSessionRejectsZeroOwnerAccountIdBeforeDispatch`
  - `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/controller/NpcFormationControllerTest.java`
    - `createFormationRejectsZeroLeaderNpcIdBeforeDispatch`
- 2026-07-10: Continued the adjacent gameplay room-read guardrail family in `services/entity-management-service` by converging `RoomEntityService` onto the typed tenant scope already owned by `EntityManagementGrpcService`. `listRoomEntities` no longer validates room tenant scope in gRPC, then downgrades back to raw tenant text for a second hidden parse inside `RoomEntityServiceImpl`; the room-entity reader now accepts the canonical positive tenant id directly and fails closed on non-positive tenant scope before any repository lookup. Added focused proof in:
  - `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/service/impl/RoomEntityServiceImplTest.java`
    - `listEntitiesRejectsZeroTenantIdBeforeRepositoryLookup`
  - `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/service/impl/EntityManagementGrpcServiceTest.java`
    - `listRoomEntitiesAllowsInternalServiceReadPath`
- 2026-07-10: Continued the adjacent remote control-plane routing-filter guardrail family in `services/game-session-service` by making remote-control-plane `pointer_version` request presence explicit for coordinator list, followup schedule, followup list, and followup-result list RPCs. `GameSessionRemoteControlPlaneService` now distinguishes absent routing bundles from explicitly provided non-positive `pointerVersion`, so direct gRPC callers can no longer have `pointerVersion=0` collapse silently to an unscoped query or schedule. Added focused proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcServiceTest.java`:
  - `listRemoteCommandCoordinatorsRejectsZeroPointerVersionBeforeDispatch`
  - `scheduleRemoteFollowupRejectsZeroPointerVersionBeforeDispatch`
  - `listRemoteFollowupsRejectsZeroPointerVersionBeforeDispatch`
  - `listRemoteFollowupResultsRejectsZeroPointerVersionBeforeDispatch`
- 2026-07-07: Continued the adjacent operator session-identity guardrail family in `services/common-security` and `services/logging-admin-service` by exposing a canonical validated current-account reader on `SessionContext` and adopting it in operator mutation/remediation actor-principal paths. `AdmissionPointerServiceImpl` and `TickRemediationServiceImpl` now fail closed when a present `accountId` claim is malformed or non-positive instead of forwarding raw claim text to control-plane writes or silently dropping audit attribution to `null`. Added focused proof in:
  - `services/common-security/src/test/java/net/firedevops/firemud/common/security/SessionContextTenantAccessTest.java`
    - `currentAccountIdOrNullReturnsParsedAccountId`
    - `currentAccountIdOrNullReturnsNullWhenClaimMissing`
    - `currentAccountIdOrNullRejectsMalformedOrNonPositiveClaim`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/AdmissionPointerServiceImplTest.java`
    - `setPointerRejectsMalformedCurrentAccountClaimBeforeMutation`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/TickRemediationServiceImplTest.java`
    - `pauseRejectsMalformedCurrentAccountClaimBeforeDispatchAndAudit`
- 2026-07-07: Continued the same current-account claim guardrail family in `services/automation-scripting-service` by adopting `SessionContext.currentAccountIdOrNull()` for dry-run principal derivation in `ScriptEventIngressServiceImpl`. Dry-run quota keys now fail closed when a present `accountId` claim is malformed or non-positive instead of silently deriving synthetic principals like `account:not-a-long`. Added focused proof in `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptEventIngressServiceImplTest.java`:
  - `dryRunRejectsMalformedCurrentAccountClaimBeforeQuotaLookup`
- 2026-07-07: Continued the same current-account claim propagation family in `services/common-security` by adopting `SessionContext.currentAccountIdOrNull()` for downstream bearer creation in `GrpcClientAuth`. Attached internal gRPC clients now fail closed when a present `accountId` claim is malformed or non-positive instead of minting downstream bearer tokens with invalid `accountId` / subject text. Added focused proof in `services/common-security/src/test/java/net/firedevops/firemud/common/security/GrpcClientAuthTest.java`:
  - `attachRejectsMalformedCurrentAccountClaim`
- 2026-07-07: Continued the adjacent signed-token actor invariant family by extracting canonical positive `sub`/`accountId` subject-match reading into `services/common-security/src/main/java/net/firedevops/firemud/common/security/JwtClaims.java` and adopting it in `services/account-service/src/main/java/net/firedevops/firemud/accountservice/service/impl/AccountServiceImpl.java`, `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/FirstPartyConnectContextService.java`, and `services/spring-cloud-gateway/src/main/java/net/firedevops/firemud/springcloudgateway/filter/GameplayHandshakeFilter.java`. Bootstrap tokens, first-party connect-context tokens, and gameplay connect tokens now share one canonical fail-closed reader for malformed/non-positive actor claims and subject/account mismatch instead of keeping three open-coded variants. Added focused proof in `services/common-security/src/test/java/unit/net/firedevops/firemud/common/security/JwtClaimsTest.java`:
  - `requireSignedActorAccountIdRejectsMalformedOrMismatchedAccountClaims`
- 2026-07-07: Continued the same signed-token auth/routing family by extracting canonical gameplay-routing claim reading into `services/common-security/src/main/java/net/firedevops/firemud/common/security/JwtClaims.java` and adopting it in `services/account-service/src/main/java/net/firedevops/firemud/accountservice/service/impl/AccountServiceImpl.java`, `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/FirstPartyConnectContextService.java`, and `services/spring-cloud-gateway/src/main/java/net/firedevops/firemud/springcloudgateway/filter/GameplayHandshakeFilter.java`. Connect-scope tokens, first-party connect-context tokens, and gameplay connect tokens now share one canonical fail-closed reader for positive `tenantId`, `worldSlug`, `realmSlug`, `gameInstanceId`, and `pointerVersion` instead of keeping three parallel claim readers. Added focused proof in `services/common-security/src/test/java/unit/net/firedevops/firemud/common/security/JwtClaimsTest.java`:
  - `requireSignedGameplayRoutingClaimsRejectsMalformedOrIncompleteRoutingBundleClaims`
- 2026-07-07: Continued bounded auth/session role-reader convergence in `services/common-security` and `services/game-session-service` by replacing `GameplayPresenceRoleClassifier`'s local JWT role parsing with canonical `SessionClaims` gameplay-elevation checks. WHO/presence role classification now shares one fail-closed session-role reader for tenant-scoped moderator/tenant-admin and global god/platform-admin semantics instead of keeping a divergent hand-rolled parser that missed moderator elevation and would drift from auth claim normalization. Added focused proof in:
  - `services/common-security/src/test/java/net/firedevops/firemud/common/security/SessionClaimsTest.java`
    - `hasGameplayElevatedRoleRecognizesGlobalGodAndScopedModerator`
    - `hasGameplayElevatedRoleIgnoresUnrelatedTenantScopes`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameplayPresenceRoleClassifierTest.java`
    - `classifyRoleReturnsGodForScopedModeratorRole`
    - `classifyRoleReturnsGodForGlobalGodRole`
    - `classifyRoleReturnsPlayerWhenScopedRolesClaimIsMalformed`
- 2026-07-07: Continued bounded session-entry request-reader convergence in `services/game-session-service` by adopting canonical `SessionIdParsing` for the remaining required `sessionId` entrypoints in `CommandServiceImpl`, `GameSessionGrpcService`, and the advisory disconnect path in `TcpProxyServiceImpl`. Command enqueue, stop/restart session, and query-state RPCs now share the same fail-closed malformed/non-positive `sessionId` reader already used by login/session-auth flows instead of mixing the generic control-plane parser into session-owned seams, while advisory proxy disconnect cleanup still drops malformed session ids without escalating transport cleanup into an app error. Added focused proof in:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/SessionIdParsingTest.java`
    - `requireRejectsMalformedAndNonPositiveSessionIdsWithCanonicalMessages`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/CommandServiceImplTest.java`
    - `enqueueRejectsMalformedSessionIdBeforeSessionLookup`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionGrpcServiceTest.java`
    - `stopSessionRejectsMalformedSessionId`
    - `restartSessionRejectsMalformedSessionId`
    - `enqueueCommandRejectsMalformedSessionId`
    - `queryStateRejectsMalformedSessionId`
- 2026-07-07: Continued bounded command-routing timeline validation in `services/game-session-service` by extending `ControlPlaneRequestParser` to validate already-parsed positive routing fields and adopting it in `AutomationGameplayCommandAdmissionSupport`, `GameSessionCommandControlPlaneService`, and `RemoteFollowupRuntimeServiceImpl`. Automation admission, gameplay-command status lookup, and remote-followup scheduling now share the same fail-closed positive `tenant_id`, `game_instance_id`, and `region_epoch` guardrail instead of keeping parallel inline checks across adjacent runtime handoff seams. Added focused proof in:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/AutomationGameplayCommandAdmissionSupportTest.java`
    - `rejectsAutomationCommandWhenRegionEpochIsZero`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcServiceTest.java`
    - `getGameplayCommandStatusRejectsZeroRegionEpoch`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/RemoteFollowupRuntimeServiceImplTest.java`
    - `scheduleFollowupRejectsZeroOriginRegionEpoch`
- 2026-07-07: Continued bounded operator session-route reader convergence in `services/game-session-service` by adopting canonical `SessionIdParsing` for the remaining privileged REST session-id seams in `GameInstanceController`, `SessionRoleController`, and `EffectiveSettingsController`, and by extending `PositiveLongParsing` with a canonical optional positive query-reader for `EffectiveSettingsController`'s synthetic routing scope fields. Stop/restart-session, refresh-role, and effective-settings lookups now reject malformed or non-positive `sessionId`, `tenantId`, `gameInstanceId`, and `bootstrapGameInstanceId` with the same explicit `INVALID_ARGUMENT` envelope used by the converged session/gRPC entrypaths instead of relying on MVC path binding failures or silently accepting invalid synthetic scope values. Imported `GlobalExceptionHandler` into `GameSessionServiceApplication` so those controller-side `IllegalArgumentException` failures resolve through the shared REST error envelope instead of surfacing as servlet errors. Added focused proof in:
  - `services/game-session-service/src/test/java/integration/net/firedevops/firemud/gamesession/controller/GameInstanceControllerTest.java`
    - `stopSessionRejectsMalformedSessionIdBeforeDispatch`
    - `restartSessionRejectsZeroSessionIdBeforeDispatch`
  - `services/game-session-service/src/test/java/integration/net/firedevops/firemud/gamesession/controller/SessionRoleControllerTest.java`
    - `refreshRolesRejectsMalformedSessionIdBeforeDispatch`
  - `services/game-session-service/src/test/java/integration/net/firedevops/firemud/gamesession/controller/EffectiveSettingsControllerIntegrationTest.java`
    - `effectiveSettingsRejectsMalformedSessionIdBeforeLookup`
    - `effectiveSettingsRejectsZeroSessionIdBeforeLookup`
    - `effectiveSettingsRejectsMalformedTenantIdBeforeSyntheticResolution`
    - `effectiveSettingsRejectsZeroGameInstanceIdBeforeSyntheticResolution`
- 2026-07-07: Continued bounded room-routing guardrail follow-through in `services/entity-management-service` by extracting a canonical gameplay room-scope reader for room-read entrypoints. `listRoomGroundInventory` and `listRoomEntities` now normalize positive `tenantId` plus required `gameInstanceId` / `roomInstanceId` before attestation or lookup, and `listRoomEntities` now fail-closes mismatched top-level `tenantId` versus `roomInstance.tenantId` instead of silently preferring one side. Added focused proof in `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/service/impl/EntityManagementGrpcServiceTest.java`:
  - `listRoomGroundInventoryRejectsBlankRoomInstanceIdBeforeAttestation`
  - `listRoomEntitiesRejectsMismatchedTenantIdsBeforeAttestationAndLookup`
- 2026-07-07: Continued bounded entity-management request-reader convergence by replacing the remaining raw `Long.parseLong(...)` tenant/entity/account readers in `EntityManagementGrpcService` with canonical positive-ID parsing. `listCharactersByAccount`, `findCharacterByName`, `createCharacter`, `updateEntity`, and `cleanupRuntimeInstance` now reject malformed or non-positive `tenantId`, `accountId`, and `entityId` with field-specific `INVALID_ARGUMENT` errors before attestation or downstream service dispatch. Added focused proof in `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/service/impl/EntityManagementGrpcServiceTest.java`:
  - `listCharactersRejectsZeroTenantIdBeforeLookup`
  - `findCharacterByNameRejectsZeroTenantIdBeforeAttestation`
  - `createCharacterRejectsZeroAccountIdBeforeCreate`
  - `updateEntityRejectsZeroEntityIdBeforeUpdate`
  - `cleanupRuntimeInstanceRejectsZeroTenantIdBeforeCleanup`
- 2026-07-07: Continued the adjacent room-target request-reader family in `services/world-management-service` by extracting a canonical gameplay room-scope reader for `getRoom` and `getRoomSnapshot`. Both room-read RPCs now normalize positive `tenantId`, `gameInstanceId`, and `roomInstanceId` before gameplay attestation or room lookup, and reject mismatched top-level `tenantId` versus `roomInstance.tenantId` instead of silently preferring one side. Added focused proof in `services/world-management-service/src/test/java/unit/net/firedevops/firemud/worldmanagement/service/impl/WorldManagementGrpcServiceTest.java`:
  - `getRoomRejectsMismatchedTenantIdsBeforeAttestationAndLookup`
  - `getRoomSnapshotRejectsBlankGameInstanceIdBeforeAttestationAndLookup`
- 2026-07-07: Continued bounded world-management REST tenant-reader convergence by adopting canonical `RequestIdValidation` for the remaining `tenantId` query readers in `RegionController` and `GenerationRuleController`. Region listing/move and generation-rule listing now reject malformed or non-positive `tenantId` through the same `INVALID_ARGUMENT` envelope already used by converged world-management ingress seams instead of relying on Spring MVC `Long` binding failures. Imported `GlobalExceptionHandler` into `WorldManagementServiceApplication` so controller-side `IllegalArgumentException` failures resolve through the shared REST error envelope in the full app path. Added focused proof in:
- 2026-07-08: Continued the same REST ingress guardrail family by closing the binding/validation regression introduced by app-wide `GlobalExceptionHandler` imports and by converging the remaining social friend ordinal path readers onto canonical request-text parsing. `GlobalExceptionHandler` now maps Spring MVC binding/validation failures (`MethodArgumentNotValidException`, `MethodArgumentTypeMismatchException`, `HttpMessageNotReadableException`, and adjacent request-binding errors) back to `400 INVALID_ARGUMENT` instead of falling through to `500 INTERNAL_ERROR`, and `social-groups` friend ordinal routes now parse `ordinal` text through shared positive-int request validation before access checks or service dispatch. Added focused proof in:
  - `services/account-service/src/test/java/integration/net/firedevops/firemud/accountservice/AccountApplicationIntegrationTest.java`
    - `linkExternalRejectsInvalidBodyWithInvalidArgumentEnvelope`
  - `services/world-management-service/src/test/java/integration/net/firedevops/firemud/worldmanagement/WorldManagementServiceApplicationIntegrationTest.java`
    - `moveRegionRejectsMalformedShardIdWithInvalidArgumentEnvelope`
    - `saveRuleRejectsMalformedBodyWithInvalidArgumentEnvelope`
  - `services/social-groups-service/src/test/java/unit/net/firedevops/firemud/socialgroups/controller/FriendControllerTest.java`
    - `getFriendByOrdinalRejectsMalformedOrdinalBeforeAccessCheck`
    - `removeFriendByOrdinalRejectsMalformedOrdinalBeforeAccessCheck`
  - `services/world-management-service/src/test/java/unit/net/firedevops/firemud/worldmanagement/controller/RegionControllerTest.java`
    - `listRejectsMalformedTenantIdBeforeDispatch`
    - `moveRejectsZeroTenantIdBeforeDispatch`
  - `services/world-management-service/src/test/java/unit/net/firedevops/firemud/worldmanagement/controller/GenerationRuleControllerTest.java`
    - `listRejectsMalformedTenantIdBeforeDispatch`
    - `listRejectsZeroTenantIdBeforeDispatch`
  - `services/world-management-service/src/test/java/integration/net/firedevops/firemud/worldmanagement/WorldManagementServiceApplicationIntegrationTest.java`
    - `listRegionsRejectsMalformedTenantIdWithInvalidArgumentEnvelope`
- 2026-07-09: Continued the same world-management REST ingress family by extracting shared `WorldManagementRequestReaders` and converging the remaining typed `RegionController.moveRegion` region-id path reader onto canonical request text. Region move now rejects malformed or non-positive `id` before tenant access or `RegionService` dispatch instead of relying on Spring MVC `Long` binding, while `GenerationRuleController` and `RegionController` now share one canonical tenant-id reader helper for this ingress family. Added focused proof in `services/world-management-service/src/test/java/unit/net/firedevops/firemud/worldmanagement/controller/RegionControllerTest.java`:
  - `moveRejectsMalformedRegionIdBeforeDispatch`
  - `moveRejectsZeroRegionIdBeforeDispatch`
- 2026-07-08: Continued the adjacent logging-admin REST ingress guardrail family by converging the remaining tenant-scoped numeric path readers onto canonical request-text parsing before tenant access checks or downstream dispatch. `AdmissionPointerController`, `GameSessionPinController`, `GameplayCommandController`, `RemoteCommandCoordinatorController`, `RemoteFollowupController`, `RemoteFollowupResultController`, and `TickRemediationController` now read `tenantId`, `gameInstanceId`, `sourceGameInstanceId`, and `targetVersionId` through shared `RequestIdValidation`-backed controller helpers instead of relying on Spring MVC primitive binding or auth checks against unvalidated values. Added focused proof in:
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/AdmissionPointerControllerTest.java`
    - `auditRejectsMalformedTenantIdBeforeDispatch`
    - `getRuntimeStateRejectsZeroGameInstanceIdBeforeDispatch`
    - `validateInstanceCutoverCompatibilityRejectsMalformedTargetVersionIdBeforeDispatch`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/GameSessionPinControllerTest.java`
    - `getPinnedScriptPatchVersionRejectsMalformedTenantIdBeforeDispatch`
    - `getGameSessionPinConvergenceRejectsZeroGameInstanceIdBeforeDispatch`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/GameplayCommandControllerTest.java`
    - `getGameplayCommandStatusRejectsZeroTenantIdBeforeDispatch`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/RemoteCommandCoordinatorControllerTest.java`
    - `getRemoteCommandCoordinatorRejectsMalformedTenantIdBeforeDispatch`
    - `listRemoteCommandCoordinatorsRejectsZeroTenantIdBeforeDispatch`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/RemoteFollowupControllerTest.java`
    - `getRemoteFollowupRejectsMalformedTenantIdBeforeDispatch`
    - `listRemoteFollowupsRejectsZeroTenantIdBeforeDispatch`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/RemoteFollowupResultControllerTest.java`
    - `getRemoteFollowupResultRejectsMalformedTenantIdBeforeDispatch`
    - `listRemoteFollowupResultsRejectsZeroTenantIdBeforeDispatch`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/TickRemediationControllerTest.java`
    - `getRuntimeOwnershipStatusRejectsMalformedTenantIdBeforeDispatch`
- 2026-07-09: Continued the same logging-admin REST ingress family by converging the remaining saga step path reader onto `LoggingAdminRequestReaders`. `SagaDashboardController.listSteps` now parses saga instance `id` through canonical request text before dashboard dispatch instead of relying on Spring MVC `Long` binding. Added focused proof in `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/SagaDashboardControllerTest.java`:
  - `listStepsRejectsMalformedIdBeforeDispatch`
  - `listStepsRejectsZeroIdBeforeDispatch`
- 2026-07-09: Continued the same automation controller reader family by extracting shared `AutomationScriptingRequestReaders` and removing the last framework-bound request binders from `FactionController`. Faction reputation adjustment now parses `playableStateScope` and `delta` through the same canonical controller bad-request path already used for tenant/character/faction ids, and `NpcFormationController` now reuses the same helper for positive ID reads instead of duplicating local try/catch blocks. Added focused proof in `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/controller/FactionControllerTest.java`:
  - `adjustReputationRejectsMalformedPlayableStateScopeBeforeDispatch`
  - `adjustReputationRejectsMalformedDeltaBeforeDispatch`
- 2026-07-09: Continued the same logging-admin runtime-target reader family by converging `TickRemediationController` onto a shared optional-positive game-instance reader. Tick-remediation status, pause, and resume REST paths now reject malformed or non-positive `gameInstanceId` through the canonical `INVALID_ARGUMENT` envelope before `TickRemediationService` dispatch instead of forwarding raw runtime-target text to the control plane. Added focused proof in `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/TickRemediationControllerTest.java`:
  - `getRuntimeOwnershipStatusRejectsMalformedGameInstanceIdBeforeDispatch`
  - `pauseRejectsZeroGameInstanceIdBeforeDispatch`
- 2026-07-09: Continued the adjacent logging-admin tick-remediation service seam by converging the remaining request-side `gameInstanceId` validation in `TickRemediationServiceImpl` onto canonical positive-id parsing. Service-level runtime ownership, pause, and resume entrypoints now reject malformed or non-positive game-instance scope before control-plane dispatch or audit flow, and the ownership mismatch check now compares against the validated request id instead of a local raw parser. Added focused proof in `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/TickRemediationServiceImplTest.java`:
  - `getRuntimeOwnershipStatusRejectsMalformedGameInstanceIdBeforeDispatch`
  - `pauseRejectsZeroGameInstanceIdBeforeDispatchAndAudit`
- 2026-07-09: Continued the adjacent automation runtime-routing helper family by tightening `RoutingBundleSupport` around canonical pointer-version parsing. Routing-bundle normalization now rejects malformed or non-positive `pointerVersion` text at the shared reader boundary instead of accepting arbitrary text and failing later when a downstream consumer parses it. Added focused proof in `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/RoutingBundleSupportTest.java`:
  - `normalizeRejectsMalformedPointerVersionText`
  - `normalizeRejectsNonPositivePointerVersionText`
- 2026-07-09: Continued the same automation runtime-routing family in `ScriptEventIngressServiceImpl` by validating complete gameplay routing bundles before dedupe lookup. Gameplay-trigger admission now rejects malformed or non-positive `pointerVersion` at the ingress reader boundary instead of letting routing normalization fail later inside `findExisting(...)`. Added focused proof in `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptEventIngressServiceImplTest.java`:
  - `rejectsMalformedGameplayPointerVersionBeforeLookupAndAuditWrite`
  - `rejectsZeroGameplayPointerVersionBeforeLookupAndAuditWrite`
- 2026-07-09: Continued the adjacent tenant-ingress request-reader family in `services/game-design-service` by extracting shared tenant access parsing for `AssetController` and `GameTemplateController` and importing `GlobalExceptionHandler` into the app. Asset upload and template create/list now reject malformed or non-positive `tenantId` through the canonical `INVALID_ARGUMENT` envelope before tenant-access or service dispatch instead of relying on raw `Long.valueOf(...)` parsing and default servlet error shaping. Added focused proof in `services/game-design-service/src/test/java/integration/net/firedevops/firemud/gamedesign/GameDesignApplicationIntegrationTest.java`:
  - `listTemplatesRejectsMalformedTenantIdWithInvalidArgumentEnvelope`
  - `createTemplateRejectsZeroTenantIdWithInvalidArgumentEnvelope`
  - `uploadAssetRejectsMalformedTenantIdWithInvalidArgumentEnvelope`
- 2026-07-09: Continued the same world-management REST ingress family by converging the last typed numeric binder in `RegionController` onto `WorldManagementRequestReaders`. Region move now parses `shardId` through the same canonical request-text reader as `tenantId` and `id`, rejecting malformed or non-positive shard values before tenant-access or service dispatch instead of relying on MVC `Integer` binding. Added focused proof in `services/world-management-service/src/test/java/unit/net/firedevops/firemud/worldmanagement/controller/RegionControllerTest.java`:
  - `moveRejectsMalformedShardIdBeforeDispatch`
  - `moveRejectsZeroShardIdBeforeDispatch`
- 2026-07-09: Continued the adjacent game-logic operator scope-reader family by extracting canonical optional-positive query parsing for `EffectiveCommunicationSettingsController` and importing `GlobalExceptionHandler` into the app. Effective communication settings inspection now rejects malformed or non-positive optional `tenantId` / `gameInstanceId` query values through the canonical `INVALID_ARGUMENT` envelope before resolver dispatch instead of relying on MVC `Long` binding and default servlet error shaping. Added focused proof in `services/game-logic-service/src/test/java/integration/net/firedevops/firemud/gamelogic/GameLogicApplicationIntegrationTest.java`:
  - `effectiveCommunicationSettingsRejectsMalformedTenantIdWithInvalidArgumentEnvelope`
  - `effectiveCommunicationSettingsRejectsZeroGameInstanceIdWithInvalidArgumentEnvelope`
- 2026-07-09: Continued the adjacent social account-scope request-body family by tightening positive-id validation on the remaining account-scoped REST bodies in `services/social-groups-service`. Chat send, mail send, voice-token issuance, friend add, and friend visibility update now reject non-positive `tenantId`, `accountId`, `senderAccountId`, `recipientAccountId`, and `friendAccountId` through bean-validation `INVALID_ARGUMENT` envelopes before access checks or service dispatch instead of trusting `@NotNull` body fields at the controller boundary. Added focused proof in:
  - `services/social-groups-service/src/test/java/unit/net/firedevops/firemud/socialgroups/controller/ChatControllerTest.java`
    - `sendMessageRejectsZeroTenantIdBeforeAccessCheckAndDispatch`
  - `services/social-groups-service/src/test/java/unit/net/firedevops/firemud/socialgroups/controller/MailControllerTest.java`
    - `sendMailRejectsZeroSenderAccountIdBeforeAccessCheckAndDispatch`
  - `services/social-groups-service/src/test/java/unit/net/firedevops/firemud/socialgroups/controller/VoiceChatControllerTest.java`
    - `createTokenRejectsZeroAccountIdBeforeAccessCheckAndDispatch`
  - `services/social-groups-service/src/test/java/unit/net/firedevops/firemud/socialgroups/controller/FriendControllerTest.java`
    - `addFriendRejectsZeroTenantIdBeforeAccessCheck`
- 2026-07-09: Continued the adjacent social guild request-body family by tightening positive-id validation on the remaining guild REST mutation bodies in `services/social-groups-service`. Guild create/alliance/member/storage/role-update entrypaths now reject non-positive `tenantId`, `guildId`, `allyGuildId`, `ownerAccountId`, and member `accountId` through bean-validation `INVALID_ARGUMENT` envelopes before tenant-access or guild-service dispatch instead of trusting `@NotNull` body fields at the controller boundary. Added focused proof in `services/social-groups-service/src/test/java/unit/net/firedevops/firemud/socialgroups/controller/GuildControllerTest.java`:
  - `createGuildRejectsZeroTenantIdBeforeDispatch`
  - `addMemberRejectsZeroGuildIdBeforeDispatch`
- 2026-07-09: Continued the adjacent entity gameplay item-body family by tightening positive-id validation on the remaining equipment/inventory REST mutation bodies in `services/entity-management-service`. Inventory add and equipment wear now reject non-positive body `itemId` through bean-validation `INVALID_ARGUMENT` envelopes before tenant-access or service dispatch instead of forwarding raw boxed ids from `@Valid` request records. Added focused proof in:
  - `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/controller/InventoryControllerTest.java`
    - `addRejectsZeroItemIdBeforeDispatch`
  - `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/controller/EquipmentControllerTest.java`
    - `wearRejectsZeroItemIdBeforeDispatch`
- 2026-07-07: Continued bounded social-account reader convergence in `services/social-groups-service` by adopting canonical `RequestIdValidation` for the remaining friend roster/account-scope request-text readers in `FriendController`. Friend lookup, removal, summary, presence, and visibility read paths now reject malformed or non-positive `tenantId`, `accountId`, and `friendAccountId` through the controller’s canonical `INVALID_ARGUMENT` envelope before `SocialAccessGuard` or `FriendService` dispatch instead of relying on Spring MVC primitive binding failures. Added focused proof in:
  - `services/social-groups-service/src/test/java/unit/net/firedevops/firemud/socialgroups/controller/FriendControllerTest.java`
    - `removeFriendRejectsMalformedFriendAccountIdBeforeAccessCheck`
    - `getFriendPresencePolicyRejectsMalformedAccountIdBeforeAccessCheck`
    - `listFriendsRejectsZeroTenantIdBeforeAccessCheck`
  - `services/social-groups-service/src/test/java/integration/net/firedevops/firemud/socialgroups/SocialGroupsApplicationIntegrationTest.java`
    - `friendRosterRejectsMalformedTenantIdAsInvalidArgument`
- 2026-07-07: Continued bounded automation controller reader convergence in `services/automation-scripting-service` by adopting canonical `RequestIdValidation` for the remaining numeric request-text readers in `FactionController` and `NpcFormationController`. Faction reputation adjustment and formation membership REST paths now reject malformed or non-positive `tenantId`, `characterId`, `factionId`, `formationId`, and `npcId` through explicit `INVALID_ARGUMENT` envelopes before controller dispatch instead of relying on Spring MVC numeric binding failures. Added focused proof in:
  - `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/controller/FactionControllerTest.java`
    - `adjustReputationRejectsMalformedTenantIdBeforeDispatch`
    - `adjustReputationRejectsZeroCharacterIdBeforeDispatch`
  - `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/controller/NpcFormationControllerTest.java`
    - `addMemberRejectsMalformedFormationIdBeforeDispatch`
    - `listMembersRejectsZeroTenantIdBeforeDispatch`
- 2026-07-07: Continued bounded entity-management gameplay path-reader convergence by adopting canonical `RequestIdValidation` for the remaining tenant/account/character/friend REST path and body readers in `CharacterController` and gameplay `FriendController`. Character listing and gameplay friend list/add/remove now reject malformed or non-positive `tenantId`, `accountId`, `characterId`, and `friendId` through explicit `INVALID_ARGUMENT` envelopes before tenant-access or friend-service dispatch instead of relying on Spring MVC numeric binding failures or downstream gameplay-scope lookup failures. Added focused proof in:
  - `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/controller/CharacterControllerTest.java`
    - `listRejectsMalformedTenantIdBeforeDispatch`
    - `listRejectsZeroAccountIdBeforeDispatch`
  - `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/controller/FriendControllerTest.java`
    - `listRejectsMalformedTenantIdBeforeDispatch`
    - `addRejectsZeroFriendIdBeforeDispatch`
    - `removeRejectsZeroFriendIdBeforeDispatch`
- 2026-07-09: Continued the same entity-management REST ingress family by extracting shared `EntityManagementRequestReaders` and converging the remaining gameplay inventory/equipment path readers onto it. `InventoryController` and `EquipmentController` now parse tenant, character, and item path ids through canonical request-text readers before tenant-access or service dispatch instead of relying on Spring MVC `Long` binding, and `CharacterController` / gameplay `FriendController` now reuse the same helper so the service keeps one canonical `INVALID_ARGUMENT` envelope for account/character-scoped REST ingress. Added focused proof in:
  - `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/controller/InventoryControllerTest.java`
    - `listRejectsMalformedTenantIdBeforeDispatch`
    - `removeRejectsZeroItemIdBeforeDispatch`
  - `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/controller/EquipmentControllerTest.java`
    - `listRejectsMalformedTenantIdBeforeDispatch`
    - `wearRejectsZeroCharacterIdBeforeDispatch`
- 2026-07-09: Continued the same entity-management controller-reader cleanup by converging `CraftingController.get` onto `EntityManagementRequestReaders`, removing the last typed numeric path binder in that controller package. Crafting recipe reads now reject malformed or non-positive `id` through the same canonical `INVALID_ARGUMENT` envelope before `CraftingService` dispatch. Added focused proof in `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/controller/CraftingControllerTest.java`:
  - `getRejectsMalformedIdBeforeDispatch`
  - `getRejectsZeroIdBeforeDispatch`
- 2026-07-07: Continued bounded account-runtime request-reader convergence in `services/account-service` by replacing raw tenant/account ID parsing in `AccountGrpcService` runtime membership/grant/entitlement entrypoints with canonical positive-ID readers. `getTenantMembershipForRuntime`, `getRealmAccessGrantForRuntime`, `ensurePublicProductionPlayerMembership`, and `getTenantEntitlementsForRuntime` now reject malformed or non-positive `tenantId` / `accountId` as `INVALID_ARGUMENT` before account-service lookup instead of falling through the shared `NOT_FOUND` path. Added focused proof in `services/account-service/src/test/java/unit/net/firedevops/firemud/accountservice/service/impl/AccountGrpcServiceTest.java`:
  - `getTenantMembershipForRuntimeRejectsZeroTenantIdBeforeLookup`
  - `getRealmAccessGrantForRuntimeRejectsZeroAccountIdBeforeLookup`
  - `ensurePublicProductionPlayerMembershipRejectsZeroTenantIdBeforeLookup`
  - `getTenantEntitlementsForRuntimeRejectsZeroTenantIdBeforeLookup`
- 2026-07-07: Continued the same `AccountGrpcService` request-reader family by converging the remaining account/tenant/account-data entrypoints onto the same canonical positive-ID helper. `createAccount`, `authenticate`, `getProfile`, `updateProfile`, `exportAccount`, `exportTenantData`, `deleteAccount`, `linkExternalAccount`, and `requestEmailVerification` now reject malformed or non-positive `tenantId` / `accountId` as `INVALID_ARGUMENT` before service dispatch instead of drifting across `UNAUTHENTICATED`, `NOT_FOUND`, or generic exception paths. Added focused proof in `services/account-service/src/test/java/unit/net/firedevops/firemud/accountservice/service/impl/AccountGrpcServiceTest.java`:
  - `createAccountRejectsZeroTenantIdBeforeCreate`
  - `authenticateRejectsZeroTenantIdBeforeAuthentication`
  - `getProfileRejectsZeroAccountIdBeforeLookup`
  - `updateProfileRejectsZeroTenantIdBeforeUpdate`
  - `exportAccountRejectsZeroAccountIdBeforeLookup`
  - `exportTenantDataRejectsZeroTenantIdBeforeLookup`
  - `deleteAccountRejectsZeroAccountIdBeforeDelete`
  - `linkExternalAccountRejectsZeroTenantIdBeforeLink`
  - `requestEmailVerificationRejectsZeroAccountIdBeforeDispatch`
- 2026-07-07: Continued the adjacent REST account/profile ingress guardrail family in `services/account-service` by adopting canonical `RequestIdValidation` for the remaining `accountId` / `tenantId` path, query, and body readers in `AccountController` and `ProfileController`. Export-account, export-tenant-data, delete-account, link-external-account, get-profile, and update-profile now reject malformed or non-positive REST IDs through the same `INVALID_ARGUMENT` envelope already used by converged account-service ingress seams instead of relying on Spring MVC `Long` binding failures, self-account access shortcuts, or inconsistent servlet error shaping. Imported `GlobalExceptionHandler` into `AccountServiceApplication` so controller-side `IllegalArgumentException` failures resolve through the shared REST error envelope in the full app path. Added focused proof in:
  - `services/account-service/src/test/java/unit/net/firedevops/firemud/accountservice/controller/AccountControllerTest.java`
    - `exportAccountRejectsMalformedAccountIdBeforeDispatch`
    - `exportTenantDataRejectsZeroTenantIdBeforeDispatch`
    - `deleteAccountRejectsZeroAccountIdBeforeDispatch`
    - `linkExternalRejectsZeroTenantIdBeforeDispatch`
  - `services/account-service/src/test/java/unit/net/firedevops/firemud/accountservice/controller/ProfileControllerTest.java`
    - `getProfileRejectsMalformedAccountIdBeforeDispatch`
    - `getProfileRejectsZeroTenantIdBeforeDispatch`
    - `updateProfileRejectsZeroTenantIdBeforeDispatch`
    - `updateProfileRejectsZeroAccountIdBeforeDispatch`
  - `services/account-service/src/test/java/integration/net/firedevops/firemud/accountservice/AccountApplicationIntegrationTest.java`
    - `exportAccountRejectsMalformedAccountIdWithInvalidArgumentEnvelope`
    - `linkExternalRejectsZeroTenantIdWithInvalidArgumentEnvelope`
    - `updateProfileRejectsZeroTenantIdWithInvalidArgumentEnvelope`
- 2026-07-09: Continued the same account REST ingress family by extracting shared `AccountRequestReaders` and converging the remaining privileged internal-runtime readers onto it. `InternalRuntimeController` now normalizes positive body/query `accountId` and `tenantId` before membership or realm-grant dispatch instead of relying on `@NotNull` DTO fields or Spring MVC `Long` binding, and `AccountController` / `ProfileController` now reuse the same helper so the service keeps one canonical positive-id reader family. Added focused proof in `services/account-service/src/test/java/unit/net/firedevops/firemud/accountservice/controller/InternalRuntimeControllerTest.java`:
  - `ensurePublicProductionMembershipRejectsZeroTenantIdBeforeDispatch`
  - `grantRealmAccessRejectsZeroAccountIdBeforeDispatch`
  - `revokeRealmAccessRejectsMalformedAccountIdBeforeDispatch`
- 2026-07-07: Continued the adjacent account-service monetization and notification request-reader family by adopting the same canonical positive-ID parsing in `NotificationGrpcService`, `PaymentGrpcService`, and `VirtualCurrencyGrpcService`. `sendNotification`, `createPaymentIntent`, `createSubscription`, `createDonation`, `refundPayment`, `getBalance`, `addCurrency`, and `spendCurrency` now reject malformed or non-positive `tenantId`, `accountId`, and `paymentId` as `INVALID_ARGUMENT` before downstream dispatch instead of relying on raw `Long.valueOf(...)` parsing at each entrypoint. Added focused proof in:
  - `services/account-service/src/test/java/unit/net/firedevops/firemud/accountservice/service/impl/NotificationGrpcServiceTest.java`
    - `sendNotificationRejectsZeroTenantIdBeforeDispatch`
  - `services/account-service/src/test/java/unit/net/firedevops/firemud/accountservice/service/impl/PaymentGrpcServiceTest.java`
    - `createPaymentIntentRejectsZeroTenantIdBeforeCreate`
    - `createSubscriptionRejectsZeroAccountIdBeforeCreate`
    - `createDonationRejectsZeroTenantIdBeforeCreate`
    - `refundPaymentRejectsZeroPaymentIdBeforeRefund`
  - `services/account-service/src/test/java/unit/net/firedevops/firemud/accountservice/service/impl/VirtualCurrencyGrpcServiceTest.java`
    - `getBalanceRejectsZeroTenantIdBeforeLookup`
    - `addCurrencyRejectsZeroAccountIdBeforeMutation`
    - `spendCurrencyRejectsZeroTenantIdBeforeSpend`
- 2026-07-07: Continued the adjacent logging-admin request-reader family by adopting canonical positive-ID parsing in `LoggingAdminGrpcService` and `ReportGrpcService`, including optional account-id fields when they are present. `toggleFeatureFlag`, `queryLogs`, `createLogEvent`, `applyModerationAction`, `evaluateModerationPolicy`, and `createReport` now reject malformed or non-positive `tenantId`, `accountId`, `sessionId`, `reporterAccountId`, and present `targetAccountId` as `INVALID_ARGUMENT` before downstream dispatch instead of relying on raw `Long.valueOf(...)` / `Long.parseLong(...)` parsing. Added focused proof in:
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/LoggingAdminGrpcServiceAuthTest.java`
    - `toggleFeatureFlagRejectsZeroTenantIdBeforeDispatch`
    - `queryLogsRejectsZeroTenantIdBeforeDispatch`
    - `createLogEventRejectsZeroAccountIdBeforeDispatch`
    - `applyModerationActionRejectsZeroSessionIdBeforeDispatch`
    - `evaluateModerationPolicyRejectsZeroAccountIdBeforeDispatch`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/ReportGrpcServiceTest.java`
    - `createReportRejectsZeroReporterAccountIdBeforeDispatch`
    - `createReportRejectsZeroTargetAccountIdBeforeDispatch`
- 2026-07-07: Continued the adjacent automation-scripting request-reader family by adopting canonical positive-ID parsing in `AutomationScriptingGrpcService` for NPC formation and script-update entrypoints. `createFormation`, `addFormationMember`, `listFormationMembers`, and `updateScript` now reject malformed or non-positive `tenantId`, `leaderNpcId`, `formationId`, and `npcId` as `INVALID_ARGUMENT` before downstream dispatch instead of relying on raw `Long.parseLong(...)` parsing inside each RPC. Added focused proof in:
  - `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/NpcFormationGrpcServiceTest.java`
    - `createFormationRejectsZeroTenantIdBeforeCreate`
    - `createFormationRejectsZeroLeaderNpcIdBeforeCreate`
    - `addFormationMemberRejectsZeroTenantIdBeforeDispatch`
    - `addFormationMemberRejectsZeroNpcIdBeforeDispatch`
    - `listFormationMembersRejectsZeroTenantIdBeforeLookup`
    - `listFormationMembersRejectsZeroFormationIdBeforeLookup`
  - `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/AutomationScriptingGrpcServiceTest.java`
    - `updateScriptRejectsZeroTenantIdBeforeUpdate`
- 2026-07-07: Continued the adjacent control-plane request-reader family in `services/game-session-service` by removing a redundant raw `tenantId` reparse in `GameSessionGrpcService.toggleFeatureFlag`. The RPC now reuses the same canonical positive tenant-id reader for both tenant access control and DTO construction, so malformed or non-positive `tenantId` values fail closed before feature-flag dispatch instead of depending on a second inline parse path. Added focused proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionGrpcServiceTest.java`:
  - `toggleFeatureFlagRejectsZeroTenantIdBeforeDispatch`
- 2026-07-07: Continued the remaining design/runtime upgrade request-reader family in `services/entity-management-service` and `services/world-management-service` by replacing the last raw numeric parsing in `validateEntityUpgradeMappings` and `applyWorldDesignMutation` with canonical positive-ID readers. Those RPCs now reject malformed or non-positive `tenantId`, `sourceGameInstanceId`, `targetVersionId`, and `versionId` with field-specific `INVALID_ARGUMENT` errors before downstream validation or mutation dispatch instead of relying on raw `Long.parseLong(...)` and generic invalid-id handling. Added focused proof in:
  - `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/service/impl/EntityManagementGrpcServiceTest.java`
    - `validateEntityUpgradeMappingsRejectsZeroSourceGameInstanceIdBeforeValidation`
  - `services/world-management-service/src/test/java/unit/net/firedevops/firemud/worldmanagement/service/impl/WorldManagementGrpcServiceTest.java`
    - `applyWorldDesignMutationRejectsZeroVersionIdBeforeMutation`
- 2026-07-07: Continued the adjacent session-launch response-reader family in `services/game-session-service` by converging `GameInstanceServiceImpl` launch/world-activation numeric readers onto canonical positive-ID parsing. Resolved launch descriptors and prepared world-instance responses now reject malformed or non-positive `tenantId` and `gameInstanceId` before world activation or gameplay state writes instead of trusting raw upstream string IDs. Added focused proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameInstanceServiceImplTest.java`:
  - `startSessionRejectsNonPositiveLaunchDescriptorTenantIdBeforeWorldPreparation`
  - `startSessionRejectsMalformedPreparedWorldInstanceGameInstanceIdBeforeActivation`
- 2026-07-07: Continued bounded session-ingress guardrail follow-through in `services/game-session-service` by converging Redis-backed IP counter reads onto a canonical fail-closed reader in `IpConnectionLimiterImpl`. `canAccept(...)` and replacement admission now share one `currentConnectionCount(...)` path, so malformed `ipconn:*` counter values no longer throw or drift across admission paths: fresh accepts fail closed, different-session replacements stay rejected, and same-session reservation transfers still remain allowed through the existing `sessionip:*` ownership check. Added focused proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/IpConnectionLimiterImplTest.java`:
  - `canAcceptRejectsMalformedCounterValue`
  - `canAcceptReplacementRejectsMalformedCounterValueForDifferentSessionIp`
  - `canAcceptReplacementAllowsMalformedCounterValueForSameSessionIp`
- 2026-07-07: Continued bounded command-routing ownership follow-through in `services/game-session-service` by extracting shared `GameplayCharacterIdParser` for tolerant target-character parsing across automation admission and durable execution. `AutomationGameplayCommandAdmissionSupport` and `DefaultDurableGameplayCommandExecutionService` now share one reader for `targetEntityId`/`characterId`, so blank, malformed, and non-positive target ids normalize to `null` consistently instead of drifting across local inline parsing, and the dead duplicate helper in `GameSessionCommandControlPlaneService` is removed. Added focused proof in:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/AutomationGameplayCommandAdmissionSupportTest.java`
    - `acceptsAutomationCommandWithMalformedTargetEntityAndLeavesCharacterIdUnset`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/DefaultDurableGameplayCommandExecutionServiceTest.java`
    - `executeRejectsSessionlessCommandWhenTargetEntityIdIsMalformed`
    - `executeRejectsSessionlessCommandWhenTargetEntityIdIsNonPositive`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcServiceTest.java`
    - `enqueueAutomationCommandDerivesCharacterIdFromNumericTargetEntity`
- 2026-07-07: Continued the adjacent current-account auth guardrail family in `services/common-security` by converging `SessionContext.isCurrentAccount(...)` onto canonical positive-id claim parsing instead of raw numeric equality. Current-account access checks now fail closed when either the requested account id or the stored `accountId` claim is zero, negative, or malformed, keeping `hasAccountAccess(...)` aligned with the positive-id semantics already used by auth/session request readers. Added focused proof in `services/common-security/src/test/java/net/firedevops/firemud/common/security/SessionContextTenantAccessTest.java`:
  - `currentAccountAccessRejectsNonPositiveAccountIds`
  - `currentAccountAccessRejectsNonPositiveCurrentAccountClaim`
- 2026-07-07: Continued the adjacent signed connect-token actor-identity family in `services/spring-cloud-gateway` by converging first-party handshake account parsing onto canonical positive-id readers plus subject/claim coherence. `GameplayHandshakeFilter` now rejects non-positive `accountId` claims and any drift between `sub` and `accountId` before minting gateway-signed connect context, so malformed actor identity cannot survive edge validation and fail later in Game Session. Added focused proof in `services/spring-cloud-gateway/src/test/java/unit/net/firedevops/firemud/springcloudgateway/filter/GameplayHandshakeFilterTest.java`:
  - `rejectsFirstPartyHandshakeWithZeroAccountIdClaim`
  - `rejectsFirstPartyHandshakeWhenSubjectDoesNotMatchAccountIdClaim`
- 2026-07-07: Continued the same gateway-signed actor-identity family in `services/game-session-service` by converging `FirstPartyConnectContextService` account parsing onto the same positive-id plus subject/claim coherence rule. Game Session now rejects gateway-signed connect contexts when `accountId` is non-positive or no longer matches `sub`, rather than trusting only the subject and carrying drift deeper into first-party `LOGIN` / `PLAY` admission. Added focused proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/FirstPartyConnectContextServiceTest.java`:
  - `parseRejectsZeroAccountClaim`
  - `parseRejectsAccountSubjectMismatch`
- 2026-07-07: Continued the same signed actor-identity family in `services/account-service` by converging both `requireBootstrapContext(...)` and `requireConnectScopeContext(...)` onto one local signed-account reader that requires `sub` and `accountId` to parse to the same positive actor id. Account Service now rejects bootstrap tokens and bootstrap-connect-scope tokens whose duplicated actor identity drifts across subject and claim instead of trusting only the claim payload after signature validation. Added focused proof in `services/account-service/src/test/java/unit/net/firedevops/firemud/accountservice/service/impl/AccountServiceImplTest.java`:
  - `listBootstrapWorldsRejectsBootstrapTokenAccountSubjectMismatch`
  - `issueConnectTokenRejectsConnectScopeAccountSubjectMismatch`
- 2026-07-07: Continued the adjacent signed gameplay-attestation identity family in `services/common-security` by converging `GameplaySessionAttestationService.requireValid(...)` onto subject/claim coherence for both gameplay-session and internal-probe attestations. Attestation validation now rejects tokens whose signed subject no longer matches the authoritative session or probe identity claims, rather than trusting only the payload before downstream routing/session match checks. Added focused proof in `services/common-security/src/test/java/net/firedevops/firemud/common/security/GameplaySessionAttestationServiceTest.java`:
  - `requireGameplaySessionMatchRejectsSubjectClaimMismatch`
  - `requireGameplayOrProbeMatchRejectsProbeSubjectClaimMismatch`
- 2026-07-07: Continued the adjacent malformed-token fallback seam in `services/game-session-service` by aligning `GameplayPresenceRoleClassifier` with the canonical JWT reader catch boundary already used in other auth/session entrypoints. WHO/presence role classification now treats `IllegalArgumentException` the same as `JwtException`, so malformed session JWTs fall back to `PLAYER` instead of bubbling parser-shape failures out of gameplay presence classification. Added focused proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameplayPresenceRoleClassifierTest.java`:
  - `classifyRoleReturnsPlayerWhenJwtParserThrowsIllegalArgumentException`
- 2026-07-07: Continued the adjacent replay-reader hardening family in `services/account-service` by converging stored replay boolean parsing onto one canonical required-boolean reader in `SessionServiceImpl`. Connect-token replay and public-production-membership replay reads now reject malformed persisted `success` / `created` flags instead of silently coercing them through `Boolean.parseBoolean(...)` and reclassifying corrupted replay payloads as valid failures or `created=false` results. Added focused proof in `services/account-service/src/test/java/unit/net/firedevops/firemud/accountservice/service/session/SessionServiceImplTest.java`:
  - `getConnectTokenReplayReturnsEmptyForMalformedSuccessFlag`
  - `getPublicProductionMembershipReplayReturnsEmptyForMalformedCreatedFlag`
- 2026-07-07: Continued the same account replay-reader family in `services/account-service` by converging successful replay text payload fields onto one canonical required-text reader in `SessionServiceImpl`. Cached connect-token and public-production-membership successes now reject blank or whitespace-padded persisted `connectToken`, `jti`, `realmSlug`, `requestId`, `issuedAt`, `expiresAt`, and `evaluatedAt` fields instead of replaying structurally corrupted success payloads as if they were valid results. Added focused proof in `services/account-service/src/test/java/unit/net/firedevops/firemud/accountservice/service/session/SessionServiceImplTest.java`:
  - `getConnectTokenReplayReturnsEmptyForBlankRequiredTextField`
  - `getConnectTokenReplayReturnsEmptyForWhitespacePaddedIdentityField`
  - `getPublicProductionMembershipReplayReturnsEmptyForBlankRequiredTextField`
- 2026-07-07: Continued the same account replay family on the writer side in `services/account-service` by fail-closing successful replay payload/key drift inside `SessionServiceImpl`. Connect-token and public-production-membership cache writes now require payload `accountId`, `tenantId`, request identity, and connect-scope identity to match the replay cache key inputs before persisting, so first-response success payload drift can no longer create unreadable replay entries that later collapse to cache misses. Added focused proof in `services/account-service/src/test/java/unit/net/firedevops/firemud/accountservice/service/session/SessionServiceImplTest.java`:
  - `storeConnectTokenReplayRejectsMismatchedPayloadIdentity`
  - `storePublicProductionMembershipReplayRejectsMismatchedRequestIdPayload`
- 2026-07-07: Continued bounded gameplay-command admission follow-through in `services/game-session-service` by converging remaining "in gameplay" checks onto canonical `SessionContext.hasGameplayIdentity()`. `AfkCommandHandler`, `ActionStateCommandHandler`, and `CommunicationCommandHandler` now fail closed on partial gameplay shells instead of treating a lone `gameInstanceId` as authoritative gameplay presence. Added focused proof in:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/command/text/AfkCommandHandlerTest.java`
    - `afkRejectsPartialGameplayIdentityShellFromResolvedSession`
    - `directAfkRejectsPartialGameplayIdentityShell`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/command/text/ActionStateCommandHandlerTest.java`
    - `blockRejectsPartialGameplayIdentityShell`
- 2026-07-07: Continued bounded logging-admin remote-reader follow-through in `services/logging-admin-service` by extracting shared `ControlPlaneResponseReaders` for remote command coordinator/followup/followup-result response IDs. `RemoteCommandCoordinatorServiceImpl`, `RemoteFollowupServiceImpl`, and `RemoteFollowupResultServiceImpl` now share one fail-closed positive-ID reader for required and optional control-plane response fields, so zero or malformed numeric response values no longer slip through DTO projection as if they were valid control-plane identity. Added focused proof in:
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/RemoteCommandCoordinatorServiceImplTest.java`
    - `getRemoteCommandCoordinatorRejectsZeroOriginGameInstanceIdInResponse`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/RemoteFollowupServiceImplTest.java`
    - `getRemoteFollowupRejectsZeroTargetGameInstanceIdInResponse`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/RemoteFollowupResultServiceImplTest.java`
    - `getRemoteFollowupResultRejectsZeroTenantIdInResponse`
- 2026-07-07: Continued bounded remote/control-plane routing guardrail follow-through in `services/game-session-service` by extracting shared `CurrentRuntimeScopeFieldEmitter` for current-runtime field emission across command and remote control-plane read surfaces. `GameSessionCommandControlPlaneService` and `GameSessionRemoteControlPlaneService` now share one writer-driven helper for origin/target runtime game-instance, region, scope, and routing-bundle fields, so partial or ambiguous current runtime authority still collapses to blank world/realm/pointer claims through one canonical emit path instead of six copied local blocks. Added focused proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcServiceTest.java`:
  - `listRemoteCommandCoordinatorsCollapsesPartialCurrentRuntimeRoutingBundleAndMarksStale`
  - `getRemoteFollowupResultCollapsesPartialCurrentRuntimeRoutingBundleAndMarksStale`
- 2026-07-07: Continued the deferred websocket/client routing-guardrail follow-through in `services/game-session-service` by extracting canonical `GameplayAdmissionPointerSnapshots.requireAdmittedRoutingBundle(...)` for client-side attestation entrypoints. `GameLogicClient` and `EntityManagementClient` now share one fail-closed admitted-routing-bundle reader instead of open-coding the same partial-versus-missing bundle checks with only caller text drift. Added focused proof in:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/GameplayAdmissionPointerSnapshotsTest.java`
    - `requireAdmittedRoutingBundleRejectsPartialRoutingClaimsWithCallerSpecificMessage`
    - `requireAdmittedRoutingBundleRejectsMissingRoutingClaimsWithCallerSpecificMessage`
  - existing client negative-path proofs continue to cover the direct adopters:
    - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/client/GameLogicClientTest.java`
      - `queryInventoryFailsClosedWhenSessionContextDropsPartOfAdmittedRoutingBundle`
      - `queryInventoryFailsClosedWhenSessionContextDropsEntireAdmittedRoutingBundle`
    - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/client/EntityManagementClientTest.java`
      - `listRoomEntitiesFailsClosedWhenSessionContextDropsPartOfAdmittedRoutingBundle`
      - `listRoomEntitiesFailsClosedWhenSessionContextDropsEntireAdmittedRoutingBundle`
- 2026-07-07: Continued the adjacent login-failure routing-cleanup seam in `services/game-session-service` by converging `LoginCommandHandler.clearFailedLoginSessionState(...)` onto canonical routing-bundle normalization. Failed login cleanup now preserves only complete routing bundles from fallback or projected session state and drops legacy partial routing tuples through `GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(...)` instead of repeating local `worldSlug` / `realmSlug` / `pointerVersion` completeness booleans. Added focused proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/command/text/LoginCommandHandlerTest.java`:
  - `invalidCredentialsDropPartialProjectedRoutingBundleDuringFailedLoginCleanup`
- 2026-07-07: Continued the adjacent automation command control-plane request-reader seam in `services/game-session-service` by converging optional `pointer_version` parsing in `GameSessionCommandControlPlaneService.enqueueAutomationCommandIfAbsent(...)` onto the canonical positive-number parser already used by neighboring control-plane readers. Present `pointer_version` values now fail closed as `INVALID_ARGUMENT` when malformed or non-positive instead of reaching later routing-bundle completeness handling through a raw `Long.parseLong(...)` result. Added focused proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcServiceTest.java`:
  - `enqueueAutomationCommandRejectsMalformedPointerVersion`
  - `enqueueAutomationCommandRejectsNonPositivePointerVersion`
- 2026-07-07: Continued the adjacent recent-presence routing snapshot seam in `services/game-session-service` by converging `RedisAccountRecentPresenceService.routingSnapshot(...)` onto canonical complete-or-absent routing-bundle normalization. Disconnect/activity snapshots now preserve one coherent routing source at a time, preferring a complete live-presence bundle and otherwise falling back to a complete session-context bundle, instead of synthesizing mixed runtime identity by merging world/realm/pointer fields independently across both sources. Added focused proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/RedisAccountRecentPresenceServiceTest.java`:
  - `recordDisconnectUsesContextRoutingWhenLivePresenceRoutingFieldsArePartial`
- 2026-07-07: Continued the deferred websocket bootstrap routing seam in `services/game-session-service` by extracting canonical generic-bootstrap routing repair into `GameplayAdmissionPointerSnapshots.repairGenericBootstrapShell(...)` and removing the local duplicate repair path from `GameSessionWebSocketHandler`. Websocket bootstrap now treats partial `worldSlug` / `realmSlug` / `pointerVersion` tuples as incomplete routing at one shared helper boundary, then either repairs from singular runtime authority or clears the tuple fail-closed before connection/message bootstrap persists session state. Added focused proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/websocket/GameSessionWebSocketHandlerTest.java`:
  - `afterConnectionEstablishedRepairsPartialRoutingTupleFromSingularRuntimeAuthority`
  - `afterConnectionEstablishedDropsPartialRoutingTupleWhenRuntimeAuthorityIsAmbiguous`
  - `handleMessageRepairsPartialGenericBootstrapRoutingBeforeInterpretingWhenMissing`
  - `handleMessageClearsPartialGenericBootstrapRoutingWhenAuthorityIsAmbiguous`
- 2026-07-07: Continued the same `services/game-session-service` guardrail family by converging prepared version-upgrade tenant lookup onto a shared tenant-scoped reader in `VersionUpgradePreparationServiceImpl`. Cross-tenant preparation reads and execute-marking now fail closed through one canonical `requirePreparedVersionUpgradeForTenant(...)` path instead of repeating inline repository lookup plus tenant checks. Added focused proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/VersionUpgradePreparationServiceImplTest.java`:
  - `getPreparedVersionUpgradeRejectsCrossTenantPreparationLookup`
  - `markPreparedVersionUpgradeExecutedRejectsCrossTenantPreparationLookup`
- 2026-07-07: Continued the adjacent prepared-upgrade replay family in `services/game-session-service` by converging idempotent replay payload matching in `VersionUpgradePreparationServiceImpl` onto shared request/execution comparison helpers. Replayed prepare and execute calls now reject mismatched `sourceGameInstanceId`, `targetVersionId`, `targetGameInstanceId`, or pointer/execution request payloads through one canonical comparison path instead of open-coded inline checks. Added focused proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/VersionUpgradePreparationServiceImplTest.java`:
  - `prepareVersionUpgradeRejectsMismatchedReplayPayloadForExistingControlPlaneRequestId`
  - `markPreparedVersionUpgradeExecutedRejectsMismatchedReplayPayload`
- 2026-07-07: Continued the adjacent cutover source-instance metadata reader family in `services/game-session-service` by converging `InstanceCutoverCompatibilityServiceImpl` source `versionId` and `gameTemplateId` reads onto canonical positive-ID parsing, and by making fallback `runtimeVersion` parsing fail with the same positive/numeric guardrail. Malformed or non-positive source-instance metadata now aborts cutover compatibility before any game-design, world, or entity validation calls. Added focused proof in `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/InstanceCutoverCompatibilityServiceImplTest.java`:
  - `validateInstanceCutoverCompatibilityRejectsZeroSourceVersionIdBeforeDownstreamChecks`
  - `validateInstanceCutoverCompatibilityRejectsMalformedRuntimeVersionBeforeDownstreamChecks`
  - `validateInstanceCutoverCompatibilityRejectsZeroGameTemplateIdBeforeDownstreamChecks`
- 2026-07-07: Continued bounded TCP-proxy routing guardrail follow-through in `services/tcp-proxy-service` by extracting canonical `TelnetRoutingBundle.normalize(...)` for telnet bootstrap and gateway header forwarding. `TelnetSessionContext` and `TelnetServerHandler` now share one complete-or-absent reader for `worldSlug`, `realmSlug`, and `pointerVersion`, so partial routing bundles are dropped consistently before session reuse or gateway bootstrap instead of relying on duplicated local checks. Added focused proof in:
  - `services/tcp-proxy-service/src/test/java/unit/net/firedevops/firemud/tcpproxy/telnet/TelnetRoutingBundleTest.java`
    - `normalizeReturnsNullWhenRealmSlugIsBlank`
    - `normalizeReturnsNullWhenPointerVersionIsBlank`
  - `services/tcp-proxy-service/src/test/java/unit/net/firedevops/firemud/tcpproxy/telnet/TelnetSessionContextTest.java`
    - `bootstrap_dropsPartialRoutingBundle`
  - `services/tcp-proxy-service/src/test/java/unit/net/firedevops/firemud/tcpproxy/telnet/TelnetServerHandlerTest.java`
    - `partialDefaultRoutingBundleIsDroppedBeforeGatewayBootstrap`
- 2026-07-07: Continued bounded automation-scripting runtime-routing guardrail follow-through by extracting canonical `AutomationRuntimeScopeSupport` for current runtime-region reads and stale-runtime filtering. `PluginActivationPreflightServiceImpl` and `PluginRuntimeStateServiceImpl` now share one runtime-scope reader and matcher for region id/epoch, so missing control-plane runtime state and malformed scoped runtime rows fail closed through the same routing helper instead of duplicating local `RuntimeScope` logic. Added focused proof in:
  - `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/AutomationRuntimeScopeSupportTest.java`
    - `currentRuntimeScopeReturnsUnknownWhenRuntimeStateIsMissing`
    - `matchesRejectsStateWithBlankRegionWhenRuntimeScopeIsKnown`
    - `currentRuntimeScopeReturnsObservedRegionWhenRuntimeStateIsPresent`
  - `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/PluginActivationPreflightServiceImplTest.java`
    - existing runtime-region conflict / stale-runtime proofs continue to cover the direct adopters
  - `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/PluginRuntimeStateServiceImplTest.java`
    - existing current-runtime filtering proofs continue to cover the direct adopters
- 2026-07-07: Continued the same automation-scripting runtime-entry family by fail-closing tenant ID readers in `PluginActivationPreflightServiceImpl` and `ScriptEventIngressServiceImpl` with canonical positive-ID parsing. Zero or malformed `tenantId` values now abort plugin activation preflight and gameplay event admission before repository lookup, runtime-control-plane reads, quota checks, or audit writes instead of drifting through local numeric parsing. Added focused proof in:
  - `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/PluginActivationPreflightServiceImplTest.java`
    - `rejectsZeroTenantIdBeforeLookups`
  - `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptEventIngressServiceImplTest.java`
    - `rejectsZeroTenantIdBeforeLookupAndAuditWrite`
- 2026-07-07: Continued the adjacent automation scheduling runtime family by converging `ScriptScheduleInstanceServiceImpl` onto the same runtime-scope matcher and positive tenant-id reader already used by the surrounding automation routing seams. Schedule materialization now rejects zero or malformed `tenantId` before definition lookup, binding lookup, or runtime-plugin filtering, and stale runtime-region filtering now reuses `AutomationRuntimeScopeSupport` instead of carrying a fourth local `RuntimeScope` copy. Added focused proof in `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptScheduleInstanceServiceImplTest.java`:
  - `reconcileObservedRuntimeStateRejectsZeroTenantIdBeforeScheduleLookup`
  - existing runtime-region drift proofs continue to cover the direct adopter:
    - `reconcileObservedRuntimeStateIgnoresEnabledPluginRowsFromDifferentRuntimeRegion`
    - `reconcileObservedRuntimeStateIgnoresEnabledPluginRowsFromDifferentRuntimeEpoch`
- 2026-07-07: Continued the adjacent automation design-digest request-reader family by converging `ScriptDesignDigestServiceImpl` onto the same canonical positive tenant-id parser used across the newer automation entry seams. Full-version and script-patch digest reads now reject zero or malformed `tenantId` before script or binding repository lookup instead of relying on duplicated raw `Long.parseLong(...)` calls. Added focused proof in `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptDesignDigestServiceImplTest.java`:
  - `getDraftDesignDigestForVersionRejectsZeroTenantIdBeforeLookups`
  - `getDraftDesignDigestForScriptPatchRejectsZeroTenantIdBeforeLookups`
- 2026-07-07: Continued the adjacent automation patch/schedule orchestration family by converging `ScriptPatchVersionCommandService` and `ScriptScheduleDefinitionServiceImpl` onto the same canonical positive tenant-id parser used by the newer automation readers. Patch notify and schedule refresh entrypoints now reject zero or malformed `tenantId` before repository reads, readiness projection writes, event admission, or schedule reconciliation instead of relying on duplicated raw `Long.parseLong(...)` calls. Added focused proof in:
  - `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptPatchVersionCommandServiceTest.java`
    - `notifyUpdateRejectsZeroTenantIdBeforeLookups`
  - `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptScheduleDefinitionServiceImplTest.java`
    - `refreshPatchSchedulesRejectsZeroTenantIdBeforeRepositoryReads`
- 2026-07-07: Continued the adjacent draft-design digest request-reader family across `services/entity-management-service` and `services/world-management-service` by converging `EntityDraftDesignDigestServiceImpl` and `WorldDraftDesignDigestServiceImpl` onto canonical positive-ID parsing for both `tenantId` and numeric `versionId`. Entity and world draft digest reads now reject zero or malformed IDs before any repository lookup instead of relying on raw `Long.parseLong(...)` at each service boundary. Added focused proof in:
  - `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/service/impl/EntityDraftDesignDigestServiceImplTest.java`
    - `rejectsZeroTenantIdBeforeRepositoryReads`
    - `rejectsZeroVersionIdBeforeRepositoryReads`
  - `services/world-management-service/src/test/java/unit/net/firedevops/firemud/worldmanagement/service/impl/WorldDraftDesignDigestServiceImplTest.java`
    - `rejectsZeroTenantIdBeforeRepositoryReads`
    - `rejectsZeroVersionIdBeforeRepositoryReads`
- 2026-07-07: Continued the adjacent entity template-reference reader family in `services/entity-management-service` by converging `EntityTemplateReferenceServiceImpl` onto canonical positive-ID parsing for `tenantId`, `versionId`, and `templateId`. Template existence checks now reject zero or malformed IDs before item/NPC repository lookup instead of relying on raw `Long.parseLong(...)` across each field. Added focused proof in `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/service/impl/EntityTemplateReferenceServiceImplTest.java`:
  - `rejectsZeroTenantIdBeforeRepositoryReads`
  - `rejectsZeroVersionIdBeforeRepositoryReads`
  - `rejectsZeroTemplateIdBeforeRepositoryReads`
  - `delegatesItemTemplateChecksToItemRepository`
- 2026-07-06: Continued bounded auth/access caller-context convergence in `services/common-security`, `services/entity-management-service`, and `services/world-management-service` by extracting canonical `SessionContext.hasAuthenticatedCallerContext()` and adopting it in both service-local `requireTenantAccessWhenPresent(...)` guards. Malformed but nonblank current-account claims now still count as caller context, so tenant-owned entity/world read surfaces fail closed instead of silently degrading into anonymous access. Added focused proof in:
  - `services/common-security/src/test/java/net/firedevops/firemud/common/security/SessionContextTenantAccessTest.java`
    - `hasAuthenticatedCallerContextTreatsMalformedAccountClaimAsPresent`
    - `hasAuthenticatedCallerContextTreatsRoleOnlyClaimsAsPresent`
  - `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/service/impl/EntityManagementGrpcServiceTest.java`
    - `listCharactersRejectsMalformedCurrentAccountClaimWithoutTenantAccess`
  - `services/world-management-service/src/test/java/unit/net/firedevops/firemud/worldmanagement/service/impl/WorldManagementGrpcServiceTest.java`
    - `requireTenantAccessWhenPresentRejectsMalformedCurrentAccountClaim`
- 2026-07-06: Continued bounded malformed-token rejection convergence in `services/common-security` and `services/spring-cloud-gateway` by narrowing gameplay attestation and first-party handshake JWT failure handling to `JwtException` plus `IllegalArgumentException`, matching the existing canonical pattern in `FirstPartyConnectContextService`. Unexpected runtime bugs in token parsing no longer get mislabeled as `SESSION_ATTESTATION_INVALID` or `CONNECT_TOKEN_REJECTED`. Added focused proof in:
  - `services/common-security/src/test/java/net/firedevops/firemud/common/security/GameplaySessionAttestationServiceTest.java`
    - `requireValidDoesNotMaskUnexpectedJwtParserRuntimeFailure`
  - `services/spring-cloud-gateway/src/test/java/unit/net/firedevops/firemud/springcloudgateway/filter/GameplayHandshakeFilterTest.java`
    - `firstPartyHandshakeDoesNotMaskUnexpectedJwtParserRuntimeFailure`
- 2026-07-06: Continued bounded auth/session replay-reader convergence in `services/account-service` by routing `SessionServiceImpl.getAccountId(...)` through the same canonical positive-long reader already used by connect-token and public-production replay payloads. Malformed or non-positive tenant-scoped session allowlist values now fail closed to `null` instead of throwing during bootstrap/session auth reads. Added focused proof in `services/account-service/src/test/java/unit/net/firedevops/firemud/accountservice/service/session/SessionServiceImplTest.java`:
  - `getAccountIdReturnsNullForMalformedStoredAccountId`
  - `getAccountIdReturnsNullForNonPositiveStoredAccountId`
- 2026-06-25 validation:
  - `./gradlew :game-session-service:test --tests '*GameSessionControlPlaneGrpcServiceTest'`
  - `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.FirstPartyConnectContextServiceTest' --tests 'net.firedevops.firemud.gamesession.websocket.GameSessionWebSocketHandlerTest' --tests 'net.firedevops.firemud.gamesession.command.text.PlayCommandHandlerTest'`
  - `./gradlew :common-security:test --tests '*JwtClaimsTest' --tests '*HttpJwtAuthInterceptorTest'`
  - `./gradlew :account-service:test --tests '*AccountServiceImplTest' --tests '*SessionServiceImplTest'`
  - `./gradlew :common-security:check -PfullCheck`
  - `./gradlew :game-session-service:check -PfullCheck`
  - `./gradlew linkCheck lintMarkdown`
  - `bash dev-tools/verify-fresh-bootstrap.sh`
  - `docker compose -f docker/docker-compose.yml -f docker/docker-compose.override.yml down -v --remove-orphans`
- 2026-07-06 validation:
  - `./gradlew :account-service:test --tests 'net.firedevops.firemud.accountservice.service.impl.AccountServiceImplTest'`
  - `./gradlew :account-service:test --tests 'net.firedevops.firemud.accountservice.service.session.SessionServiceImplTest'`
  - `./gradlew :common-security:test --tests 'net.firedevops.firemud.common.security.SessionContextTenantAccessTest'`
  - `./gradlew :common-security:test --tests 'net.firedevops.firemud.common.security.GameplaySessionAttestationServiceTest'`
  - `./gradlew :entity-management-service:test --tests 'net.firedevops.firemud.entitymanagement.service.impl.EntityManagementGrpcServiceTest'`
  - `./gradlew :spring-cloud-gateway:test --tests 'net.firedevops.firemud.springcloudgateway.filter.GameplayHandshakeFilterTest'`
  - `./gradlew :social-groups-service:test --tests 'net.firedevops.firemud.socialgroups.service.impl.SocialGroupsGrpcServiceTest'`
  - `./gradlew :world-management-service:test --tests 'net.firedevops.firemud.worldmanagement.service.impl.WorldManagementGrpcServiceTest'`
  - `./gradlew spotlessApply`
  - `./gradlew linkCheck lintMarkdown`
  - `dev-tools/validation/run-locked-gradle.sh :account-service:check :common-security:check :entity-management-service:check :world-management-service:check -PfullCheck`
  - `dev-tools/validation/run-locked-gradle.sh :common-security:check :spring-cloud-gateway:check -PfullCheck`
  - `dev-tools/validation/run-locked-gradle.sh :social-groups-service:check -PfullCheck`
  - `dev-tools/validation/run-locked-gradle.sh :world-management-service:check -PfullCheck`
- 2026-07-07 validation:
  - `./gradlew spotlessApply`
  - `dev-tools/validation/run-locked-gradle.sh :account-service:test --tests 'net.firedevops.firemud.accountservice.controller.AccountControllerTest' --tests 'net.firedevops.firemud.accountservice.controller.ProfileControllerTest' --no-configuration-cache`
  - `dev-tools/validation/run-locked-gradle.sh :account-service:integrationTest --tests 'net.firedevops.firemud.accountservice.AccountApplicationIntegrationTest' --no-configuration-cache`
  - `dev-tools/validation/run-locked-gradle.sh :automation-scripting-service:test --tests 'net.firedevops.firemud.automationscripting.controller.FactionControllerTest' --tests 'net.firedevops.firemud.automationscripting.controller.NpcFormationControllerTest' --no-configuration-cache`
  - `dev-tools/validation/run-locked-gradle.sh :entity-management-service:test --tests 'net.firedevops.firemud.entitymanagement.controller.CharacterControllerTest' --tests 'net.firedevops.firemud.entitymanagement.controller.FriendControllerTest' --no-configuration-cache`
  - `dev-tools/validation/run-locked-gradle.sh :social-groups-service:test --tests 'net.firedevops.firemud.socialgroups.controller.FriendControllerTest' --no-configuration-cache`
  - `dev-tools/validation/run-locked-gradle.sh :social-groups-service:integrationTest --tests 'net.firedevops.firemud.socialgroups.SocialGroupsApplicationIntegrationTest' --no-configuration-cache`
  - `dev-tools/validation/run-locked-gradle.sh :world-management-service:test --tests 'net.firedevops.firemud.worldmanagement.controller.RegionControllerTest' --tests 'net.firedevops.firemud.worldmanagement.controller.GenerationRuleControllerTest' --no-configuration-cache`
  - `dev-tools/validation/run-locked-gradle.sh :world-management-service:integrationTest --tests 'net.firedevops.firemud.worldmanagement.WorldManagementServiceApplicationIntegrationTest' --no-configuration-cache`
  - `dev-tools/validation/run-locked-gradle.sh :entity-management-service:check -PfullCheck`
  - `./gradlew :automation-scripting-service:test --tests 'net.firedevops.firemud.automationscripting.service.impl.NpcFormationGrpcServiceTest'`
  - `./gradlew :automation-scripting-service:test --tests 'net.firedevops.firemud.automationscripting.service.impl.AutomationRuntimeScopeSupportTest' --tests 'net.firedevops.firemud.automationscripting.service.impl.PluginActivationPreflightServiceImplTest' --tests 'net.firedevops.firemud.automationscripting.service.impl.PluginRuntimeStateServiceImplTest'`
  - `./gradlew :automation-scripting-service:test --tests 'net.firedevops.firemud.automationscripting.service.impl.PluginActivationPreflightServiceImplTest' --tests 'net.firedevops.firemud.automationscripting.service.impl.ScriptEventIngressServiceImplTest'`
  - `./gradlew :automation-scripting-service:test --tests 'net.firedevops.firemud.automationscripting.service.impl.ScriptScheduleInstanceServiceImplTest'`
  - `./gradlew :automation-scripting-service:test --tests 'net.firedevops.firemud.automationscripting.service.impl.ScriptDesignDigestServiceImplTest'`
  - `./gradlew :automation-scripting-service:test --tests 'net.firedevops.firemud.automationscripting.service.impl.ScriptPatchVersionCommandServiceTest' --tests 'net.firedevops.firemud.automationscripting.service.impl.ScriptScheduleDefinitionServiceImplTest'`
  - `./gradlew :entity-management-service:test --tests 'net.firedevops.firemud.entitymanagement.service.impl.EntityDraftDesignDigestServiceImplTest'`
  - `./gradlew :entity-management-service:test --tests 'net.firedevops.firemud.entitymanagement.service.impl.EntityTemplateReferenceServiceImplTest'`
  - `./gradlew :world-management-service:test --tests 'net.firedevops.firemud.worldmanagement.service.impl.WorldDraftDesignDigestServiceImplTest'`
  - `dev-tools/validation/run-locked-gradle.sh :automation-scripting-service:check -PfullCheck`
  - `dev-tools/validation/run-locked-gradle.sh :entity-management-service:check :world-management-service:check -PfullCheck`
  - `./gradlew :tcp-proxy-service:test --tests 'net.firedevops.firemud.tcpproxy.telnet.TelnetRoutingBundleTest' --tests 'net.firedevops.firemud.tcpproxy.telnet.TelnetSessionContextTest' --tests 'net.firedevops.firemud.tcpproxy.telnet.TelnetServerHandlerTest'`
  - `dev-tools/validation/run-locked-gradle.sh :tcp-proxy-service:check -PfullCheck`
  - `./gradlew linkCheck lintMarkdown`

##### source-02-1-7-task-list-auth-session-and-routing-guardrail-follow-through-vertical-slice-1-807: Scope Rules

- Keep this slice bounded to auth/session/routing entry seams that already have a canonical target shape in the repo docs or live code.
- Prefer one shared helper plus direct adopters over repeated inline guard clauses.
- Add focused tests for each real new guardrail.
- Do not widen a batch into unrelated auth, transport, or validation cleanup unless current proof shows the seam cannot land cleanly without it.
- Treat this document as the single growing slice doc for these bounded guardrail follow-through tasks. Add new supertask entries here instead of creating a new meta-workflow doc tree.
- For gameplay admission-pointer checks, keep `LoginCommandHandler` and `SessionRoutingNormalizationService` scope-agnostic at the current seam: those paths should validate strict runtime identity (`tenantId`, `gameInstanceId`, `worldSlug`, `realmSlug`, `pointerVersion`) and fail safe on stale routing, but should not reject otherwise recoverable sessions on `playableStateScope` drift alone. Reserve `playableStateScope` enforcement for command-routing-authoritative seams such as `CommandServiceImpl`, where durable routing metadata is being accepted or persisted.

##### source-02-1-7-task-list-auth-session-and-routing-guardrail-follow-through-vertical-slice-1-807: 1. Malformed Token and Claim-Shape Rejection

- [x] Audit the canonical JWT and signed-token entry points used by bootstrap, first-party session entry, reconnect, and related auth handoff flows, and list the exact claim-shape assumptions each path currently trusts.
- [x] Ensure malformed, blank, or inconsistent required claims fail closed with canonical application-level errors instead of falling through to local fallback identity guesses, partial session reuse, or transport exceptions.
- [x] Converge duplicated claim-validation snippets onto the smallest practical shared helper where the same invariant is being enforced in multiple live entry points.
- [x] Add focused tests covering malformed token structure, missing required claims, blank string claims, non-numeric ids where numeric ids are required, and inconsistent cross-claim identity when those cases are applicable to the live seam.
- [x] Update the matching auth/session docs only when the canonical current behavior changed or was previously underspecified.

##### source-02-1-7-task-list-auth-session-and-routing-guardrail-follow-through-vertical-slice-1-807: 2. Session and Routing Identity Fail-Closed Guards

- [x] Audit the current auth/session readers and routing-bundle consumers for places that still trust non-positive ids, blank `worldSlug` / `realmSlug`, stale partial routing bundles, or inconsistent runtime-target identity after canonical lookup.
- [x] Ensure canonical session authority, first-party connect-context reuse, and admission-pointer identity checks reject incomplete or inconsistent identity before downstream routing, replay, or runtime-stop decisions are made.
- [x] Keep guards on the owning canonical reader/helper layer where practical rather than adding scattered downstream null/blank checks to every caller.
- [x] Add focused tests for invalid tenant id, invalid game instance id, blank world/realm identity, stale pointer identity, and any other currently live negative path the batch tightens.

##### source-02-1-7-task-list-auth-session-and-routing-guardrail-follow-through-vertical-slice-1-807: 3. Replay, Rehydration, and Payload Mismatch Guards

- [x] Audit replay and rehydration payloads that are treated as authoritative for auth/session/routing-adjacent flows, especially public-production membership, connect-token replay, and other cached or Redis-backed handoff state.
- [x] Ensure replay payloads fail closed when identity fields in the payload do not match the lookup key or owning request context.
- [x] Prefer validating replay identity at the canonical persistence/read boundary so mismatched payloads cannot be silently stored and later replayed as truth.
- [x] Add focused tests proving mismatched stored payloads are rejected with stable local failure semantics.

##### source-02-1-7-task-list-auth-session-and-routing-guardrail-follow-through-vertical-slice-1-807: 4. Application-Level Error Normalization

- [x] Audit auth/session/routing-adjacent gRPC methods for runtime failures that still escape as transport errors or expose raw internal exception messages to callers.
- [x] Ensure application-level failures return canonical `ErrorDetail` responses, use centralized internal-error helpers where available, and keep transport `onError()` reserved for infrastructure failures.
- [x] Add or refresh focused tests proving internal failures produce canonical app-error payloads rather than raw exception leakage.

##### source-02-1-7-task-list-auth-session-and-routing-guardrail-follow-through-vertical-slice-1-807: 5. Bounded Delegated Supertask Pattern

- [x] When delegating a batch from this slice to a cheaper fast model, define the exact seam first:
  - the invariant being enforced
  - the initial in-scope files or services
  - the allowed expansion boundary
  - the exact validation commands
- [x] Treat each delegated batch as a real bounded implementation cut, not as an open-ended "find guardrails" sweep.
- [x] Require each delegated batch to return:
  - the concrete still-valid gaps it found
  - the exact files changed
  - the tests or checks run
  - any stale suggested hardening it intentionally skipped

##### source-02-1-7-task-list-auth-session-and-routing-guardrail-follow-through-vertical-slice-1-807: 6. Validation Expectations

- [x] Run `./gradlew spotlessApply` before hand-off for any code batch under this slice.
- [x] Run the touched service `./gradlew :<service>:check -PfullCheck` paths for any changed services.
  - `:common-security:check -PfullCheck` passed.
  - `:game-session-service:check -PfullCheck` currently blocked by local infra/test-runtime issues (`NoSuchFileException` in unit/cross/integration test-result binary outputs during test task collection and one pre-existing cross-service assertion failure in `MultiplayerLoadProofCrossServiceTest`).
- [x] Run `./gradlew linkCheck lintMarkdown` when this slice doc or related design docs change.
  - Passed with clean output.
- [x] Run `bash dev-tools/verify-fresh-bootstrap.sh` when a batch changes runtime behavior, packaged auth wiring, admission routing, or other boot-critical cross-service seams.
  - `Fresh bootstrap proof` succeeded with WebSocket and Telnet smoke proofs.
- [x] Bring local Docker compose resources back down after smoke proof when they are no longer being used.
  - `docker compose -f docker/docker-compose.yml -f docker/docker-compose.override.yml down -v --remove-orphans`

##### source-02-1-7-task-list-auth-session-and-routing-guardrail-follow-through-vertical-slice-1-807: 7. Current Supertask Queue

###### source-02-1-7-task-list-auth-session-and-routing-guardrail-follow-through-vertical-slice-1-807: Supertask A: malformed JWT and signed-token claim rejection

- Canonical seam: bootstrap, first-party session-entry, reconnect, and related signed-token parsing paths must reject malformed or inconsistent claim sets without fallback identity reconstruction.
- Expected pattern: shared helper convergence where the same claim contract is repeated, plus focused negative-path tests.

###### source-02-1-7-task-list-auth-session-and-routing-guardrail-follow-through-vertical-slice-1-807: Supertask B: fail-closed session and routing identity validation

- Canonical seam: auth/session/routing readers must reject blank world/realm identity, non-positive ids, and inconsistent runtime-target identity before routing bundles are reused or persisted onward.
- Expected pattern: canonical reader/helper hardening plus focused tests in the owning service.

###### source-02-1-7-task-list-auth-session-and-routing-guardrail-follow-through-vertical-slice-1-807: Supertask C: replay payload identity mismatch guards

- Canonical seam: cached or replayed auth/session/routing-adjacent payloads must prove they still match the owning lookup identity before reuse.
- Expected pattern: boundary validation at storage or readback seams, plus mismatch tests.

###### source-02-1-7-task-list-auth-session-and-routing-guardrail-follow-through-vertical-slice-1-807: Supertask D: gRPC app-error normalization for auth/session/routing seams

- Canonical seam: application-level failures in these paths must return canonical `ErrorDetail` responses and must not leak raw internal exception messages.
- Expected pattern: centralized helper usage, updated tests, and no transport-error regressions.

##### source-02-1-7-task-list-auth-session-and-routing-guardrail-follow-through-vertical-slice-1-807: Deferred Follow-Up

- Adjacent still-valid seam likely worth next bounded batch: scan neighboring auth/session command-routing/ownership paths for remaining non-positive id or malformed routing-identity parsing that still uses local hand-rolled logic instead of canonical helpers.
- If a future batch discovers a repeated guardrail family that spans well beyond auth/session/routing seams, split that into a different real slice instead of silently letting this document become a repo-wide generic hardening bucket.
- If a future batch changes the actual target-state architecture rather than only tightening an existing invariant, document that in the owning product slice instead of here.
<!-- /migration-source -->

### source-02-1-7-1-task-list-auth-entry-negative-path-parity-vertical-slice-1-96

#### 02.1.7.1 Task List: Auth Entry Negative-Path Parity Vertical Slice - Auth entry negative-path parity (source lines 1-96)

##### Preserved Source Text: source-02-1-7-1-task-list-auth-entry-negative-path-parity-vertical-slice-1-96

<!-- migration-source path="design/project-management/vertical-slices/02.1.7.1-task-list-auth-entry-negative-path-parity-vertical-slice.md" lines="1-96" sha256="b4bcf970c8dc47cad49151defb0afe6ad6d8d21d9a2a5c50d0f0d4316941cf8c" heading-offset="3" -->
#### source-02-1-7-1-task-list-auth-entry-negative-path-parity-vertical-slice-1-96: 02.1.7.1 Task List: Auth Entry Negative-Path Parity Vertical Slice

##### source-02-1-7-1-task-list-auth-entry-negative-path-parity-vertical-slice-1-96: Goal and Status

Goal: close the remaining malformed-input, blank-identity, and inconsistent-claim negative paths across live auth, session, and routing entry seams so equivalent bad input fails the same way no matter which bootstrap, reconnect, websocket, or gRPC surface receives it. Status: complete.

##### source-02-1-7-1-task-list-auth-entry-negative-path-parity-vertical-slice-1-96: Completion Notes

- 2026-06-29: Completed. Canonical JWT claim parsing for account bootstrap/connect-scope tokens is now enforced in `AccountServiceImpl` via `JwtClaims`-backed claim extraction in `requireBootstrapContext` and `requireConnectScopeContext`; this closes non-positive and malformed claim drift on the account entry seam.
- 2026-06-29: Added focused proof in `AccountServiceImplTest.listBootstrapWorldsRejectsMalformedBootstrapTokenClaims`, `AccountServiceImplTest.listBootstrapWorldsRejectsNonPositiveBootstrapTokenClaims`, and `AccountServiceImplTest.issueConnectTokenRejectsNonPositiveConnectScopeClaims`.

##### source-02-1-7-1-task-list-auth-entry-negative-path-parity-vertical-slice-1-96: Why This Slice Exists

`02.1.7` already landed the first real guardrail pass for malformed JWTs, incomplete connect context, replay identity mismatch, and gRPC app-error normalization. That closed the first obvious holes, but the family is still broad enough that remaining negative-path drift can survive in smaller entry seams:

- one entry point may reject blank or non-positive identity early while another falls through to a later null lookup or inconsistent error code;
- one path may treat malformed routing claims as `INVALID_ARGUMENT` while another still maps the same condition to an internal error or a downstream domain mismatch;
- newer helper seams can still have callers that parse or normalize the same identity facts locally instead of reusing the canonical validation path;
- focused happy-path proof does not guarantee that every equivalent malformed request shape fails closed with the same semantics.

This slice is for parity work, not architecture invention. The canonical entry contracts already exist; the remaining job is to finish converging equivalent negative paths onto the same owning helpers and tests.

##### source-02-1-7-1-task-list-auth-entry-negative-path-parity-vertical-slice-1-96: Scope

- auth/session/routing entry surfaces that already have a canonical target design and only need negative-path convergence;
- malformed or inconsistent JWT, signed-token, connect-context, session-shell, and routing-bundle inputs that still drift across equivalent live entry points;
- shared helper convergence when multiple entry paths are enforcing the same invariant with slightly different local logic;
- focused tests that prove equivalent invalid inputs produce equivalent fail-closed outcomes.

##### source-02-1-7-1-task-list-auth-entry-negative-path-parity-vertical-slice-1-96: Out of Scope

- redesigning auth/session/routing architecture;
- broad repo-wide null-check scavenger hunts unrelated to canonical auth/session/routing entry seams;
- unrelated happy-path feature work;
- transport/infrastructure failures that legitimately belong on `onError()` instead of application-level errors.

##### source-02-1-7-1-task-list-auth-entry-negative-path-parity-vertical-slice-1-96: Locked Direction

- keep invariant ownership as close as practical to the canonical parser, reader, or helper instead of scattering new caller-local `if (blank)` guards everywhere;
- equivalent malformed or inconsistent input should fail with one stable local outcome across equivalent live surfaces;
- fail closed is the default when identity, scope, or routing truth is incomplete or contradictory;
- negative-path tests should prove real user-visible or operator-visible semantics, not only internal exception type changes.

##### source-02-1-7-1-task-list-auth-entry-negative-path-parity-vertical-slice-1-96: Planned Work

###### source-02-1-7-1-task-list-auth-entry-negative-path-parity-vertical-slice-1-96: 1. Remaining Entry-Point Audit

- [x] Audit the remaining live auth/session/routing entry points that parse or trust claim-shaped or routing-shaped input after `02.1.7`, including shared security helpers, Account Service bootstrap/session paths, Game Session websocket/bootstrap/session readers, and any control-plane auth seams that still normalize identity locally.
- [x] For each still-live seam, record the exact required fields, the malformed/blank/non-positive cases it currently accepts or rejects, and whether equivalent sibling seams already reject those cases differently.
- [x] Ignore stale or already-converged suggestions; this slice is for still-valid parity gaps only.

###### source-02-1-7-1-task-list-auth-entry-negative-path-parity-vertical-slice-1-96: 2. Canonical Helper Follow-Through

- [x] Move any repeated malformed-identity validation that still appears in multiple live entry points onto the owning canonical helper when the invariant is genuinely shared.
- [x] Remove local fallback identity reconstruction when canonical parsed identity is absent, blank, non-positive, or internally contradictory.
- [x] Prefer stable error-code convergence over one-off message-text tweaks.

###### source-02-1-7-1-task-list-auth-entry-negative-path-parity-vertical-slice-1-96: 3. Routing and Session Negative-Path Parity

- [x] Audit remaining entry points that accept or reconstruct routing identity from persisted shells, signed reconnect/connect context, or current session reads.
- [x] Ensure blank `worldSlug` / `realmSlug`, non-positive tenant or game-instance ids, incomplete routing bundles, and inconsistent runtime-target identity fail closed before downstream routing reuse.
- [x] Keep these guards on the owning reader/helper seams wherever practical.

###### source-02-1-7-1-task-list-auth-entry-negative-path-parity-vertical-slice-1-96: 4. Focused Proof

- [x] Add or refresh focused negative-path tests that prove equivalent malformed input shapes now fail consistently across sibling live entry seams.
- [x] Cover malformed token structure, missing required claims, blank string claims, non-positive numeric ids, incomplete routing bundles, and contradictory identity when those cases are live for the touched seam.
- [x] Prove application-level failures stay on canonical `ErrorDetail` semantics rather than escaping as transport errors.

##### source-02-1-7-1-task-list-auth-entry-negative-path-parity-vertical-slice-1-96: Acceptance Shape

- equivalent malformed auth/session/routing inputs no longer drift across sibling live entry surfaces;
- canonical helpers own the shared validation rules instead of repeated caller-local variations;
- no touched entry point falls back to guessed identity or stale routing when canonical parsed identity is incomplete;
- focused negative-path proof is green for every touched seam.

##### source-02-1-7-1-task-list-auth-entry-negative-path-parity-vertical-slice-1-96: Spark Delegation Notes

- Treat this as bounded parity work, not a generic defensive-coding sweep.
- Start by enumerating still-live negative-path drift in one narrow seam, then fix that seam end to end before expanding.
- Return the exact still-valid gaps found, the files changed, the tests run, and the suggested gaps intentionally skipped because they were stale or outside scope.

##### source-02-1-7-1-task-list-auth-entry-negative-path-parity-vertical-slice-1-96: Suggested Starting Surfaces

- `services/common-security`
- `services/account-service`
- `services/game-session-service`

##### source-02-1-7-1-task-list-auth-entry-negative-path-parity-vertical-slice-1-96: Validation

- `./gradlew spotlessApply`
- `./gradlew :common-security:check -PfullCheck`
- `./gradlew :account-service:check -PfullCheck`
- `./gradlew :game-session-service:check -PfullCheck`
- `bash dev-tools/verify-fresh-bootstrap.sh`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-02-1-7-2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice-1-105

#### 02.1.7.2 Task List: Malformed JWT and Claim-Shape Parity Vertical Slice - Malformed token and claim parity (source lines 1-105)

##### Preserved Source Text: source-02-1-7-2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice-1-105

<!-- migration-source path="design/project-management/vertical-slices/02.1.7.2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice.md" lines="1-105" sha256="3d71513148ca329941b9a5a10a3409705646948e5306546fc19518c26ac0240e" heading-offset="3" -->
#### source-02-1-7-2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice-1-105: 02.1.7.2 Task List: Malformed JWT and Claim-Shape Parity Vertical Slice

##### source-02-1-7-2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice-1-105: Goal and Status

Goal: close the remaining malformed JWT, malformed signed-token, and inconsistent claim-shape entry gaps so equivalent bad auth payloads fail the same way across live HTTP, websocket, gRPC, and bootstrap seams. Status: complete.

##### source-02-1-7-2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice-1-105: Current Snapshot (2026-06-29)

- This slice is currently `complete`.
- Canonical malformed-claim handling now covers the bounded remaining seams in this branch: shared gameplay attestation parsing and gateway admin JWT authorization both fail closed on malformed claim shape using the same local semantics already used in sibling HTTP and gRPC auth seams.
- Accuracy note (2026-06-29): bounded parity is complete for the targeted live seams in this slice; future work should reopen only if a new auth entry path drifts back to caller-local malformed-claim handling.

##### source-02-1-7-2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice-1-105: Why This Slice Exists

`02.1.7` and `02.1.7.1` already landed the first real fail-closed pass for malformed auth/session/routing payloads, but one narrow hardening family remains well-suited to a bounded parity batch:

- some live entry seams still parse JWT- or signed-token-shaped claims through local logic instead of the smallest canonical helper path;
- equivalent malformed claim structures can still drift between `PERMISSION_DENIED`, `INVALID_ARGUMENT`, or later fallback behavior depending on the entry surface;
- wrong-type claim payloads, blank subject/account fields, malformed role maps, or partially present scoped-role claims are exactly the kind of smaller-context parity work that should be completed in one bounded pass rather than rediscovered ad hoc later.

This slice is explicitly for parity and helper convergence, not for redesigning auth/session architecture.

##### source-02-1-7-2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice-1-105: Scope

- live JWT and signed-token parsing seams in `common-security`, Account Service, Game Session, and any auth-adjacent gateway/bootstrap entry points that already have a canonical contract;
- malformed or inconsistent claim-shape cases such as blank subject, non-numeric ids where numeric ids are required, wrong JSON/claim types, malformed global/scoped-role shapes, or contradictory identity claims;
- focused tests proving equivalent malformed auth payloads fail with equivalent application semantics.

##### source-02-1-7-2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice-1-105: Out of Scope

- repo-wide generic validation cleanup;
- transport or infrastructure failures that legitimately belong on `onError()`;
- broader positive-path login/session/routing feature work.

##### source-02-1-7-2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice-1-105: Locked Direction

- prefer one canonical claim parser/helper per invariant instead of repeated caller-local shape checks;
- malformed or contradictory claim-shaped input must fail closed before partial session/bootstrap reuse;
- equivalent malformed auth payloads should converge on stable local semantics across sibling entry surfaces.

##### source-02-1-7-2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice-1-105: Completion Notes

- 2026-06-29: Completed. `GameplaySessionAttestationService` now uses canonical `JwtClaims` claim extraction for required and optional attestation claims instead of local string coercion, so malformed iterable/blank claim shapes normalize the same way as sibling auth readers.
- 2026-06-29: Completed. `JwtAuthFilter` now fail-closes malformed claim-shape errors from `SessionClaims` as `401 Unauthorized` instead of leaking an uncaught `IllegalArgumentException` on gateway admin routes.
- 2026-06-29: Focused proof added in `services/common-security/src/test/java/net/firedevops/firemud/common/security/GameplaySessionAttestationServiceTest.java` and `services/spring-cloud-gateway/src/test/java/unit/net/firedevops/firemud/springcloudgateway/filter/JwtAuthFilterTest.java`.

##### source-02-1-7-2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice-1-105: Planned Work

###### source-02-1-7-2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice-1-105: 1. Remaining Claim-Shape Audit

- [x] Enumerate the still-live JWT and signed-token parsing seams after `02.1.7.1`, including HTTP interceptors, websocket/bootstrap filters, connect-context/signed-token helpers, and auth-adjacent service entry points.
- [x] Record which required claims each seam currently trusts and where equivalent malformed claim shapes still drift in behavior.
- [x] Skip already-converged or stale suggestions.

###### source-02-1-7-2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice-1-105: 2. Canonical Helper Convergence

- [x] Move repeated malformed-claim handling onto the smallest owning helper when the invariant is truly shared.
- [x] Remove caller-local fallback identity reconstruction when canonical parsed claims are absent, blank, or contradictory.
- [x] Converge on stable application error semantics rather than surface-local exception behavior.

###### source-02-1-7-2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice-1-105: 3. Focused Negative-Path Proof

- [x] Add or refresh focused tests for malformed token structure, wrong-type claims, blank claim strings, malformed role maps, contradictory account/tenant identity, and equivalent invalid signed-token cases where live.
- [x] Prove touched seams return canonical application-level errors rather than partial success or transport leakage.

##### source-02-1-7-2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice-1-105: Completion Evidence

- `services/common-security/src/main/java/net/firedevops/firemud/common/security/GameplaySessionAttestationService.java`
- `services/spring-cloud-gateway/src/main/java/net/firedevops/firemud/springcloudgateway/filter/JwtAuthFilter.java`
- `services/common-security/src/test/java/net/firedevops/firemud/common/security/GameplaySessionAttestationServiceTest.java`
- `services/spring-cloud-gateway/src/test/java/unit/net/firedevops/firemud/springcloudgateway/filter/JwtAuthFilterTest.java`

##### source-02-1-7-2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice-1-105: Accuracy Notes

- Confirmed complete as of 2026-06-29 for the bounded seams actually targeted here: shared attestation parsing and gateway admin JWT authorization.
- Existing docs and tests still show broader auth-helper adoption outside this slice, but no stale open item remains inside this bounded claim-shape parity batch.

##### source-02-1-7-2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice-1-105: Acceptance Shape

- equivalent malformed JWT or signed-token claim shapes no longer drift across sibling entry surfaces;
- canonical claim helpers own the touched validation rules;
- no touched seam falls back to guessed identity when canonical claim truth is malformed or contradictory;
- focused negative-path proof is green for every touched seam.

##### source-02-1-7-2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice-1-105: Spark Delegation Notes

- Keep the batch narrow: malformed JWT and signed-token claim-shape parity only.
- Enumerate adopters first, then update the owning helper plus direct callers in one pass.
- Return exact changed files, exact still-valid gaps, and exact validation commands run.

##### source-02-1-7-2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice-1-105: Suggested Starting Surfaces

- `services/common-security`
- `services/account-service`
- `services/game-session-service`
- `services/spring-cloud-gateway`

##### source-02-1-7-2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice-1-105: Validation

- `./gradlew spotlessApply`
- `./gradlew :common-security:check -PfullCheck`
- `./gradlew :account-service:check -PfullCheck`
- `./gradlew :game-session-service:check -PfullCheck`
- `./gradlew :spring-cloud-gateway:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-02-1-7-3-task-list-positive-identity-and-routing-bundle-guardrails-vertical-slice-1-87

#### 02.1.7.3 Task List: Positive Identity and Routing-Bundle Guardrails Vertical Slice - Positive identity and routing guardrails (source lines 1-87)

##### Preserved Source Text: source-02-1-7-3-task-list-positive-identity-and-routing-bundle-guardrails-vertical-slice-1-87

<!-- migration-source path="design/project-management/vertical-slices/02.1.7.3-task-list-positive-identity-and-routing-bundle-guardrails-vertical-slice.md" lines="1-87" sha256="7bab80182d471147bcd9f44e17a6318361d0a1da78aaf804d1a4bd3bcb480c76" heading-offset="3" -->
#### source-02-1-7-3-task-list-positive-identity-and-routing-bundle-guardrails-vertical-slice-1-87: 02.1.7.3 Task List: Positive Identity and Routing-Bundle Guardrails Vertical Slice

##### source-02-1-7-3-task-list-positive-identity-and-routing-bundle-guardrails-vertical-slice-1-87: Goal and Status

Goal: finish the remaining non-positive identifier, blank routing identity, and partial routing-bundle fail-closed guards across live auth/session/routing entry seams. Status: complete.

##### source-02-1-7-3-task-list-positive-identity-and-routing-bundle-guardrails-vertical-slice-1-87: Current Snapshot (2026-06-29)

- This slice is currently `complete` in this branch.
- Representative completion evidence includes `JwtClaims`/`SessionClaims` enforcement and `non-positive`/routing-bundle guardrails in auth and session seams.
- Keep future edits scoped to new invalid-shape regressions only; re-open only if claim-shape/route behavior drifts again.

##### source-02-1-7-3-task-list-positive-identity-and-routing-bundle-guardrails-vertical-slice-1-87: Accuracy Notes

- Confirmed as of 2026-06-29 that live auth/session seams are still using canonical helpers for required identity and routing-bundle validation.
- No stale open items remain in this slice-specific scope; keep this branch focused to regression guards when drift appears.

##### source-02-1-7-3-task-list-positive-identity-and-routing-bundle-guardrails-vertical-slice-1-87: Why This Slice Exists

The broader `02.1.7` family already proved the architecture: canonical readers and helpers own routing/session truth, and callers should fail closed instead of reconstructing identity locally. The remaining work is a bounded parity cut:

- some seams still reject blank or non-positive ids early while sibling seams allow the same values to fall through to later mismatches or null lookups;
- partial `{worldSlug, realmSlug, pointerVersion}` bundles can still survive on some entry/readback paths because one field family is validated locally and another only downstream;
- this is exactly the kind of mechanical helper-plus-adopters convergence that a smaller-context worker can complete if the scope is specified tightly.

##### source-02-1-7-3-task-list-positive-identity-and-routing-bundle-guardrails-vertical-slice-1-87: Scope

- non-positive or blank `tenantId`, `accountId`, `sessionId`, `gameInstanceId`, `characterId`, and `pointerVersion` handling where those identifiers are already canonically positive;
- blank `worldSlug` / `realmSlug` and partial routing-bundle handling on live auth/session/routing entry seams;
- focused proof for fail-closed identity and routing-bundle semantics.

##### source-02-1-7-3-task-list-positive-identity-and-routing-bundle-guardrails-vertical-slice-1-87: Out of Scope

- changing the canonical routing or session architecture;
- generic repo-wide null-check cleanup;
- deeper cutover/reconnect feature work already owned by `09.1`.

##### source-02-1-7-3-task-list-positive-identity-and-routing-bundle-guardrails-vertical-slice-1-87: Locked Direction

- keep guards on the owning canonical reader/helper seam wherever practical;
- non-positive identifiers and partial routing bundles should fail before downstream reuse, not after ad hoc fallback behavior;
- equivalent invalid identity shapes should converge on one local fail-closed outcome across sibling entry surfaces.

##### source-02-1-7-3-task-list-positive-identity-and-routing-bundle-guardrails-vertical-slice-1-87: Planned Work

###### source-02-1-7-3-task-list-positive-identity-and-routing-bundle-guardrails-vertical-slice-1-87: 1. Identifier and Routing Audit

- [x] Enumerate still-live entry seams that accept or reconstruct positive identifiers or routing bundles after `02.1.7.1`.
- [x] Record where equivalent non-positive ids, blank selectors, or partial routing bundles still drift across sibling seams.
- [x] Ignore already-converged or stale findings.

###### source-02-1-7-3-task-list-positive-identity-and-routing-bundle-guardrails-vertical-slice-1-87: 2. Canonical Guardrail Follow-Through

- [x] Move touched positive-id and routing-bundle guards onto the smallest owning helper when the invariant is shared.
- [x] Remove caller-local fallback behavior that preserves partially known identity after canonical validation fails.
- [x] Keep touched semantics aligned with already-live canonical session/routing authority.

###### source-02-1-7-3-task-list-positive-identity-and-routing-bundle-guardrails-vertical-slice-1-87: 3. Focused Proof

- [x] Add or refresh focused tests for zero/negative ids, blank routing selectors, missing pointer version inside an otherwise present routing bundle, and any other still-live invalid identity shape the batch tightens.
- [x] Prove touched seams fail closed before routing reuse or session-shell preservation.

##### source-02-1-7-3-task-list-positive-identity-and-routing-bundle-guardrails-vertical-slice-1-87: Acceptance Shape

- touched entry seams no longer accept non-positive canonical identifiers or partial routing bundles;
- canonical helpers own the touched identity/routing guards instead of repeated local checks;
- focused negative-path proof is green for the still-live seams covered by the batch.

##### source-02-1-7-3-task-list-positive-identity-and-routing-bundle-guardrails-vertical-slice-1-87: Spark Delegation Notes

- Keep this batch limited to positive-identifier and routing-bundle guards.
- Start with one authoritative checklist of identifiers and bundle fields, then enumerate adopters from there.
- Return exact changed files, exact invalid shapes covered, and exact validation commands run.

##### source-02-1-7-3-task-list-positive-identity-and-routing-bundle-guardrails-vertical-slice-1-87: Suggested Starting Surfaces

- `services/common-security`
- `services/account-service`
- `services/game-session-service`

##### source-02-1-7-3-task-list-positive-identity-and-routing-bundle-guardrails-vertical-slice-1-87: Validation

- `./gradlew spotlessApply`
- `./gradlew :common-security:check -PfullCheck`
- `./gradlew :account-service:check -PfullCheck`
- `./gradlew :game-session-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-02-2-1-task-list-session-start-admission-ordering-and-ip-limit-safety-vertical-slice-1-64

#### Session Start Admission Ordering and IP-Limit Safety Vertical Slice - Session-start admission ordering (source lines 1-64)

##### Preserved Source Text: source-02-2-1-task-list-session-start-admission-ordering-and-ip-limit-safety-vertical-slice-1-64

<!-- migration-source path="design/project-management/vertical-slices/02.2.1-task-list-session-start-admission-ordering-and-ip-limit-safety-vertical-slice.md" lines="1-64" sha256="817871bdb577eb343be67a4422cd748565b873ea7995292167727526f7c5e398" heading-offset="3" -->
#### source-02-2-1-task-list-session-start-admission-ordering-and-ip-limit-safety-vertical-slice-1-64: Session Start Admission Ordering and IP-Limit Safety Vertical Slice

##### source-02-2-1-task-list-session-start-admission-ordering-and-ip-limit-safety-vertical-slice-1-64: Goal and Status

Goal: make session-start admission safe so IP-cap or similar admission rejection cannot tear down an already-running live session before the new session is actually accepted. Status: complete.

##### source-02-2-1-task-list-session-start-admission-ordering-and-ip-limit-safety-vertical-slice-1-64: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end-to-end.
- [x] Verify and close any follow-ups.

##### source-02-2-1-task-list-session-start-admission-ordering-and-ip-limit-safety-vertical-slice-1-64: Why This Slice Exists

Current session startup can stop an existing running session before the API layer applies IP-admission checks for the new attempt. That creates a bad failure mode:

- the new session is rejected;
- the old session is already gone;
- the caller loses a valid existing session because of admission ordering rather than an intentional replacement.

This needs a dedicated follow-up because it is not just a validation bug; it is a lifecycle-ordering contract problem between gameplay admission and runtime session replacement.

##### source-02-2-1-task-list-session-start-admission-ordering-and-ip-limit-safety-vertical-slice-1-64: Scope

- Define the safe ordering between:
  - existing-session replacement;
  - new-session creation;
  - IP/admission registration;
  - runtime-state propagation.
- Define what should happen when a new admission is rejected after an existing live session is found.
- Keep the final user-visible behavior coherent with the canonical `LOGIN` / `PLAY` / session-lifecycle design.

##### source-02-2-1-task-list-session-start-admission-ordering-and-ip-limit-safety-vertical-slice-1-64: Implementation Notes

Implemented now:

- public gRPC session start preflights IP admission before replacing an existing running session;
- replacement admission keeps the old session alive until the new session is created and the IP slot is successfully transferred or reserved;
- failed replacement admission now stops only the tentative new session and leaves the existing live session untouched;
- replacement-aware IP reservation transfer exists so same-IP replacement does not falsely trip the cap.
- REST `/sessions` is now explicitly treated as a non-canonical operator/bootstrap path and no longer uses the destructive replace-first default session-start behavior.
- the shared `GameInstanceService.startSession(request)` default is now non-destructive, so callers must opt into replacement explicitly instead of inheriting replace-first behavior accidentally.

Verification notes:

- the canonical gRPC admission path now preflights IP admission before replacement and has focused unit coverage for rejection, transfer, and failure ordering;
- the non-canonical REST/operator path stays non-destructive by default and no longer inherits replace-first behavior accidentally.

Locked now:

- `GameSessionGrpcService.startSession` is the canonical gameplay admission seam;
- once the replacement session is admitted successfully, failure to tear down the old session is downgraded to warning/cleanup follow-up rather than revoking the newly accepted session.
- non-gRPC/operator/dev/test entry points must not become parallel canonical gameplay admission models;
- if a non-gRPC path creates gameplay sessions at all, it should delegate into the same replacement-safe workflow or remain explicitly non-canonical.

##### source-02-2-1-task-list-session-start-admission-ordering-and-ip-limit-safety-vertical-slice-1-64: Out of Scope

- Broader auth redesign.
- Cross-game or social presence rules.
- Preview-only admission glue.

##### source-02-2-1-task-list-session-start-admission-ordering-and-ip-limit-safety-vertical-slice-1-64: Remaining Follow-Up Discussion

- none currently beyond keeping future non-gRPC/operator entry points explicitly non-canonical unless they fully delegate.
<!-- /migration-source -->

### source-02-3-task-list-reconnect-and-session-recovery-vertical-slice-1-161

#### Reconnect and Session Recovery Semantics Vertical Slice Task List - Reconnect and session recovery (source lines 1-161)

##### Preserved Source Text: source-02-3-task-list-reconnect-and-session-recovery-vertical-slice-1-161

<!-- migration-source path="design/project-management/vertical-slices/02.3-task-list-reconnect-and-session-recovery-vertical-slice.md" lines="1-161" sha256="dc7ed70cf0c10170d334298d0dcca087a009ef7b4d659d1fbb1a1ae031a4b25a" heading-offset="3" -->
#### source-02-3-task-list-reconnect-and-session-recovery-vertical-slice-1-161: Reconnect and Session Recovery Semantics Vertical Slice Task List

##### source-02-3-task-list-reconnect-and-session-recovery-vertical-slice-1-161: Goal and Status

Goal: align the implemented reconnect and takeover path with the explicit recovery model described in the design docs so Telnet and generic WebSocket clients recover consistently and observably after disconnects, edge restarts, revocations, and stale-session loss. Status: baseline live; gameplay identity keying, same-session resume preservation, runtime membership and entitlement checks in `PLAY`, canonical reconnect docs, WebSocket plus Telnet reconnect-after-move coverage, bounded per-player screen-buffer restore plus fresh `LOOK` redraw, Telnet bridge-close taxonomy preservation, and Gateway close-reason propagation tests are now implemented. Manual QA remains, while broader non-edge failover invisibility and first-party connect-token parity now live in dedicated follow-up slices rather than stretching this slice indefinitely.

This slice is a follow-up to **Login and Session** and **Login and Session Hardening**. It does not rework the basic gameplay loop. It focuses on making reconnect behavior trustworthy, explicit, and testable for the currently implemented Telnet and generic WebSocket gameplay paths.

Implementation note:

- The initial runtime authority pass using `GetTenantMembershipForRuntime(accountId, tenantId)` and `GetTenantEntitlementsForRuntime(tenantId)` is now live.
- This slice now stops at the reconnect contract that is executable in the current generic WebSocket and Telnet runtime.
- Broader first-party bootstrap/connect-token parity moved to `02.4-task-list-first-party-reconnect-parity-vertical-slice.md`.
- Shared-state failover prerequisites moved to `02.5-task-list-non-edge-failover-invisibility-vertical-slice.md`.
- True non-edge restart invisibility moved to `02.6-task-list-live-backend-rebind-invisibility-vertical-slice.md`.

##### source-02-3-task-list-reconnect-and-session-recovery-vertical-slice-1-161: Target-State Constraint

- [x] Treat **non-edge restart invisibility** as a first-class design goal. Restarts of Game Session, Game Logic, and other non-edge gameplay services should ideally cause at most a short stall while shared Redis-backed state and durable coordination recover ownership.
- [x] Treat visible reconnect requirements after non-edge restarts as implementation debt unless a hard edge transport loss also occurred.
- [x] Preserve the explicit `LOGIN` / `PLAY` recovery contract for edge reconnects, but do not let that explicit contract become an excuse for making ordinary non-edge restarts user-visible.

##### source-02-3-task-list-reconnect-and-session-recovery-vertical-slice-1-161: 1. Canonical Recovery Model Review

- [x] Re-read the [Reconnection Strategy](../../architecture/system-architecture-reconnection.md), [Authentication & Authorization](../../architecture/system-architecture-authentication.md), [Game Session protocols](../../architecture/microservices/game-session-service/protocols.md), [TCP Proxy protocols](../../architecture/microservices/tcp-proxy-service/protocols.md), and [Spring Cloud Gateway client behavior](../../architecture/microservices/spring-cloud-gateway/client-behavior.md) docs and identify the parts that are currently design-only versus implemented.
- [x] Make one canonical recovery sequence explicit for each transport class:
  - Telnet
  - generic WebSocket
  - first-party `/ws/game/**`
- [x] Ensure every flow states clearly:
  - when a fresh `LOGIN` is required
  - when a fresh `PLAY` is required
  - when a fresh first-party connect token is required
  - what constitutes resume vs fresh session start

##### source-02-3-task-list-reconnect-and-session-recovery-vertical-slice-1-161: 2. Session Resume and Takeover Semantics in Game Session

- [x] Before changing this service for the slice, run `./gradlew :game-session-service:test` and stabilize the baseline if necessary.
- [x] Tighten the implementation around the canonical gameplay uniqueness key `{tenantId, gameInstanceId, characterId}` so takeover and resume behavior is consistently keyed there rather than on weaker socket/session hints.
- [x] Ensure resume is authorized from current identity and current membership/revocation state, not from the prior backend token alone.
- [x] Add or refine explicit failure paths for:
  - expired gameplay session
  - revoked membership
  - changed tenant/world access
  - stale or missing room/game-instance context
- [x] Document and test exactly what session state is reconstructed after resume:
  - room snapshot replay or rerun
  - command queue expectations
  - what is intentionally not replayed

##### source-02-3-task-list-reconnect-and-session-recovery-vertical-slice-1-161: 3. Edge-Restart and Disconnect Classification

- [x] Add or refine tests and docs for reconnect behavior after:
  - TCP Proxy restart
  - Gateway restart / planned drain
  - upstream unavailability
- [x] For Game Session and other non-edge restarts, distinguish clearly between:
  - target-state invisible restart behavior
  - current implementation-visible behavior that still needs to be removed
- [x] Ensure WebSocket close-code and Telnet disconnect-category mapping is stable, bounded, and documented enough for first-party and reference clients to implement retry policy correctly.
- [x] Ensure missing close metadata and abnormal transport loss still map into a deterministic retry class.
- [x] Add cross-service tests that prove the intended disconnect classification rather than only asserting eventual reconnect success.

Current implementation note:

- Gateway WebSocket integration tests already prove explicit clean logout and explicit `internal_error` close-reason propagation.
- TCP Proxy unit and cross-service tests already prove deterministic Telnet mapping for planned drain, takeover logout, explicit `internal_error`, and missing-close-metadata fallback to `backend_unavailable`.
- The architecture docs now distinguish target-state invisible non-edge failover from today's still-visible edge-boundary failures; visible reconnect after Game Session or other non-edge restarts remains implementation debt rather than intended client behavior.
- Broader restart invisibility for non-edge services remains open and is intentionally split between `02.5-task-list-non-edge-failover-invisibility-vertical-slice.md` for shared-state prerequisites and `02.6-task-list-live-backend-rebind-invisibility-vertical-slice.md` for actual live backend rebind.

##### source-02-3-task-list-reconnect-and-session-recovery-vertical-slice-1-161: 4. Telnet Reconnect and Smart-Client Metadata

- [x] Make explicit whether advanced Telnet attach hints remain useful during reconnect and, if so, how they interact with the resumed gameplay binding without becoming authoritative.
- [x] Add focused Telnet reconnect coverage for:
  - reconnect without hidden smart-client metadata
  - duplicate or late disconnect hints
  - stale-session expiry
- [x] Ensure Telnet clients never appear to get a hidden transport-preserving recovery on the same TCP socket; reconnect must remain explicit and player-visible.

Current implementation note:

- `SESSION` is intentionally gone from the canonical flow in initial development.
- If smart-client attach metadata returns later, it should arrive as hidden MCP-style transport metadata rather than as a typed gameplay line and should be treated as a future follow-up, not as unfinished work for this slice.

##### source-02-3-task-list-reconnect-and-session-recovery-vertical-slice-1-161: 5. First-Party Web Reconnect Semantics

- [x] Explicitly defer first-party `/ws/game/**` reconnect parity to a dedicated follow-up slice instead of pretending the current generic reconnect implementation already satisfies the connect-token and signed-connect-context design.
- [x] Document how much of this recovery is automated by the first-party client versus what remains the canonical protocol-visible contract.

##### source-02-3-task-list-reconnect-and-session-recovery-vertical-slice-1-161: 6. Recovery UX and Player-Facing Feedback

- [x] Define the bounded player-facing outputs for:
  - resume succeeded
  - previous resumable state was stale or expired and a fresh session was entered automatically
  - membership/access changed so resume was denied
  - backend unavailable during reconnect
- [x] Ensure expiry of a resumable session does not normally surface as a “type the same command again” error when fresh `PLAY` admission can be performed automatically.
- [x] Ensure these outputs are player-helpful rather than backend-internal, while still preserving enough structure for clients to act on them.
- [x] Update transcripts/examples to show at least one successful resume and one stale-session fallback that fresh-enters gameplay automatically.

Current first-pass policy:

- revoked membership or removed tenant/world access fails closed;
- tenant gameplay unavailable fails closed;
- stale, expired, or partially missing resumable state falls through to invisible fresh entry whenever current `PLAY` admission still succeeds;
- backend authority unavailability fails closed with retryable temporary-unavailable messaging.

Current reconstruction contract:

- reconnect restores a bounded durable per-player transcript keyed to gameplay identity when one exists; Redis may cache it but is not authoritative;
- reconnect then emits a fresh `LOOK` redraw so current authoritative state wins over any stale transcript context;
- prompts are treated as a distinct output class and are not part of the reconnect transcript buffer by default;
- received-but-not-yet-processed commands and unsent transient output are not durably replayed across reconnect.

##### source-02-3-task-list-reconnect-and-session-recovery-vertical-slice-1-161: 7. Observability and Operator Confidence

- [x] Audit and, if needed, add metrics/logs for:
  - successful resume
  - takeover
  - failed resume due to revocation/expiry
  - reconnect after edge restart
  - dropped or duplicate disconnect hints
- [x] Ensure the logs and metrics are enough for operators to distinguish:
  - real player disconnects
  - edge instability
  - session-staleness cleanup
  - revocation/authorization failures

Current implementation note:

- Game Session now emits bounded reconnect metrics for `gamesession.session.resume`, `gamesession.session.takeover`, `gamesession.session.resume_denied`, and `gamesession.session.fresh_entry_fallback`.
- Game Session disconnect-notify handling also meters duplicate and missing-context hints via `gamesession.notifydisconnect.duplicate` and `gamesession.notifydisconnect.missing_context`.
- TCP Proxy already meters bridge shutdown classes and disconnect notification failures, including `tcpproxy.bridge.shutdown`, `tcpproxy.disconnect.notify.transport_failure`, and `tcpproxy.disconnect.notify.app_error`.
- Additional operator-facing synthesis may still be useful later, but the basic per-class counters now separate player logout/takeover classes from retryable transport failures, stale-session fallback, and authorization denials well enough for first-pass operational triage.

##### source-02-3-task-list-reconnect-and-session-recovery-vertical-slice-1-161: 8. Cross-Service End-to-End Tests

- [x] Add or refresh WebSocket and Telnet cross-service tests for:
  - reconnect after voluntary disconnect
  - reconnect after Gateway restart / planned drain where current edge-boundary visibility applies
  - takeover from a second client
  - failed resume after revocation or stale-session expiry
- [x] Keep the tests bounded and representative; do not grow this slice into a full chaos/recovery matrix.

##### source-02-3-task-list-reconnect-and-session-recovery-vertical-slice-1-161: 9. Final QA Checklist

- [ ] Manually verify one happy-path reconnect over Telnet and one over WebSocket.
- [ ] Manually verify one takeover flow and one failed-resume flow.
- [x] Confirm that the implemented behavior now matches the documented recovery model for the current generic WebSocket and Telnet reconnect paths, with first-party parity and non-edge invisibility tracked separately.

---

##### source-02-3-task-list-reconnect-and-session-recovery-vertical-slice-1-161: Deferred Follow-Up

- Pull the broader gameplay-admission target forward once the first runtime authority pass is live:
  - `GetAdmissionPointer(tenantId, worldSlug, realmSlug)` integration
  - `EnsurePublicProductionPlayerMembership(accountId, tenantId, worldSlug, realmSlug, requestId)` for first public-production admission
  - richer `REALMS` / `CHARS` parity
- See `02.4-task-list-first-party-reconnect-parity-vertical-slice.md` for first-party `/ws/game/**` reconnect and connect-token parity.
- See `02.5-task-list-non-edge-failover-invisibility-vertical-slice.md` for the shared-state coordination prerequisite and `02.6-task-list-live-backend-rebind-invisibility-vertical-slice.md` for true backend failover without visible reconnect.
- A later slice may extend this into richer replay/reconstruction, broader buffered-context recovery, or admin-driven live session transfer if those become product priorities.
<!-- /migration-source -->

### source-02-4-task-list-first-party-reconnect-parity-vertical-slice-1-55

#### First-Party Reconnect Parity Vertical Slice Task List - First-party reconnect authority (source lines 1-55)

##### Preserved Source Text: source-02-4-task-list-first-party-reconnect-parity-vertical-slice-1-55

<!-- migration-source path="design/project-management/vertical-slices/02.4-task-list-first-party-reconnect-parity-vertical-slice.md" lines="1-55" sha256="ce9e63e2db61683cdca2b52bc7c16fee888a6271112b27671c2cd0d417029a90" heading-offset="3" -->
#### source-02-4-task-list-first-party-reconnect-parity-vertical-slice-1-55: First-Party Reconnect Parity Vertical Slice Task List

##### source-02-4-task-list-first-party-reconnect-parity-vertical-slice-1-55: Goal and Status

Goal: bring the implemented first-party `/ws/game/**` reconnect path into line with the connect-token and gateway-signed connect-context design so first-party web recovery is governed by the same documented rules rather than hand-waved as equivalent to generic WebSocket reconnect. Status: baseline live; manual QA still pending.

This follow-up is intentionally separate from `02.3`. The generic Telnet and WebSocket reconnect path stands on its own, while this slice closes the first-party `/ws/game/**` gap by making connect-token handshake enforcement, gateway-signed connect-context validation, bare first-party `LOGIN`, and reconnect redraw behavior real in code. Manual QA and broader future admission work still remain outside this slice.

##### source-02-4-task-list-first-party-reconnect-parity-vertical-slice-1-55: Implementation Notes

- Account Service now exposes `POST /auth/player-bootstrap` and `POST /auth/connect-token` for the first-party gameplay bootstrap path.
- Spring Cloud Gateway now enforces fresh connect-token presentation on non-proxy `/ws/game/**` handshakes, emits deterministic handshake failure classes, and forwards a signed `X-Firemud-Connect-Context` plus `X-Firemud-Connection-Mode: first_party_web` on successful first-party handshakes.
- Game Session now validates the signed connect context, allows bare first-party `LOGIN` to consume the already-verified bootstrap identity, and keeps `PLAY` as the gameplay-binding step subject to the same runtime membership and entitlement checks as other reconnect flows.
- First-party reconnect now restores the bounded screen buffer and then emits a fresh `LOOK` after successful `LOGIN` + `PLAY`.
- Broader admission-pointer, richer discovery, and public-production first-join creation work remain future architecture tasks and are not part of this slice.

##### source-02-4-task-list-first-party-reconnect-parity-vertical-slice-1-55: 1. Runtime Authority and Handshake Inputs

- [x] Audit the current first-party `/ws/game/**` runtime path and identify exactly which pieces of the documented design are still absent in code:
  - fresh connect-token acquisition after disconnect,
  - gateway-signed `X-Firemud-Connect-Context`,
  - replay/expiry checks,
  - and deterministic scope validation.
- [x] Update the Gateway and Game Session docs so the target-state contract is stated once and referenced consistently from the reconnect docs.

##### source-02-4-task-list-first-party-reconnect-parity-vertical-slice-1-55: 2. Gateway Enforcement

- [x] Implement or tighten Gateway enforcement so first-party gameplay reconnect requires a fresh valid connect token after disconnect.
- [x] Ensure Gateway produces deterministic handshake failure classes for:
  - missing token,
  - expired token,
  - replayed token,
  - invalid signed connect context,
  - and scope mismatch.
- [x] Add integration tests proving the expected HTTP / close-reason behavior for these failure classes.

##### source-02-4-task-list-first-party-reconnect-parity-vertical-slice-1-55: 3. Game Session Admission Semantics

- [x] Ensure bare first-party `LOGIN` still consumes already-verified bootstrap/connect identity rather than reintroducing credential-style semantics at the gameplay socket.
- [x] Ensure `PLAY` remains the gameplay-binding step after reconnect.
- [x] Ensure first-party reconnect uses current runtime membership and entitlement checks the same way generic reconnect already does.

##### source-02-4-task-list-first-party-reconnect-parity-vertical-slice-1-55: 4. Cross-Service Coverage

- [x] Add focused first-party reconnect tests proving:
  - reconnect requires a fresh connect token,
  - `LOGIN` consumes verified identity,
  - `PLAY` binds gameplay scope,
  - stale or mismatched connect scope fails deterministically,
  - and successful reconnect still restores transcript buffer plus fresh `LOOK`.

##### source-02-4-task-list-first-party-reconnect-parity-vertical-slice-1-55: 5. Final QA Checklist

- [ ] Manually verify one happy-path first-party reconnect and one failed reconnect due to invalid/expired connect-token state.
- [x] Confirm the implemented first-party reconnect behavior matches the documented bootstrap/connect-token model rather than the generic WebSocket fallback assumptions.
<!-- /migration-source -->

### source-02-5-task-list-non-edge-failover-invisibility-vertical-slice-1-45

#### Non-Edge Failover Invisibility Vertical Slice Task List - Shared reconnect continuity state (source lines 1-45)

##### Preserved Source Text: source-02-5-task-list-non-edge-failover-invisibility-vertical-slice-1-45

<!-- migration-source path="design/project-management/vertical-slices/02.5-task-list-non-edge-failover-invisibility-vertical-slice.md" lines="1-45" sha256="bdbf926c95d15e968a1be1cea6c647cd4662c720e8c83b61b38f366f861ddf53" heading-offset="3" -->
#### source-02-5-task-list-non-edge-failover-invisibility-vertical-slice-1-45: Non-Edge Failover Invisibility Vertical Slice Task List

##### source-02-5-task-list-non-edge-failover-invisibility-vertical-slice-1-45: Goal and Status

Goal: externalize the reconnect-critical state that still lives only inside one Game Session instance so same-type instances can safely take over from shared state later, instead of failing over from hidden process-local assumptions. Status: baseline live; manual QA remains.

This follow-up is intentionally separate from `02.3`. The current reconnect slice already documents that visible reconnect after non-edge restart is implementation debt. This slice covers the bounded prerequisite work: moving reconnect-critical coordination state out of local memory and into shared storage. The larger live-upstream rebind work is intentionally split into a dedicated follow-up slice.

##### source-02-5-task-list-non-edge-failover-invisibility-vertical-slice-1-45: 1. Service-State Audit

- [x] Re-read the Game Session, Game Logic, Redis, and protocol-bridging docs and verify that meaningful live gameplay state is externalized rather than hidden in process-local memory.
- [x] Identify any remaining process-local state that prevents same-type instances from taking over safely after restart.
- [x] Document one canonical ownership model for:
  - gameplay session context,
  - reconnect/connect-context state,
  - disconnect deduplication and reconnect hints,
  - command/tick coordination inputs,
  - and screen-buffer access.

##### source-02-5-task-list-non-edge-failover-invisibility-vertical-slice-1-45: 2. Shared Coordination Prerequisites

- [x] Replace reconnect-critical in-memory registries with shared-state-backed implementations where feasible.
- [x] Ensure disconnect dedupe and first-party connect-context tracking survive Game Session restart within their bounded TTL windows.
- [x] Keep local-only state clearly limited to the truly non-shareable parts of a live process, such as the actual in-process WebSocket object.

##### source-02-5-task-list-non-edge-failover-invisibility-vertical-slice-1-45: 3. Edge and Retry Semantics

- [x] Keep the client-visible retry taxonomy honest:
  - edge process loss may still drop sockets bound to that process;
  - non-edge restart should not masquerade as a clean logout;
  - and local process-memory cleanup must not silently reintroduce hidden single-instance assumptions.
- [x] Ensure the shared-state prerequisite work is documented separately from the later live edge-session rebind work so “failover-ready coordination” does not get confused with “already invisible to players”.

##### source-02-5-task-list-non-edge-failover-invisibility-vertical-slice-1-45: 4. Regression Coverage

- [x] Add focused tests proving the new shared-state pieces survive instance-local loss assumptions:
  - reconnect/connect-context lookup still works after registry replacement,
  - duplicate disconnect hints are still suppressed after dedupe replacement,
  - and screen-buffer access continues to use gameplay identity rather than local socket identity.
- [x] Keep the coverage bounded; do not turn this into a full cluster-chaos matrix.

##### source-02-5-task-list-non-edge-failover-invisibility-vertical-slice-1-45: 5. Final QA Checklist

- [ ] Manually verify the shared-state replacements preserve current reconnect behavior for one resumed session and one fresh-entry fallback.
- [x] Confirm the remaining live backend rebind work is explicitly carried into the follow-up invisibility slice rather than implied to be done here.
<!-- /migration-source -->

### source-02-6-task-list-live-backend-rebind-invisibility-vertical-slice-1-38

#### Live Backend Rebind Invisibility Vertical Slice Task List - Live backend rebind continuity (source lines 1-38)

##### Preserved Source Text: source-02-6-task-list-live-backend-rebind-invisibility-vertical-slice-1-38

<!-- migration-source path="design/project-management/vertical-slices/02.6-task-list-live-backend-rebind-invisibility-vertical-slice.md" lines="1-38" sha256="6d43982198385256245884b18d5a10f39569dddf81ac574623a99253934d04ce" heading-offset="3" -->
#### source-02-6-task-list-live-backend-rebind-invisibility-vertical-slice-1-38: Live Backend Rebind Invisibility Vertical Slice Task List

##### source-02-6-task-list-live-backend-rebind-invisibility-vertical-slice-1-38: Goal and Status

Goal: establish the live Gateway-side backend rebind mechanism behind an already-connected edge socket so bounded upstream loss causes at most a brief stall instead of an immediate forced reconnect. Status: baseline live for the Gateway-owned gameplay WebSocket bridge, stable edge transport session ids, and focused upstream rebind proof; the focused Game Session process-bounce proof now lives in `02.7-task-list-process-restart-invisibility-vertical-slice.md`, while Game Logic continuity continues in `02.8-task-list-game-logic-restart-invisibility-vertical-slice.md`.

This slice intentionally follows `02.5`. It assumes reconnect-critical coordination state has already been externalized enough that a replacement same-type instance can reconstruct responsibility safely from shared state.

##### source-02-6-task-list-live-backend-rebind-invisibility-vertical-slice-1-38: Implementation Notes

- Gateway now owns `/ws/game/**` through a custom gameplay WebSocket bridge instead of leaving that path on the stock proxied session route.
- The bridge preserves a stable edge-owned transport session identifier for the lifetime of the downstream socket and reuses it when the upstream Game Session socket is rebound.
- A focused integration proof now demonstrates abrupt upstream loss followed by successful upstream rebind without dropping the downstream client socket.
- This slice intentionally stops at the live bridge and focused abrupt-upstream-loss rebind proof. Game Session process-bounce continuity is split into `02.7-task-list-process-restart-invisibility-vertical-slice.md`, and Game Logic restart continuity is split into `02.8-task-list-game-logic-restart-invisibility-vertical-slice.md`.

##### source-02-6-task-list-live-backend-rebind-invisibility-vertical-slice-1-38: 1. Rebind Model

- [x] Define the canonical rebind model for an already-established edge connection when its current Game Session instance disappears.
- [x] Make explicit which parts of the bridge stay edge-owned and which parts may be rebound to a replacement backend instance.
- [x] Keep the model honest about the bounded loss envelope for in-flight commands or outputs during the switchover.

##### source-02-6-task-list-live-backend-rebind-invisibility-vertical-slice-1-38: 2. Live Upstream Rebind

- [x] Replace the current static Gateway gameplay WebSocket proxy path with a custom gameplay bridge that can reconnect upstream without dropping the downstream socket immediately.
- [x] Ensure the edge owns a stable transport session identifier that survives upstream Game Session reconnection and is reused on the rebound upstream socket.
- [x] Ensure bounded upstream loss can cause at most a brief stall for an already-connected client rather than an immediate visible `LOGIN` / `PLAY` cycle.
- [x] Reconstruct backend transport-session handling from shared or deterministic edge-owned state rather than relying on the dead instance.
- [x] Keep existing edge-visible taxonomy only for the cases where the edge route itself really dropped.

##### source-02-6-task-list-live-backend-rebind-invisibility-vertical-slice-1-38: 3. Regression Coverage

- [x] Add one focused proof for upstream loss invisibility with the downstream socket preserved while the Gateway bridge rebounds the upstream Game Session connection.
- [x] Keep the suite bounded and operationally meaningful; do not turn it into generalized chaos testing.

##### source-02-6-task-list-live-backend-rebind-invisibility-vertical-slice-1-38: 4. Final QA Checklist

- [ ] Manually verify one abrupt upstream loss / rebind path while a client remains connected at the edge.
- [ ] Confirm the player sees continued gameplay after a brief stall rather than a forced explicit reconnect flow.
<!-- /migration-source -->

### source-02-7-task-list-process-restart-invisibility-vertical-slice-1-33

#### Game Session Process Restart Invisibility Vertical Slice Task List - Game Session restart continuity (source lines 1-33)

##### Preserved Source Text: source-02-7-task-list-process-restart-invisibility-vertical-slice-1-33

<!-- migration-source path="design/project-management/vertical-slices/02.7-task-list-process-restart-invisibility-vertical-slice.md" lines="1-33" sha256="228187fd23f0892e674d2e2f6232a62c3e55ce33aeb522a67ee2bb6445341622" heading-offset="3" -->
#### source-02-7-task-list-process-restart-invisibility-vertical-slice-1-33: Game Session Process Restart Invisibility Vertical Slice Task List

##### source-02-7-task-list-process-restart-invisibility-vertical-slice-1-33: Goal and Status

Goal: prove that a real Game Session process restart can be hidden behind the already-live Gateway gameplay bridge so an established client sees at most a bounded stall instead of a forced reconnect. Status: baseline live for the focused same-JVM process-bounce proof; Game Logic continuity continues in `02.8-task-list-game-logic-restart-invisibility-vertical-slice.md`.

This slice intentionally starts after the Gateway-owned gameplay bridge and focused upstream rebind proof are already live. It narrows the remaining work to actual process-restart continuity instead of first introducing the bridge mechanism itself.

##### source-02-7-task-list-process-restart-invisibility-vertical-slice-1-33: Implementation Notes

- `02.6` already provides the Gateway-owned gameplay WebSocket bridge, stable edge transport session ids, and a focused abrupt-upstream-loss rebind proof.
- This slice now proves the same mechanism against a real Game Session-like upstream process bounce in the Gateway integration harness, with isolated Reactor resources so the restart behaves like a separate process lifecycle instead of collapsing shared JVM globals.
- Game Logic restart invisibility is intentionally split out to `02.8-task-list-game-logic-restart-invisibility-vertical-slice.md` because it depends on command/tick ownership reconstruction, not just the bridge.
- This slice should stay honest about the bounded loss envelope:
  - no command replay fantasy,
  - no transport-byte replay guarantee,
  - reconnect screen buffer plus fresh current-state redraw remain the player-visible continuity model.

##### source-02-7-task-list-process-restart-invisibility-vertical-slice-1-33: 1. Game Session Process Restart

- [x] Add one focused proof that a real Game Session restart can occur while the downstream Gateway socket remains open and gameplay traffic resumes after a bounded stall.
- [x] Confirm the stable edge transport session id is reused across the rebound upstream connection.
- [x] Ensure the resumed path still uses current shared reconnect/admission state rather than stale process-local memory, with the process-bounce proof relying on the same shared reconnect path rather than a compatibility shim.

##### source-02-7-task-list-process-restart-invisibility-vertical-slice-1-33: 2. Edge and Classification Discipline

- [x] Keep edge-visible disconnect taxonomy only for cases where the edge route itself really dropped or upstream rebind exhausted its bounded stall window.
- [x] Avoid turning this slice into generalized chaos testing; keep the proofs operationally meaningful and bounded.

##### source-02-7-task-list-process-restart-invisibility-vertical-slice-1-33: 3. Final QA Checklist

- [ ] Manually verify one real Game Session restart while a client stays connected at the edge.
- [ ] Confirm the player sees continued gameplay after a brief stall instead of a forced `LOGIN` / `PLAY` cycle.
<!-- /migration-source -->

### source-02-8-task-list-game-logic-restart-invisibility-vertical-slice-1-34

#### Game Logic Restart Invisibility Vertical Slice Task List - Game Logic restart continuity (source lines 1-34)

##### Preserved Source Text: source-02-8-task-list-game-logic-restart-invisibility-vertical-slice-1-34

<!-- migration-source path="design/project-management/vertical-slices/02.8-task-list-game-logic-restart-invisibility-vertical-slice.md" lines="1-34" sha256="19793fce4a162cb90e32a40ba68631e2b181ab1caf9a5e3349b2d89a4c71d478" heading-offset="3" -->
#### source-02-8-task-list-game-logic-restart-invisibility-vertical-slice-1-34: Game Logic Restart Invisibility Vertical Slice Task List

##### source-02-8-task-list-game-logic-restart-invisibility-vertical-slice-1-34: Goal and Status

Goal: prove that a real Game Logic process restart can be hidden behind the already-live Gateway gameplay bridge and Game Session continuity path so an established client sees at most a bounded stall instead of a forced reconnect. Status: baseline live for the focused post-restart command proof; manual QA remains.

This slice intentionally starts after the Gateway-owned gameplay bridge and the focused Game Session process-bounce proof are already live. It narrows the remaining work to Game Logic command/tick ownership reconstruction rather than reopening the bridge or reconnect basics.

##### source-02-8-task-list-game-logic-restart-invisibility-vertical-slice-1-34: Implementation Notes

- `02.6` already provides the Gateway-owned gameplay WebSocket bridge, stable edge transport session ids, and a focused abrupt-upstream-loss rebind proof.
- `02.7` now provides a focused Game Session-like process-bounce proof without dropping the downstream Gateway socket.
- This slice now proves the focused Game Logic continuity case that already matches the current architecture: post-restart gameplay commands such as `LOOK` still succeed on the same connected client because player-visible world/session state is not stored in Game Logic process memory.
- The deeper remaining gap is queue/tick-driven orchestration continuity if Game Logic later becomes part of that execution path; that is not part of the current baseline proof.
- This slice should stay honest about the bounded loss envelope:
  - no command replay fantasy,
  - no transport-byte replay guarantee,
  - reconnect screen buffer plus fresh current-state redraw remain the player-visible continuity model.

##### source-02-8-task-list-game-logic-restart-invisibility-vertical-slice-1-34: 1. Game Logic Process Restart

- [x] Add one focused proof that a real Game Logic restart can occur without forcing a visible client reconnect when post-restart gameplay commands are reissued on the same connected client.
- [x] Confirm the player-visible continuity model remains rooted in shared world/session state rather than stale process-local Game Logic memory by moving the player before restart and verifying the post-restart `LOOK` still resolves the moved destination room.
- [x] Ensure the resumed path still uses shared ownership and coordination state rather than stale process-local memory for the currently live stateless aggregation paths (`LOOK`, movement-derived room context, and communication fan-out inputs).

##### source-02-8-task-list-game-logic-restart-invisibility-vertical-slice-1-34: 2. Edge and Classification Discipline

- [x] Keep edge-visible disconnect taxonomy only for cases where the edge route itself really dropped or upstream rebind exhausted its bounded stall window.
- [x] Avoid turning this slice into generalized chaos testing; keep the proofs operationally meaningful and bounded.

##### source-02-8-task-list-game-logic-restart-invisibility-vertical-slice-1-34: 3. Final QA Checklist

- [ ] Manually verify one real Game Logic restart while a client stays connected at the edge.
- [ ] Confirm the player sees continued gameplay after a brief stall instead of a forced `LOGIN` / `PLAY` cycle.
<!-- /migration-source -->

### source-09-2-task-list-public-production-admission-and-membership-creation-vertical-slice-1-68

#### Public-Production Admission and Membership Creation Vertical Slice - Audited primary runtime or service owner (source lines 1-68)

##### Preserved Source Text: source-09-2-task-list-public-production-admission-and-membership-creation-vertical-slice-1-68

<!-- migration-source path="design/project-management/vertical-slices/09.2-task-list-public-production-admission-and-membership-creation-vertical-slice.md" lines="1-68" sha256="268699c29b9665bcc59d360bdb22bcfeeac161a1ab6d2d6a4c950f02a64c6cf6" heading-offset="3" -->
#### source-09-2-task-list-public-production-admission-and-membership-creation-vertical-slice-1-68: Public-Production Admission and Membership Creation Vertical Slice

##### source-09-2-task-list-public-production-admission-and-membership-creation-vertical-slice-1-68: Goal and Status

Goal: make first admission through a tenant's default public production realm an explicit, idempotent, auditable control-plane flow rather than an implicit side effect of discovery or `PLAY`, while preserving the stricter access rules for non-production realms. Status: complete at the current bounded boundary.

##### source-09-2-task-list-public-production-admission-and-membership-creation-vertical-slice-1-68: Why This Slice Exists

The multi-tenancy design already distinguishes between publicly discoverable production entry and explicitly granted non-production realms, but that policy is not yet represented as a dedicated slice. This work needs a bounded home because it changes who can enter a tenant at all, not just how clients render discovery.

##### source-09-2-task-list-public-production-admission-and-membership-creation-vertical-slice-1-68: Implementation Notes

The target-state contract is now sharper in the architecture docs:

- first public admission is explicitly gated by current default-production status, caller-visible public discovery, current admission-pointer truth, and runtime entitlement availability;
- `EnsurePublicProductionPlayerMembership` is now documented as the sole writer boundary for first-join membership creation, with idempotency, race-safety, and durable audit expectations;
- the minimum failure surface is now explicit enough to distinguish policy denial from pointer unavailability and tenant billing blocks;
- `requestId` is now explicitly the attempt idempotency key, and failed first-join attempts are explicitly non-committing.

The first implementation cut now exists:

- `account-service` exposes `EnsurePublicProductionPlayerMembership(...)` as a concrete service boundary and internal REST surface;
- bootstrap discovery now treats visible production realms as publicly discoverable even before a membership row exists;
- `POST /auth/connect-token` now creates the membership through that writer boundary when the selected target is the visible production realm and runtime entitlements still allow gameplay;
- text-client `PLAY` now uses that same writer boundary when a visible public-production realm is selected without an existing gameplay-admission membership, so first join no longer depends on a bootstrap-only side effect path;
- resulting membership becomes immediately visible to `GetTenantMembershipForRuntime`, and successful creation emits durable audit logging.
- that writer boundary now also treats `requestId` as an explicit replay key instead of leaving repeated attempts implicit in membership existence or log trails: repeated request ids replay the same successful or failed first-join result, and operator-facing REST/gRPC/internal responses now surface `requestId` plus `replayed` explicitly so first admission can be distinguished from a retried attempt.

What remains open is later-domain follow-through outside this slice, not a current gameplay-entry gap in the public-production membership contract.

##### source-09-2-task-list-public-production-admission-and-membership-creation-vertical-slice-1-68: Scope

- public-production discovery and admission policy
- `EnsurePublicProductionPlayerMembership(accountId, tenantId, worldSlug, realmSlug, requestId)` semantics
- idempotent first-join membership creation
- relationship between caller-bound discovery, membership reads, and gameplay admission checks
- explicit rejection rules for non-production or non-public realms

##### source-09-2-task-list-public-production-admission-and-membership-creation-vertical-slice-1-68: Out of Scope

- broader tenant billing and entitlement policy beyond the admission-critical reads already defined
- bootstrap discovery UX details beyond the policy inputs this slice requires
- realm catalog and pointer mechanics beyond the dependencies on `09.1`

##### source-09-2-task-list-public-production-admission-and-membership-creation-vertical-slice-1-68: Locked Direction

- only the default public production realm may be publicly discoverable in v1.
- non-production realms require explicit access grants and do not piggyback on public-production discovery rules.
- first public admission uses an explicit membership-creation surface, not a hidden side effect scattered across callers.
- the resulting membership must become immediately authoritative for runtime admission reads.

##### source-09-2-task-list-public-production-admission-and-membership-creation-vertical-slice-1-68: Current Remaining Work

- [x] Align text-client `PLAY` and any future non-bootstrap admission paths on the same membership-creation boundary without duplicate policy branches for the current gameplay entry surfaces.
- [x] Tighten audit/idempotency proof so repeated `requestId` attempts are surfaced more explicitly to operators than the current durable log-event trail.
- [x] Keep the current public-production checks anchored to the now-live Game Session admission-pointer authority rather than reintroducing local config or caller-guessed routing for the current gameplay entry surfaces.

##### source-09-2-task-list-public-production-admission-and-membership-creation-vertical-slice-1-68: Validation

- `./gradlew :account-service:test --tests 'net.firedevops.firemud.accountservice.service.impl.AccountServiceImplTest' --tests 'net.firedevops.firemud.accountservice.service.impl.AccountGrpcServiceTest' --tests 'net.firedevops.firemud.accountservice.controller.InternalRuntimeControllerTest' --tests 'net.firedevops.firemud.accountservice.controller.AuthControllerTest' --tests 'net.firedevops.firemud.accountservice.service.session.SessionServiceImplTest' --tests 'net.firedevops.firemud.accountservice.security.GrpcJwtAuthInterceptorTest' :game-session-service:test --tests 'net.firedevops.firemud.gamesession.command.text.PlayCommandHandlerTest'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck linkCheck lintMarkdown check`
- `bash dev-tools/verify-fresh-bootstrap.sh`

##### source-09-2-task-list-public-production-admission-and-membership-creation-vertical-slice-1-68: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->
