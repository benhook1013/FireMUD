# Moderation Policies

This file defines the moderation-policy boundary and example policy categories for hosted FireMUD games. Operators may adapt policy definitions to community needs. The target owner-side enforcement workflow is distinct from the currently gated/unavailable moderation-action mutation and the live `EvaluateModerationPolicy` read surface described below.

For details on moderation tooling see the [Logging & Admin Service overview](./README.md), [API contracts](./api-contracts.md), and [runtime model](./runtime-and-data.md).

## Implementation Status

- `POST /moderation/actions` and the internal `ApplyModerationAction` gRPC ingress are unavailable/gated and currently do not persist `moderation_actions` policy-input/audit records or mutate Account, Game Session, or Social & Groups enforcement state.
- `EvaluateModerationPolicy` is a live read consumed by Game Session and Social & Groups at their authoritative enforcement boundaries.
- The target owner-side enforcement mutation, action-family schema, shared cross-language `mutationDigest/v1` vectors, Account authorization-reference issuance, Logging & Admin receiving-boundary validation/redemption, versioned policy propagation, and complete appeal workflow remain gated or unimplemented. This document does not claim current tests prove those target obligations.

## Core Policies

1. **Hate Speech** - policy definitions may classify discriminatory language and recommend sanctions for an authorized moderator or owning runtime to apply.
2. **Profanity Filtering** - a tenant may define chat-filtering policy for Social & Groups to enforce at its authoritative send boundary. See the [Social & Groups Service design](../social-groups-service/README.md) for the communication-delivery contract.
3. **Harassment and Threats** - policy definitions may classify threats and recommend bounded account or gameplay consequences; Account and Game Session remain the authoritative enforcement owners.
4. **Spam Prevention** - Social & Groups may enforce configured chat-send limits and mute policy at its authoritative boundary.
5. **Cheating and Exploits** - policy evaluation may produce an auditable recommendation, while the runtime that owns the affected capability applies any consequence.

## Profanity Filters

Tenant-configurable word lists, normalization, masking, and bypass detection belong to the Social & Groups chat-send policy boundary. These are target policy capabilities rather than evidence that automatic filtering, tenant word-list administration, or Unicode-lookalike detection is currently implemented.

## Enforcement Workflow

1. Offending logs or reports are flagged in the Logging & Admin Service dashboards. These dashboards are described in [Analytics Dashboards](./analytics-dashboards.md).
2. Moderators review the context and determine the severity.
3. The unavailable/gated `POST /moderation/actions` and `ApplyModerationAction` contracts (see [`logging_admin_service.proto`](../../../../protos/logging-admin/v1/logging_admin_service.proto)) do not currently persist policy input or audit evidence. They remain unavailable until the action-family schema, shared cross-language `mutationDigest/v1` golden vectors, Account-issued authorization-reference issuance and Logging & Admin receiving-boundary validation/redemption flow exist. Logging & Admin redeems the reference at its receiving boundary; no owner-side authorization-reference redemption is required for this persistence path. The separate live `EvaluateModerationPolicy` contract evaluates policy for enforcement owners; it does not itself delete accounts, terminate sessions, or make the target mutation workflow complete.
4. The owning runtime enforces the applicable policy at its authoritative boundary: Game Session enforces gameplay admission, Social & Groups enforces chat-send policy, and Account owns account security-state transitions and player notification. A broader cross-service suspension, recovery, appeal, and notification workflow remains separate Account-owned product work.

## Appeals

Account owns any future player appeal, recovery, notification, and security-state workflow. A complete appeal web flow and cross-service case-management process are not currently implemented. Moderation records should retain the evidence needed by that future workflow without making Logging & Admin the account-state owner.
