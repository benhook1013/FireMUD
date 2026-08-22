# Moderation Policies

This document is the canonical Logging & Admin owner contract for safety policy intent, moderation cases, bounded appeal cases/evidence, audit, and the owner-local enforcement split. Runtime restriction state remains with the service that protects the boundary. The fixed category and lifecycle rules follow [ADR 0141](../../decisions/adr-0141-fixed-safety-restriction-categories-and-independent-lifecycles.md), appeals follow [ADR 0142](../../decisions/adr-0142-bounded-moderation-appeal-cases.md), the Logging & Admin ingress boundary follows [ADR 0047](../../decisions/adr-0047-logging-admin-as-external-operator-write-ingress.md), and the durable operator-write identity and recovery rules follow [ADR 0048](../../decisions/adr-0048-durable-idempotent-operator-write-execution.md).

## Implementation Status

- `POST /moderation/actions` and `ApplyModerationAction` are gated/unavailable. They currently persist neither the `moderation_actions` policy-input/audit record nor owner enforcement state.
- `EvaluateModerationPolicy` remains a live internal read consumed at the Game Session `GAMEPLAY_ADMISSION` and Social & Groups `CHAT_SEND` boundaries. It is not an operator mutation and does not make the target owner-local command workflow complete.
- Complete fixed-category persistence, digest-bound owner commands, monotonic owner projections, bounded appeals, and player-facing notices are not implemented or proved. Current code and tests must not be read as proof of these target obligations.

Target behavior, once the mutation gate is complete, is for the gated policy-input/audit path to durably record policy intent/case/audit evidence and redeem its one Account reference exactly once for that local persistence; it never forwards or reuses that reference. A separate future typed digest-bound owner-enforcement command receives its own Account reference, which the receiving owner redeems/authorizes before committing local enforcement. Current behavior is the gated/unavailable action path: it performs no moderation persistence, owner forwarding, redemption, or enforcement. The live `EvaluateModerationPolicy` read is the only current moderation decision seam and carries no mutation authorization reference.

## Fixed Safety Categories

FireMUD accepts exactly these five categories at this boundary:

| Category | Purpose | Enforcement owner | Scope and effect |
| --- | --- | --- | --- |
| `account_security_lock` | Protective response to verified or high-confidence compromise | Account Service | Global account subject; revokes ordinary account/bootstrap authority and clears only through Account security recovery |
| `platform_access_ban` | Punitive denial of ordinary platform access | Account Service | Global account subject across tenants; survives credential reset/security recovery and changes only through a later authorized revision |
| `gameplay_ban` | Punitive denial of gameplay | Game Session Service | Exact tenant or exact tenant-and-realm scope; fences `PLAY`, new gameplay command admission, and covered active bindings |
| `chat_mute` | Restriction on sending communication | Social & Groups Service | Exact tenant, tenant-and-realm, or tenant/realm/channel scope; blocks sending while ordinary receipt remains available |
| `chat_ban` | Restriction on ordinary communication participation | Social & Groups Service | Exact tenant, tenant-and-realm, or tenant/realm/channel scope; blocks participation, sending, and history while essential notices remain available |

Scope is explicit data. An omitted realm or channel means the declared broader tenant scope; it is not an inferred current realm or wildcard. Cross-tenant/platform scope is valid only for the two Account-owned categories.

Logging & Admin owns policy intent, punitive case decisions, operator ingress, and audit for `platform_access_ban`, `gameplay_ban`, `chat_mute`, and `chat_ban`. Account-owned security policy and recovery own `account_security_lock` decisions and may consume security evidence without turning the lock into punitive moderation. Account remains the enforcement owner for both account-wide categories.

## Independent Lifecycle and Owner Commands

Restrictions stack independently. Every protected boundary evaluates all applicable categories and exact scopes. The enforcement owner applies [ADR 0141's deterministic broadest-effect response precedence](../../decisions/adr-0141-fixed-safety-restriction-categories-and-independent-lifecycles.md#independent-stacking-and-clearance) when more than one of its local categories denies the same boundary, without exposing the lower-priority category. That response choice does not delete, merge, suspend, or rewrite a narrower record. Expiry, removal, recovery clearance, or correction of one category has no effect on any other category or scope. In particular, security recovery never clears `platform_access_ban`, and removing a gameplay restriction never removes chat restrictions.

Every creation, extension, expiry, removal, recovery clearance, or correction is a new immutable owner revision ordered monotonically by subject/category/scope. The policy/audit input records at least the exact category and normalized scope, effective and expiry times, authenticated actor, case or security-event identity, source action/request identity, player-safe notice and safe reason code, owner revision/enforcement epoch, idempotency identity, and immutable payload digest. Protected evidence and moderator notes are separate from the safe notice.

At `/moderation/actions`, Logging & Admin rejects unknown or legacy values such as `ban`, `account_ban`, `account_security_ban`, and `account_security_lock`, plus any aliases for them. The protective `account_security_lock` is the Account-owned fixed category and is not a punitive moderation action: only Account-owned security policy and recovery may create, extend, expire, remove, or clear it. Account may consume security evidence, but the moderation ingress cannot create or clear the lock. Unknown values are never heuristically translated. The owner durably commits the accepted revision, current effective projection, and idempotent result together. Same request identity with the same digest replays the stored result; a different digest conflicts. Delayed, duplicate, or reordered revisions cannot erase newer state or resurrect older state.

The gated policy-input/audit mutation has its own `controlPlaneRequestId`, digest, and Account reference; Logging & Admin redeems that reference exactly once for local persistence, and exact retries reconcile only that local identity. Any later owner-enforcement command has a distinct, linked `controlPlaneRequestId`, command digest, and separately issued Account reference bound to the original intent/case and the exact owner mutation. That receiving owner redeems its reference exactly once, independently authorizes the current actor/scope and domain facts, and atomically commits its state and result. Neither reference nor request identity is reused across the two mutations. `EvaluateModerationPolicy` is a live read and carries no mutation authorization reference. Success is reported only after the applicable commit. Lost responses or final audit failures are reconciled with the same identity and digest for that mutation; a timeout or ambiguous result never creates a replacement identity. This forwarding description is target behavior only; the current action path is gated/unavailable as stated above.

Target owner-local enforcement keeps routine `PLAY`, gameplay command, chat send, participation, and history decisions from synchronously querying Logging & Admin. Game Session and Social & Groups read their own indexed durable state; owner read failure at a protected boundary fails closed with a category-appropriate safe diagnostic. Logging & Admin or observability outage does not disable an already committed restriction while its owner can read state.

## Policy Evaluation and Enforcement Workflow

1. Logs or reports are received by Logging & Admin and retained as evidence/case ingress. A report or personal block/ignore relationship never automatically creates a staff restriction.
2. An authorized moderator reviews the case and selects one fixed category and explicit scope. Profanity filtering, spam limits, and tenant policy definitions remain policy inputs; they do not create a new category.
3. In the target workflow, Logging & Admin validates the closed vocabulary, policy, actor, scope, and safe notice, records the durable intent/case/audit row, and redeems its one Account reference exactly once for that local request identity/digest. A separate future owner-enforcement command has a distinct linked request identity/digest and carries its own reference to the receiving owner for redemption and commit; the local identity and reference are never forwarded or reused. The current mutation is gated/unavailable and performs none of that persistence or forwarding.
4. The owning service commits and enforces its local revision: Account at authentication and account-authority boundaries; Game Session at `PLAY`, command admission, and active-binding containment; Social & Groups at send, participation, history, and essential-notice delivery.
5. The affected player receives only the safe category/scope effect, expiry when disclosable, and permitted next step. Internal evidence, reporter identity, detection methods, and unrelated restrictions are not exposed.

## Bounded Moderation Appeals

Logging & Admin owns the appeal case, evidence references, jurisdiction, reviewer decision, and append-only audit. Account authenticates the appellant, provides the browser handoff and notifications, and owns account-security recovery. Account is not the moderation case owner; Logging & Admin is not Account security or runtime enforcement authority.

Each restriction policy declares appeal eligibility. Severe or long-lived punitive restrictions require an appeal path. A brief automatically expiring mute may be ineligible when its notice and expiry are clear; ineligibility returns a stable caller-safe result and does not create an empty case. Filing an appeal never changes, suspends, or stays enforcement and grants no gameplay, communication, or authentication authority.

The only case lifecycle is `SUBMITTED -> UNDER_REVIEW -> DECIDED`, with terminal decision `UPHELD`, `MODIFIED`, or `OVERTURNED`. A case records at least:

- `appealCaseId`, appellant and submission/request identity;
- enforcement owner, fixed restriction type, subject, and exact tenant/platform scope;
- exact appealed owner revision and payload digest plus originating moderation action/case linkage;
- jurisdiction, eligibility policy version, safe submission, and bounded evidence references;
- reviewer, decision, safe player-facing rationale, and timestamps; and
- resulting owner command identity/digest/revision when modification or reversal applies.

An upheld decision creates no enforcement mutation or owner command. A modified or overturned decision sends a new digest-bound command to the existing owner, referencing the appeal case and exact appealed revision. The command carries the exact appealed owner revision and payload digest as an expected-state precondition; a mismatch returns a stable conflict/no-op without mutating newer state, while the same owner-command identity and digest replays the stored result. The owner applies a matching command as a newer ordered transition and must not erase a later unrelated restriction. Original action, evidence, appeal transition, decision, and owner revisions remain append-only.

`DECIDED` is the terminal case/review fact, not owner-command completion. The resulting owner command has its own distinct linked identity/digest and reports completion or phase-qualified pending/failure through the ADR 0048 execution contract; lost acknowledgement reconciles that same owner-command identity rather than changing the case decision or issuing a replacement implicitly.

Tenant-scoped gameplay/chat appeals use the applicable tenant policy and authorized tenant moderation jurisdiction. Punitive platform-ban appeals use platform jurisdiction. `account_security_lock` clearance remains Account security recovery, not a moderation appeal. Platform review of a tenant case requires an explicit platform policy; global roles do not silently bypass tenant isolation.

Gameplay clients do not collect appeal evidence or authentication secrets. An eligible appeal or status action may return an opaque, short-lived HTTPS URL whose server-side intent binds the authenticated account, gameplay session when applicable, exact case or restriction revision, action, expiry, and stable appeal-submission request identity. That identity is persisted and correlated with `appealCaseId` for status and is distinct from any later owner-command `controlPlaneRequestId`. The browser independently authenticates through Account before reaching the caller-bound Logging & Admin surface. The URL carries no secret, evidence, reporter identity, or completion authority.

Submission is idempotent by that stable appeal-submission request identity, and only one active appeal may exist for an exact account and restriction revision. Account, tenant, and status-polling limits return retry guidance without revealing another subject's case. Each policy declares finite case/evidence retention, authorized readers, redaction, legal-hold handling, export treatment, and terminal erasure/minimization. Player export may include the player's submission and safe decision summary, but not protected evidence or reporter identity absent a separate legal entitlement.

This is a bounded appeal product, not a general tribunal: no mandatory multi-reviewer panel, hearings, discovery, arbitrary escalation levels, or universal response-time promise is implied.

## Related Documentation

- [Logging & Admin API Contracts](./api-contracts.md)
- [Logging & Admin Runtime and Data](./runtime-and-data.md)
- [Account safety restriction owner](../account-service/api-contracts.md#account-owned-safety-restriction-contract)
- [Game Session owner-local gameplay restrictions](../game-session-service/runtime-and-data.md#owner-local-gameplay-restrictions)
- [Social & Groups owner-local communication restrictions](../social-groups-service/runtime-and-data.md#owner-local-communication-restrictions)
