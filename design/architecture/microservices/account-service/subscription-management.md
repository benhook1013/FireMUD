# Subscription Management Design

This document describes how the Account Service models and manages subscriptions for FireMUD game creators and players, and how subscription state drives tenant availability and resource quotas.

The goals of the subscription system include:

- Support recurring billing for hosting plans and optional in-game features.
- Handle upgrades, downgrades, and cancellations without data loss.
- Respect tenant isolation while still enabling platform-wide reporting.
- Coordinate with Stripe while keeping billing state consistent in FireMUD’s own database.

Payment-instrument ownership, explicit per-subscription binding, and billing-owner transfer follow the canonical [ADR 0044](../../decisions/adr-0044-account-owned-payment-instruments-with-explicit-subscription-binding.md) contract.

## Implementation Status

The plan-change timing, over-quota behavior, pending-plan metadata, and full quota-bearing runtime entitlement response described below are canonical target-state behavior. Current implementation has the first runtime availability surface, but quota fields, pending plan metadata, downgrade/cancellation enforcement, and the target separation between create and existing-subscription update APIs still need implementation follow-through. The current `CreateSubscriptionRequest` carries only tenant/account/plan fields: it does not carry the target caller-stable `requestId`/`requestDigest` retry boundary or immutable creation-operation identities and must not be treated as proof of that contract. Expected row-version/CAS is not a creation precondition and is required for every supported existing-subscription mutation through `UpdateSubscription`.

The target `CreateSubscription` caller boundary uses a high-entropy stable `requestId` as the sole logical create-operation key. Account computes a versioned `requestDigest` from the normalized create tuple, including the resolved billing owner, immutable `tenantId`, initial `plan_code`, and exact instrument-selection input; clients do not supply that digest. Account captures the resolved owner's authority version/generation and validates that exact fence in the same local intent/outbox transaction before persisting the pair or creating provider work; an owner change or generation/version mismatch fails closed. A retry with the same `requestId` and normalized create fields recomputes the same digest and replays the same `subscriptionId`, durable operation, and outcome; changed fields that produce a different digest return `IDEMPOTENCY_CONFLICT`. The provider `subscription_provisioning_request_id` and cleanup identity remain separate provider-operation keys and must not replace the caller identity.

## Plan and Entitlement Model

Subscriptions are modeled as **plans** that define resource limits and entitlements, and **subscription** records that attach those plans to specific tenants:

- **Plan**  
  - Identified internally by `plan_code` (for example, `basic-hosting`, `pro-hosting`).  
  - Maps to Stripe products/prices as described in the [Stripe Integration Design](./stripe-integration.md#domain-model).  
  - Encodes entitlements and quotas such as maximum active sessions, maximum concurrent game instances, and storage or world-size tiers. These quotas align with the per-tenant configuration and quota enforcement described in [Multi-Tenancy](../../system-architecture-multi-tenancy.md#tenant-configuration--scaling).

- **Subscription**  
  - Identified solely by immutable internal `subscriptionId` and permanently scoped to one immutable `tenantId`. `accountId` and `plan_code` are mutable lookup, authorization, and constraint fields governed by explicit operations and row-version/CAS checks; they are not composite key or stable-identity material. The current `accountId` is the subscription's billing-owner subject binding; it is changed only by an explicit audited billing-owner handoff and is not an ad-hoc tenant role. Generic updates and billing-owner transfer reject any `tenantId` change.
  - Represents the current hosting agreement and billing status for a tenant.  
  - Tracks Stripe `subscription` and `customer` identifiers alongside local fields like `status`, current period boundaries, and any trial or grace-period metadata.

Entitlements exposed to other services are derived from the active subscription’s plan and status and are always scoped to a single tenant (`tenantId`).

Plan entitlements are the source of truth for hosting/runtime quotas only. One-time account, character, or virtual-currency purchases use the purchase-entitlement model in [Account Service Runtime and Data](./runtime-and-data.md#monetization-design) and must not be folded into the tenant hosting subscription row.

### Authorization Roles for Billing and Subscriptions

To keep authorization consistent, subscription and billing operations map to the role model defined in the Authentication & Authorization design:

- **Per-tenant operations** (for example, create/update/cancel subscription for a given `tenantId`, view billing history for that tenant):
  - `tenantAdmin` for that `tenantId` may view the subscription under the caller-bound tenant variant, but tenant authority alone cannot create, update, cancel, rebind, or otherwise mutate a subscription. For an existing subscription, every mutation requires an authenticated subject whose `accountId` matches `subscription.accountId`, or an explicit audited billing-owner handoff.
  - Global roles (`platformAdmin`, `billingAdmin`) must use explicitly cross-tenant billing-safe route variants (`cross_tenant_billing_safe`) rather than caller-bound tenant variants, with the dedicated audited authorization required before selecting an account-owned instrument.
- **Cross-tenant billing reports and analytics** (for example, billing-focused multi-tenant reports and revenue dashboards):
  - Allowed only for global roles:
    - `platformAdmin` for full reporting, and
    - `billingAdmin` for billing-focused reporting surfaces.

Implementations must not introduce ad-hoc “owner” or “admin” roles; authorization roles remain `tenantAdmin`, `platformAdmin`, and `billingAdmin` from the shared role model plus the Tenant Authorization Contract. The billing owner is not a role: it is the subscription-record subject bound by `subscription.accountId`. Support roles (`support`) may read high-level subscription state and derived entitlements for troubleshooting purposes but must not be granted access to detailed billing artifacts (for example, invoices or payment methods) or to any subscription-mutating APIs. These troubleshooting endpoints must be explicitly classified as `cross_tenant_support_safe` in the [Authorization Route Matrix](../../system-architecture-authz-route-matrix.md#classification-rules) so support access cannot expand to billing-safe or data-bearing surfaces.
For billing-safe tenant routes that intentionally remain reachable during `grace`, `suspended`, or `canceled` periods, services must perform a live membership/role check against authoritative account-tenant membership data (for example `GetCallerTenantMembership(tenantId)`) before allowing mutations; JWT role claims alone are insufficient for billing-safe mutations. Caller-bound tenant variants must derive actor identity from auth context rather than a client-supplied `accountId`.
Billing-safe mutations consume the caller-bound membership authority defined in [Account Runtime and Data](./runtime-and-data.md#membership-and-entitlement-authority); unavailable or contradictory membership authority fails those mutations closed.
Saved payment instruments are account-owned and managed only by the authenticated billing-owner subject whose account is bound by the subscription, or by an explicitly audited cross-tenant billing administrator. A tenant role alone cannot inspect or mutate another account’s instruments. Each subscription binds its chosen instrument explicitly; changing one subscription does not alter another. Detachment is blocked until every referencing subscription has a successfully installed replacement. Billing-owner transfer is an explicit, audited handoff through the dedicated `cross_tenant_billing_safe` route. For Stripe, it replaces rather than reassigns the provider subscription: new-owner billing starts when the old subscription ends at the recorded paid-through boundary, the old binding remains authoritative until verified cutover, and saved instruments never transfer between accounts.

Every existing-subscription mutation, including plan change, cancellation, instrument rebinding, and billing-owner transfer, must use the exact HTTPS `control-ui` route class and recent ordinary reauthentication. The owner path requires the route's required assurance and binds the authenticated subject to `subscription.accountId`; the only cross-tenant alternative is the explicit audited billing-owner handoff, which retains the exact HTTPS `control-ui` and recent-reauthentication requirements and additionally requires `privileged_control` assurance and explicit target binding. Creation has no subscription row or `subscription.accountId` yet: Account must first resolve the authoritative billing owner for the target tenant and authorize the actor against that resolved owner, or require the explicit audited handoff authority, before persisting any subscription intent or row. The actor must remain distinct from the billing-owner subject. Payment selection is never inferred from an account, Stripe customer, or provider default: creation and any mutation that changes payment binding must carry an explicit instrument or deterministic selection that resolves to exactly one instrument owned by the authorized billing owner.

Current support-safe allowlist in this domain:

- `GetTenantEntitlementsCrossTenantSupportSafe(tenantId)`
- `GetSubscriptionCrossTenantSupportSafe(tenantId)` with high-level status/plan fields only
- `ListSubscriptionsCrossTenantSupportSafe` with high-level status/plan fields only

The following are explicitly not support-safe: invoice exports, payment method details, Stripe customer metadata, any subscription mutation (`CreateSubscription`, plan change, cancellation), and billing-report variants such as `ListSubscriptionsCrossTenantBillingSafeReports`.

All billing/support route classifications in this domain must be registered in [Authorization Route Matrix](../../system-architecture-authz-route-matrix.md).

## Lifecycle Flows

The subscription lifecycle is modeled as a finite state machine:

Before the billing lifecycle states below, creation uses two durable provisioning states:

- `pending` – Account has committed the subscription operation, pending subscription row, billing-owner/instrument binding, captured and locally revalidated owner authority version/generation, observed instrument version reservation, customer-operation reference, stable creation-operation provider idempotency identity, separate cleanup/cancellation-operation identity, and outbox work item before any customer reconciliation or provider call; provider creation has not been claimed.
- `provisioning` – A worker has confirmed the customer operation, then atomically revalidated instrument ownership, the exact reserved version, matching customer, and attachability before claiming the outbox item and creating or reconciling the provider subscription. Creation retries reuse the creation-operation identity; terminal cleanup uses the separate cleanup/cancellation identity while retaining the creation identity for provider-object lookup. This state grants no hosting entitlement and cannot be billed.

Only a confirmed provider subscription ID may transition the row from `pending`/`provisioning` into `trialing` or `active`. A timeout or lost provider response is reconciled with the creation-operation identity before another create is attempted; if the provider resource exists but the local ID is missing, the creation lookup repairs the reference without creating a duplicate.

The immutable internal `subscriptionId` is the sole stable subscription identity. `subscription_provisioning_request_id` is only the immutable provider-idempotency identity for the initial subscription-create operation; the cleanup/cancellation operation has a distinct immutable provider identity and retains the creation identity for provider-object lookup. Neither identity may be reused as the identity of a later plan, instrument, cancellation, or other existing-subscription update. Each existing-subscription update creates its own immutable operation-specific request ID and captures the subscription row version expected by that operation. Account commits the update only when that row version still matches; retries reuse the same update request ID and row-version/CAS guard, so a stale caller cannot overwrite a newer update and a provider mutation cannot be issued twice for one logical operation.

Customer provisioning is independently durable and keyed by `accountId`. The committed pending subscription carries a durable reference to the customer-provisioning operation. Before any customer provider call, Account persists or reuses one stable customer-provisioning intent and provider idempotency identity. A timeout, lost response, process crash, or failed local commit resumes by reconciling that same intent and provider metadata; ambiguous state remains nonterminal and cannot authorize a replacement customer-create attempt. Account deletion is blocked while this intent, a `pending`/`provisioning` subscription, or committed provider/outbox work remains unresolved.

After a billing-owner transfer, deletion remains blocked until the transfer is terminally reconciled as `completed`, or terminally canceled with provider absence or old-subscription cancellation. That terminal transfer outcome does not waive the remaining customer, subscription, webhook, outbox, cleanup, cancellation, or provider-reconciliation blockers; all must clear before deletion can proceed.

The provider customer reference on a `pending` subscription may be null. The worker may first reconcile the account-keyed customer-provisioning intent while the subscription remains `pending`, but it must not claim the subscription as `provisioning` until the same transaction observes a confirmed, non-null Stripe customer reference that matches the billing owner and selected instrument. If reconciliation cannot confirm that customer, the worker blocks and leaves the subscription `pending`; it must not claim provisioning or call Stripe's provider-subscription API.

At subscription-intent commit, Account records the selected `paymentInstrumentId` and its observed `instrumentVersion` and reserves that version for the pending operation. The worker claim transaction must re-read and atomically verify the instrument owner, exact version, confirmed non-null matching Stripe customer, and attachable state before moving the subscription to `provisioning` or calling Stripe. Detach uses the same reservation/version boundary: a pending or provisioning reservation blocks detach, while a detach that commits first advances the version and makes a stale worker claim fail closed before provider mutation. Neither path may silently replace the instrument or proceed with a stale binding.

- `trialing` – The tenant is in a time-limited trial; full hosting features are enabled but may be subject to conservative quotas.  
- `active` – The subscription is paid and current; quotas and entitlements from the selected plan apply.  
- `past_due` – Recent billing attempts have failed; the platform has not yet enforced restrictions, but alerts and UI warnings appear.  
- `grace` – A configured recovery period after `past_due` where connected sessions and the exact same resumable bindings may continue, while public join, fresh gameplay bindings, new instances, scale-out, and quota growth are blocked.
- `suspended` – Hosting entitlements are temporarily disabled due to non-payment or policy; new sessions and instance starts are blocked for the tenant.  
- `canceled` – The subscription has been terminated; long-term data retention and clean-up policies apply.

This lifecycle table is the canonical subscription status contract for FireMUD. Other documents (including Stripe integration and auth/session gating) must reuse these exact status values and transition semantics.

State transitions are driven by:

- Admin or creator actions (for example, creating or canceling a subscription, changing plans).  
- Stripe events (for example, successful invoice, payment failure, subscription updated/deleted).  
- Internal timers (for example, moving from `past_due` to `grace` to `suspended` once configured durations elapse).

Each transition triggers domain events that downstream services can consume to adjust quotas or availability.

Account owns the lifecycle boundary. A transition becomes authoritative when Account commits the new status, its monotonic billing sequence/authority changes, and the durable event/outbox evidence. Provisioning is a durable pre-lifecycle workflow: the subscription operation, pending row, customer-operation reference, and outbox evidence commit before any customer reconciliation or provider call, and recovery resumes that intent rather than creating a second one. Provider confirmation and downstream revocation, projection, instance drain, socket closure, and cache convergence are separate execution stages. They may be partial or pending after the Account commit, so status and enforcement progress must be reported separately; a retry must resume the idempotent transition execution rather than create a second lifecycle transition or roll back committed Account authority.

### Plan Changes, Downgrades, and Cancellation Timing

Plan changes have one canonical entitlement timing model:

- Upgrades take effect immediately after Stripe confirms the subscription update and the Account Service commits the new plan snapshot. The next `SubscriptionStatusChanged` event advances `tenantBillingSequence`, and runtime services may admit new load against the higher quota only after observing or refreshing to that sequence.
- Downgrades default to taking effect at the next billing period boundary. The Account Service records `pending_plan_code`, `pending_effective_at`, and the current period end so creator UIs can show the future quota before it is enforced.
- Immediate downgrades are allowed only when the caller explicitly chooses immediate effect and acknowledges any over-quota impact. The Account Service must emit a `SubscriptionStatusChanged` event that advances `tenantBillingSequence` and marks the lower plan as current before runtime services enforce it.
- Cancellation defaults to period-end cancellation. During the paid current period the subscription remains `active` or `grace` according to payment state, with `cancel_at_period_end=true`; at the effective cancellation time it transitions to `canceled` and follows the hard cutoff behavior below.
- Immediate cancellation transitions to `canceled` immediately after provider confirmation and Account Service commit, then emits the same hard cutoff events as billing suspension.

When a tenant is above the newly effective quota after downgrade or cancellation-to-lower-plan, enforcement is deterministic: existing admitted gameplay sessions and already-running instances are not killed solely for quota excess while the tenant remains `trialing`, `active`, `past_due`, or `grace`; admission for new sessions, new instance starts, scale-out, and quota-increasing operations is denied until observed usage falls below the effective quota or the plan is upgraded. If the new state is `suspended` or `canceled`, the hard cutoff rules apply instead and existing gameplay sessions are revoked. Runtime entitlement responses must include enough quota fields for enforcement points to make this decision without relying on plan-code-specific local tables.

## Tenant Availability and Quota Enforcement

Subscription status feeds directly into tenant availability and resource enforcement. To keep behavior consistent across services, the following states and effects apply:

- When a subscription is `trialing` or `active`:
  - The tenant is **available for gameplay**; the Game Session Service may start and run game instances for that tenant.  
  - Quotas derived from the plan (for example, maximum `active_sessions`, allowed world size) are applied when starting instances or admitting new player sessions.

- When a subscription is `past_due`:
  - The tenant remains available for gameplay, but operator dashboards and creator/admin UIs surface prominent warnings.  
  - Existing and new gameplay continue under the tenant's ordinary plan quotas; `past_due` alone does not revoke sessions or close admission.

- When a subscription enters `grace`:
  - The tenant continues to run existing game instances and connected player sessions, and the same still-resumable session may reconnect.
  - First-time public join, a fresh gameplay binding, new instances, scale-out, and quota-increasing operations are blocked until billing is brought current.
  - Gameplay sessions and auth token sessions remain valid unless explicitly revoked for security reasons.

- When a subscription is `suspended` or `canceled`:
  - Tenant-level hosting is disabled for gameplay:
    - The Game Session Service and world-management flows must reject new game instance creations, restarts, and startup requests for the tenant when consulting `GetTenantEntitlementsForRuntime(tenantId, requestId)`.
    - New player-bootstrap/login attempts that would select or admit that tenant, and tenant-selection or gameplay-admission attempts for that tenant, are rejected with a dedicated error code and user-facing message indicating that the game is currently unavailable due to billing. This tenant cutoff does not disable tenantless `/auth/login` or `/auth/player-bootstrap` issuance: they may establish their respective `control-ui` or short-lived discovery authority without selecting a tenant or performing a tenant-scoped admission check. Any later tenant selection or gameplay admission still evaluates and enforces the cutoff.
  - Existing running game instances for the tenant must be transitioned to shutdown:
    - The Account authority commit immediately makes new admission unauthorized. Downstream admission closes when the event is delivered or reconciliation observes the new generation, and no later than the `<=60-second` delayed/missed-event bound.
    - Connected gameplay authority is revoked immediately when Game Session receives the event; delayed or missed delivery is caught within the same `<=60-second` reconciliation bound. Game Session sends one bounded, non-sensitive availability notice and closes the sockets; the notice flush does not permit continued gameplay.
    - Instance processes enter a bounded drain window (target: 5 minutes maximum) for internal cleanup and then stop. During this window they are not gameplay-admissible.
  - A small, explicitly defined **billing-safe control-plane surface** remains accessible so owners can resolve billing issues or export tenant-scoped data. This surface includes actions such as updating payment methods, viewing invoices, and initiating tenant-bounded exports, but does not include full account export, starting game instances, or editing live gameplay configuration. Service-specific docs and shared authorization middleware must explicitly mark which routes participate in this billing-safe surface so they remain reachable while gameplay is blocked.
  - As part of the transition into `suspended` or `canceled`, the Account Service is the authority for the cutoff: it must commit the billing state, a monotonic durable `TenantBillingStateChanged` outbox event, and the idempotent Account-owned tenant authority-generation advance in one database transaction. This makes the authority cutoff and event enqueue immediate at the Account commit; it does not claim that a remote gameplay socket has already closed, and the cutoff workflow does not report enforcement complete until the authority projection succeeds.
  - Game Session and related services must close and revoke all affected gameplay sockets immediately when this event is delivered:
    - Revoke all gameplay sessions for the affected `tenantId` (kicking connected sockets and preventing reconnect), and
    - Reconcile entitlement caches and admission gates for the tenant.
    The socket-closure timing starts at consumer delivery, not at the Account Service commit, because durable events are at-least-once and may be delayed.
  - Downstream services must not write Account-owned authority generations directly. Implementations must not scan `session:auth:token:*` in hot paths; opportunistic cleanup of older token records is allowed only via purpose-built, bounded Account-owned indexes and background work.
  - Durable event consumption is the fast path. Game Session must also batch-reconcile active tenant authority independently of event delivery so a delayed or missed event is detected and reconciled within the `<=60-second` SLA. Failure to renew that authority-reconciliation lease closes admission and terminates bindings whose authority cannot be re-established at the bound; routine commands do not perform entitlement reads.

These behaviors tie directly into the revocation rules in [Session Behavior](../../system-architecture-session-behavior.md#session-and-identity-management): `TenantBillingStateChanged` events for `suspended` or `canceled` must trigger revocation of gameplay sessions and regular tenant-scoped token authority, while softer billing states (`trialing`, `active`, `past_due`, `grace`) do not trigger automatic revocation and instead rely on quota and availability rules.

## Runtime Entitlement Contract

The canonical per-tenant membership, entitlement response, freshness, and billing-sequence contract is [Account Runtime and Data](./runtime-and-data.md#membership-and-entitlement-authority). This document retains the subscription-local consequences:

- Account exposes separate runtime, tenant-admin, and support-safe entitlement surfaces; subscription status and plan metadata are projected into the canonical runtime response rather than maintained by consumers.
- Game Session and World Management use `GetTenantEntitlementsForRuntime(tenantId, requestId)` before instance start, new bindings, scaling, or other capacity-affecting lifecycle work. `grace` may preserve already-authorized gameplay while blocking new commitments.
- Runtime consumers invalidate or refresh cached entitlement state when Account emits `SubscriptionStatusChanged` or `TenantBillingStateChanged`; Account remains the sole producer of those events.
- Strict capacity admission remains an Account-owned target-only commitment and is described by the [capacity-attempt proof obligations](../../system-architecture-versioning-runtime.md#focused-capacity-attempt-proof-obligations). The current runtime still lacks the durable authority/usage ledger and reservation lifecycle.

The runtime and capacity gRPC surfaces use concrete mTLS workload identity plus exact method authorization; external billing APIs use `control-ui` JWTs and route-specific membership or global-role authorization. An end-user JWT is not a substitute for internal workload authentication, and carried tenant context is operation data rather than permission.

## Edge Cases and Failure Handling

Edge cases around billing and subscription management include:

- **Payment failures and retries** – Repeated failed charges move a subscription from `active` → `past_due` → `grace` → `suspended` based on configured retry and grace-period policies. Webhooks annotate `subscription` records with Stripe’s failure reasons so operators can investigate.  
- **Partial periods and proration** – Upgrades and downgrades use Stripe’s proration settings; internal subscription records track the current plan and effective period, but proration details are left to Stripe.  
- **Webhook delays or outages** – If webhooks are delayed, internal subscription state may temporarily lag Stripe; regular reconciliation jobs query Stripe to detect mismatches and correct local state. During an outage, only an existing, still-resumable binding with a valid positive authoritative snapshot may use the bounded continuity fallback. New joins, fresh bindings, expansion, quota increases, target changes, and any operation with uncertain or missing authority fail closed; outage duration must not silently widen the fallback.
- **Bounded billing reconciliation** – Every provider, webhook, subscription, cancellation, and transfer reconciliation operation uses a durable retry count, backoff schedule, and absolute deadline. An operation is `RECONCILING` while attempts can still make progress; repeated attempts without changed provider/local evidence are stalled. Exhausted attempts, the deadline, or the stalled threshold transitions the operation exactly once to terminal `BILLING_RECONCILIATION_TERMINAL_FAILURE`, records the affected tenant/subscription/provider identities, last evidence, attempt history, and failure reason, stops automatic retries, and emits an operator-resolution work item. Only an audited operator-resolution operation may move that failure to a safe terminal outcome by proving provider absence, terminal provider cancellation, or completed billing-owner transfer and committing the matching Account row, CAS, and outbox evidence. Until that succeeds, the failure is unresolved billing work and cannot satisfy account-deletion or billing-authority preconditions; unresolved provider state must never silently permit deletion.

## APIs and Events

The Account Service exposes subscription APIs and emits events so other services can react to billing changes:

### Event Delivery Semantics (Required)

Downstream services depend on billing events for timely entitlement enforcement, but event transport is intentionally at-least-once and may be delayed. To keep behavior safe and deterministic, all billing-related events must follow these semantics:

- **At-least-once delivery** – consumers must assume duplicates and must apply events idempotently.
- **Per-tenant sequencing** – every event that affects a tenant’s availability or quotas (for example `SubscriptionStatusChanged` and `TenantBillingStateChanged`) must carry a monotonically increasing `tenantBillingSequence` scoped to `{tenantId}` so consumers can detect out-of-order or missing events.
- **Idempotency key** – every event includes a stable `eventId` (UUID) and the `(tenantId, tenantBillingSequence)` pair; consumers persist the latest applied sequence per tenant and treat older/duplicate events as no-ops.
- **Gap detection and reconciliation** – if a consumer detects a sequence gap (or has no prior authority-generation/version for a tenant), it must call `GetTenantEntitlementsForRuntime(tenantId, requestId)` with a stable high-entropy reconciliation identity and must emit an operator-visible structured `billing_event_gap` metric/log. The gap record must include `tenantId`, `expectedTenantBillingSequence`, `observedTenantBillingSequence`, `eventId`, `eventType`, `consumer` (service and subscription/handler), `eventOccurredAt` or `emittedAt`, `detectedAt`, `reconciledAt` when complete, and the event/request `correlationId` or `causationId`. These fields are mandatory; a free-text warning without them is not sufficient for detecting delayed cutoff enforcement.
- **Periodic refresh** – even when events are flowing, every healthy runtime consumer must refresh or reconcile cached entitlements at least once per 60 seconds so extended event outages meet the `<=60-second` delayed/missed-event SLA rather than causing unbounded drift.

- `CreateSubscription` – Create one hosting subscription for an immutable `tenantId` and initial `plan_code`. The caller supplies a high-entropy stable `requestId`; Account computes and persists a versioned `requestDigest` from the normalized create tuple, including the resolved billing owner, immutable `tenantId`, initial `plan_code`, and exact instrument-selection input. A retry with the same `requestId` and normalized create fields recomputes the same digest and replays the same durable operation and outcome; changed fields that produce a different digest return `IDEMPOTENCY_CONFLICT`; the caller identity is distinct from provider operation identities. It rejects an existing `subscriptionId`, expected row version, update operation ID, or any other update-only field. Creation must resolve the authoritative billing owner before its subscription row exists, capture the owner's authority version/generation, authorize the actor against that resolved owner (or require the explicit audited cross-tenant handoff), and require either an explicit saved `paymentInstrumentId` or deterministic instrument-selection input that resolves to exactly one saved instrument owned by that owner. In one local Account transaction, Account locks or CAS-validates the resolved owner and exact captured version/generation before committing the subscription operation, immutable internal `subscriptionId` and `tenantId`, owner, owner fence, customer-operation reference, confirmed provider customer when available, instrument binding and reserved instrument version, `pending` status, immutable `subscription_provisioning_request_id`, separate cleanup/cancellation provider-operation identity, and outbox work item before customer reconciliation or provider creation. An owner-fence mismatch fails closed before any provider operation. The worker may reconcile the customer operation while the row remains pending, but its provisioning claim atomically requires a confirmed matching customer and revalidates ownership, exact instrument version, and attachability; detach racing with provisioning is serialized by that reservation/version boundary and a stale claim fails before Stripe. Creation retries retain the creation identity for lookup; cleanup retries reuse the distinct cleanup identity. Stripe customer and provider defaults are prohibited.
- `CommitTenantCapacityAdmission` – Target Account-owned internal RPC and final capacity fence described above. Its canonical callers are Game Session and World Management; it requires stable outer `(tenantId, requestId, admissionId)`, fresh per-attempt `admissionAttemptId`, current `capacityRearmFence`, the exact complete Account-authenticated evidence bundle returned by evaluation, bounded `capacityDelta`, and a versioned `mutationDigest` over the attempt identity, fence, and complete payload. An exact identity-and-payload retry replays; any changed bundle version, snapshot/evaluation identity, entitlement field, tuple, cutoff, checkpoint, delta, attempt ID, fence, or digest conflicts before usage mutation. The initial attempt needs no rearm CAS. A new attempt requires an Account-owned CAS over the same outer tuple, terminal `OWNER_EXECUTION/NOT_EXECUTED_AFTER_AUTHORIZATION` plus durable no-commit evidence, current fence, and prior digest; that CAS advances the fence without creating a second reservation. Stale or losing fences return `FENCE_REJECTED` only with durable no-accept/no-runtime-mutation proof. It is the only contract that can turn a fresh entitlement snapshot into a strict capacity commitment. Downstream owners cannot replace it with a local compare or cached flag. No proto RPC, caller integration, durable usage ledger, or reservation lifecycle is implemented yet. See the canonical [capacity-attempt proof obligations](../../system-architecture-versioning-runtime.md#focused-capacity-attempt-proof-obligations).
- `UpdateSubscription` – Apply one plan, instrument, cancellation, or other supported update to an existing immutable `subscriptionId`. Period-end and immediate cancellation both use this mutation; no separate `CancelSubscription` mutation exists. It requires the target subscription identity, a distinct immutable operation-specific request ID, and the expected subscription row version/CAS; it rejects `subscription_provisioning_request_id`, creation-only fields, and any attempted `tenantId` change. Retries reuse the same update identity and guard and never reuse the initial provisioning identity. Provider activation and billing require a confirmed provider subscription ID. Caller-bound tenant variants derive actor identity from auth context; cross-tenant admin variants are separate APIs.
- `GetSubscriptionTenantHighLevel` / `ListSubscriptionsTenantHighLevel` – Query subscription state for one tenant, scoped by tenant authorization.  
- `ListSubscriptionsCrossTenantSupportSafe` – Cross-tenant support-safe high-level listing for troubleshooting only.  
- `ListSubscriptionsCrossTenantBillingSafeReports` – Cross-tenant billing-report listing for `billingAdmin`/`platformAdmin` only.  
- Domain events such as `SubscriptionStatusChanged` and `TenantBillingStateChanged` – Consumed by Game Session, world-management, and admin/logging services to adjust availability, quotas, and observability.

External HTTP billing and subscription APIs use the declared `control-ui` JWT plus route-specific tenant-membership or global-role authorization. Internal runtime and capacity gRPC APIs use concrete mTLS workload identity and exact method authorization; they do not accept an end-user JWT as caller authentication. Callers must still be authorized for the `tenantId` they are querying or modifying. Cross-tenant subscription data access is split by explicit route class: support-safe views are restricted to `support`/`platformAdmin`, while billing-report views are restricted to `billingAdmin`/`platformAdmin`.

For related context, see:

- [Product Requirements – Monetization](../../../product/requirements.md#28-moderation-administration--monetization)
- [Stripe Integration Design](./stripe-integration.md)
- [Account Service README](./README.md)
