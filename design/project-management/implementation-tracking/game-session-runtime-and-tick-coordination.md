# Game Session Runtime and Tick Coordination

## Current Status

The lossless source transposition is complete. This tracker consolidates the live Game Session execution substrate by capability; the unchanged source evidence remains the audit backstop.

## Implementation Record Index

Use this index to locate the current domain capability. The detailed evidence preserves every allocated legacy source line and is intentionally kept in the same document for comparison.

| Capability and ownership focus | Source-declared status | Source range | Evidence |
| --- | --- | --- | --- |
| [`02.18.11` Migrate Live Gameplay Commands Onto the Durable Execution Path](../vertical-slices/02.18.11-task-list-migrate-live-gameplay-commands-onto-durable-execution-path-vertical-slice.md) - Audited primary runtime or service owner | complete for the current built-in state-changing gameplay command surface, with movement, item/equipment/container mutations, and communication/activity mutations | 1-111 | [source evidence](#source-02-18-11-task-list-migrate-live-gameplay-commands-onto-durable-execution-path-vertical-slice-1-111) |
| [`02.18.13` Runtime Feature Flag Authority Convergence](../vertical-slices/02.18.13-task-list-runtime-feature-flag-authority-convergence-vertical-slice.md) - Runtime feature-flag ownership and consumer authority | complete | 1-2, 4-6, 8-19, 22-32, 35-39, 41-48 | [source evidence](#source-02-18-13-task-list-runtime-feature-flag-authority-convergence-vertical-slice-1-2-4-6-8-19-22-32-35-39-41-48) |
| [`02.18.15` World and Session Lifecycle Concurrency Hardening](../vertical-slices/02.18.15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice.md) - Game-session lifecycle concurrency and session-state fencing | complete | 1-24, 28-43, 45-55 | [source evidence](#source-02-18-15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice-1-24-28-43-45-55) |
| [Shared Saga Ownership and Control-Plane Facade Thinning Vertical Slice](../vertical-slices/02.18.20-task-list-shared-saga-ownership-and-control-plane-facade-thinning-vertical-slice.md) - Control-plane facade extraction and delegation boundary | implemented | 8-9, 23-24, 38-39 | [source evidence](#source-02-18-20-task-list-shared-saga-ownership-and-control-plane-facade-thinning-vertical-slice-8-9-23-24-38-39) |
| [Tick Scheduler Backpressure and Merge Semantics Vertical Slice](../vertical-slices/02.18.6-task-list-tick-scheduler-backpressure-and-merge-semantics-vertical-slice.md) - Audited primary runtime or service owner | complete at the current boundary | 1-79 | [source evidence](#source-02-18-6-task-list-tick-scheduler-backpressure-and-merge-semantics-vertical-slice-1-79) |
| [`02.18.7` Durable Command Ingress and Status Ledger](../vertical-slices/02.18.7-task-list-durable-command-ingress-and-status-ledger-vertical-slice.md) - Audited primary runtime or service owner | complete for the current ingress/status boundary | 1-186 | [source evidence](#source-02-18-7-task-list-durable-command-ingress-and-status-ledger-vertical-slice-1-186) |
| [02.18.7.1 Task List: Operator Gameplay Command Status Readback Vertical Slice](../vertical-slices/02.18.7.1-task-list-operator-gameplay-command-status-readback-vertical-slice.md) - Audited primary runtime or service owner | complete at the current bounded boundary | 1-89 | [source evidence](#source-02-18-7-1-task-list-operator-gameplay-command-status-readback-vertical-slice-1-89) |
| [`02.18.8` Tick Batch and Effect Ledger Hardening](../vertical-slices/02.18.8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice.md) - Audited primary runtime or service owner | complete at the current boundary | 1-315 | [source evidence](#source-02-18-8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice-1-315) |
| [02.18.8.1 Task List: Timer Origin and Queue-Source Convergence Vertical Slice](../vertical-slices/02.18.8.1-task-list-timer-origin-and-queue-source-convergence-vertical-slice.md) - Audited primary runtime or service owner | complete | 1-101 | [source evidence](#source-02-18-8-1-task-list-timer-origin-and-queue-source-convergence-vertical-slice-1-101) |
| [02.18.8.2 Task List: Fair-Selected Work-Source Convergence Vertical Slice](../vertical-slices/02.18.8.2-task-list-fair-selected-work-source-convergence-vertical-slice.md) - Audited primary runtime or service owner | complete | 1-88 | [source evidence](#source-02-18-8-2-task-list-fair-selected-work-source-convergence-vertical-slice-1-88) |
| [02.18.8.3 Task List: Remote Result Reconciliation and Cross-Region Payload Follow-Through Vertical Slice](../vertical-slices/02.18.8.3-task-list-remote-result-reconciliation-and-cross-region-payload-follow-through-vertical-slice.md) - Audited primary runtime or service owner | complete | 1-88 | [source evidence](#source-02-18-8-3-task-list-remote-result-reconciliation-and-cross-region-payload-follow-through-vertical-slice-1-88) |
| [02.18.8.4 Task List: Operator Remote Command Coordinator Readback Vertical Slice](../vertical-slices/02.18.8.4-task-list-operator-remote-command-coordinator-readback-vertical-slice.md) - Audited primary runtime or service owner | complete at the current bounded boundary | 1-89 | [source evidence](#source-02-18-8-4-task-list-operator-remote-command-coordinator-readback-vertical-slice-1-89) |
| [02.18.8.5 Task List: Operator Remote Command Coordinator List Readback Vertical Slice](../vertical-slices/02.18.8.5-task-list-operator-remote-command-coordinator-list-readback-vertical-slice.md) - Audited primary runtime or service owner | complete at the current bounded boundary | 1-99 | [source evidence](#source-02-18-8-5-task-list-operator-remote-command-coordinator-list-readback-vertical-slice-1-99) |
| [02.18.8.6 Task List: Operator Remote Followup List Readback Vertical Slice](../vertical-slices/02.18.8.6-task-list-operator-remote-followup-list-readback-vertical-slice.md) - Audited primary runtime or service owner | complete at the current bounded boundary | 1-94 | [source evidence](#source-02-18-8-6-task-list-operator-remote-followup-list-readback-vertical-slice-1-94) |
| [02.18.8.7 Task List: Operator Remote Followup Result List Readback Vertical Slice](../vertical-slices/02.18.8.7-task-list-operator-remote-followup-result-list-readback-vertical-slice.md) - Audited primary runtime or service owner | complete at the current bounded boundary | 1-94 | [source evidence](#source-02-18-8-7-task-list-operator-remote-followup-result-list-readback-vertical-slice-1-94) |
| [02.18.8.8 Task List: Operator Remote Followup Point Readback Pair Vertical Slice](../vertical-slices/02.18.8.8-task-list-operator-remote-followup-point-readback-pair-vertical-slice.md) - Audited primary runtime or service owner | complete at the current bounded boundary | 1-99 | [source evidence](#source-02-18-8-8-task-list-operator-remote-followup-point-readback-pair-vertical-slice-1-99) |
| [`02.18.9` Region Epoch, Fencing, and Runtime Ownership](../vertical-slices/02.18.9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice.md) - Audited primary runtime or service owner | implemented at the current game-instance runtime boundary, pending later true region partitioning follow-through | 1-160 | [source evidence](#source-02-18-9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice-1-160) |
| [02.18.9.1 Task List: Operator Runtime Ownership-Status Readback Vertical Slice](../vertical-slices/02.18.9.1-task-list-operator-runtime-ownership-status-readback-vertical-slice.md) - Audited primary runtime or service owner | complete at the current bounded boundary | 1-88 | [source evidence](#source-02-18-9-1-task-list-operator-runtime-ownership-status-readback-vertical-slice-1-88) |

## Canonical Design Sources

- [Tick concepts and invariants](../../architecture/system-architecture-tick-concepts-and-invariants.md) defines durable execution identity, ordering, ownership, and replay boundaries.
- [Tick execution flows](../../architecture/system-architecture-tick-execution-flows.md) defines ingress, staging, batch drain, effect application, and recovery.
- [Tick failures and operations](../../architecture/system-architecture-tick-failures-and-operations.md) and the [tick incident runbook](../../architecture/system-architecture-tick-incident-runbook.md) define pressure, failure, and operator response.
- [Game Session Service](../../architecture/microservices/game-session-service/README.md) owns the live command, batch, effect, and runtime ownership records.
- [Automation Scripting Service](../../architecture/microservices/automation-scripting-service/README.md), [Game Logic Service](../../architecture/microservices/game-logic-service/README.md), and [Logging & Admin Service](../../architecture/microservices/logging-admin-service/README.md) consume or expose the canonical runtime seams rather than duplicating their truth.

## Consolidated Implementation Record

### Authority and Scope

Game Session is the durable owner of gameplay command, tick batch, tick effect, runtime ownership, remote coordinator, remote follow-up, and remote result records. PostgreSQL is the recovery and execution-truth store. Redis remains the fast coordination plane for locks, queues, pending state, wake-up hints, and scheduling cadence; Redis residue is never treated as the sole durable execution record.

The live queue and ownership boundary is still `(tenantId, gameInstanceId)`. The owner row and every current batch also carry an explicit `regionId`, currently bootstrapped from that game-instance boundary, so readers and replay do not infer scope from key names. Region-aware callers must provide the admitted `regionId` and may retain `gameInstanceId` for identity; disagreement, missing ownership, stale same-instance scope, and mismatched returned identity fail closed.

### Durable Command Ingress

After ingress validation, rate and queue-target resolution, Game Session writes one `gameplay_command` row before Redis staging and assigns a stable `commandId`. The row includes tenant/game/session/actor identity, sanitized command text, admitted routing and runtime scope, timestamps and failure information, comparable `enqueueSeq`, automation/script/plugin provenance where applicable, and the latest origin/queue source tuple. Credential-bearing input is sanitized before persistence, so passwords and OTPs do not become durable command history.

The current command lifecycle is explicit: `ACCEPTED` means durable identity exists but staging has not succeeded; `STAGED` means Redis staging succeeded; `DRAINED` means a durable tick batch consumed the staged command; `RETRY_QUEUED` means an abandoned attempt was returned for another attempt; `FAILED` means staging failed before gameplay application; and `LOST_BEFORE_STAGING` is the terminal recovery state for accepted work that never reached staging. Startup recovery converges accepted-but-never-staged rows to that terminal state. Batch drain and abandonment update command rows, so status is not ingress-only.

`commandId` is carried in enqueue responses, Redis tick payloads, logs/MDC, batch manifests, effect rows, retries, and remote execution. `GetGameplayCommandStatus` is the current canonical Game Session read by `(tenantId, gameInstanceId, commandId)` and exposes `enqueueSeq`, the latest selected queue-source kind/state/ordinal and due-point tuple, lifecycle and routing data, and remote correlation/result fields. The richer older `ackLevel`/`ingressStatus` and bound-tick `GetCommandStatus` vocabulary is not implemented. Logging & Admin exposes the tenant-qualified `GET /gameplay-commands/{tenantId}/{gameInstanceId}/{commandId}` read and validates tenant and game-instance identity before trusting the response.

Rollback queue purge controls cover script-patch and plugin-version scopes. They remove matching not-yet-drained Redis queue entries and terminal-mark each corresponding durable gameplay command with `executionOutcome=PURGED`, `gameplayResult=NOT_APPLIED`, failure code `ROLLBACK_PURGED`, the operator-supplied reason as failure message, and `completedAt`; already-drained or applied work is outside this queue-purge terminalization path.

### Scheduler and Queue Pressure

The scheduler has bounded fan-out: one runtime scope can have at most one pending or running tick. Overlapping pulses merge instead of accumulating debt, executor rejection is observable, and gameplay and automation/script ticks use disjoint Redis key namespaces. The scheduler records scheduled, merged, rejected, and paused-cycle counters, queue-depth signals, pressure summaries, and conservative consecutive-cycle thresholds for sustained rejection, elevated merging, and high executor queue depth. Threshold crossings produce alert counters and consecutive-cycle gauges, and canonical alert rules consume the exported gauges.

The policy is observability-first. Pressure is an operator alert/input and does not automatically change gameplay timing. The tick incident runbook has a dedicated scheduler-pressure flow. There is no dedicated queue/lease scheduler or broader game-logic action scheduler at this boundary.

### Durable Batches, Effects, and Source Contracts

Redis queue-to-pending staging creates one durable `tick_batch` with `tickBatchId`, tenant/game scope, `batchSource`, status, solo-tick requirement, command count, expected effect count, selected-work manifest JSON and digest, ownership snapshot, and timestamps. Each selected gameplay command creates one durable `tick_effect` with stable `effectId`, batch and command links, effect key/type, target aggregate, status, failure code/message, and timestamps. Target aggregates narrow to the known character or entity when the command provides one instead of always using the game instance.

Batch states are `STAGED`, `DRAINED`, `APPLIED`, and `ABANDONED`. Effect states are `STAGED`, `DRAINED`, `APPLIED`, `REPLAY_NOOP`, `REJECTED`, and `ABANDONED`. Existing pending work is correlated to an older staged batch or to a bounded `PENDING_REPLAY` batch created during recovery. The current tick boundary requires durable command identity; it does not retain a no-`commandId` recovery path.

The sealed selected-work manifest is the replay contract. It records explicit `regionId`, `enqueueSeq`/comparable source ordering, source kind/type and state, due tick/point, target and routing facts, automation dispatch/work-item identity, script/plugin provenance, and source-state metadata. `enqueueSeq` is used as `sourceOrdinal` where available instead of a transient batch slot. Durable command and remote-follow-up rows also persist their latest origin-source and queue-source tuples so status and operator reads do not need to parse manifests or payload JSON.

Timer-origin work preserves `SCHEDULE_TIMER` and its due/order tuple through stage, drain, replay, requeue, and readback unless it intentionally becomes explicit retry work. First attempts use `GAMEPLAY_COMMAND`/`REDIS_PENDING_CLAIMED`; retries use `GAMEPLAY_RETRY`, with `REDIS_RETRY_QUEUED` at requeue and `REDIS_RETRY_CLAIMED` at claim. Automation work items, ingress/handler audits, and handoff events persist `sourceKind`, `sourceState`, `sourceOrdinal`, `sourceDueTickId`, and `sourceDueAtMs`. Fair-selected timer, retry, and remote-follow-up candidates persist one comparable claim-time ordering fact on both durable rows and the sealed manifest; replay and operator reads do not reconstruct it from timestamps, payloads, or incidental iteration order.

### Replay, Drain, and Effect Execution

After Redis drain commit, Game Session executes `DRAINED` effects from the durable ledger. A restart between drain and application resumes from durable batch/effect rows. Replay rebuilds from the sealed manifest and command ledger, rewrites Redis pending to that projection, and returns Redis-only residue to the queue. A pending manifest digest mismatch is recorded as `MANIFEST_MISMATCH` and forced through a fresh replay batch rather than mutating the old batch contract.

Stale drain, fresh-stage rollback, and manifest-mismatch repair requeue actual commands, advance their queue-source state deliberately, and increment `tick_requeued_action_total` by action count and durable source (`player`, `automation`, or `unknown`). Requeue preserves sealed source identity, comparable ordinal, due point, and source state; mutable command-row state cannot replace the sealed ordering facts. Only the first current migrated command families consume the post-drain effect seam, while pure views remain direct reads.

### Gameplay Mutation Consumers

Built-in movement no longer uses the old synchronous mutation handler. It enqueues durably, drains into batch/effect rows, applies the authoritative room mutation through the idempotent durable effect seam, and delivers player-visible output asynchronously through the websocket/screen-buffer-aware output service. `MoveCommandHandler` remains planning-only; its former public synchronous result path is removed.

`GET`, `DROP`, `PUT`, `TAKE`, `WEAR`, and `REMOVE` use the same durable ingress, effect executor, downstream `effectId` propagation, and asynchronous output shape for Entity Management mutations. `SAY`, `WHISPER`, `TELL`, and `AFK` also enqueue durably; authoritative mutation or downstream RPC and actor-visible output occur after durable execution, replay uses a Game Session-owned guard rather than reinvoking the handler, and the same `effectId` reaches Game Logic and Social Groups persistence. `INVENTORY`, `EQUIPMENT`, and `CONTAINER` remain direct read-only views.

The item command handler records `gamesession.command.item.*` invocation and failure metrics with bounded `type` and `error` tags across inventory, equipment, and container verbs.

### Ownership, Epochs, and Fencing

`runtime_region_status` is the current durable owner-of-record keyed by the live tenant/game boundary. It stores explicit `regionId`, `regionEpoch`, opaque `executorFence`, owner service and instance identity, paused state, `lastCommittedTickBatchId`, and backlog truth. Tick processing observes this row and refreshes owner identity from the shared runtime identity. Pause, resume, and recovery advance the durable epoch/fence timeline; the fence is an opaque generation token and is compared for equality/freshness rather than treated as a numeric sequence.

Batch creation seals the owner `regionEpoch`/`executorFence` and `regionId`. Drain/finalization rejects a changed owner, rolls pending work back to the queue, and abandons the batch with `STALE_EXECUTOR_FENCE`. Before applying `DRAINED` effects, the executor checks the same snapshot again; stale effects are not sent to domain handlers, are abandoned, and their commands are requeued for a fresh fenced batch. `lastCommittedTickBatchId` advances only on durable batch drain.

The canonical `GetRuntimeOwnershipStatus` read returns owner, scope, epoch/fence, last committed batch, pending gameplay-command count, due remote-follow-up count, oldest due remote-follow-up tick, and remote follow-up drain lag. Logging & Admin exposes it at `GET /tick-remediation/status/{tenantId}` with exactly one `gameInstanceId` or `regionId` selector. Automation admission and cross-service runtime-state consumers use the same explicit current scope, including activation, schedule reconciliation, script-patch projection, event ingress, work-item handoff, and operator reads; stale region scope is rejected or cancelled before it can flatten into the local queue.

### Cross-Region Remote Runtime

Origin scheduling and target execution are Game Session-owned durable work. `remote_command_coordinator`, `remote_followup`, and origin-addressed `remote_followup_result` rows preserve origin/target tenant, game-instance and region scope, epochs, routing bundle (`playableStateScope`, `worldSlug`, `realmSlug`, `pointerVersion`), payload summary, due/deadline policy, source and queue tuples, target entity/effect identity, command identity, and script/plugin/publication/dispatch/work-item provenance. Remote rows remain self-describing even when the original command row is absent; persisted row fields are canonical and payload JSON is optional enrichment.

The live payload families are `enqueue_automation_command`, `enqueue_gameplay_command`, and `trigger_script_event`. Schedule requests carry explicit payload kind, requested command, solo-tick policy, routing/provenance, origin-source tuple, and for script events `eventType`, `eventSchemaVersion`, `scriptEventId`, `triggerMode`, `readSnapshotToken`, and `eventPayloadJson`. JSON must parse and agree with explicit contract fields when supplied. Unsupported, invalid, command-less, snapshot-incomplete, or malformed boolean, numeric, or textual fields fail closed before durable schedule writes, and older rows with unsupported kinds fail closed at target execution.

Scheduling writes an explicit coordinator-to-follow-up `followupId` link and a bounded best-effort `remote:<tenantId>:<entityId>` wake-up hint. Target execution fairly selects due work, claims at most one follow-up per concrete `targetEntityId` in a batch, pages past duplicate-heavy candidate windows when needed, records `claimOrdinal`, and stages selected rows as `tick_batch`/`tick_effect` work with `batchSource=REMOTE_FOLLOWUP_DRAIN`. The follow-up row itself advances `queueSourceKind=REMOTE_FOLLOWUP` and its scheduled/claimed/applied/abandoned states, preserves one source-local `queueSourceOrdinal` across release/reclaim cycles, and clears stale release failure metadata when reclaimed. Shared rollback and stale-fence paths explicitly release claimed follow-ups.

Target execution terminalizes the target follow-up and writes exactly one deterministic result identity, `remote-result:<followupId>`, per follow-up. Replays of the same result must agree with immutable `resultId` and do not rewrite the original `observedAt`; conflicting outcome or payload is rejected. Result rows persist `resultCommandId`, `resultErrorCode`, and `resultMessage`, and target follow-ups mirror concrete failure code/message. Missing-coordinator failures explicitly abandon and unclaim the target row rather than stranding it as `CLAIMED`.

Origin tick reconciliation is the only path that advances coordinator state from `PENDING_REMOTE` or `REMOTE_TIMEOUT_ABANDONED`. It reconciles durable result-inbox rows before deadline timeout evaluation from committed tick progress, requires matching origin/target epoch and scope, waits for linked target gameplay-command convergence after mere remote admission, and fails closed when the target command is missing for supported payloads. Target terminal failure is mirrored with its concrete failure taxonomy. Late results cannot overwrite the original timeout/abandon cause, and paused origins still reconcile results without advancing timeout or tick progression. Remote due/backlog gauges (`remote_followups_due_total` and `remote_followups_drain_lag_ms`) use durable owned-region truth without tenant/region metric labels.

Automation emits local same-scope commands through `EnqueueAutomationCommandIfAbsent` and cross-scope commands through durable `enqueue_automation_command` follow-ups. The internal Game Session scheduling API accepts canonical remote requests and returns durable coordinator/follow-up ids plus created-versus-reused idempotency truth. Durable `script_handoff_events` and `ListScriptHandoffEvents` preserve target scope and remote ids with script/plugin/dispatch identity. Remote-admitted target commands persist `remoteCoordinatorId` and `remoteFollowupId` and are idempotent by durable follow-up identity; command status and remote reads expose the linked target command's execution/gameplay outcome rather than relying on payload summaries.

### Operator Readback and Facade Boundaries

Game Session control-plane reads are canonical and bounded. The runtime-state read for `(tenantId, gameInstanceId)` returns the current runtime version plus the resolved launch descriptor, version/release identifiers, and script-patch pin metadata including the persisted pin `controlPlaneRequestId`; `GetGameSessionPinConvergence` provides the direct pin-convergence read used by rollback/promotion orchestration. `GameSessionControlPlaneGrpcService` is the transport, authorization, and application-error delegation layer; `GameSessionCommandControlPlaneService` owns gameplay-command status projection, staged automation-command admission, alias validation, remote-result/status mapping, and publication lookup helpers. The remaining remote runtime read/delegation collaborators keep the facade from carrying a second domain implementation. Application failures stay in normal responses for the existing control-plane mapping rather than being reconstructed by callers.

Logging & Admin consumes Game Session directly and has no parallel command, ownership, coordinator, follow-up, or result database. Current REST surfaces are `GET /gameplay-commands/{tenantId}/{gameInstanceId}/{commandId}`, `GET /remote-command-coordinators/{tenantId}/{coordinatorId}`, `GET /remote-command-coordinators/{tenantId}`, `GET /remote-followups/{tenantId}/{followupId}`, `GET /remote-followups/{tenantId}`, `GET /remote-followup-results/{tenantId}/{resultId}`, `GET /remote-followup-results/{tenantId}`, and `GET /tick-remediation/status/{tenantId}`. Every route is tenant-guarded; point reads validate tenant and exact id, list reads validate every returned row, invalid enum filters fail closed, and control-plane scope mismatches are rejected.

Coordinator listing has a bounded REST filter set of origin/target game instance and region, state, follow-up id, command id, and limit. Upstream coordinator, follow-up, and result reads also expose first-class filters across scope and epochs, routing bundle, script/plugin/dispatch provenance, payload/source/queue/claim state, target entity/effect and target-command identity/outcomes, deadlines and late-result policy, script-event identity, and result error/message/outcomes. Rich list hydration is bounded in bulk rather than issuing one point lookup per row. Point and list DTOs preserve linked follow-up, result, target-command, publication, event, source, deadline, and current runtime comparison fields.

### Lifecycle and Feature Authority

Game Session `GameInstance` and World Management `WorldInstance` rows use optimistic-lock row versions. Redis session-context writes use watched multi-key retries across canonical session, identity, and name indexes. World termination does not hold a database transaction open across Entity Management cleanup, and same-request retries continue from `TERMINATING` without requiring callers to discover a changed lifecycle epoch. The hardening deliberately avoids long-lived transactions across blocking RPCs and avoids relying on in-process compensation as the only safety mechanism.

Runtime feature-flag writes enter through Logging & Admin but persist with Game Session, the runtime owner. Consumers read that one canonical authority; the former split Logging & Admin persistence path is retired. This authority convergence does not mean that all feature-controlled behavior is applied: richer runtime feature application and broader consumer coverage remain future work beyond the current toggle/persistence/read seam. Static build-time flags, deployment-only toggles, moderation, entitlement, and unrelated operator-control policy are outside this runtime authority boundary.

### Validation and Proof

The recorded proof covers durable command identity and sanitized persistence, restart/Redis-loss recovery at the ingress and ownership boundaries, accepted-but-never-staged convergence, single-record retry/replay updates, command correlation, batch/effect creation, manifest-digest replay repair, stale drain and post-drain fence rejection, durable `DRAINED` resumption, source-tuple preservation through timer/retry/remote claim-release transitions, fair selection, deterministic remote-result replay, paused-origin and late-result reconciliation, payload validation, target-command convergence, tenant/scope/id guards, and operator DTO/request mapping.

The focused validation commands recorded for the live slices are `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.impl.TickStagingServiceTest' --tests 'net.firedevops.firemud.gamesession.service.impl.TickBatchExecutionServiceTest' --tests 'net.firedevops.firemud.gamesession.service.impl.TickServiceImplTest'`, the corresponding `RemoteFollowupRuntimeServiceImplTest` and `DefaultDurableRemoteFollowupExecutionServiceTest`, `dev-tools/validation/run-locked-gradle.sh :game-session-service:check -PfullCheck`, and `dev-tools/validation/run-locked-gradle.sh :logging-admin-service:check -PfullCheck`. Logging & Admin point/list/status controller and service tests cover successful reads, tenant guards, invalid enums, and fail-closed response mismatches; Game Session control-plane contract tests cover the upstream reads and fencing. The scheduler has a real bounded `ThreadPoolTaskExecutor` pressure proof including threshold trip and recovery reset. Lifecycle, feature-authority, and migrated command suites provide the corresponding focused proofs.

The recorded Markdown and contract checks for these slices include `./gradlew linkCheck lintMarkdown`; this consolidation pass also passed both checks.

## Active Gaps

- Ownership is durable only at the current game-instance queue boundary. True region-partitioned execution, a cluster scheduler, and lease-owner forwarding from session front ends are not implemented.
- The scheduler has no adaptive gameplay-timing feedback loop. Pressure remains observable and operator-driven; changing gameplay timing requires preview or production-like data and a deliberate decision.
- Only the current built-in state-changing command families consume the durable effect path. Later authored or game-defined authoritative mutations must use it; read-only views remain direct by design.
- Timer, retry, remote-follow-up, and the three current remote payload families are live, but any new work-source or payload family still needs explicit durable source, ordering, ownership, fence, replay, result, and operator-read contracts. No side channel is part of the current implementation.
- The bounded operator readback family does not include historical dashboards, pagination/sorting, full region-level UI, or a generalized gameplay-command history surface.
- Downstream/domain-specific effect ledgers and replay/idempotency consumers beyond the migrated families remain future implementation work.
- Runtime feature-flag authority is canonical, but richer runtime feature application and complete consumer coverage remain unimplemented.

## To Discuss

No competing target state is currently recorded. Sol must decide the policy and evidence required before adaptive scheduling, true region partitioning, lease-owner forwarding, a new durable work-source or payload family, or a new authoritative gameplay mutation path is introduced. The current contract also leaves the older richer command acknowledgement vocabulary intentionally unresolved because `GetGameplayCommandStatus` is the live canonical read.

## Service and Contract Map

| Owner | Current responsibility | Primary contract boundary |
| --- | --- | --- |
| Game Session | Command ledger, queue staging, ticks, durable batches/effects, ownership, remote runtime, and canonical control-plane projection | Game Session gRPC/control plane; durable PostgreSQL records; Redis coordination |
| Automation Scripting | Timer/origin work, local same-scope command admission, cross-scope durable follow-up handoff, and script-event production | Automation ingress and Game Session command/follow-up APIs |
| Game Logic, Entity Management, Social Groups, and other domain services | Execute admitted durable effects and apply domain-owned idempotency/replay guards | Durable effect ids, command provenance, downstream owner-fence and replay contracts |
| Logging & Admin | Tenant-guarded operator ingress/readback and pause/resume remediation without parallel runtime truth | REST/OpenAPI over canonical Game Session control-plane reads |
| World Management | Lifecycle orchestration and cleanup invoked through non-transaction-spanning RPC boundaries | Optimistic lifecycle rows and termination/cleanup contracts |
| Redis and PostgreSQL | Fast coordination versus durable execution, ordering, recovery, and ownership truth | Queue/lock/pending/wake-up keys; command, batch, effect, ownership, and remote ledgers |

Focused scheduler, durable-ledger, remote-runtime, operator-readback, lifecycle, and fencing proofs remain recorded with exact commands in the source evidence.

## Source Evidence

The following records are the unchanged line-preserving transposition used as the audit backstop for the consolidated record above. Heading depth is shifted by three levels and same-directory Markdown links are rebased only so the combined tracker remains valid and navigable.

### source-02-18-11-task-list-migrate-live-gameplay-commands-onto-durable-execution-path-vertical-slice-1-111

#### `02.18.11` Migrate Live Gameplay Commands Onto the Durable Execution Path - Audited primary runtime or service owner (source lines 1-111)

##### Preserved Source Text: source-02-18-11-task-list-migrate-live-gameplay-commands-onto-durable-execution-path-vertical-slice-1-111

<!-- migration-source path="design/project-management/vertical-slices/02.18.11-task-list-migrate-live-gameplay-commands-onto-durable-execution-path-vertical-slice.md" lines="1-111" sha256="18ac9cf5c9a8812aa18e88fb058ba2f6d44b34d608a5e4acd6ad74f7ae9c2cc4" heading-offset="3" -->
#### source-02-18-11-task-list-migrate-live-gameplay-commands-onto-durable-execution-path-vertical-slice-1-111: `02.18.11` Migrate Live Gameplay Commands Onto the Durable Execution Path

##### source-02-18-11-task-list-migrate-live-gameplay-commands-onto-durable-execution-path-vertical-slice-1-111: Goal and Status

Goal: move direct state-changing gameplay commands off ad hoc synchronous mutation paths and onto the durable command/tick/effect execution substrate once that substrate exists. Status: complete for the current built-in state-changing gameplay command surface, with movement, item/equipment/container mutations, and communication/activity mutations now migrated.

##### source-02-18-11-task-list-migrate-live-gameplay-commands-onto-durable-execution-path-vertical-slice-1-111: Checklist

- [x] Define target-state behavior and scope.
- [x] Discussion pass with user before implementation.
- [x] Implement the slice end-to-end.
- [x] Verify and close any follow-ups.

##### source-02-18-11-task-list-migrate-live-gameplay-commands-onto-durable-execution-path-vertical-slice-1-111: Why This Slice Exists

Some gameplay paths still bypass the intended durable/replayable model today. That is acceptable while the stronger substrate is still missing, but it cannot remain the long-term shape:

- once durable command ids, tick batches, effect ledgers, fencing, and idempotency exist, direct synchronous mutation paths become the main architecture escape hatch;
- if left in place, they create two execution classes:
  - durable/replay-safe
  - ad hoc best-effort

This slice exists to track the migration explicitly rather than leaving it as an implicit cleanup note after the harder runtime work lands.

##### source-02-18-11-task-list-migrate-live-gameplay-commands-onto-durable-execution-path-vertical-slice-1-111: Discussion Gate

The discussion gate has been cleared. The agreed first pass is:

- movement is the first proving ground;
- pure view/meta commands may remain off the durable execution path for now;
- the first batch should prove one real state-changing family end to end before broadening to every gameplay command family.

Implementation now live at the first boundaries:

- built-in movement input no longer executes through the old synchronous dispatch handler path;
- movement commands now enqueue durably, drain into the `tick_batch` / `tick_effect` ledger, and execute from the post-drain durable effect path;
- the migrated movement path uses the same `MoveCommandHandler` planning logic as before for player-visible behavior, but the authoritative room mutation now occurs through the new idempotent durable effect seam rather than direct handler-side `SessionContextService.save(...)`;
- the old public synchronous movement handler result path has been removed, leaving `MoveCommandHandler` as a planning-only component for the durable executor instead of a second mutation entry point;
- async player-visible move output is now delivered through the live websocket/screen-buffer-aware `PlayerOutputDeliveryService`.
- item/equipment/container mutations now follow the same command ingress shape: `GET`, `DROP`, `PUT`, `TAKE`, `WEAR`, and `REMOVE` enqueue durably from the player command path, then execute from the durable effect executor with player-visible output delivered asynchronously;
- communication/activity mutations now follow that same shape for `SAY`, `WHISPER`, `TELL`, and `AFK`: the dispatch layer enqueues them durably, the executor performs the authoritative mutation or downstream RPC from the durable path, actor-visible output is delivered asynchronously after durable execution rather than inline from dispatch, replayed durable effects now converge through a Game Session-owned replay guard instead of re-invoking the handler, and the same durable `effectId` now flows through Game Logic into Social Groups so downstream chat persistence also converges on replay;
- read-only item views such as `INVENTORY`, `EQUIPMENT`, and `CONTAINER` intentionally remain direct view commands for now because they do not mutate authoritative gameplay state.

##### source-02-18-11-task-list-migrate-live-gameplay-commands-onto-durable-execution-path-vertical-slice-1-111: Suggested Direction

The durable migration direction is now established:

1. keep movement as the first Game Session-owned effect-idempotent mutation family;
2. keep item/equipment/container mutations as the first cross-service Entity Management effect-idempotent mutation family;
3. migrate any later state-changing gameplay command families through the same durable effect executor before adding new direct mutation seams;
4. add domain-level replay guards in the service that owns each later mutation rather than stopping at Game Session ledger identity;
5. leave pure meta/view commands off the durable path unless there is a real need.

The key principle remains:

- do not migrate commands onto a half-built durable path;
- only start this slice once the earlier substrate slices are grounded enough to be real consumers.

##### source-02-18-11-task-list-migrate-live-gameplay-commands-onto-durable-execution-path-vertical-slice-1-111: Scope

- Identify which command families still bypass the durable execution model.
- Define the migration order for those families.
- Move the first direct gameplay family onto the durable path.
- Remove duplicate or parallel execution semantics where possible.
- Keep command classification, prompt policy, and player-visible behavior aligned while execution internals change.

##### source-02-18-11-task-list-migrate-live-gameplay-commands-onto-durable-execution-path-vertical-slice-1-111: Out of Scope

- Building the durable command/tick/effect substrate itself.
- Migrating every command in one batch.
- Treating read-only view/meta commands as durable work without a correctness reason.
- Reworking pure meta/view commands unnecessarily.

##### source-02-18-11-task-list-migrate-live-gameplay-commands-onto-durable-execution-path-vertical-slice-1-111: Target State

- State-changing gameplay commands execute through the durable command/tick/effect path.
- Direct synchronous side-effect paths are reduced or eliminated for those families.
- Replay/recovery behavior is consistent across migrated gameplay commands.
- Command registry/dispatch architecture remains the canonical entry seam while execution shifts underneath it.

##### source-02-18-11-task-list-migrate-live-gameplay-commands-onto-durable-execution-path-vertical-slice-1-111: First Migration Candidates

Movement was the first proving ground because:

- it is user-visible;
- it changes authoritative gameplay state;
- it currently demonstrates the bypass issue clearly;
- it will pressure-test command ingress, batching, effect identity, fencing, and replay semantics together.

Item/equipment/container mutations are the second proving ground because they exercise cross-service Entity Management mutations behind the same Game Session durable effect executor. They now prove ingress, post-drain execution, downstream `effectId` propagation, and Entity Management response replay for `GET`, `DROP`, `PUT`, `TAKE`, `WEAR`, and `REMOVE`.

Communication/activity mutations are the third proving ground because they keep execution inside the Game Session boundary while still mutating live authoritative state and downstream player-visible behavior. `SAY`, `WHISPER`, `TELL`, and `AFK` now prove that later state-changing command families can reuse the same enqueue/executor/output shape without reintroducing synchronous mutation escape hatches in the text-command dispatch layer.

##### source-02-18-11-task-list-migrate-live-gameplay-commands-onto-durable-execution-path-vertical-slice-1-111: Validation

- [x] Prove the first migrated command family no longer bypasses the durable path.
- [x] Prove replay/retry/failover for that family converge safely at the first movement/session-context seam.
- [x] Prove player-visible semantics remain correct during the migration.
- [x] Prove old direct execution branches are removed or made unreachable for the migrated family.
- [x] Prove command registry/dispatch seams remain stable while execution internals change.
- [x] Prove the first item/equipment/container mutation family enqueues durably instead of mutating synchronously from the player dispatch path.
- [x] Prove item/equipment/container mutations have domain-level effect idempotency at the Entity Management boundary.
- [x] Prove the first communication/activity mutation family enqueues durably instead of mutating synchronously from the player dispatch path.
- [x] Prove player-visible communication/activity behavior still arrives correctly when output is delivered from durable execution rather than inline dispatch.
- [x] Prove replayed communication/activity durable effects no longer re-invoke the Game Session-owned handler path.
- [x] Prove the durable communication `effectId` reaches the downstream Social Groups persistence boundary.
- [x] Prove the full Game Session service still passes `./gradlew :game-session-service:check -PfullCheck` after the current built-in mutation families are migrated.

##### source-02-18-11-task-list-migrate-live-gameplay-commands-onto-durable-execution-path-vertical-slice-1-111: Follow-On Work

- Any future gameplay mutation family, including richer authored/game-defined mutations if they begin mutating authoritative state rather than emitting notices only, must start on the durable command/effect path instead of reopening direct synchronous execution seams.
<!-- /migration-source -->

### source-02-18-13-task-list-runtime-feature-flag-authority-convergence-vertical-slice-1-2-4-6-8-19-22-32-35-39-41-48

#### `02.18.13` Runtime Feature Flag Authority Convergence - Runtime feature-flag ownership and consumer authority (source lines 1-2, 4-6, 8-19, 22-32, 35-39, 41-48)

##### Preserved Source Text: source-02-18-13-task-list-runtime-feature-flag-authority-convergence-vertical-slice-1-2-4-6-8-19-22-32-35-39-41-48

<!-- migration-source path="design/project-management/vertical-slices/02.18.13-task-list-runtime-feature-flag-authority-convergence-vertical-slice.md" lines="1-2, 4-6, 8-19, 22-32, 35-39, 41-48" sha256="c3d83fa7e66ed1ba9b6c8ef78591f8976a84adc432d5315a17e3fefb98c96014" heading-offset="3" -->
#### source-02-18-13-task-list-runtime-feature-flag-authority-convergence-vertical-slice-1-2-4-6-8-19-22-32-35-39-41-48: `02.18.13` Runtime Feature Flag Authority Convergence

<!-- source-gap: lines 3-3 -->

##### source-02-18-13-task-list-runtime-feature-flag-authority-convergence-vertical-slice-1-2-4-6-8-19-22-32-35-39-41-48: Implementation Notes

<!-- source-gap: lines 7-7 -->

##### source-02-18-13-task-list-runtime-feature-flag-authority-convergence-vertical-slice-1-2-4-6-8-19-22-32-35-39-41-48: Why This Slice Exists

Runtime feature flags currently have split persistence authority:

- Logging & Admin stores operator toggles in its own database;
- Game Session stores its own feature-flag rows independently.

That conflicts with the architecture, which says operator-facing writes should enter through Logging & Admin but land on the owning runtime/control-plane authority instead of creating a second persisted truth.

##### source-02-18-13-task-list-runtime-feature-flag-authority-convergence-vertical-slice-1-2-4-6-8-19-22-32-35-39-41-48: Scope

<!-- source-gap: lines 20-21 -->
- Game Session as runtime consumer/owner where appropriate
- contract and read-model alignment for current runtime flag use

##### source-02-18-13-task-list-runtime-feature-flag-authority-convergence-vertical-slice-1-2-4-6-8-19-22-32-35-39-41-48: Out of Scope

- general settings-model redesign
- static build-time flags or deployment-only toggles
- moderation, entitlement, or unrelated operator-control-plane policy

##### source-02-18-13-task-list-runtime-feature-flag-authority-convergence-vertical-slice-1-2-4-6-8-19-22-32-35-39-41-48: Locked Direction

<!-- source-gap: lines 33-34 -->
- runtime reads should have one canonical persistence owner, not two parallel databases that happen to contain similar rows.

##### source-02-18-13-task-list-runtime-feature-flag-authority-convergence-vertical-slice-1-2-4-6-8-19-22-32-35-39-41-48: Acceptance Shape

- one domain owns persisted runtime feature-flag truth.
<!-- source-gap: lines 40-40 -->
- runtime consumers read from the canonical authority, and docs stop describing split ownership.

##### source-02-18-13-task-list-runtime-feature-flag-authority-convergence-vertical-slice-1-2-4-6-8-19-22-32-35-39-41-48: Checklist

- [x] Define the canonical feature-flag owner and write/read contract.
- [x] Replace the split persistence path with operator-ingress-to-owner propagation.
- [x] Remove or retire the non-owning duplicate persistence path.
- [x] Add focused tests proving operator writes and runtime ownership stay coherent.
<!-- /migration-source -->

### source-02-18-15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice-1-24-28-43-45-55

#### `02.18.15` World and Session Lifecycle Concurrency Hardening - Game-session lifecycle concurrency and session-state fencing (source lines 1-24, 28-43, 45-55)

##### Preserved Source Text: source-02-18-15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice-1-24-28-43-45-55

<!-- migration-source path="design/project-management/vertical-slices/02.18.15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice.md" lines="1-24, 28-43, 45-55" sha256="43afec2e15691b1ad3d1c1d1a8d8309ac3cf7049a31427ab4f552701d5d26c36" heading-offset="3" -->
#### source-02-18-15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice-1-24-28-43-45-55: `02.18.15` World and Session Lifecycle Concurrency Hardening

Goal: harden the current world/session lifecycle substrate so manual epoch checks, Redis session-state writes, and cross-service lifecycle orchestration stop relying on open transactions and in-process compensation as the only safety mechanism. Status: complete.

##### source-02-18-15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice-1-24-28-43-45-55: Implementation Notes

Game Session `GameInstance` rows and World Management `WorldInstance` rows now carry optimistic-lock row versions. Redis session-context writes already use watched multi-key retries for canonical session, identity, and name indexes. World termination no longer keeps the service transaction open across the Entity Management cleanup RPC, and same-request termination retries can continue from `TERMINATING` without requiring the caller to discover the incremented lifecycle epoch.

##### source-02-18-15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice-1-24-28-43-45-55: Why This Slice Exists

Several current audit findings are really one connected lifecycle/concurrency problem:

- session state is still stored in Redis as plain set/delete with no fencing or compare-and-set semantics;
- `GameInstance` still lacks stronger concurrency protection and crash-safe lifecycle convergence;
- world lifecycle methods still hold database transactions open across blocking gRPC calls;
- world termination still performs remote cleanup before the local transaction commits;
- manual epoch/version checks still rely on read-check-save logic instead of stronger atomic enforcement.

This is narrower than the later fully durable command/effect ledger work, but broader than one small transaction cleanup. It needs a real slice so the current lifecycle substrate can be tightened before the heavier `02.18.7`-`02.18.11` train lands.

##### source-02-18-15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice-1-24-28-43-45-55: Scope

- Game Session lifecycle row/state concurrency for start/stop/restart
- Redis session-state fencing or CAS semantics
<!-- source-gap: lines 25-27 -->

##### source-02-18-15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice-1-24-28-43-45-55: Out of Scope

- full durable command ledger and effect ledger work already tracked in `02.18.7`-`02.18.11`
- full event-sourced runtime redesign
- unrelated account/auth policy work

##### source-02-18-15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice-1-24-28-43-45-55: Locked Direction

- blocking remote cleanup/validation should not remain inside long-lived local DB transactions.
- lifecycle safety should not depend only on in-process compensation after partial durable publication.
- current manual lifecycle/epoch fields need stronger atomic enforcement than today’s read-check-save logic.
- this slice should strengthen the current lifecycle substrate directly, not add temporary dual-path compatibility scaffolding.

##### source-02-18-15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice-1-24-28-43-45-55: Acceptance Shape

<!-- source-gap: lines 44-44 -->
- session lifecycle state has clearer concurrency protection than raw Redis set/delete plus unversioned `GameInstance` rows.
- current epoch/lifecycle fields are enforced through stronger atomic write semantics or explicit optimistic-lock boundaries.
- docs clearly distinguish this lifecycle hardening from the later fully durable command/effect train.

##### source-02-18-15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice-1-24-28-43-45-55: Checklist

- [x] Define the current-target concurrency model for world/session lifecycle before the later durable-command train.
- [x] Move remote cleanup/validation out of the most risky open transaction boundaries.
- [x] Strengthen Game Session lifecycle row and Redis session-state concurrency protection.
- [x] Add focused tests through the existing lifecycle and service suites for current retry/crash-adjacent behavior.
- [x] Update durability/hardening docs so these current protections are explicit and not implied by later planned slices.
<!-- /migration-source -->

### source-02-18-20-task-list-shared-saga-ownership-and-control-plane-facade-thinning-vertical-slice-8-9-23-24-38-39

#### Shared Saga Ownership and Control-Plane Facade Thinning Vertical Slice - Control-plane facade extraction and delegation boundary (source lines 8-9, 23-24, 38-39)

##### Preserved Source Text: source-02-18-20-task-list-shared-saga-ownership-and-control-plane-facade-thinning-vertical-slice-8-9-23-24-38-39

<!-- migration-source path="design/project-management/vertical-slices/02.18.20-task-list-shared-saga-ownership-and-control-plane-facade-thinning-vertical-slice.md" lines="8-9, 23-24, 38-39" sha256="7c67a0c39e14a7c53b17c942f86d5fb1ea2e9b7b50541213bd418cbecc00b6ae" heading-offset="3" -->
- The Game Session command/status control-plane cluster now lives in [GameSessionCommandControlPlaneService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/GameSessionCommandControlPlaneService.java), which owns gameplay-command status projection, staged automation-command admission, alias validation, remote-result/status mapping, and publication lookup helpers. [GameSessionControlPlaneGrpcService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcService.java) now delegates that cluster instead of carrying those helpers inline, reducing the façade from its earlier ~3k-line shape down to a narrower transport/auth/error layer.

<!-- source-gap: lines 10-22 -->
- extract the remaining gameplay-command / automation-command / alias-validation cluster out of `GameSessionControlPlaneGrpcService`;
- reduce the gRPC class toward transport/auth/error delegation only for the extracted command-control-plane seam;
<!-- source-gap: lines 25-37 -->
- [x] Extract the remaining Game Session command/status control-plane cluster into a dedicated collaborator.
- [x] Reduce `GameSessionControlPlaneGrpcService` to transport/auth/error delegation for that seam.
<!-- /migration-source -->

### source-02-18-6-task-list-tick-scheduler-backpressure-and-merge-semantics-vertical-slice-1-79

#### Tick Scheduler Backpressure and Merge Semantics Vertical Slice - Audited primary runtime or service owner (source lines 1-79)

##### Preserved Source Text: source-02-18-6-task-list-tick-scheduler-backpressure-and-merge-semantics-vertical-slice-1-79

<!-- migration-source path="design/project-management/vertical-slices/02.18.6-task-list-tick-scheduler-backpressure-and-merge-semantics-vertical-slice.md" lines="1-79" sha256="d2d8d95207e602f2639c55282587cdd1a73eccc8e6ed386093b03608e0dfef38" heading-offset="3" -->
#### source-02-18-6-task-list-tick-scheduler-backpressure-and-merge-semantics-vertical-slice-1-79: Tick Scheduler Backpressure and Merge Semantics Vertical Slice

##### source-02-18-6-task-list-tick-scheduler-backpressure-and-merge-semantics-vertical-slice-1-79: Goal and Status

Goal: finish the remaining runtime-hardening gap by making tick scheduling bounded, observable, and safe under executor pressure without introducing a dedicated queue/lease scheduler. Status: complete at the current boundary.

##### source-02-18-6-task-list-tick-scheduler-backpressure-and-merge-semantics-vertical-slice-1-79: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end-to-end.
- [x] Verify and close any follow-ups.

##### source-02-18-6-task-list-tick-scheduler-backpressure-and-merge-semantics-vertical-slice-1-79: Why This Slice Exists

The hardening review already locked the target direction:

- keep bounded fan-out;
- allow at most one pending or running tick per session;
- merge/skip under pressure rather than accumulating unbounded debt;
- expose merges, rejections, and scheduler pressure in metrics/logging.

It also exposed one adjacent correctness problem that belongs with this slice:

- gameplay ticks and automation script ticks currently use overlapping Redis key prefixes such as `tick:queue:*`, `tick:lock:*`, and `tick:pending:*`;
- if those services share Redis, `(tenantId, sessionId)` and `(tenantId, scriptId)` collisions can corrupt queues, steal locks, or cross-rollback unrelated work.

This dedicated slice exists so that runtime-scheduling hardening is tracked as a real follow-up instead of lingering only in a temp worklist.

##### source-02-18-6-task-list-tick-scheduler-backpressure-and-merge-semantics-vertical-slice-1-79: Implementation Notes

Implemented now:

- gameplay and automation/script tick Redis key namespaces are separated;
- focused unit proofs now assert the distinct gameplay and automation tick Redis key prefixes directly;
- scheduler fan-out is bounded so one session has at most one pending/running tick;
- overlapping scheduler pulses merge instead of queueing duplicate work;
- executor rejections increment explicit rejection metrics;
- scheduler now records scheduled, merged, rejected, and paused-cycle counters;
- scheduler logs pressure summaries when merges or rejections occur;
- scheduler now tracks conservative consecutive-cycle thresholds for:
  - sustained rejections;
  - sustained elevated merge cycles;
  - sustained high executor queue depth;
- threshold crossings now increment dedicated alert counters and expose consecutive-cycle gauges so operators can alert on the observability-first policy directly;
- the canonical tick alert snippets now use the exported threshold/cycle gauges instead of re-hardcoding numeric defaults in rules;
- the tick incident runbook now has a dedicated scheduler-pressure incident flow instead of routing these alerts through the generic stalled-region path;
- focused real-executor proof now exercises the configured threshold path under actual bounded `ThreadPoolTaskExecutor` pressure, including alert-threshold trip and post-pressure recovery reset.

No further work remains in this slice at the current boundary. If real preview/production data later shows that a dedicated queue/lease scheduler or materially different thresholds are warranted, that should land as a new follow-up slice rather than re-opening this one as vague future debt.

##### source-02-18-6-task-list-tick-scheduler-backpressure-and-merge-semantics-vertical-slice-1-79: Scope

- Define one pending/running tick cap per session.
- Define merge/skip behavior under scheduler pressure.
- Define rejection handling and observability.
- Define the minimum metrics/logging expected from the scheduler.
- Define unambiguous Redis key namespaces so gameplay ticks and automation/script ticks cannot collide in shared Redis topology.

##### source-02-18-6-task-list-tick-scheduler-backpressure-and-merge-semantics-vertical-slice-1-79: Out of Scope

- Replacing the scheduler with a dedicated queue/lease subsystem.
- Broader game-logic action scheduling beyond the current tick model.

##### source-02-18-6-task-list-tick-scheduler-backpressure-and-merge-semantics-vertical-slice-1-79: Locked Direction

- bounded fan-out remains the current scheduler model;
- one session should have at most one pending or running tick at a time;
- overlapping scheduler pulses merge rather than accumulate debt;
- executor rejection is observable and does not silently disappear;
- Redis namespaces for gameplay session ticks and automation/script ticks must remain distinct.
- current scheduler pressure handling is observability-first:
  - merges, rejections, queue depth, and paused cycles must be visible in metrics/logging/alerts;
  - gameplay timing policy does not automatically adapt in response to pressure in the current model;
  - scheduler pressure is treated as an operator alert/input, not as an automatic gameplay-timing control loop;
  - any future timing feedback policy should be a separate deliberate decision backed by preview/prod-like data rather than an incidental side effect of scheduler saturation.
- the first alert-threshold direction should remain simple and conservative:
  - sustained non-zero rejection is alert-worthy;
  - sustained elevated merge rate is alert-worthy;
  - sustained high executor queue depth is alert-worthy.
<!-- /migration-source -->

### source-02-18-7-task-list-durable-command-ingress-and-status-ledger-vertical-slice-1-186

#### `02.18.7` Durable Command Ingress and Status Ledger - Audited primary runtime or service owner (source lines 1-186)

##### Preserved Source Text: source-02-18-7-task-list-durable-command-ingress-and-status-ledger-vertical-slice-1-186

<!-- migration-source path="design/project-management/vertical-slices/02.18.7-task-list-durable-command-ingress-and-status-ledger-vertical-slice.md" lines="1-186" sha256="5c6bf83462e2463184adb5ee47d32d8622e77daa0a5aafa557ad532badbf4e37" heading-offset="3" -->
#### source-02-18-7-task-list-durable-command-ingress-and-status-ledger-vertical-slice-1-186: `02.18.7` Durable Command Ingress and Status Ledger

##### source-02-18-7-task-list-durable-command-ingress-and-status-ledger-vertical-slice-1-186: Goal and Status

Goal: introduce one durable command-ingress and command-status substrate so accepted gameplay commands have a canonical persisted identity, lifecycle, and operator-visible outcome instead of existing only as transient queue/pending state plus ad hoc logs. Status: complete for the current ingress/status boundary.

##### source-02-18-7-task-list-durable-command-ingress-and-status-ledger-vertical-slice-1-186: Checklist

- [x] Define target-state behavior and scope.
- [x] Discussion pass with user before implementation.
- [x] Implement the slice end-to-end.
- [x] Verify and close any follow-ups.

##### source-02-18-7-task-list-durable-command-ingress-and-status-ledger-vertical-slice-1-186: Implementation Notes

Current substrate now live:

- `game-session-service` persists a `gameplay_command` ledger row after ingress validation/rate/queue-target resolution and before Redis queue staging.
- The first command lifecycle vocabulary is concrete:
  - `ACCEPTED` means the command has durable identity but has not yet been staged into the runtime queue.
  - `STAGED` means Redis queue staging succeeded.
  - `DRAINED` means a later durable tick batch consumed the queued command through the current Redis stage/drain runtime path.
  - `RETRY_QUEUED` means a durable tick-batch attempt abandoned and requeued the command for another attempt.
  - `FAILED` means staging failed before gameplay application.
  - `LOST_BEFORE_STAGING` means recovery found a durably accepted command that never reached staging.
- `CommandEnqueueResult` and the gRPC `EnqueueCommandResponse` now carry `commandId` for accepted/staging-failed commands.
- The ledger stores sanitized command text rather than raw credential-bearing text, so `LOGIN` payloads do not persist passwords or OTPs.
- Game Session startup recovery converges accepted-but-never-staged records to terminal `LOST_BEFORE_STAGING` instead of leaving them ambiguous after crash/restart.
- The Game Session control-plane gRPC surface exposes `GetGameplayCommandStatus` for bounded operator/runtime diagnosis by `commandId`.
- Command ingress logs and MDC now include `commandId` around queue staging so runtime logs can correlate with the durable record.
- Redis tick queue payloads now carry the durable `commandId` alongside the solo/normal tick marker, so the identity survives past ingress and can be consumed by the later tick/effect ledger work.
- the command ledger now also persists a comparable current-boundary `enqueueSeq` on each accepted command, and `GetGameplayCommandStatus` exposes that value so later batch/replay work does not need to infer ordering from timestamps or PostgreSQL row ids indirectly.
- the same status read now also exposes the last selected queue-source kind/state/ordinal plus due-point tuple from the durable command ledger, so current attempt provenance no longer depends on parsing the tick batch manifest or guessing from `RETRY_QUEUED`.
- The current tick runtime now updates command rows again when durable tick batches drain or abandon queued work, so the command ledger no longer stops at ingress-only queue staging.
- Logging & Admin now exposes the canonical command-status read directly under `GET /gameplay-commands/{tenantId}/{commandId}`, so bounded operator tooling no longer needs direct gRPC access just to inspect one durable command row.
- The richer target-state status surface described in older tick docs is still not live at this slice boundary:
  - there is no separate `ackLevel` / `ingressStatus` contract yet;
  - `GetGameplayCommandStatus` is the canonical current API, not the older target-state `GetCommandStatus` / bound-tick vocabulary.
- The remaining deeper runtime work now belongs in `02.18.8` and later slices rather than leaving this slice half-open.

##### source-02-18-7-task-list-durable-command-ingress-and-status-ledger-vertical-slice-1-186: Why This Slice Exists

The architecture docs already assume a stronger command contract than the runtime currently exposes:

- gameplay commands should have durable identity;
- command execution and gameplay-visible result should be tracked independently of Redis queue state;
- crash recovery should be able to distinguish:
  - accepted but never staged;
  - staged but not fully applied;
  - applied;
  - failed;
  - abandoned/replayed.

Today, the main runtime only exposes immediate enqueue acceptance and transient Redis coordination state. That is enough for the current narrow runtime, but it is not enough for the intended hosted-operator bar:

- operators need to answer "what happened to command X?" without reconstructing logs;
- later tick-batch, effect-ledger, and replay safety work needs a stable durable command identity to attach to;
- reset/recovery flows need one canonical place to converge accepted-but-unbound commands into terminal status.

This slice exists to pull that already-documented architecture requirement into explicit tracked implementation work.

##### source-02-18-7-task-list-durable-command-ingress-and-status-ledger-vertical-slice-1-186: Discussion Gate

The discussion gate has been cleared. The agreed direction is to do this before more gameplay/domain work because later state-changing features should attach to one durable execution identity rather than expanding direct-mutation paths.

The discussion confirmed:

- exact command-status vocabulary;
- minimum persisted payload shape;
- whether the first public surface should expose polling/query or only internal status updates;
- whether player-facing command ids should be directly returned immediately or wrapped in a higher-level acknowledgement shape.

The suggested direction below remains the target for the unfinished parts of the slice.

##### source-02-18-7-task-list-durable-command-ingress-and-status-ledger-vertical-slice-1-186: Suggested Direction

The first honest implementation should stay narrow and durable:

- add a persistent command ledger table in `game-session-service`;
- assign a canonical `commandId` at ingress;
- persist:
  - `tenantId`
  - `gameInstanceId`
  - `sessionId` / actor identity as appropriate
  - canonical parsed command payload or normalized command envelope
  - `executionOutcome`
  - `gameplayResult`
  - timestamps for accepted/staged/applied/failed
  - retry/replay counters or last-attempt metadata
  - failure code and message where applicable
- return `commandId` and immediate acceptance from enqueue paths;
- make later tick/batch/effect work update this ledger instead of inventing separate parallel status truth.

##### source-02-18-7-task-list-durable-command-ingress-and-status-ledger-vertical-slice-1-186: Scope

- Define one durable command-ingress identity and persistence model.
- Define one canonical command-status lifecycle.
- Define the minimum persisted command payload/envelope required for replay/recovery observability.
- Define how command status separates:
  - execution convergence;
  - gameplay-visible success/failure.
- Define how resets and crash recovery converge accepted-but-never-staged commands.

##### source-02-18-7-task-list-durable-command-ingress-and-status-ledger-vertical-slice-1-186: Out of Scope

- Deeper tick-batch and effect-ledger execution truth beyond the current command-linked batch drain statuses.
- Full player-facing command history UI.
- Replacing every direct gameplay path in the same slice.
- Long-term transcript/history UX.

##### source-02-18-7-task-list-durable-command-ingress-and-status-ledger-vertical-slice-1-186: Current Design Pressure

The current architecture docs already describe command-status and recovery concepts, but they are not yet reflected as concrete runtime substrate:

- there is no durable command-status table in `game-session-service`;
- the gRPC command surface still centers on immediate acceptance rather than durable lifecycle;
- reset/recovery design references accepted-but-unbound command convergence, but that convergence has no concrete storage seam yet.

##### source-02-18-7-task-list-durable-command-ingress-and-status-ledger-vertical-slice-1-186: Target State

- Every accepted gameplay command receives a stable `commandId`.
- Command ingress persists before or alongside queue staging, not after side effects.
- Command records survive Redis loss, process restart, and executor failover.
- Command status distinguishes:
  - `ACCEPTED`
  - `STAGED`
  - `APPLIED`
  - `FAILED`
  - `LOST_BEFORE_STAGING`
  - later replay/abandon states if required
- Gameplay-visible result is persisted separately from internal execution convergence so operators can tell "applied but returned user-visible failure" from "never applied".
- Later tick/effect ledgers attach to `commandId` rather than building a second top-level identity model.

##### source-02-18-7-task-list-durable-command-ingress-and-status-ledger-vertical-slice-1-186: Suggested First Data Model

The first implementation should likely include one durable command table with fields equivalent to:

- `command_id`
- `tenant_id`
- `game_instance_id`
- `session_id`
- `actor_id` or equivalent runtime subject
- `raw_command_text`
- canonical normalized command envelope/payload
- `execution_outcome`
- `gameplay_result`
- `accepted_at`
- `staged_at`
- `completed_at`
- `last_attempt_at`
- `failure_code`
- `failure_message`

The key design principle is:

- one canonical command record per logical accepted command;
- retries and replay update that record rather than creating shadow status records.

##### source-02-18-7-task-list-durable-command-ingress-and-status-ledger-vertical-slice-1-186: Suggested Status Split

The first design should keep two axes explicit:

- `executionOutcome`
  - did the runtime converge the command?
- `gameplayResult`
  - what should the player/operator consider the resulting gameplay state?

This avoids later confusion when a command is durably accepted and processed but ultimately results in no-op, replay, rejection, or recovery failure.

##### source-02-18-7-task-list-durable-command-ingress-and-status-ledger-vertical-slice-1-186: Recommended Implementation Order

1. Define the canonical command status vocabulary and persistence shape.
2. Persist command records at ingress with assigned `commandId`.
3. Thread `commandId` through queue/tick handling.
4. Update the command record at stage/complete/failure boundaries.
5. Add one bounded query/diagnostic surface for operator/runtime use.
6. Only then attach tick-batch and effect-ledger work to the same command ids.

##### source-02-18-7-task-list-durable-command-ingress-and-status-ledger-vertical-slice-1-186: Validation

- [x] Prove accepted commands receive durable `commandId`s.
- [x] Prove command records survive process restart and Redis loss at the current ingress/recovery boundary.
- [x] Prove accepted-but-never-staged commands can converge to terminal status during reset/recovery.
- [x] Prove retries/replays update one canonical record rather than creating duplicate command truth at the current command-ledger level.
- [x] Prove logs can correlate runtime behavior by `commandId`.
- [x] Prove queued tick payloads carry the durable `commandId`.
<!-- /migration-source -->

### source-02-18-7-1-task-list-operator-gameplay-command-status-readback-vertical-slice-1-89

#### 02.18.7.1 Task List: Operator Gameplay Command Status Readback Vertical Slice - Audited primary runtime or service owner (source lines 1-89)

##### Preserved Source Text: source-02-18-7-1-task-list-operator-gameplay-command-status-readback-vertical-slice-1-89

<!-- migration-source path="design/project-management/vertical-slices/02.18.7.1-task-list-operator-gameplay-command-status-readback-vertical-slice.md" lines="1-89" sha256="a6d03d6193d0cd77bb93a214ce4e0a8c80438f03682f39964dbf7e37da19e42b" heading-offset="3" -->
#### source-02-18-7-1-task-list-operator-gameplay-command-status-readback-vertical-slice-1-89: 02.18.7.1 Task List: Operator Gameplay Command Status Readback Vertical Slice

##### source-02-18-7-1-task-list-operator-gameplay-command-status-readback-vertical-slice-1-89: Goal and Status

Goal: expose the canonical Game Session `GetGameplayCommandStatus` query through Logging & Admin so operators can inspect one durable gameplay command row, its lifecycle, routing bundle, and remote follow-up state without dropping to gRPC. Status: complete at the current bounded boundary.

##### source-02-18-7-1-task-list-operator-gameplay-command-status-readback-vertical-slice-1-89: Why This Slice Exists

`02.18.7` already landed the durable gameplay-command ledger and the bounded Game Session status query, but one operator seam still lagged behind:

- Game Session exposed `GetGameplayCommandStatus` only on the control-plane gRPC surface;
- operators could inspect adjacent runtime and scripting read models through Logging & Admin, but gameplay command status still required direct gRPC access;
- that left the durable command-status substrate one transport seam short of the operator ingress that now owns the rest of the bounded runtime readback family.

This slice closes that one readback gap without widening into list/search dashboards, transcript history, or a new local command-status projection.

##### source-02-18-7-1-task-list-operator-gameplay-command-status-readback-vertical-slice-1-89: Scope

- Logging & Admin client, service, controller, DTO, and OpenAPI support for `GetGameplayCommandStatus`;
- tenant-qualified operator REST ingress for one `{tenantId, commandId}` command-status read;
- focused proof for successful readback, tenant guard enforcement, and fail-closed mismatched tenant identity.

##### source-02-18-7-1-task-list-operator-gameplay-command-status-readback-vertical-slice-1-89: Out of Scope

- changes to Game Session command-ledger persistence or status vocabulary;
- operator list/filter history surfaces for gameplay commands;
- wider tick/effect-ledger follow-through already tracked under `02.18.8` and later slices.

##### source-02-18-7-1-task-list-operator-gameplay-command-status-readback-vertical-slice-1-89: Locked Direction

- Logging & Admin must consume the canonical Game Session command-status query directly rather than rebuilding local status truth;
- operator ingress stays bounded to one tenant-qualified `commandId` read instead of widening to search/list semantics;
- control-plane tenant mismatches are contract violations and must fail closed.

##### source-02-18-7-1-task-list-operator-gameplay-command-status-readback-vertical-slice-1-89: Planned Work

###### source-02-18-7-1-task-list-operator-gameplay-command-status-readback-vertical-slice-1-89: 1. Operator Read Surface

- [x] Add a Logging & Admin control-plane client method for `GetGameplayCommandStatus`.
- [x] Add a tenant-qualified Logging & Admin route for one bounded gameplay-command status read.
- [x] Map the canonical command lifecycle, routing, publication-link, and remote follow-up fields onto a dedicated operator DTO.

###### source-02-18-7-1-task-list-operator-gameplay-command-status-readback-vertical-slice-1-89: 2. Proof and Docs

- [x] Add focused Logging & Admin controller/service proof for successful readback and tenant-guard failure paths.
- [x] Reuse the existing Game Session gRPC proof as the upstream contract evidence for command-status reads.
- [x] Update `02.18.7`, slice indexes, and Logging & Admin `openapi.yaml` so the operator readback is tracked explicitly.

##### source-02-18-7-1-task-list-operator-gameplay-command-status-readback-vertical-slice-1-89: Acceptance Shape

- Logging & Admin exposes `GET /gameplay-commands/{tenantId}/{commandId}`;
- the route returns the canonical durable command row, including lifecycle timestamps, queue-source provenance, current routing bundle, publication links, and remote follow-up/result state;
- callers without access to `tenantId` are rejected before the control-plane read;
- a control-plane response whose `tenantId` does not match the requested tenant fails closed instead of being silently trusted.

##### source-02-18-7-1-task-list-operator-gameplay-command-status-readback-vertical-slice-1-89: Completion Notes

- `GameSessionControlPlaneClient` now exposes `getGameplayCommandStatus(String commandId)` for Logging & Admin.
- `GameplayCommandController` now serves `GET /gameplay-commands/{tenantId}/{commandId}` and enforces tenant access before delegating.
- `GameplayCommandStatusServiceImpl` now consumes the canonical Game Session response, reuses the shared app-error-to-HTTP mapping pattern, validates returned `tenantId`, and maps lifecycle, routing, publication-link, and remote follow-up fields onto `GameplayCommandStatusDto`.
- Logging & Admin `openapi.yaml` now documents the gameplay-command route and DTO so the published operator contract matches the landed endpoint.

##### source-02-18-7-1-task-list-operator-gameplay-command-status-readback-vertical-slice-1-89: Completion Evidence

- Logging & Admin implementation:
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/client/GameSessionControlPlaneClient.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/controller/GameplayCommandController.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/GameplayCommandStatusService.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/impl/GameplayCommandStatusServiceImpl.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/dto/GameplayCommandStatusDto.java`
  - `services/logging-admin-service/src/main/resources/openapi.yaml`
- Focused Logging & Admin proof:
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/GameplayCommandControllerTest.java`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/GameplayCommandStatusServiceImplTest.java`
- Existing Game Session command-status contract proof reused by this operator surface:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcServiceTest.java`

##### source-02-18-7-1-task-list-operator-gameplay-command-status-readback-vertical-slice-1-89: Validation

- `./gradlew :logging-admin-service:test --tests 'net.firedevops.firemud.loggingadmin.controller.GameplayCommandControllerTest' --tests 'net.firedevops.firemud.loggingadmin.service.impl.GameplayCommandStatusServiceImplTest'`
- `./gradlew spotlessApply`
- `dev-tools/validation/run-locked-gradle.sh :logging-admin-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`

##### source-02-18-7-1-task-list-operator-gameplay-command-status-readback-vertical-slice-1-89: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-02-18-8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice-1-315

#### `02.18.8` Tick Batch and Effect Ledger Hardening - Audited primary runtime or service owner (source lines 1-315)

##### Preserved Source Text: source-02-18-8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice-1-315

<!-- migration-source path="design/project-management/vertical-slices/02.18.8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice.md" lines="1-315" sha256="21637300512b4bde4e0aad2ff87d3ca538ec8471d1afab7366830fa20887dbbf" heading-offset="3" -->
#### source-02-18-8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice-1-315: `02.18.8` Tick Batch and Effect Ledger Hardening

##### source-02-18-8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice-1-315: Goal and Status

Goal: add the first durable tick-batch and effect-ledger substrate so Redis tick staging becomes hot-path coordination over durable execution truth instead of the only real record of in-flight gameplay work. Status: complete at the current boundary.

##### source-02-18-8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice-1-315: Checklist

- [x] Define target-state behavior and scope.
- [x] Discussion pass with user before implementation.
- [x] Implement the slice end-to-end.
- [x] Verify and close any follow-ups.

##### source-02-18-8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice-1-315: Implementation Notes

First ledger substrate now live:

- `game-session-service` persists a durable `tick_batch` row whenever the current Redis tick runtime stages a batch from queue to pending.
- `game-session-service` persists one `tick_effect` row per queued gameplay command inside that batch, keyed by a stable `effectId` and linked back to the durable `commandId` when one exists.
- `tick_effect.targetAggregate` is now also target-aware at the current boundary: when the staged command already knows a concrete character or entity target, the durable effect row records that narrower aggregate instead of flattening everything to the whole game instance.
- Durable `tick_batch` rows now also capture the current `regionEpoch` / `executorFence` ownership snapshot from the first live ownership row, so later fencing work has a real batch-level attachment point.
- Durable `tick_batch` rows now also persist the current explicit `regionId` from that ownership row instead of leaving the transitional `gameInstanceId` -> region mapping implicit in replay-grade batch history.
- Durable `tick_batch` rows now persist the current-boundary selected-work manifest, `expectedEffectCount`, and a stable manifest digest for the gameplay-command queue entries that formed the batch.
- The sealed gameplay-command manifest root now also records that same current-boundary `regionId`, so later region-scoped replay and ownership work do not have to infer the runtime scope only from batch foreign keys or queue-key naming.
- The current gameplay-command manifest now carries the first comparable current-boundary ordering and source-state details too: `enqueueSeq`, `sourceType`, `dueTickId`, explicit source-state metadata, and the current automation provenance already present on staged command rows (`automationDispatchId`, `automationWorkItemId`, script/plugin identity, target entity, region scope, and admitted routing bundle) for each selected command item.
- Automation durable work items, ingress audits, handler audits, and handoff events now also persist explicit current-boundary source metadata (`sourceKind`, `sourceState`, `sourceOrdinal`, `sourceDueTickId`, `sourceDueAtMs`) for gameplay-originated trigger work and scheduler-fired timer work instead of forcing later runtime reads to infer timer origin/order from payload blobs or ad hoc event ids.
- Game Session automation-command handoff now also preserves that source contract onto the durable `gameplay_command` ledger (`originSourceKind`, `originSourceState`, `originSourceOrdinal`, due-point metadata), exposes it through gameplay-command status reads, and carries it into the selected-work manifest beside queue-claim metadata instead of flattening all automation-originated queue work to one opaque source.
- The sealed gameplay-command manifest now uses the durable queue ordering fact (`enqueueSeq`) as `sourceOrdinal` when it exists instead of persisting a transient in-memory slot index as if it were a replay-grade ordering key.
- The first bounded retry-source follow-through is now live at the current queue boundary:
  - when a staged gameplay command is being re-attempted from `RETRY_QUEUED`, the selected-work manifest records `sourceKind=GAMEPLAY_RETRY` and `sourceState=REDIS_RETRY_CLAIMED` instead of collapsing that pass back into the same metadata as first-time queue work;
  - ordinary first-attempt queue work still records `sourceKind=GAMEPLAY_COMMAND` and `sourceState=REDIS_PENDING_CLAIMED`;
  - this is still only a first retry-source proving ground inside the current gameplay-command queue boundary, not yet the full separate retry/timer/follow-up work-source model from target-state tick docs.
- That current queue-claim truth no longer lives only inside sealed batch JSON:
  - `gameplay_command` now persists the last selected queue-source kind/state/ordinal plus due-point tuple (`queueSourceKind`, `queueSourceState`, `queueSourceOrdinal`, `queueSourceDueTickId`, `queueSourceDueAtMs`) whenever the tick runtime claims work for a batch;
  - `GetGameplayCommandStatus` now exposes that current queue-source metadata directly, so operator/runtime reads can distinguish first-attempt queue claims from retry claims without parsing batch manifests or inferring from `executionOutcome` alone.
- Requeue observability now also preserves that same current-boundary source truth instead of flattening all retries into one player-only counter:
  - stale-drained batch abandonment, fresh-stage rollback, and manifest-mismatch replay repair now increment `tick_requeued_action_total` by actual action count and by durable command source (`player`, `automation`, or `unknown`);
  - replay/repair metrics therefore line up with the same source distinctions already present on the gameplay-command ledger and sealed selected-work manifest instead of reporting every requeue as generic player work.
- Requeue-time queue-source truth now also lands immediately on the durable command ledger instead of only after the next retry claim:
  - when stale-drained batches or manifest-mismatch replay repair return a command to Redis, the saved command row now flips `queueSourceKind=GAMEPLAY_RETRY` and `queueSourceState=REDIS_RETRY_QUEUED` at that requeue step;
  - later retry claims still advance that same row and the selected-work manifest to `REDIS_RETRY_CLAIMED`, but operator/runtime reads no longer have a false first-attempt gap between requeue and the next claimed pass.
- Replay recovery now treats the sealed durable gameplay-command manifest as authoritative when Redis `pending` diverges:
  - mismatch no longer means "create a fresh batch from whatever Redis currently claims is pending";
  - Game Session now rebuilds the replay batch from the sealed durable manifest plus command ledger rows, rewrites Redis `pending` to that sealed projection, and returns any redis-only residue back to the queue.
- The current batch/effect lifecycle vocabulary is now concrete across both the stage/drain seam and the first post-drain execution seam:
  - batch states: `STAGED`, `DRAINED`, `APPLIED`, `ABANDONED`
  - effect states: `STAGED`, `DRAINED`, `APPLIED`, `REPLAY_NOOP`, `REJECTED`, `ABANDONED`
- Existing pending work replay is now correlated back to a durable batch row instead of being only a Redis-side drain event; if no older staged batch row exists, recovery creates one bounded `PENDING_REPLAY` batch record before convergence.
- The command ledger now advances again when batches drain or abandon:
  - `DRAINED` for the current stage/drain runtime completion seam
  - `RETRY_QUEUED` when a batch attempt is abandoned and rolled back into the queue
- The current batch-drain seam now refuses to finalize when the durable owner row's `regionEpoch` / `executorFence` no longer matches the batch snapshot, so Redis drain/rollback behavior is no longer blind to ownership drift.
- The current runtime now also re-enters durable execution from the ledger itself:
  - after Redis drain commit, Game Session executes any `DRAINED` effects for that queue boundary from the durable ledger rather than assuming the queue already implies terminal gameplay work;
  - if a worker restarts after drain but before effect application, later ticks resume from the durable `tick_batch` / `tick_effect` rows instead of silently forgetting that work existed.
- Post-drain durable replay now honors the current ownership fence before effect application; stale drained batches are abandoned and their unapplied durable commands are requeued for a fresh fenced batch instead of being applied by an old executor.
- Pending replay now verifies that any surviving `STAGED` batch still matches its sealed selected-work manifest digest before reuse; mismatched pending residue is recorded as `MANIFEST_MISMATCH` and forced onto a fresh replay batch instead of silently mutating the older batch contract.
- This is still intentionally narrower than the long-term architecture:
- the current tick path still uses the present game-instance queue boundary rather than the final region-partitioned runtime model, even though the durable batch contract now stores an explicit current-boundary `regionId` field for that scope;
- the current tick queue boundary now requires durably identified gameplay commands instead of carrying an older no-`commandId` fallback path into sealed batch recovery;
- only the first migrated command family currently consumes the post-drain execution seam;
- later gameplay services and richer effect families still need to move onto the same durable execution truth;
- the live substrate now persists the current gameplay-command selected-work manifest, but not yet per-source claim records for timer/retry/remote-follow-up candidates or the cross-region result-inbox contract described in target-state tick docs.
- the first durable cross-region substrate is now executable on both sides of the ownership seam: `remote_followup`, `remote_command_coordinator`, and origin-addressed `remote_followup_result` rows exist as Game Session-owned records; the origin-side runtime can now schedule follow-ups, write the bounded best-effort `remote:<tenantId>:<entityId>` wake-up hint, preserve an explicit coordinator `followupId` link instead of relying on ambiguous tuple matching, reconcile durable result-inbox rows before evaluating deadline timeouts from committed tick progress, and surface durable due/backlog pressure from target-region follow-up rows during ticks; the target-side runtime now also has a dedicated claim/release service that durably marks bounded due follow-ups `CLAIMED` against one `tickBatchId`, stages them as first-class `tick_batch` / `tick_effect` work with `batchSource=REMOTE_FOLLOWUP_DRAIN`, and executes them through a dedicated durable remote-followup executor. The first real payload handlers are now live too: kind `enqueue_automation_command` reuses the same automation-command admission contract as the control-plane `EnqueueAutomationCommandIfAbsent` surface, kind `enqueue_gameplay_command` now admits a non-automation target-side gameplay command directly from durable remote-row truth, and kind `trigger_script_event` now admits a target-region script trigger through the same Automation ingress contract using explicit remote scope plus routing/provenance truth from the durable remote rows. Unsupported, invalid, command-less, or snapshot-incomplete live payload kinds now fail closed at origin-side schedule time before durable coordinator/followup rows are even written, and target execution still fails closed if an older row reaches execution with an unsupported payload kind. Target execution now only terminalizes the target-side followup row plus one durable origin-addressed result row; even missing-coordinator failures now explicitly abandon and unclaim the target-side followup instead of leaving it stranded `CLAIMED`. Origin-side tick reconciliation is the only path that advances `remote_command_coordinator` out of `PENDING_REMOTE` or `REMOTE_TIMEOUT_ABANDONED`, and that reconciliation now fails closed across origin-epoch changes so stale-epoch pending results cannot bypass reset/timeout convergence. The shared tick rollback/stale-fence paths now explicitly release claimed follow-ups again instead of leaving abandoned batch ownership implicit.
- that remote origin-side schedule seam is now also exposed through the internal Game Session control-plane API instead of remaining reachable only from in-process Java wiring: services can submit canonical remote follow-up requests over gRPC and get back the durable coordinator/followup ids plus idempotent created-vs-reused truth, while the same payload-validation and immutable-metadata rules still fail closed through that API.
- that due/backlog pressure is now also visible on the shared runtime ownership status read instead of only internal tick gauges: `GetRuntimeOwnershipStatus` carries current pending gameplay-command count, due remote-followup count, and oldest due remote-followup tick id for the same current-boundary scope.
- the live tick runtime now emits aggregate remote follow-up gauges from that same durable due-followup truth instead of only an internal helper: `remote_followups_due_total` and `remote_followups_drain_lag_ms` are now updated from owned-region durable due-followup state, while region-specific drilldown stays on runtime ownership/control-plane reads rather than forbidden tenant/region metric labels.
- that same ownership-status read now also exposes `remoteFollowupDrainLagMs` directly, so operators no longer need to reconstruct backlog age by hand from `lastCommittedTickId` and `oldestDueRemoteFollowupTickId`.
- remote command control-plane reads now also expose the joined current followup state plus latest durable result summary on the coordinator entry itself instead of forcing operators to stitch together coordinator, followup, and result-list reads by hand just to understand one cross-region command.
- those same remote control-plane reads now also carry linked gameplay-command script/plugin/publication provenance instead of forcing operators to hand-join coordinator/followup/result rows back to `gameplay_command` just to see which script-patch publication produced one remote command leg.
- the durable remote substrate now also persists the admitted gameplay routing bundle (`playableStateScope`, `worldSlug`, `realmSlug`, `pointerVersion`) on coordinator, followup, and result rows themselves so cross-region runtime truth stays self-describing even when operators are looking at remote legs instead of the original local command ledger.
- that same durable remote substrate now also persists command provenance (`scriptPatchVersion`, `pluginId`, `pluginVersionId`) on coordinator, followup, and result rows themselves instead of reconstructing it only through later joins back to `gameplay_command`.
- the remote substrate now also persists direct script dispatch identity (`commandId`, `automationDispatchId`, `automationWorkItemId`, `scriptId`) on followup/result rows and direct automation/script identity on coordinator rows, so control-plane remote history stays self-describing even when the original command ledger row is gone or unavailable.
- remote control-plane reads now treat those persisted remote-row fields as canonical instead of depending on later `gameplay_command` joins just to show script/plugin/publication or dispatch identity for one remote leg.
- origin-side remote scheduling now also accepts that same routing/provenance surface directly on `ScheduleRequest`, so coordinator/followup rows can still be persisted with canonical remote-row truth even when the linked `gameplay_command` row is unavailable at schedule time.
- the live target-side payload handlers (`enqueue_automation_command` and `enqueue_gameplay_command`) now also treat persisted remote-row metadata as canonical fallback input instead of requiring duplicate script/dispatch/routing fields inside payload JSON just to rehydrate ordinary target-side command admission.
- target-side gameplay-command ledger rows are now self-describing for remote execution too: remote-admitted commands persist `remoteCoordinatorId` / `remoteFollowupId`, remote-target duplicates are admitted idempotently by durable `remoteFollowupId`, and `GetGameplayCommandStatus` resolves remote state/results from those persisted ids instead of assuming only the origin command row can see cross-region convergence.
- the local Automation -> Game Session durable command path is now fenced to the exact currently owned `regionId` only: automation admission refuses missing region ownership even when an old same-instance ownership row still exists, so stale same-instance handoff truth can no longer flatten back into the wrong local queue through `gameInstanceId` fallback.
- remote coordinator and followup control-plane reads now also expose the linked target-side gameplay command row directly (`targetCommandId`, execution outcome, gameplay result) so operators can inspect target-leg convergence from one remote-runtime surface instead of inferring it only from payload summaries or a second manual command-status lookup.
- remote coordinator reads now also prefer that same durable followup-linked target command for `latestResultCommandId`, so the coordinator summary and target-command fields no longer disagree when payload JSON drifts from the real admitted target command id.
- origin-side pending remote reconciliation no longer terminalizes on target admission alone when a linked target command row exists: successful result-inbox rows that only prove remote enqueue now keep the coordinator `PENDING_REMOTE` until the target command leg reaches a durable terminal outcome, then mirror that target command convergence back onto the origin coordinator/command lifecycle.
- that origin-side success path now also fails closed when the linked target command row is missing entirely for the supported live payload families: a target `APPLIED` inbox row without durable target-command evidence no longer counts as remote success, and the origin coordinator stays pending until it can see real target-command convergence or later times out.
- when the linked target command does terminate unsuccessfully, origin command status no longer collapses that to a generic remote-abandoned code: the mirrored origin gameplay-command failure code/message now reuses the target command's concrete terminal failure taxonomy when it exists.
- origin-addressed remote result reads now also expose the referenced target command's execution/gameplay status when the durable result payload names one, so result inspection no longer stops at the payload summary while coordinator/followup reads show richer target-leg truth.
- those result reads now also prefer the durable `followupId` -> target-command link as authority for both the target-leg command id and target-leg status instead of trusting payload JSON first, keeping the remote row authority pattern consistent even when payload summaries drift.
- remote control-plane reads now also summarize the live remote payload/result contract into structured fields (`payloadKind`, requested command text, admitted command id, error code) so operators no longer need to parse opaque JSON blobs just to understand the currently shipped remote payload families.
- those remote payload/result summaries now also prefer durable row authority instead of reparsing JSON first: follow-up rows persist `payloadKind` plus requested command text, result rows persist `resultErrorCode`, and operator/gameplay status reads only fall back to payload parsing for older rows that predate those first-class columns.
- result rows now also persist the admitted target-side `resultCommandId` as first-class durable truth, so coordinator/result/gameplay-status reads no longer need payload JSON just to recover the target-leg command identity when the linked gameplay row is not yet available.
- gameplay command status now carries that same structured remote result summary (`remoteResultCommandId`, `remoteResultErrorCode`) instead of forcing callers back to payload JSON for the same first remote payload family.
- gameplay command status reads now also preserve remote execution correlation instead of forcing operators to join command and followup tables manually: `GetGameplayCommandStatus` exposes the linked remote coordinator id, followup id, and current remote state whenever a command has entered the cross-region followup path.
- that same gameplay command-status read now also exposes the durable remote followup contract directly from the linked `remote_followup` row: followup status, payload kind, requested command, solo-tick requirement, origin-source tuple, target entity/effect/failure summary, and trigger-script-event identity no longer require a second followup read once the command already points at the remote leg.
- that same command-status surface now also carries the latest remote terminal result outcome/payload/observed-at timestamp when one has been recorded, so command reads can explain current remote state without a second round-trip to result listing APIs.
- gameplay command-status reads now also expose the current owned runtime `gameInstanceId` / `regionId` / `regionEpoch` plus the current owned routing bundle (`playableStateScope`, `worldSlug`, `realmSlug`, `pointerVersion`) and explicit stale-routing signaling for the command's game instance from the durable ownership and admission-pointer authority surfaces themselves, so later operator/runtime comparisons do not have to infer stale-vs-current command scope only by manually joining separate ownership and routing reads.
- Logging & Admin now also exposes the canonical one-row remote coordinator read directly under `GET /remote-command-coordinators/{tenantId}/{coordinatorId}`, so bounded operator tooling can inspect the joined coordinator/followup/result/runtime-comparison surface without direct gRPC access.
- Logging & Admin now also exposes the canonical one-row remote followup and remote result reads directly under `GET /remote-followups/{tenantId}/{followupId}` and `GET /remote-followup-results/{tenantId}/{resultId}`, so exact-id operator diagnosis no longer has to widen back out to gRPC or list-only scans once one durable remote leg is already known.
- Logging & Admin now also exposes the first bounded coordinator list surface under `GET /remote-command-coordinators/{tenantId}`, so operators can discover matching remote coordinator rows by the highest-value current scope and identity filters before widening into the broader remote list family later.
- that same current-runtime comparison surface now extends to the standalone remote control-plane reads too: remote coordinator, followup, and origin-addressed result entries each now expose the current owned origin/target runtime `gameInstanceId` / `regionId` / `regionEpoch` plus the current owned routing bundle (`playableStateScope`, `worldSlug`, `realmSlug`, `pointerVersion`) and explicit stale-routing signaling beside their persisted remote row scope, and the three list APIs now accept that same current-runtime scope as bounded filter input, so stale-vs-current remote runtime drift no longer requires a second manual ownership-status join or a broader client-side scan per leg.
- the standalone remote list surfaces now accept the current owned runtime `gameInstanceId` filters as first-class bounded input too, not just the exposed current runtime region scope, so operators can constrain historical remote legs against the exact currently owned origin/target instance boundary without a second ownership join or a wider client-side pass.
- that same command-status surface now also exposes the linked remote origin/target region scope plus origin deadline policy from the durable coordinator row, so operator/runtime reads do not have to infer the active cross-region execution boundary only from remote ids and later list calls.
- that same command-status surface now also prefers the durable `followupId` -> target-command link for `remoteResultCommandId` instead of trusting payload JSON first, so origin command status and remote result listing agree on the canonical target-leg command identity.
- that same command-status surface now also carries the linked target command's execution/gameplay outcome, so the origin command read can show the current remote leg state without forcing another coordinator or result-list hop after durable followup linkage has already resolved the target command row.
- the standalone remote followup/result list surfaces now also carry the coordinator's origin deadline and late-result policy, so operator reads can inspect the current remote leg plus its timeout/late-result contract without a second coordinator join just to recover those policy fields, and those list reads now support bounded filtering over persisted origin/target scope, script/plugin/dispatch/command provenance, admitted routing-bundle fields, and current live payload/result classifiers instead of forcing one exact coordinator id or one full target-region scan.
- remote-followup fair selection now also uses one persisted `claimTargetAggregate` contract on the durable followup row instead of recomputing fairness from ad hoc fallbacks: schedule time now stamps `entity:<targetEntityId>` or `game-instance:<targetGameInstanceId>` as explicit row authority, the target-region drain uses that same aggregate to ensure only one fair-selected row per real execution aggregate becomes durably owned by a batch, the sealed `REMOTE_FOLLOWUP_QUEUE` manifest carries the same aggregate, and coordinator/followup/result plus gameplay-command status reads now expose and filter that contract directly.
- the linked durable `gameplay_command` ledger now also converges with that same remote lifecycle instead of leaving command truth stranded at the pre-remote staging boundary: scheduling a cross-region follow-up moves the command into explicit `PENDING_REMOTE`, origin-side timeout reconciliation drives `TIMEOUT`, durable result-inbox reconciliation drives terminal `APPLIED` or `NOT_APPLIED`, and late-result reconciliation now preserves `PARTIAL` rather than hiding that history only on the coordinator row.
- target-side durable result writes now also fail closed on scope drift: `recordResult` rejects any origin/target region id or epoch that does not exactly match both the linked coordinator row and the linked followup row, so stale executors cannot smuggle a terminal remote outcome through the durable inbox on the wrong timeline.
- origin-side follow-up scheduling now also treats the durable coordinator/followup identity as immutable on retry: if a repeat `commandId` or target-timeline `effectKey` comes back with a different coordinator id, followup id, or remote scope, Game Session rejects it instead of silently rewriting the existing rows.
- that retry immutability now also covers durable scheduling metadata, not only ids and scope: repeated schedule attempts cannot rewrite due tick, deadlines, payload JSON, routing bundle, or script/plugin/dispatch provenance under the same coordinator/followup identity.
- origin-addressed result inbox rows now use `resultId` as a true idempotency key instead of a mutable overwrite slot: repeating the same `resultId` with different outcome or payload is rejected fail-closed.
- Automation -> Game Session staged-command admission now also fails closed on region-scope drift at the ownership seam: when a work item still names the old `regionId` for a game instance but the current `runtime_region_status` row already owns a different region, Game Session rejects the handoff as `STALE_TIMELINE` / `stale_region_id` before it can flatten that stale region scope into the local durable gameplay-command queue.
- That same region-scope ownership truth now also crosses the service boundary earlier: `GetGameInstanceRuntimeState` exposes the current owned `regionId` / `regionEpoch`, and Automation handoff cancels stale-scope work items as `runtime_region_scope_advanced` before round-tripping them into Game Session only to hit the same fail-closed ownership rejection there.
- remote-followup claim fairness is now tighter at the current boundary: target-side claim selection only durably claims one due followup per concrete `targetEntityId` in a batch, leaving additional due rows for the same entity `SCHEDULED` instead of greedily claiming ambiguous same-entity work.
- remote-followup claim ordering is now explicit durable truth instead of implied from due tick alone: target-side claim selection pages past duplicate-heavy candidate windows to fill a fair batch when more distinct entities exist later in the due set, persists one `claimOrdinal` on each claimed followup row, and exposes that claim-time order through both the sealed `REMOTE_FOLLOWUP_QUEUE` manifest and control-plane followup/coordinator reads.
- remote-followup queue-claim truth now also lives on the durable followup row itself instead of only in transient drain code and sealed manifest JSON: schedule time stamps `queueSourceKind=REMOTE_FOLLOWUP` plus `queueSourceState=TARGET_REGION_SCHEDULED` and due-point metadata, claim time advances that same row to `TARGET_REGION_CLAIMED` with explicit `queueSourceOrdinal`, release moves it back to the scheduled state, and terminal target-side result handling advances it to applied/abandoned queue-source truth so later reads do not have to infer remote queue history from `status` plus batch state alone.
- that remote queue-source ordinal now also preserves one durable source-local ordering key instead of being rewritten to the current batch-local slot number on every claim: target-side drain keeps `claimOrdinal` as the fair-selected batch position, but `queueSourceOrdinal` now reuses the persisted remote-followup row identity across claim/release cycles so the sealed `REMOTE_FOLLOWUP_QUEUE` manifest and control-plane reads retain one comparable ordering fact for the source itself rather than only one ephemeral per-batch index.
- the sealed `REMOTE_FOLLOWUP_QUEUE` manifest now also carries the same first-class command/provenance/routing/payload-summary truth already persisted on remote follow-up rows (`commandId`, dispatch/work item/script/plugin identity, routing bundle, `payloadKind`, and requested command text) instead of leaving replay/inspection to fall back to raw payload JSON for those facts.
- target-side durable remote execution now follows that same row-authority pattern for live drain work: `remote_followup.payloadKind` and `requestedCommand` are execution authority, while `payloadJson` is only optional enrichment for extra fields, so malformed or drifted payload blobs can no longer erase already-persisted admitted command facts at execution time.
- that same execution-mode truth is now first-class too: `remote_followup.requiresSoloTick` is persisted at schedule time, exposed on remote control-plane reads, and used as the durable target-side authority instead of leaving solo-tick admission dependent on reparsing payload JSON during every drain pass.
- target-side remote execution now also rejects malformed `requiresSoloTick` payload values fail-closed instead of coercing non-boolean JSON onto `false`, so older payload blobs cannot silently widen solo-tick admission when the durable row does not already carry explicit truth.
- target-side remote execution now also rejects malformed numeric payload fields fail-closed instead of silently defaulting them away: present non-integral or non-positive numeric values such as `pointerVersion`, due-point fields, and origin-source ordinals now invalidate execution rather than being coerced onto absent/default semantics beside durable row authority.
- target-side remote execution now also rejects malformed textual payload fields fail-closed instead of coercing non-string JSON scalars through `asText()`: present non-textual `kind`, requested-command, routing, provenance, and origin-source fields now invalidate execution rather than being silently stringified beside durable row authority.
- target-side remote follow-up claim now also clears stale release failure metadata before a row re-enters `CLAIMED`, so later drain/execution passes do not inherit an old rollback/abandon reason as if it were still current truth.
- target-side terminal follow-up rows now also mirror the concrete remote result `errorCode` and `message` when the target leg abandons, instead of flattening every non-applied outcome to generic `REMOTE_ABANDONED` on the follow-up row while richer failure truth exists one table over.
- origin-side `gameplay_command` reconciliation now follows that same durable remote-result authority when the target command row is unavailable, preferring the concrete remote result `errorCode` and `message` over collapsing back to generic coordinator-state failure.
- remote result rows now also persist `resultMessage` as first-class durable truth alongside `resultCommandId` and `resultErrorCode`, and command-status / coordinator / result reads now prefer that stored message over reparsing payload JSON for current rows.
- remote result rows now also persist full origin/target game-instance scope as first-class durable truth alongside origin/target region scope, and `ListRemoteFollowupResults` now exposes and filters that same scope instead of making result inbox reads the odd downgraded remote-runtime surface.
- gameplay command-status reads now also carry the remote origin/target game-instance ids from the linked coordinator instead of stopping at remote region scope, so the command ledger view preserves the same cross-region runtime identity surface as the standalone coordinator/followup/result reads.
- the standalone remote list reads now also accept the persisted epoch facts they already store: followup reads can filter by `originRegionEpoch` as well as `targetRegionEpoch`, and result reads can filter by both origin and target region epoch instead of forcing operators to recover that narrower slice client-side after a broader scoped query.
- the durable query path now matches that stronger operator contract too: origin-scope followup reads and result-scope reads have explicit epoch-aware indexes, so the new full-scope filter surface is backed by first-class storage access instead of relying on broader pre-epoch scans.
- the target-side remote result contract now follows the same explicit-authority pattern as schedule-time payload admission: `resultCommandId`, `resultErrorCode`, and `resultMessage` are first-class request authority, `resultPayloadJson` is only optional enrichment, and replay/idempotence no longer depends on resubmitting an identical JSON blob when the explicit durable result tuple already matches.
- remote follow-up rows now also persist first-class origin-source metadata (`originSourceKind`, `originSourceState`, `originSourceOrdinal`, `originSourceDueTickId`, `originSourceDueAtMs`) parsed once at schedule time, target-side execution now prefers those durable row fields over payload JSON/default fallbacks when admitting downstream gameplay work, remote coordinator/followup reads expose the same tuple directly, and the sealed `REMOTE_FOLLOWUP_QUEUE` manifest carries it beside payload/routing/provenance truth.
- target-side remote followup execution now treats the rest of the persisted coordinator/followup contract the same way too: once schedule-time authority has stamped requested command text, solo-tick policy, target entity, routing bundle, direct provenance, and trigger-script-event identity onto durable rows, target-side admission/execution prefers those stored fields and only falls back to payload JSON when the durable row authority is absent, so later payload drift cannot silently rewrite the target-leg contract.
- remote coordinator/followup/result control-plane reads now also expose and filter that first-class remote queue-source contract (`queueSourceKind`, `queueSourceState`, `queueSourceOrdinal`, `queueSourceDueTickId`, `queueSourceDueAtMs`) beside the older origin-source tuple, so operator/runtime reads can inspect or bound the target-region queue claim lifecycle directly instead of inferring it from claimed batch ids and durable followup status only.
- the internal remote-followup scheduling contract now carries that origin-source tuple as explicit request fields too, so new callers can populate durable remote authority without encoding scheduler/source metadata inside payload JSON.
- remote coordinator control-plane reads are now no longer point-lookup only: `ListRemoteCommandCoordinators` provides a bounded tenant-scoped list over persisted origin/target game-instance and region scope, state, linked `followupId`, script/plugin/dispatch/command provenance, and admitted routing-bundle fields so operator diagnostics can pivot into the same durable remote substrate without already knowing one exact coordinator id.
- the durable query path now also matches that fuller operator contract instead of stopping at scope-only pivots: coordinator, followup, and result list reads are all backed by explicit routing/provenance indexes for script-patch/plugin-version plus admitted routing-bundle filters, and followup/result reads also carry first-class payload/result classifier filters (`payloadKind`, `originSourceKind`, `resultErrorCode`) instead of requiring later client-side filtering over already-persisted row authority.
- coordinator reads now also surface linked followup target-leg identity directly (`targetEntityId`, `effectKey`, terminal followup failure code/message), and coordinator list queries can now filter by those same target-leg traits plus live payload/source classifiers instead of forcing operators back down to a second followup-specific scan just to isolate one remote execution family.
- the remaining remote list surfaces now follow that same direct-identity pattern too: followup list queries can pivot by `automationWorkItemId`, `targetEntityId`, `effectKey`, `failureCode`, and `requiresSoloTick`, result list queries can pivot by `automationWorkItemId` and durable `resultCommandId`, and the new identity-aware indexes keep those filters on row authority instead of treating them as expensive afterthoughts.
- that direct-identity filter surface now also includes the linked target command leg itself on coordinator and followup reads: operators can filter by `targetCommandId`, `targetCommandExecutionOutcome`, and `targetCommandGameplayResult` instead of recovering one remote execution family by scanning wider scope/provenance slices first and then inspecting each returned row's linked target command fields client-side.
- origin-addressed result reads now follow that same pattern too: `ListRemoteFollowupResults` can filter by the linked `resultCommandId` and now also by that result command's execution/gameplay outcome, so remote result investigation no longer stops at "which command id was referenced" when operators need one concrete terminal target-command family.
- result inbox reads now also stop being the downgraded remote surface for linked followup truth: `ListRemoteFollowupResults` can filter by the linked followup's `targetEntityId`, `effectKey`, `failureCode`, `payloadKind`, `originSourceKind`, `eventType`, and `scriptEventId`, and each returned result row now carries that same target-leg/payload/trigger-event truth directly from the durable followup instead of forcing a second followup lookup to understand which remote execution family produced one result.
- the widened remote coordinator/followup/result list surfaces now also hydrate that linked coordinator/followup/target-command truth in bounded bulk instead of per-row point lookups, so the richer operator contract does not regress into N+1 repository churn as remote-runtime coverage expands.
- those same widened remote list surfaces now also pivot on the durable policy/state fields they already expose instead of only showing them after the fact: coordinator reads can now filter by late-result policy, mirrored coordinator execution/gameplay outcome, followup status/claim state/solo-tick mode, followup origin-source state, and latest-result outcome/error; followup reads can now filter by claimed tick-batch id, requested command text, origin-source state, and coordinator-backed deadline/late-result policy; result reads can now filter by concrete result message plus the linked followup's solo-tick mode, claimed batch, origin-source state, and late-result policy.
- `trigger_script_event` now follows the same durable-authority pattern as the enqueue payload families: schedule-time contract fields (`eventType`, `eventSchemaVersion`, `scriptEventId`, `triggerMode`, `readSnapshotToken`, `eventPayloadJson`) are validated explicitly, persisted on `remote_followup`, exposed on coordinator/followup control-plane reads, filterable by `eventType` / `scriptEventId`, and preferred during target-side execution instead of reparsing those facts from payload JSON first.
- that same internal scheduling contract now also carries `payloadKind`, requested command text, and `requiresSoloTick` as first-class request fields, and `payloadJson` is now only optional enrichment there: if callers still provide a payload blob it must parse and agree with the explicit payload contract, but schedule-time authority no longer depends on reparsing JSON just to know which live remote payload family was admitted.
- schedule-time remote payload admission is now fail-closed for those live families too: remote scheduling rejects invalid JSON, missing `kind`, unsupported payload kinds, command-less enqueue payloads, and script-event trigger payloads that omit their required event identity, snapshot-token, or event-body fields before any durable coordinator or follow-up rows are written.
- the first bounded target-region script-event remote payload family is now live beside the enqueue handlers: target-side execution can admit `trigger_script_event` follow-ups through Automation scripting using persisted remote-row routing/provenance truth instead of payload-only identity, with the same deterministic replay/idempotence contract as the other current remote payload families.
- Automation scripting handoff now also consumes that remote substrate directly for emitted gameplay commands: emitted commands may carry explicit target runtime scope, local same-scope commands still use `EnqueueAutomationCommandIfAbsent`, cross-scope commands now schedule durable `enqueue_automation_command` follow-ups instead of flattening remote intent into a local-only handoff, and durable `script_handoff_events` plus `ListScriptHandoffEvents` preserve the target runtime scope and remote coordinator/followup ids for that cross-region path, with direct control-plane filtering by remote scope, remote ids, and origin script/plugin/dispatch identity so that later remote-runtime diagnostics do not depend on scanning one mixed handoff stream client-side.
- target-side durable remote execution now writes one deterministic result-inbox identity per followup (`remote-result:<followupId>`) instead of minting a fresh random result id on every replay attempt, so repeated execution can only reassert the same durable terminal fact and must agree with the immutable `resultId` guard.
- identical remote result replays now stop rewriting inbox timing truth: once a durable `resultId` row exists, later same-fact replays only reconverge target followup state and do not overwrite the original `observedAt` terminal timestamp.
- ignored late results no longer corrupt mirrored command failure taxonomy: when origin-side reconciliation keeps a command in its prior timeout/abandoned terminal state, the durable `gameplay_command` failure code now preserves the original terminal cause instead of being rewritten to the coordinator’s later bookkeeping state such as `LATE_RESULT_IGNORED`.
- paused ownership no longer strands origin-side remote result convergence: when normal tick work is skipped because the runtime is paused, Game Session still reconciles durable remote result-inbox rows for the current owned region, but it continues to fail closed by not advancing timeout/tick progression while paused.
- the current ownership-boundary read-model follow-through is now also live across the cross-service runtime projection seam instead of stopping at one narrow handoff guard: `GetGameInstanceRuntimeState` resolves one canonical owned `regionId` / `regionEpoch`, and Automation consumers that validate, persist, or project runtime command scope now use that explicit scope on activation, schedule reconciliation, script-patch pin projection, event ingress, work-item handoff, and operator-facing automation control-plane reads rather than inferring stale-vs-current command scope only from `gameInstanceId`.

##### source-02-18-8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice-1-315: Why This Slice Exists

The current runtime has tokened locks, pending queues, and rollback scripts, but it did not yet have the durable batch/effect ledger the architecture docs assume:

- there was no `tick_batch` durable execution record in the current service schema;
- Redis `pending`/`queue` state was still doing more than the intended coordination role;
- crash recovery could not answer "which batch/effects were durably drained, which were abandoned, and which need replay?" from durable truth alone.

This matters because the intended hosted-runtime model relies on:

- replay-aware execution truth at the current batch-drain boundary;
- bounded live repair burden;
- one durable execution trail per batch/effect attempt rather than reconstructing current stage/drain behavior from logs and Redis residue.

##### source-02-18-8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice-1-315: Discussion Gate

The discussion gate has been cleared. The agreed first pass is:

- one durable batch row per current Redis stage/drain attempt at the existing game-instance queue boundary;
- one durable effect row per queued gameplay command in that batch;
- honest `DRAINED` / `ABANDONED` convergence for the current runtime instead of pretending full downstream gameplay mutation ledgers already exist;
- defer region epoch, fencing, and final effect idempotency to later dedicated slices.

##### source-02-18-8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice-1-315: Suggested Direction

The first implementation adds two durable layers in `game-session-service`:

- `tick_batch`
  - one row per staged/owned tick batch at the current queue/pending boundary
- `tick_effect`
  - one row per queued gameplay command inside that batch

Redis remains:

- lease/lock/queue coordination
- fast staging metadata
- wake-up / backpressure / scheduling mechanics

PostgreSQL becomes:

- durable batch identity
- durable effect identity/outcome
- replay/reconciliation source of truth for the current stage/drain lifecycle

##### source-02-18-8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice-1-315: Scope

- Define one durable tick-batch identity model.
- Define one durable effect/application ledger tied to batch and command identity.
- Define the minimum status vocabulary for batches and effects.
- Define how Redis staging correlates to durable batch/effect rows.
- Define how replay and abandonment read from the ledger instead of guessing from Redis.

##### source-02-18-8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice-1-315: Locked Direction

- timer, retry, and remote-follow-up candidates are target-state first-class work sources for durable batch selection, not downstream special cases outside the sealed batch contract.
- every admitted work source must persist one explicit source kind, source state, and comparable claim-time ordering key in the selected-work manifest instead of relying on timestamps, incidental database order, or replay-time inference.
- durable batch ownership is fail-closed: only fair-selected work becomes durably owned by the batch, and claimed-but-not-selected work must remain explicitly releasable/requeueable rather than half-owned.
- replay and post-drain recovery rebuild from the persisted batch contract; they do not reconstruct cross-source ordering or ownership semantics from transient Redis residue.

##### source-02-18-8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice-1-315: Out of Scope

- Full region-fencing rollout.
- Full command-ingress implementation if not already landed.
- Final downstream gameplay mutation ledgers.
- Migrating every gameplay command onto the path in the same slice.

##### source-02-18-8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice-1-315: Target State

- Every staged gameplay batch has a durable `tickBatchId`.
- Every current queued command inside that batch has a durable `tick_effect` row.
- Redis `pending` replay and batch drains can be correlated back to durable batch/effect rows instead of existing only as transient coordination residue.
- Batch status and effect status converge independently but consistently for the current runtime seam.
- Replay/rollback tools can consult the ledger, not only transient Redis keys.

##### source-02-18-8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice-1-315: Current First Data Model

The first implementation now includes:

- `tick_batch`
  - `tick_batch_id`
  - `tenant_id`
  - `game_instance_id`
  - `batch_source`
  - `status`
  - `requires_solo_tick`
  - `command_count`
  - `expected_effect_count`
  - `selected_work_manifest_digest`
  - `selected_work_manifest_json`
  - timestamps
- `tick_effect`
  - `effect_id`
  - `tick_batch_id`
  - `command_id`
  - `effect_key`
  - `effect_type`
  - `target_aggregate`
  - `status`
  - failure code/message
  - timestamps

Later slices still need to add region ownership, epoch, tick identity, and stronger participant/idempotency semantics.

##### source-02-18-8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice-1-315: Current Status Model

The first pass keeps the vocabulary simple and honest:

- batch states:
  - `STAGED`
  - `DRAINED`
  - `APPLIED`
  - `ABANDONED`
- effect states:
  - `STAGED`
  - `DRAINED`
  - `APPLIED`
  - `REPLAY_NOOP`
  - `REJECTED`
  - `ABANDONED`

The exact vocabulary can still grow later, but the important part in this first pass is:

- durable terminal/non-terminal distinction;
- ability to reconcile current queue/pending behavior from durable state after Redis loss or restart.

##### source-02-18-8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice-1-315: Recommended Implementation Order

1. Land durable command-ingress/status first.
2. Add durable `tick_batch`.
3. Correlate Redis staging with `tick_batch_id`.
4. Add durable `tick_effect`.
5. Update replay/recovery logic to read durable batch/effect state at the current stage/drain seam.
6. Only then grow more advanced fencing or cross-region follow-up behavior.

##### source-02-18-8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice-1-315: Current Remaining Work

- [x] The current gameplay-command selected-work manifest, retry-source distinction, remote-followup queue-source metadata, and comparable `enqueueSeq` contract are now carried through fair-selected timer, retry, and remote-followup claim paths via [`02.18.8.2`](../vertical-slices/02.18.8.2-task-list-fair-selected-work-source-convergence-vertical-slice.md).
- [x] The durable result-inbox pattern, paused-origin convergence, and richer current payload-family reconciliation now stay on durable coordinator/followup/result authority via [`02.18.8.3`](../vertical-slices/02.18.8.3-task-list-remote-result-reconciliation-and-cross-region-payload-follow-through-vertical-slice.md).
- [x] Keep the first operator remote-runtime follow-through on the same durable authority by exposing canonical remote followup list rows through Logging & Admin rather than stopping at the gRPC control-plane via [`02.18.8.6`](../vertical-slices/02.18.8.6-task-list-operator-remote-followup-list-readback-vertical-slice.md).
- [x] Keep the origin-addressed remote result inbox on that same operator path by exposing canonical remote followup result list rows through Logging & Admin rather than leaving result truth gRPC-only via [`02.18.8.7`](../vertical-slices/02.18.8.7-task-list-operator-remote-followup-result-list-readback-vertical-slice.md).
- [x] Keep that same operator remote-runtime path symmetric for exact-id diagnosis by exposing canonical remote followup and remote result point reads through Logging & Admin rather than forcing exact row inspection back onto gRPC or client-side list filtering via [`02.18.8.8`](../vertical-slices/02.18.8.8-task-list-operator-remote-followup-point-readback-pair-vertical-slice.md).
- [x] Carry the newly landed remote followup / coordinator / result substrate from origin-side schedule/result/timeout execution plus the new internal scheduling API, committed-tick timeout reconciliation, first hint/backlog observability, and target-side claim/release truth, including shared batch-failure release behavior and the current fail-closed remote executor boundary, into the current live gameplay producers and richer target-region payload handlers, with the bounded remote-runtime/operator follow-through now represented by [`02.18.8.3`](../vertical-slices/02.18.8.3-task-list-remote-result-reconciliation-and-cross-region-payload-follow-through-vertical-slice.md) and [`02.18.8.4`](../vertical-slices/02.18.8.4-task-list-operator-remote-command-coordinator-readback-vertical-slice.md) through [`02.18.8.8`](../vertical-slices/02.18.8.8-task-list-operator-remote-followup-point-readback-pair-vertical-slice.md).
- [x] Keep the local Automation -> Game Session durable command path fenced to the exact currently owned `regionId` so stale same-instance handoff rows are rejected rather than silently flattened back into the wrong local queue while the broader live remote-followup producer lane is still growing.
- [x] Keep operator/runtime read models honest about the same current ownership boundary: any cross-service read used to validate or project runtime command scope should expose the current owned `regionId` / `regionEpoch` instead of forcing later callers to infer stale-vs-current scope only from `gameInstanceId`.
- [x] Tighten remote-follow-up claim semantics so only the fair-selected work for an entity becomes durably owned by a batch instead of leaving claimed-but-not-selected rows ambiguous.

At the current boundary this parent slice is now closed. The honest remaining tails sit in later owning families rather than inside the first durable batch/effect substrate itself:

- `02.18.9` carries the model into fuller region ownership and fencing.
- `02.18.10` carries the effect ledger into later domain-specific replay/idempotency consumers.
- `10.1` carries remote and scheduler-backed producer growth further as additional scripting/event families become canonical.

##### source-02-18-8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice-1-315: Validation

- [x] Prove each staged batch creates one durable `tickBatchId`.
- [x] Prove Redis `pending` state can be correlated back to durable batch rows.
- [x] Prove each staged gameplay-command batch persists selected-work manifest details and a digest at the current queue boundary.
- [x] Prove replay after crash uses the durable ledger rather than only transient keys at the current batch-drain seam.
- [x] Prove abandoned/failed batches converge to durable terminal state at the current batch-drain seam.
- [x] Prove the current batch-drain seam rejects stale ownership before drain/commit.
- [x] Prove effect rows are stable enough to support later idempotency/fencing work.
- [x] Prove post-drain runtime recovery can resume from durable `DRAINED` effects instead of depending only on Redis `pending`.
<!-- /migration-source -->

### source-02-18-8-1-task-list-timer-origin-and-queue-source-convergence-vertical-slice-1-101

#### 02.18.8.1 Task List: Timer Origin and Queue-Source Convergence Vertical Slice - Audited primary runtime or service owner (source lines 1-101)

##### Preserved Source Text: source-02-18-8-1-task-list-timer-origin-and-queue-source-convergence-vertical-slice-1-101

<!-- migration-source path="design/project-management/vertical-slices/02.18.8.1-task-list-timer-origin-and-queue-source-convergence-vertical-slice.md" lines="1-101" sha256="7811ef59a357097ef1d7a39a6699f12885b38dcab7eecac0055b8d9578d795cb" heading-offset="3" -->
#### source-02-18-8-1-task-list-timer-origin-and-queue-source-convergence-vertical-slice-1-101: 02.18.8.1 Task List: Timer Origin and Queue-Source Convergence Vertical Slice

##### source-02-18-8-1-task-list-timer-origin-and-queue-source-convergence-vertical-slice-1-101: Goal and Status

Goal: finish converging durable tick source metadata so timer-fired, retry-queued, and remote-followup work stays self-describing through stage, drain, replay, requeue, and operator-read paths instead of flattening back into generic gameplay-command queue facts. Status: complete.

##### source-02-18-8-1-task-list-timer-origin-and-queue-source-convergence-vertical-slice-1-101: Completion Notes

- 2026-06-29: Completed. Stage, drain, replay, and requeue paths now preserve or intentionally transition queue/source tuples without flattening; remote follow-up manifest and queue metadata are carried through from durable rows.
- 2026-06-29: Evidence added in
  - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickBatchExecutionService.java`
  - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickServiceImpl.java`
  - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickStagingService.java`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/TickStagingServiceTest.java`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/TickBatchExecutionServiceTest.java`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/TickServiceImplTest.java`

##### source-02-18-8-1-task-list-timer-origin-and-queue-source-convergence-vertical-slice-1-101: Why This Slice Exists

`02.18.8` already established the durable batch/effect ledger and the first comparable source tuple for staged gameplay work. That closed the big substrate gap, but source metadata can still drift when one seam preserves timer or remote provenance while another rewrites it to generic queue-local state:

- selected-work manifests may preserve some first-class source fields while later drain, replay, or requeue paths overwrite them with transient batch-local values;
- timer-origin work can still be flattened back to generic gameplay-command queue identity on some stage/drain paths even though the durable command row already knows it came from `SCHEDULE_TIMER`;
- remote-followup selection, release, and operator reads now persist richer queue/source fields, but equivalent follow-through seams can still lag behind or keep reparsing older payload shapes;
- tests can prove one path, such as fresh staging, while the next path, such as replay repair or drained-batch status projection, still loses the same source tuple.

This slice is for finishing the first-class source contract the current durable tick design already assumes. It is not for reopening the broader tick architecture.

##### source-02-18-8-1-task-list-timer-origin-and-queue-source-convergence-vertical-slice-1-101: Scope

- selected-work manifest source tuples for timer-origin, retry, and remote-followup work at the current durable tick boundary;
- queue-source and origin-source preservation across stage, drain, replay, release, requeue, and status/read-model surfaces;
- focused tests for the touched source-preservation seams;
- operator/runtime reads that should prefer first-class durable source fields over payload inference or generic queue fallback.

##### source-02-18-8-1-task-list-timer-origin-and-queue-source-convergence-vertical-slice-1-101: Out of Scope

- redesigning region partitioning, ownership, or the long-term tick runtime architecture;
- unrelated gameplay command durability work;
- feature expansion into brand-new work-source families with no current durable substrate.

##### source-02-18-8-1-task-list-timer-origin-and-queue-source-convergence-vertical-slice-1-101: Locked Direction

- source metadata must remain first-class durable truth once admitted; later paths should not reconstruct or flatten it when durable row authority already exists;
- comparable ordering should use one real source-local fact, not incidental batch slot order or replay-time inference;
- replay and requeue should preserve or intentionally advance source state, not erase source identity;
- tests should prove source-tuple preservation across lifecycle transitions, not only one snapshot in one manifest.

##### source-02-18-8-1-task-list-timer-origin-and-queue-source-convergence-vertical-slice-1-101: Planned Work

###### source-02-18-8-1-task-list-timer-origin-and-queue-source-convergence-vertical-slice-1-101: 1. Timer-Origin Stage/Drain/Replay Parity

- [x] Audit every current timer-origin lifecycle seam that touches `originSource*`, `queueSource*`, or selected-work manifest source fields.
- [x] Keep `SCHEDULE_TIMER` and its comparable ordering/due-point tuple authoritative when work is ordinary timer-origin gameplay command selection rather than a later retry reinterpretation.
- [x] Prove timer-origin parity across fresh stage, batch drain, replay repair, and command-status/read-model surfaces.

###### source-02-18-8-1-task-list-timer-origin-and-queue-source-convergence-vertical-slice-1-101: 2. Retry and Requeue State Progression

- [x] Audit the seams that advance replayed or requeued commands from scheduled or fresh states into `GAMEPLAY_RETRY` queue-source truth.
- [x] Ensure requeue/release paths advance source state deliberately without discarding source identity or comparable ordering.
- [x] Add focused proof that post-requeue status reads still tell operators whether a command is timer-origin work currently living in a retry queue path versus first-attempt queue work.

###### source-02-18-8-1-task-list-timer-origin-and-queue-source-convergence-vertical-slice-1-101: 3. Remote-Followup Queue-Source Follow-Through

- [x] Audit target-side remote-followup selection, release, drain, and result/read surfaces for any remaining flattening back to payload parsing or batch-local slot order when durable row authority already exists.
- [x] Keep remote follow-up queue-source and origin-source fields authoritative on the remote rows and prefer those fields in execution and operator reads.
- [x] Add focused proof that claim/release cycles preserve comparable source facts and do not mint a misleading new source identity on every retry.

###### source-02-18-8-1-task-list-timer-origin-and-queue-source-convergence-vertical-slice-1-101: 4. Focused Proof and Reader Convergence

- [x] Update status/read-model tests to prove the same source tuple visible in the durable rows is also visible in command, batch, followup, and result reads after the touched lifecycle transition.
- [x] Prefer shared helper convergence where multiple tick services are making the same source-selection decision with slightly different local logic.

##### source-02-18-8-1-task-list-timer-origin-and-queue-source-convergence-vertical-slice-1-101: Acceptance Shape

- timer-origin work remains visibly timer-origin across the touched lifecycle transitions unless it intentionally advances into an explicit retry-source state;
- queue-source and origin-source tuples are preserved on durable rows and preferred by readers over payload or slot inference;
- remote-followup claim/release/replay paths keep one coherent source contract instead of rewriting source identity on each pass;
- focused stage/drain/replay/read tests are green for the touched seams.

##### source-02-18-8-1-task-list-timer-origin-and-queue-source-convergence-vertical-slice-1-101: Spark Delegation Notes

- Start from one explicit invariant such as "timer-origin selected work must stay `SCHEDULE_TIMER` through stage and drain unless the command is actually requeued as retry work."
- Enumerate the exact files and helpers that participate in that invariant before editing.
- Stop when one source family is end-to-end consistent and the targeted tests are green; do not widen into unrelated tick architecture.

##### source-02-18-8-1-task-list-timer-origin-and-queue-source-convergence-vertical-slice-1-101: Suggested Starting Surfaces

- `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickStagingService.java`
- `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickBatchExecutionService.java`
- `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickServiceImpl.java`
- `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/TickStagingServiceTest.java`
- `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/TickBatchExecutionServiceTest.java`
- `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/TickServiceImplTest.java`

##### source-02-18-8-1-task-list-timer-origin-and-queue-source-convergence-vertical-slice-1-101: Validation

- `./gradlew spotlessApply`
- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.impl.TickStagingServiceTest' --tests 'net.firedevops.firemud.gamesession.service.impl.TickBatchExecutionServiceTest' --tests 'net.firedevops.firemud.gamesession.service.impl.TickServiceImplTest'`
- `./gradlew :game-session-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-02-18-8-2-task-list-fair-selected-work-source-convergence-vertical-slice-1-88

#### 02.18.8.2 Task List: Fair-Selected Work-Source Convergence Vertical Slice - Audited primary runtime or service owner (source lines 1-88)

##### Preserved Source Text: source-02-18-8-2-task-list-fair-selected-work-source-convergence-vertical-slice-1-88

<!-- migration-source path="design/project-management/vertical-slices/02.18.8.2-task-list-fair-selected-work-source-convergence-vertical-slice.md" lines="1-88" sha256="95d73d2d3d33e7082c0db05e70a1ce218f07ed8702cafcff0d1165c2699446ab" heading-offset="3" -->
#### source-02-18-8-2-task-list-fair-selected-work-source-convergence-vertical-slice-1-88: 02.18.8.2 Task List: Fair-Selected Work-Source Convergence Vertical Slice

##### source-02-18-8-2-task-list-fair-selected-work-source-convergence-vertical-slice-1-88: Goal and Status

Goal: carry the now-live gameplay-command selected-work manifest and queue-source truth into timer, retry, and remote-followup claim paths so all claimed work persists one comparable ordering and source contract instead of falling back to source-specific inference. Status: complete.

##### source-02-18-8-2-task-list-fair-selected-work-source-convergence-vertical-slice-1-88: Why This Slice Exists

`02.18.8` already landed the first durable tick-batch/effect ledger, the gameplay-command selected-work manifest, the first retry-source proof, and explicit remote-followup queue metadata on durable rows. The remaining gap is narrower and mechanical:

- gameplay-command work already has a bounded persisted claim/source contract;
- sibling timer, retry, and remote-followup paths still keep too much fair-selection truth implicit in source-local drain code, timestamps, or payload-specific iteration order;
- later replay, operator reads, and fairness audits should not need one interpretation rule per source family.

This slice is for converging those still-live work sources onto one persisted claim contract, not for redesigning tick ownership or cross-region execution.

##### source-02-18-8-2-task-list-fair-selected-work-source-convergence-vertical-slice-1-88: Scope

- fair-selected timer, retry, and remote-followup candidates at the current Game Session durable tick boundary;
- persisted comparable ordering and claim/source metadata for those work sources;
- sealed selected-work manifest and durable-row convergence for the touched source families;
- focused proof for fair selection, replay visibility, and operator/read-model parity on the touched paths.

##### source-02-18-8-2-task-list-fair-selected-work-source-convergence-vertical-slice-1-88: Out of Scope

- later region partitioning beyond the current ownership boundary;
- broader remote result reconciliation or richer payload-family expansion;
- domain-level effect idempotency work already owned by `02.18.10`.

##### source-02-18-8-2-task-list-fair-selected-work-source-convergence-vertical-slice-1-88: Locked Direction

- every fair-selected work source should persist one comparable ordering fact at claim time instead of relying on timestamps, slot order, or source-local iteration behavior;
- source identity and queue/claim state should live on durable rows and sealed manifests together, not in one place only;
- replay and operator reads should be able to explain why one work item was claimed ahead of another without re-running source-specific scheduler logic.

##### source-02-18-8-2-task-list-fair-selected-work-source-convergence-vertical-slice-1-88: Planned Work

###### source-02-18-8-2-task-list-fair-selected-work-source-convergence-vertical-slice-1-88: 1. Source-Contract Audit

- [x] Enumerate the current timer, retry, and remote-followup claim paths that still derive fair-selection or comparable order implicitly.
- [x] Record the persisted source/claim facts already available on durable rows versus the facts still trapped in source-local code.
- [x] Keep the batch limited to current live source families; do not invent new work kinds.

###### source-02-18-8-2-task-list-fair-selected-work-source-convergence-vertical-slice-1-88: 2. Comparable Ordering and Durable Claim Convergence

- [x] Persist one comparable ordering fact for each touched source family at claim time.
- [x] Carry the same claim/source tuple onto the durable rows and sealed manifest for the touched families.
- [x] Remove touched source-local fallbacks that still reconstruct claim order from timestamps, payload parsing, or incidental iteration order.

###### source-02-18-8-2-task-list-fair-selected-work-source-convergence-vertical-slice-1-88: 3. Focused Proof

- [x] Add or refresh focused proof that timer, retry, and remote-followup claims expose one coherent source/ordering contract across stage, manifest, replay, and read-model surfaces.
- [x] Prove the touched families remain fair-selected and replay-readable without source-specific inference.

###### source-02-18-8-2-task-list-fair-selected-work-source-convergence-vertical-slice-1-88: Completion Evidence

- Stage/proof paths are implemented in:
  - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickStagingService.java`
  - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickBatchExecutionService.java`
- Focused tests include:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/TickStagingServiceTest.java`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/TickBatchExecutionServiceTest.java`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/TickServiceImplTest.java`

##### source-02-18-8-2-task-list-fair-selected-work-source-convergence-vertical-slice-1-88: Acceptance Shape

- touched timer, retry, and remote-followup work persists one comparable claim-ordering contract at the current durable boundary;
- durable rows and sealed manifests agree on the source/claim tuple for the touched families;
- focused proof is green for fair-selection and replay/read visibility on the touched paths.

##### source-02-18-8-2-task-list-fair-selected-work-source-convergence-vertical-slice-1-88: Spark Delegation Notes

- Start from one table of touched source families, current claim helpers, and the persisted fields they already have.
- Keep this slice on fair-selected work-source convergence only.
- Return exact changed files, exact source families converged, and exact validation commands run.

##### source-02-18-8-2-task-list-fair-selected-work-source-convergence-vertical-slice-1-88: Suggested Starting Surfaces

- `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickStagingService.java`
- `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickServiceImpl.java`
- `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/RemoteFollowupRuntimeServiceImpl.java`
- `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/`

##### source-02-18-8-2-task-list-fair-selected-work-source-convergence-vertical-slice-1-88: Validation

- `./gradlew spotlessApply`
- `./gradlew :game-session-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-02-18-8-3-task-list-remote-result-reconciliation-and-cross-region-payload-follow-through-vertical-slice-1-88

#### 02.18.8.3 Task List: Remote Result Reconciliation and Cross-Region Payload Follow-Through Vertical Slice - Audited primary runtime or service owner (source lines 1-88)

##### Preserved Source Text: source-02-18-8-3-task-list-remote-result-reconciliation-and-cross-region-payload-follow-through-vertical-slice-1-88

<!-- migration-source path="design/project-management/vertical-slices/02.18.8.3-task-list-remote-result-reconciliation-and-cross-region-payload-follow-through-vertical-slice.md" lines="1-88" sha256="a9421b077c46af142817c4e5262d1ec55ee7f1a44fb57983dc0f8c80b4ec5a81" heading-offset="3" -->
#### source-02-18-8-3-task-list-remote-result-reconciliation-and-cross-region-payload-follow-through-vertical-slice-1-88: 02.18.8.3 Task List: Remote Result Reconciliation and Cross-Region Payload Follow-Through Vertical Slice

##### source-02-18-8-3-task-list-remote-result-reconciliation-and-cross-region-payload-follow-through-vertical-slice-1-88: Goal and Status

Goal: strengthen late-result reconciliation, paused-origin convergence, and richer cross-region payload-family follow-through so remote followup execution stays on durable row authority end to end instead of drifting back to payload-only or status-only repair paths. Status: complete.

##### source-02-18-8-3-task-list-remote-result-reconciliation-and-cross-region-payload-follow-through-vertical-slice-1-88: Why This Slice Exists

`02.18.8` already landed the first executable remote followup substrate: durable coordinator/followup/result rows, target-side claim/release/execution, origin-side timeout and result reconciliation, and the first live remote payload families. The remaining gap is bounded:

- the remote substrate is real, but later reconciliation and richer payload handling can still drift into special cases;
- origin-side late-result and paused-origin behavior should stay anchored on durable inbox/coordinator/followup truth rather than bookkeeping shortcuts;
- richer remote payload families should extend the same row-authoritative contract rather than reopening payload-first execution rules.

This slice is for hardening the current remote execution contract, not for inventing a separate cross-region architecture.

##### source-02-18-8-3-task-list-remote-result-reconciliation-and-cross-region-payload-follow-through-vertical-slice-1-88: Scope

- origin-side remote result reconciliation, including late-result and paused-origin behavior;
- the current durable coordinator/followup/result contract for richer live remote payload families;
- durable-row-authoritative remote operator/read-model surfaces for the touched paths;
- focused proof for reconciliation and payload-authority behavior.

##### source-02-18-8-3-task-list-remote-result-reconciliation-and-cross-region-payload-follow-through-vertical-slice-1-88: Out of Scope

- broad new remote producers unrelated to the touched payload families;
- final region-partitioned runtime ownership beyond the current `02.18.9` boundary;
- unrelated scripting ingress or operator-read-model work already owned by `10.1` and `10.5`.

##### source-02-18-8-3-task-list-remote-result-reconciliation-and-cross-region-payload-follow-through-vertical-slice-1-88: Locked Direction

- origin-side reconciliation must prefer durable coordinator/followup/result truth over payload parsing or status-only inference;
- paused-origin behavior should keep result convergence live without silently advancing unrelated timeout or ownership progression;
- richer remote payload families should reuse the same explicit durable metadata contract as the current live families instead of reintroducing opaque payload authority.

##### source-02-18-8-3-task-list-remote-result-reconciliation-and-cross-region-payload-follow-through-vertical-slice-1-88: Planned Work

###### source-02-18-8-3-task-list-remote-result-reconciliation-and-cross-region-payload-follow-through-vertical-slice-1-88: 1. Reconciliation Audit

- [x] Enumerate the current late-result, paused-origin, and richer payload-family paths that still depend on payload-first or status-only repair logic.
- [x] Record where durable coordinator/followup/result rows already have the needed authority versus where callers still reconstruct it locally.
- [x] Keep the batch limited to the current live remote runtime boundary.

###### source-02-18-8-3-task-list-remote-result-reconciliation-and-cross-region-payload-follow-through-vertical-slice-1-88: 2. Durable Remote Authority Follow-Through

- [x] Converge touched origin-side reconciliation paths on durable row authority for late-result, paused-origin, and target-leg outcome handling.
- [x] Extend the same durable-authority rule to the next live remote payload families touched by the audit.
- [x] Remove touched payload-only fallbacks that rewrite or reinterpret remote execution truth after scheduling.

###### source-02-18-8-3-task-list-remote-result-reconciliation-and-cross-region-payload-follow-through-vertical-slice-1-88: 3. Focused Proof

- [x] Add or refresh focused proof for late-result reconciliation, paused-origin convergence, and durable-row-authoritative richer payload handling.
- [x] Prove the touched remote paths stay replay-safe and operator-readable without payload-first repair.

###### source-02-18-8-3-task-list-remote-result-reconciliation-and-cross-region-payload-follow-through-vertical-slice-1-88: Completion Evidence

- Implementation points:
  - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/RemoteFollowupRuntimeServiceImpl.java`
  - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/DefaultDurableRemoteFollowupExecutionService.java`
- Focused tests:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/RemoteFollowupRuntimeServiceImplTest.java`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/DefaultDurableRemoteFollowupExecutionServiceTest.java`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/TickServiceImplTest.java`

##### source-02-18-8-3-task-list-remote-result-reconciliation-and-cross-region-payload-follow-through-vertical-slice-1-88: Acceptance Shape

- touched remote result reconciliation paths stay on durable coordinator/followup/result authority end to end;
- paused-origin and late-result behavior remain explicit and fail closed;
- richer touched remote payload families reuse the same durable metadata contract instead of reintroducing payload-first execution truth.

##### source-02-18-8-3-task-list-remote-result-reconciliation-and-cross-region-payload-follow-through-vertical-slice-1-88: Spark Delegation Notes

- Start with one matrix of current remote payload families, reconciliation helpers, and where durable authority already exists.
- Keep the work on remote result reconciliation and richer payload follow-through only.
- Return exact changed files, exact touched payload families, and exact validation commands run.

##### source-02-18-8-3-task-list-remote-result-reconciliation-and-cross-region-payload-follow-through-vertical-slice-1-88: Suggested Starting Surfaces

- `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/RemoteFollowupRuntimeServiceImpl.java`
- `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/DefaultDurableRemoteFollowupExecutionService.java`
- `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickServiceImpl.java`
- `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/`

##### source-02-18-8-3-task-list-remote-result-reconciliation-and-cross-region-payload-follow-through-vertical-slice-1-88: Validation

- `./gradlew spotlessApply`
- `./gradlew :game-session-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-02-18-8-4-task-list-operator-remote-command-coordinator-readback-vertical-slice-1-89

#### 02.18.8.4 Task List: Operator Remote Command Coordinator Readback Vertical Slice - Audited primary runtime or service owner (source lines 1-89)

##### Preserved Source Text: source-02-18-8-4-task-list-operator-remote-command-coordinator-readback-vertical-slice-1-89

<!-- migration-source path="design/project-management/vertical-slices/02.18.8.4-task-list-operator-remote-command-coordinator-readback-vertical-slice.md" lines="1-89" sha256="315869dd733985baa2c5e68df2143d5caa21f1b67dd4d1173f2fde8e910b0c68" heading-offset="3" -->
#### source-02-18-8-4-task-list-operator-remote-command-coordinator-readback-vertical-slice-1-89: 02.18.8.4 Task List: Operator Remote Command Coordinator Readback Vertical Slice

##### source-02-18-8-4-task-list-operator-remote-command-coordinator-readback-vertical-slice-1-89: Goal and Status

Goal: expose the canonical Game Session `GetRemoteCommandCoordinator` query through Logging & Admin so operators can inspect one cross-region coordinator row, its linked follow-up state, current runtime/routing comparison, and publication/provenance detail without dropping to gRPC. Status: complete at the current bounded boundary.

##### source-02-18-8-4-task-list-operator-remote-command-coordinator-readback-vertical-slice-1-89: Why This Slice Exists

`02.18.8` already landed the durable remote-runtime substrate and the first rich Game Session control-plane remote reads. One operator seam still lagged behind:

- Game Session exposed `GetRemoteCommandCoordinator`, but only on the gRPC control-plane surface;
- operator tooling could now inspect gameplay-command status and adjacent runtime state through Logging & Admin, but the bounded coordinator record still required direct gRPC access;
- that left the first remote-runtime diagnostic truth one transport seam short of the operator ingress just as the coordinator entry now carries the joined follow-up/result/runtime comparison surface.

This slice closes that one coordinator readback gap without widening into broader coordinator/followup/result list surfaces yet.

##### source-02-18-8-4-task-list-operator-remote-command-coordinator-readback-vertical-slice-1-89: Scope

- Logging & Admin client, service, controller, DTO, and OpenAPI support for `GetRemoteCommandCoordinator`;
- tenant-qualified operator REST ingress for one `{tenantId, coordinatorId}` coordinator read;
- focused proof for successful readback, tenant guard enforcement, and fail-closed tenant/coordinator mismatch handling.

##### source-02-18-8-4-task-list-operator-remote-command-coordinator-readback-vertical-slice-1-89: Out of Scope

- broader remote coordinator, follow-up, or result list/filter HTTP surfaces;
- changes to Game Session remote-runtime persistence or reconciliation behavior;
- UI/dashboard composition over the returned remote-runtime data.

##### source-02-18-8-4-task-list-operator-remote-command-coordinator-readback-vertical-slice-1-89: Locked Direction

- Logging & Admin must consume the canonical Game Session coordinator read directly rather than rebuilding local remote-runtime joins;
- the operator surface stays bounded to one coordinator id at this slice boundary;
- control-plane tenant or coordinator-id mismatches are contract violations and must fail closed.

##### source-02-18-8-4-task-list-operator-remote-command-coordinator-readback-vertical-slice-1-89: Planned Work

###### source-02-18-8-4-task-list-operator-remote-command-coordinator-readback-vertical-slice-1-89: 1. Operator Read Surface

- [x] Add a Logging & Admin control-plane client method for `GetRemoteCommandCoordinator`.
- [x] Add a tenant-qualified Logging & Admin route for one bounded remote command coordinator read.
- [x] Map the canonical coordinator payload, including linked follow-up/result/publication/runtime comparison fields, onto a dedicated operator DTO.

###### source-02-18-8-4-task-list-operator-remote-command-coordinator-readback-vertical-slice-1-89: 2. Proof and Docs

- [x] Add focused Logging & Admin controller/service proof for successful readback and tenant-guard failure paths.
- [x] Reuse the existing Game Session gRPC proof as the upstream contract evidence for coordinator reads.
- [x] Update `02.18.8`, slice indexes, and Logging & Admin `openapi.yaml` so the operator readback is tracked explicitly.

##### source-02-18-8-4-task-list-operator-remote-command-coordinator-readback-vertical-slice-1-89: Acceptance Shape

- Logging & Admin exposes `GET /remote-command-coordinators/{tenantId}/{coordinatorId}`;
- the route returns the canonical remote coordinator entry, including linked follow-up state, latest durable result summary, publication links, current origin/target runtime comparison, and stale-routing signaling;
- callers without access to `tenantId` are rejected before the control-plane read;
- a control-plane response whose `tenantId` or `coordinatorId` does not match the requested values fails closed instead of being silently trusted.

##### source-02-18-8-4-task-list-operator-remote-command-coordinator-readback-vertical-slice-1-89: Completion Notes

- `GameSessionControlPlaneClient` now exposes `getRemoteCommandCoordinator(long tenantId, String coordinatorId)` for Logging & Admin.
- `RemoteCommandCoordinatorController` now serves `GET /remote-command-coordinators/{tenantId}/{coordinatorId}` and enforces tenant access before delegating.
- `RemoteCommandCoordinatorServiceImpl` now consumes the canonical Game Session response, reuses the shared app-error-to-HTTP mapping pattern, validates returned `tenantId` plus `coordinatorId`, and maps the joined remote-runtime payload onto `RemoteCommandCoordinatorDto`.
- Logging & Admin `openapi.yaml` now documents the remote coordinator route and DTO so the published operator contract matches the landed endpoint.

##### source-02-18-8-4-task-list-operator-remote-command-coordinator-readback-vertical-slice-1-89: Completion Evidence

- Logging & Admin implementation:
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/client/GameSessionControlPlaneClient.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/controller/RemoteCommandCoordinatorController.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/RemoteCommandCoordinatorService.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/impl/RemoteCommandCoordinatorServiceImpl.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/dto/RemoteCommandCoordinatorDto.java`
  - `services/logging-admin-service/src/main/resources/openapi.yaml`
- Focused Logging & Admin proof:
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/RemoteCommandCoordinatorControllerTest.java`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/RemoteCommandCoordinatorServiceImplTest.java`
- Existing Game Session contract proof reused by this operator surface:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcServiceTest.java`

##### source-02-18-8-4-task-list-operator-remote-command-coordinator-readback-vertical-slice-1-89: Validation

- `./gradlew :logging-admin-service:test --tests 'net.firedevops.firemud.loggingadmin.controller.RemoteCommandCoordinatorControllerTest' --tests 'net.firedevops.firemud.loggingadmin.service.impl.RemoteCommandCoordinatorServiceImplTest'`
- `./gradlew spotlessApply`
- `dev-tools/validation/run-locked-gradle.sh :logging-admin-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`

##### source-02-18-8-4-task-list-operator-remote-command-coordinator-readback-vertical-slice-1-89: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-02-18-8-5-task-list-operator-remote-command-coordinator-list-readback-vertical-slice-1-99

#### 02.18.8.5 Task List: Operator Remote Command Coordinator List Readback Vertical Slice - Audited primary runtime or service owner (source lines 1-99)

##### Preserved Source Text: source-02-18-8-5-task-list-operator-remote-command-coordinator-list-readback-vertical-slice-1-99

<!-- migration-source path="design/project-management/vertical-slices/02.18.8.5-task-list-operator-remote-command-coordinator-list-readback-vertical-slice.md" lines="1-99" sha256="28d66d4fa134b5fdf866ea582c03bbae6ab3795555cc92ebfb4787ae6f951f8e" heading-offset="3" -->
#### source-02-18-8-5-task-list-operator-remote-command-coordinator-list-readback-vertical-slice-1-99: 02.18.8.5 Task List: Operator Remote Command Coordinator List Readback Vertical Slice

##### source-02-18-8-5-task-list-operator-remote-command-coordinator-list-readback-vertical-slice-1-99: Goal and Status

Goal: expose a bounded Logging & Admin list surface over the canonical Game Session `ListRemoteCommandCoordinators` query so operators can scan remote coordinator rows by the highest-value current filters without dropping to gRPC. Status: complete at the current bounded boundary.

##### source-02-18-8-5-task-list-operator-remote-command-coordinator-list-readback-vertical-slice-1-99: Why This Slice Exists

`02.18.8.4` landed one bounded coordinator readback by exact id, but the operator surface still lacked even a basic list entry point:

- Game Session already exposed a coordinator list query with much broader filter depth;
- Logging & Admin could read one known coordinator id, but it could not list matching coordinators for the most common scope and identity pivots;
- requiring exact coordinator ids kept the new remote coordinator readback from being discoverable enough for normal operator diagnosis.

This slice adds the first honest list surface without pretending to land every upstream filter in the same batch.

##### source-02-18-8-5-task-list-operator-remote-command-coordinator-list-readback-vertical-slice-1-99: Scope

- Logging & Admin client/service/controller support for coordinator listing;
- one bounded list route under `/remote-command-coordinators/{tenantId}`;
- a deliberately small filter set: `originGameInstanceId`, `originRegionId`, `targetGameInstanceId`, `targetRegionId`, `state`, `followupId`, `commandId`, and `limit`;
- focused proof that the bounded filter set maps onto the canonical Game Session request and that returned rows still fail closed on tenant mismatch.

##### source-02-18-8-5-task-list-operator-remote-command-coordinator-list-readback-vertical-slice-1-99: Out of Scope

- the full upstream remote coordinator filter family;
- remote follow-up or result list/readback HTTP surfaces;
- operator sorting, pagination, or dashboard composition beyond the current bounded list.

##### source-02-18-8-5-task-list-operator-remote-command-coordinator-list-readback-vertical-slice-1-99: Locked Direction

- Logging & Admin must reuse the canonical Game Session list query rather than building a local coordinator projection;
- the first HTTP list surface should stay intentionally narrow and explicit instead of mirroring all upstream filter knobs at once;
- each returned coordinator row must still match the requested tenant or fail closed.

##### source-02-18-8-5-task-list-operator-remote-command-coordinator-list-readback-vertical-slice-1-99: Planned Work

###### source-02-18-8-5-task-list-operator-remote-command-coordinator-list-readback-vertical-slice-1-99: 1. Bounded List Surface

- [x] Add a Logging & Admin control-plane client method for `ListRemoteCommandCoordinators`.
- [x] Add a tenant-qualified Logging & Admin list route with the bounded filter set.
- [x] Reuse `RemoteCommandCoordinatorDto` so list and point reads stay on one operator payload shape.

###### source-02-18-8-5-task-list-operator-remote-command-coordinator-list-readback-vertical-slice-1-99: 2. Proof and Docs

- [x] Add focused controller/service proof for successful bounded listing and request mapping.
- [x] Update Logging & Admin `openapi.yaml` so the published contract includes the list route and supported query params.
- [x] Update `02.18.8`, slice indexes, and progress notes so the bounded list readback is tracked explicitly.

##### source-02-18-8-5-task-list-operator-remote-command-coordinator-list-readback-vertical-slice-1-99: Acceptance Shape

- Logging & Admin exposes `GET /remote-command-coordinators/{tenantId}`;
- callers can filter by the bounded current set:
  - `originGameInstanceId`
  - `originRegionId`
  - `targetGameInstanceId`
  - `targetRegionId`
  - `state`
  - `followupId`
  - `commandId`
  - `limit`
- the route returns the canonical coordinator DTO rows from Game Session;
- callers without access to `tenantId` are rejected before the control-plane read;
- any returned row whose `tenantId` does not match the requested tenant fails closed.

##### source-02-18-8-5-task-list-operator-remote-command-coordinator-list-readback-vertical-slice-1-99: Completion Notes

- `GameSessionControlPlaneClient` now exposes `listRemoteCommandCoordinators(ListRemoteCommandCoordinatorsRequest request)` for Logging & Admin.
- `RemoteCommandCoordinatorController` now also serves `GET /remote-command-coordinators/{tenantId}` with the bounded current filter set.
- `RemoteCommandCoordinatorServiceImpl` now maps the bounded filter request onto the canonical Game Session gRPC request and validates `tenantId` across every returned row before projecting to DTOs.
- Logging & Admin `openapi.yaml` now documents the list route and the supported query params.

##### source-02-18-8-5-task-list-operator-remote-command-coordinator-list-readback-vertical-slice-1-99: Completion Evidence

- Logging & Admin implementation:
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/client/GameSessionControlPlaneClient.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/controller/RemoteCommandCoordinatorController.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/RemoteCommandCoordinatorService.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/impl/RemoteCommandCoordinatorServiceImpl.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/dto/RemoteCommandCoordinatorListRequest.java`
  - `services/logging-admin-service/src/main/resources/openapi.yaml`
- Focused Logging & Admin proof:
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/RemoteCommandCoordinatorControllerTest.java`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/RemoteCommandCoordinatorServiceImplTest.java`
- Existing Game Session contract proof reused by this operator surface:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcServiceTest.java`

##### source-02-18-8-5-task-list-operator-remote-command-coordinator-list-readback-vertical-slice-1-99: Validation

- `./gradlew :logging-admin-service:test --tests 'net.firedevops.firemud.loggingadmin.controller.RemoteCommandCoordinatorControllerTest' --tests 'net.firedevops.firemud.loggingadmin.service.impl.RemoteCommandCoordinatorServiceImplTest'`
- `./gradlew spotlessApply`
- `dev-tools/validation/run-locked-gradle.sh :logging-admin-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`

##### source-02-18-8-5-task-list-operator-remote-command-coordinator-list-readback-vertical-slice-1-99: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-02-18-8-6-task-list-operator-remote-followup-list-readback-vertical-slice-1-94

#### 02.18.8.6 Task List: Operator Remote Followup List Readback Vertical Slice - Audited primary runtime or service owner (source lines 1-94)

##### Preserved Source Text: source-02-18-8-6-task-list-operator-remote-followup-list-readback-vertical-slice-1-94

<!-- migration-source path="design/project-management/vertical-slices/02.18.8.6-task-list-operator-remote-followup-list-readback-vertical-slice.md" lines="1-94" sha256="0828e0c50a53709187bc0afa774bf98771d2e48a89c9a82dc143acfa0c00150b" heading-offset="3" -->
#### source-02-18-8-6-task-list-operator-remote-followup-list-readback-vertical-slice-1-94: 02.18.8.6 Task List: Operator Remote Followup List Readback Vertical Slice

##### source-02-18-8-6-task-list-operator-remote-followup-list-readback-vertical-slice-1-94: Goal and Status

Goal: expose the canonical Game Session `ListRemoteFollowups` query through Logging & Admin so operators can inspect durable remote followup rows, linked target-command outcomes, current runtime/routing comparison, and current source/claim metadata without dropping to gRPC. Status: complete at the current bounded boundary.

##### source-02-18-8-6-task-list-operator-remote-followup-list-readback-vertical-slice-1-94: Why This Slice Exists

`02.18.8.4` and `02.18.8.5` already exposed remote command coordinator point and list reads, but the durable remote followup rows themselves still stopped at the Game Session gRPC surface:

- Game Session already exposed the richer `ListRemoteFollowups` control-plane query, including origin/target scope, routing bundle, source/queue metadata, target-command linkage, deadline policy, and current runtime comparison fields;
- Logging & Admin operators could inspect one coordinator or a bounded coordinator list, but they still could not scan the actual durable followup rows that the remote executor and timeout/result reconciliation use as authority;
- that left the first remote followup row truth one transport seam short of the operator ingress just as the durable remote substrate had become materially richer than the older coordinator-only surface.

This slice closes that one operator readback gap without widening into remote followup result readback in the same batch.

##### source-02-18-8-6-task-list-operator-remote-followup-list-readback-vertical-slice-1-94: Scope

- Logging & Admin client/service/controller/DTO/OpenAPI support for `ListRemoteFollowups`;
- one tenant-qualified list route under `/remote-followups/{tenantId}`;
- current durable filter support for the existing high-value Game Session filter family across scope, routing bundle, provenance, payload, target-command, claim/source, deadline-policy, and current runtime comparison fields;
- focused proof for successful readback, tenant guard enforcement, invalid enum rejection, and fail-closed tenant mismatch handling.

##### source-02-18-8-6-task-list-operator-remote-followup-list-readback-vertical-slice-1-94: Out of Scope

- remote followup result operator readback;
- Game Session remote-runtime persistence, scheduling, or reconciliation changes;
- dashboard composition, pagination, or later UI ergonomics over the returned rows.

##### source-02-18-8-6-task-list-operator-remote-followup-list-readback-vertical-slice-1-94: Locked Direction

- Logging & Admin must reuse the canonical Game Session list query rather than rebuilding local remote followup joins;
- the operator payload should preserve the durable remote followup row truth directly, including linked target-command and current runtime comparison fields, instead of collapsing back to coordinator-only summaries;
- invalid enum filters and returned tenant mismatches must fail closed.

##### source-02-18-8-6-task-list-operator-remote-followup-list-readback-vertical-slice-1-94: Planned Work

###### source-02-18-8-6-task-list-operator-remote-followup-list-readback-vertical-slice-1-94: 1. Operator Read Surface

- [x] Add a Logging & Admin control-plane client method for `ListRemoteFollowups`.
- [x] Add a tenant-qualified Logging & Admin route for remote followup listing.
- [x] Map the canonical remote followup entry, including source/queue/deadline/runtime comparison detail, onto a dedicated operator DTO.

###### source-02-18-8-6-task-list-operator-remote-followup-list-readback-vertical-slice-1-94: 2. Proof and Docs

- [x] Add focused Logging & Admin controller/service proof for successful readback, tenant-guard failure, invalid enum rejection, and tenant mismatch handling.
- [x] Reuse the existing Game Session gRPC proof as the upstream contract evidence for followup reads.
- [x] Update `02.18.8`, slice indexes, progress notes, and Logging & Admin `openapi.yaml` so the operator followup list readback is tracked explicitly.

##### source-02-18-8-6-task-list-operator-remote-followup-list-readback-vertical-slice-1-94: Acceptance Shape

- Logging & Admin exposes `GET /remote-followups/{tenantId}`;
- callers can filter the canonical list by the currently landed Game Session durable filter family for remote followups, including scope/routing/provenance/payload/source/claim/deadline/current-runtime pivots already supported upstream;
- the route returns canonical remote followup rows, including linked target-command outcome, publication links, event payload identity, source/queue tuples, and current origin/target runtime comparison fields;
- callers without access to `tenantId` are rejected before the control-plane read;
- invalid `playableStateScope` input fails closed;
- any returned row whose `tenantId` does not match the requested tenant fails closed.

##### source-02-18-8-6-task-list-operator-remote-followup-list-readback-vertical-slice-1-94: Completion Notes

- `GameSessionControlPlaneClient` now exposes `listRemoteFollowups(ListRemoteFollowupsRequest request)` for Logging & Admin.
- `RemoteFollowupController` now serves `GET /remote-followups/{tenantId}` and enforces tenant access before delegating.
- `RemoteFollowupServiceImpl` now maps the operator filter surface onto the canonical Game Session gRPC request, validates `playableStateScope`, maps the durable followup row onto `RemoteFollowupDto`, and validates `tenantId` across every returned row before projecting it to REST callers.
- Logging & Admin `openapi.yaml` now documents the route, query params, and response DTO so the published operator contract matches the landed endpoint.
- 2026-07-02 validation refresh: `dev-tools/validation/run-locked-gradle.sh :logging-admin-service:check -PfullCheck` and `./gradlew linkCheck lintMarkdown` both passed cleanly on the current branch after the remote followup/result/point readback stack settled.

##### source-02-18-8-6-task-list-operator-remote-followup-list-readback-vertical-slice-1-94: Completion Evidence

- Logging & Admin implementation:
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/client/GameSessionControlPlaneClient.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/controller/RemoteFollowupController.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/RemoteFollowupService.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/impl/RemoteFollowupServiceImpl.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/dto/RemoteFollowupDto.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/dto/RemoteFollowupListRequest.java`
  - `services/logging-admin-service/src/main/resources/openapi.yaml`
- Focused Logging & Admin proof:
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/RemoteFollowupControllerTest.java`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/RemoteFollowupServiceImplTest.java`
- Existing Game Session contract proof reused by this operator surface:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcServiceTest.java`

##### source-02-18-8-6-task-list-operator-remote-followup-list-readback-vertical-slice-1-94: Validation

- `./gradlew :logging-admin-service:test --tests 'net.firedevops.firemud.loggingadmin.controller.RemoteFollowupControllerTest' --tests 'net.firedevops.firemud.loggingadmin.service.impl.RemoteFollowupServiceImplTest'`
- `./gradlew spotlessApply`
- `dev-tools/validation/run-locked-gradle.sh :logging-admin-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`

##### source-02-18-8-6-task-list-operator-remote-followup-list-readback-vertical-slice-1-94: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-02-18-8-7-task-list-operator-remote-followup-result-list-readback-vertical-slice-1-94

#### 02.18.8.7 Task List: Operator Remote Followup Result List Readback Vertical Slice - Audited primary runtime or service owner (source lines 1-94)

##### Preserved Source Text: source-02-18-8-7-task-list-operator-remote-followup-result-list-readback-vertical-slice-1-94

<!-- migration-source path="design/project-management/vertical-slices/02.18.8.7-task-list-operator-remote-followup-result-list-readback-vertical-slice.md" lines="1-94" sha256="51a9d6f37de87edde07ceb2202f5ec45cf9eb258aaefa480b8f2daf0340f9129" heading-offset="3" -->
#### source-02-18-8-7-task-list-operator-remote-followup-result-list-readback-vertical-slice-1-94: 02.18.8.7 Task List: Operator Remote Followup Result List Readback Vertical Slice

##### source-02-18-8-7-task-list-operator-remote-followup-result-list-readback-vertical-slice-1-94: Goal and Status

Goal: expose the canonical Game Session `ListRemoteFollowupResults` query through Logging & Admin so operators can inspect durable origin-addressed remote result rows, linked followup/target-command truth, and current runtime/routing comparison without dropping to gRPC. Status: complete at the current bounded boundary.

##### source-02-18-8-7-task-list-operator-remote-followup-result-list-readback-vertical-slice-1-94: Why This Slice Exists

`02.18.8.6` exposed durable remote followup rows through Logging & Admin, but the origin-addressed result inbox still stopped at the Game Session gRPC surface:

- Game Session already exposed `ListRemoteFollowupResults` with the same durable scope, routing bundle, provenance, target-command, queue/source, and current runtime comparison depth as the richer followup surface;
- Logging & Admin could now inspect coordinators and followups, but the actual terminal remote result rows still required direct gRPC access;
- that left the remote execution family one operator seam short of the canonical origin-addressed result truth just as the result rows had stopped being the downgraded remote surface.

This slice closes that one operator result readback gap without widening into pagination or dashboard composition in the same batch.

##### source-02-18-8-7-task-list-operator-remote-followup-result-list-readback-vertical-slice-1-94: Scope

- Logging & Admin client/service/controller/DTO/OpenAPI support for `ListRemoteFollowupResults`;
- one tenant-qualified list route under `/remote-followup-results/{tenantId}`;
- current durable filter support for the existing Game Session result filter family across origin/target scope, routing bundle, provenance, payload, target-command, source/queue, deadline-policy, and current runtime comparison fields;
- focused proof for successful readback, tenant guard enforcement, invalid enum rejection, and fail-closed tenant mismatch handling.

##### source-02-18-8-7-task-list-operator-remote-followup-result-list-readback-vertical-slice-1-94: Out of Scope

- Game Session remote-runtime persistence, scheduling, or reconciliation changes;
- UI/dashboard composition, pagination, or sorting beyond the current bounded list route;
- inventing a local result projection in Logging & Admin.

##### source-02-18-8-7-task-list-operator-remote-followup-result-list-readback-vertical-slice-1-94: Locked Direction

- Logging & Admin must reuse the canonical Game Session result list query rather than rebuilding local result/followup joins;
- the operator payload should preserve the durable origin-addressed result row truth directly, including linked followup and target-command detail plus current runtime comparison fields;
- invalid enum filters and returned tenant mismatches must fail closed.

##### source-02-18-8-7-task-list-operator-remote-followup-result-list-readback-vertical-slice-1-94: Planned Work

###### source-02-18-8-7-task-list-operator-remote-followup-result-list-readback-vertical-slice-1-94: 1. Operator Read Surface

- [x] Add a Logging & Admin control-plane client method for `ListRemoteFollowupResults`.
- [x] Add a tenant-qualified Logging & Admin route for remote result listing.
- [x] Map the canonical remote result entry, including linked followup/queue/deadline/runtime comparison detail, onto a dedicated operator DTO.

###### source-02-18-8-7-task-list-operator-remote-followup-result-list-readback-vertical-slice-1-94: 2. Proof and Docs

- [x] Add focused Logging & Admin controller/service proof for successful readback, tenant-guard failure, invalid enum rejection, and tenant mismatch handling.
- [x] Reuse the existing Game Session gRPC proof as the upstream contract evidence for result reads.
- [x] Update `02.18.8`, slice indexes, progress notes, and Logging & Admin `openapi.yaml` so the operator remote result readback is tracked explicitly.

##### source-02-18-8-7-task-list-operator-remote-followup-result-list-readback-vertical-slice-1-94: Acceptance Shape

- Logging & Admin exposes `GET /remote-followup-results/{tenantId}`;
- callers can filter the canonical list by the currently landed Game Session durable filter family for remote result rows, including scope/routing/provenance/payload/source/queue/deadline/current-runtime pivots already supported upstream;
- the route returns canonical remote result rows, including linked target-command outcome, publication links, trigger-event identity, failure/late-result policy, and current origin/target runtime comparison fields;
- callers without access to `tenantId` are rejected before the control-plane read;
- invalid `playableStateScope` input fails closed;
- any returned row whose `tenantId` does not match the requested tenant fails closed.

##### source-02-18-8-7-task-list-operator-remote-followup-result-list-readback-vertical-slice-1-94: Completion Notes

- `GameSessionControlPlaneClient` now exposes `listRemoteFollowupResults(ListRemoteFollowupResultsRequest request)` for Logging & Admin.
- `RemoteFollowupResultController` now serves `GET /remote-followup-results/{tenantId}` and enforces tenant access before delegating.
- `RemoteFollowupResultServiceImpl` now maps the operator filter surface onto the canonical Game Session gRPC request, validates `playableStateScope`, maps the durable result row onto `RemoteFollowupResultDto`, and validates `tenantId` across every returned row before projecting it to REST callers.
- Logging & Admin `openapi.yaml` now documents the route, query params, and response DTO so the published operator contract matches the landed endpoint.
- 2026-07-02 validation refresh: `dev-tools/validation/run-locked-gradle.sh :logging-admin-service:check -PfullCheck` and `./gradlew linkCheck lintMarkdown` both passed cleanly on the current branch after the remote followup/result/point readback stack settled.

##### source-02-18-8-7-task-list-operator-remote-followup-result-list-readback-vertical-slice-1-94: Completion Evidence

- Logging & Admin implementation:
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/client/GameSessionControlPlaneClient.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/controller/RemoteFollowupResultController.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/RemoteFollowupResultService.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/impl/RemoteFollowupResultServiceImpl.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/dto/RemoteFollowupResultDto.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/dto/RemoteFollowupResultListRequest.java`
  - `services/logging-admin-service/src/main/resources/openapi.yaml`
- Focused Logging & Admin proof:
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/RemoteFollowupResultControllerTest.java`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/RemoteFollowupResultServiceImplTest.java`
- Existing Game Session contract proof reused by this operator surface:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcServiceTest.java`

##### source-02-18-8-7-task-list-operator-remote-followup-result-list-readback-vertical-slice-1-94: Validation

- `./gradlew :logging-admin-service:test --tests 'net.firedevops.firemud.loggingadmin.controller.RemoteFollowupResultControllerTest' --tests 'net.firedevops.firemud.loggingadmin.service.impl.RemoteFollowupResultServiceImplTest'`
- `./gradlew spotlessApply`
- `dev-tools/validation/run-locked-gradle.sh :logging-admin-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`

##### source-02-18-8-7-task-list-operator-remote-followup-result-list-readback-vertical-slice-1-94: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-02-18-8-8-task-list-operator-remote-followup-point-readback-pair-vertical-slice-1-99

#### 02.18.8.8 Task List: Operator Remote Followup Point Readback Pair Vertical Slice - Audited primary runtime or service owner (source lines 1-99)

##### Preserved Source Text: source-02-18-8-8-task-list-operator-remote-followup-point-readback-pair-vertical-slice-1-99

<!-- migration-source path="design/project-management/vertical-slices/02.18.8.8-task-list-operator-remote-followup-point-readback-pair-vertical-slice.md" lines="1-99" sha256="44236eaf05058ae7e096be2b919d2427b676bc31f30cf40336d31d99fa1de7ad" heading-offset="3" -->
#### source-02-18-8-8-task-list-operator-remote-followup-point-readback-pair-vertical-slice-1-99: 02.18.8.8 Task List: Operator Remote Followup Point Readback Pair Vertical Slice

##### source-02-18-8-8-task-list-operator-remote-followup-point-readback-pair-vertical-slice-1-99: Goal and Status

Goal: expose the canonical Game Session `GetRemoteFollowup` and `GetRemoteFollowupResult` queries through Logging & Admin so operators can inspect one exact durable followup row or one exact durable origin-addressed result row without dropping to gRPC. Status: complete at the current bounded boundary.

##### source-02-18-8-8-task-list-operator-remote-followup-point-readback-pair-vertical-slice-1-99: Why This Slice Exists

`02.18.8.6` and `02.18.8.7` already exposed the remote followup and remote result list surfaces, but one exact-id seam still lagged behind:

- Game Session already exposed durable list reads for remote followups and remote followup results, but Logging & Admin still had no direct exact-id read for either row family;
- operators could list or scan remote rows, but they still had to rely on gRPC or client-side filtering when they already knew one exact `followupId` or `resultId`;
- that kept the operator path asymmetric with the earlier remote command coordinator readback work even though the durable followup and result rows had become first-class remote authority.

This slice closes that one exact-id operator seam without widening into new filters, dashboards, or remote-runtime execution changes.

##### source-02-18-8-8-task-list-operator-remote-followup-point-readback-pair-vertical-slice-1-99: Scope

- Game Session control-plane support for `GetRemoteFollowup` and `GetRemoteFollowupResult`;
- Logging & Admin client/service/controller/OpenAPI support for one tenant-qualified point read per row family;
- focused proof for successful readback, tenant guard enforcement, and fail-closed tenant/id mismatch handling on the Logging & Admin side.

##### source-02-18-8-8-task-list-operator-remote-followup-point-readback-pair-vertical-slice-1-99: Out of Scope

- new remote-runtime persistence, scheduling, reconciliation, or payload-family behavior;
- broader list/filter surface changes beyond the exact-id reads;
- UI/dashboard composition over the returned remote row payloads.

##### source-02-18-8-8-task-list-operator-remote-followup-point-readback-pair-vertical-slice-1-99: Locked Direction

- Logging & Admin must reuse the canonical Game Session point reads directly rather than rebuilding local remote-runtime joins;
- the point reads should preserve the same canonical remote followup and remote result payload shapes already used by the list surfaces;
- returned tenant or row-id mismatches are contract violations and must fail closed.

##### source-02-18-8-8-task-list-operator-remote-followup-point-readback-pair-vertical-slice-1-99: Planned Work

###### source-02-18-8-8-task-list-operator-remote-followup-point-readback-pair-vertical-slice-1-99: 1. Canonical Point Reads

- [x] Add Game Session control-plane RPCs for exact remote followup and remote followup result reads.
- [x] Add Logging & Admin client/service/controller support for tenant-qualified followup and result point reads.
- [x] Reuse the existing operator DTOs so point and list reads stay on the same canonical payload shapes.

###### source-02-18-8-8-task-list-operator-remote-followup-point-readback-pair-vertical-slice-1-99: 2. Proof and Docs

- [x] Add focused Game Session and Logging & Admin proof for successful exact-id readback.
- [x] Add focused Logging & Admin proof for tenant-access enforcement and fail-closed tenant/id mismatch handling.
- [x] Update `02.18.8`, slice indexes, progress notes, and Logging & Admin `openapi.yaml` so the exact-id operator seam is tracked explicitly.

##### source-02-18-8-8-task-list-operator-remote-followup-point-readback-pair-vertical-slice-1-99: Acceptance Shape

- Game Session exposes `GetRemoteFollowup` and `GetRemoteFollowupResult`;
- Logging & Admin exposes `GET /remote-followups/{tenantId}/{followupId}` and `GET /remote-followup-results/{tenantId}/{resultId}`;
- each route returns the canonical durable row payload, including linked target-command and current runtime comparison detail already carried by the upstream entry types;
- callers without access to `tenantId` are rejected before the control-plane read;
- any control-plane response whose `tenantId` or exact row id does not match the requested values fails closed instead of being silently trusted.

##### source-02-18-8-8-task-list-operator-remote-followup-point-readback-pair-vertical-slice-1-99: Completion Notes

- `GameSessionRemoteControlPlaneService` now serves exact-id followup and result reads from the durable repositories and reuses the existing entry mappers so point and list reads stay on one canonical payload shape.
- `GameSessionControlPlaneGrpcService` now exposes `GetRemoteFollowup` and `GetRemoteFollowupResult` with the existing admin-role and app-error handling pattern used by the neighboring remote control-plane reads.
- Logging & Admin now exposes `GET /remote-followups/{tenantId}/{followupId}` and `GET /remote-followup-results/{tenantId}/{resultId}` through the existing remote followup/result services, validates returned `tenantId` plus exact row id, and reuses the list DTO projections.
- Logging & Admin `openapi.yaml` now documents both point-read routes so the published operator contract matches the landed ingress.
- 2026-07-02 validation refresh: `dev-tools/validation/run-locked-gradle.sh :game-session-service:check -PfullCheck`, `dev-tools/validation/run-locked-gradle.sh :logging-admin-service:check -PfullCheck`, and `./gradlew linkCheck lintMarkdown` all passed cleanly on the current branch with the exact-id readback pair in place.

##### source-02-18-8-8-task-list-operator-remote-followup-point-readback-pair-vertical-slice-1-99: Completion Evidence

- Game Session implementation and proof:
  - `protos/game-session/v1/game_session_service.proto`
  - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/GameSessionRemoteControlPlaneService.java`
  - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcService.java`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcServiceTest.java`
- Logging & Admin implementation:
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/client/GameSessionControlPlaneClient.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/controller/RemoteFollowupController.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/controller/RemoteFollowupResultController.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/RemoteFollowupService.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/RemoteFollowupResultService.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/impl/RemoteFollowupServiceImpl.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/impl/RemoteFollowupResultServiceImpl.java`
  - `services/logging-admin-service/src/main/resources/openapi.yaml`
- Focused Logging & Admin proof:
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/RemoteFollowupControllerTest.java`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/RemoteFollowupResultControllerTest.java`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/RemoteFollowupServiceImplTest.java`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/RemoteFollowupResultServiceImplTest.java`

##### source-02-18-8-8-task-list-operator-remote-followup-point-readback-pair-vertical-slice-1-99: Validation

- `./gradlew :logging-admin-service:test --tests 'net.firedevops.firemud.loggingadmin.controller.RemoteFollowupControllerTest' --tests 'net.firedevops.firemud.loggingadmin.controller.RemoteFollowupResultControllerTest' --tests 'net.firedevops.firemud.loggingadmin.service.impl.RemoteFollowupServiceImplTest' --tests 'net.firedevops.firemud.loggingadmin.service.impl.RemoteFollowupResultServiceImplTest' --tests 'net.firedevops.firemud.gamesession.service.impl.GameSessionControlPlaneGrpcServiceTest'`
- `./gradlew spotlessApply`
- `dev-tools/validation/run-locked-gradle.sh :logging-admin-service:check -PfullCheck`
- `dev-tools/validation/run-locked-gradle.sh :game-session-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`

##### source-02-18-8-8-task-list-operator-remote-followup-point-readback-pair-vertical-slice-1-99: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-02-18-9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice-1-160

#### `02.18.9` Region Epoch, Fencing, and Runtime Ownership - Audited primary runtime or service owner (source lines 1-160)

##### Preserved Source Text: source-02-18-9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice-1-160

<!-- migration-source path="design/project-management/vertical-slices/02.18.9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice.md" lines="1-160" sha256="160d63a49d7bac2dc73ad13fafb44e1b278c85840615302ed670c742d2b85f01" heading-offset="3" -->
#### source-02-18-9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice-1-160: `02.18.9` Region Epoch, Fencing, and Runtime Ownership

##### source-02-18-9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice-1-160: Goal and Status

Goal: introduce durable region ownership, epoch, and fencing semantics so stale executors cannot safely continue acting after failover/reset and runtime coordination has one canonical owner-of-record model. Status: implemented at the current game-instance runtime boundary, pending later true region partitioning follow-through.

##### source-02-18-9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice-1-160: Checklist

- [x] Define target-state behavior and scope.
- [x] Discussion pass with user before implementation.
- [x] Implement the slice end-to-end.
- [x] Verify and close any follow-ups.

##### source-02-18-9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice-1-160: Implementation Notes

Current-boundary ownership substrate now live:

- `game-session-service` persists a durable `runtime_region_status` row keyed by the current runtime queue boundary `{tenantId, gameInstanceId}`.
- The first row shape is concrete:
  - `tenantId`
  - `gameInstanceId`
  - explicit current-boundary `regionId`
  - `regionEpoch`
  - `executorFence`
  - current owner service / service-instance identity
  - paused state
  - last committed durable tick-batch id
- The current runtime still uses `gameInstanceId` as the effective queue/ownership boundary, so this first pass binds ownership there instead of pretending real multi-region partitioning already exists; the durable owner row now records that boundary through an explicit `regionId` field (currently derived from `gameInstanceId`) so later true-region follow-through no longer has to mint that field retroactively.
- `TickServiceImpl` now observes durable ownership before processing, and the current owner identity is refreshed from the shared `RuntimeIdentity` bean.
- Game-instance pause/resume operations now bump durable `regionEpoch` and `executorFence` while updating the paused bit, so there is finally one durable fence timeline for admin-driven runtime interruptions.
- Durable `tick_batch` rows now capture the current `regionEpoch` and `executorFence` snapshot at batch creation time.
- Durable `tick_batch` rows now also carry the same explicit `regionId` from the owner row, so batch history and control-plane reads expose one concrete runtime scope field even before true multi-region partitioning ships.
- Tick drain/finalization now rejects stale ownership before committing the current batch outcome:
  - if the durable owner row no longer matches the batch's `regionEpoch` / `executorFence`, the runtime treats the batch as stale, rolls Redis `pending` work back to queue, and converges the durable batch to `ABANDONED` with `STALE_EXECUTOR_FENCE`.
- `runtime_region_status.lastCommittedTickBatchId` is now updated on durable batch drain so the owner row has one bounded pointer to the latest committed batch under that ownership timeline.
- `GameSessionControlPlaneService` now exposes a bounded `GetRuntimeOwnershipStatus` query for the current owner row instead of leaving ownership inspection to ad hoc database reads; the control-plane lookup now accepts explicit `regionId` as the preferred selector while retaining `{tenantId, gameInstanceId}` as the current-boundary fallback, and it now carries first backlog truth (`pendingGameplayCommandCount`, `dueRemoteFollowupCount`, `oldestDueRemoteFollowupTickId`) so operator/runtime reads can see queue pressure from the same durable status surface rather than inferring from logs or private gauges.
- Logging & Admin now exposes that same canonical ownership-status query at `GET /tick-remediation/status/{tenantId}` with exactly one of `gameInstanceId` or `regionId`, so operators can inspect the current durable owner row and backlog/fence state through the same ingress that already owns pause/resume remediation.
- That ownership status read now also returns the explicit current-boundary `regionId`, and Game Session's Automation tick-progress publication reads the same stored field rather than reconstructing region scope ad hoc at publish time.
- Tick ownership observation, stale-fence checks, tick advancement, and fresh-stage batch manifest stamping now preserve that stored `regionId` as the runtime authority instead of rewriting it from `gameInstanceId` on every Tick path; the `gameInstanceId`-derived value remains only as the bootstrap default when a brand-new current-boundary owner row is first created.
- Automation gameplay-command admission now follows that same region-first ownership rule: when the handoff request already carries an admitted `regionId`, Game Session validates against the matching durable owner row before falling back to `{tenantId, gameInstanceId}`, instead of risking stale same-instance rows winning just because they share the current queue boundary.
- Current-boundary ownership helpers now also fail closed when callers already carry both selectors: queue-control ownership reads and automation gameplay-command admission both reject region-scoped owner rows whose stored `gameInstanceId` no longer matches the caller’s requested runtime target, instead of accepting any found `regionId` row and silently flattening same-region drift back into one queue boundary.
- `GetGameInstanceRuntimeState` now follows the same selector rule for runtime-state reads: callers that already carry admitted runtime scope can pass `regionId` as the preferred owner-row selector while still including `gameInstanceId` for identity, and Game Session now fails closed if those two selectors disagree instead of silently trusting the stale same-instance path.
- Automation-side stale-scope checks that already carry admitted runtime scope now use that region-aware runtime-state lookup (`ScriptEventIngressServiceImpl` and `ScriptGameplayCommandHandoffServiceImpl`) instead of flattening their fence checks back to `{tenantId, gameInstanceId}`.
- The same exact-scope rule now carries further into Automation-side plugin/runtime consumers that still project or validate current ownership truth: plugin activation preflight, plugin policy convergence, and scheduler materialization now all treat persisted plugin runtime rows as current only when both `runtimeRegionId` and `runtimeRegionEpoch` match the current Game Session owner scope, instead of letting same-region-id stale-epoch rows keep influencing activation, convergence, or schedule decisions.
- The stale-fence guarantee now also covers the first post-drain effect-application seam:
  - before applying `DRAINED` effects, the executor re-checks the durable owner row against the batch's `regionEpoch` / `executorFence`;
  - if ownership has moved, the executor does not call the durable effect handler;
  - unapplied drained effects are marked `ABANDONED`, their durable commands are moved back to the Redis queue for a fresh fenced batch, and the stale batch is marked `ABANDONED` with `STALE_EXECUTOR_FENCE`.
- The live `executorFence` is currently an opaque generation token rather than the older numeric-order example used in some target-state docs; equality/freshness matching is the honest current contract at this boundary.

Still intentionally future-facing outside this slice:

- move from current game-instance ownership to true region-scoped ownership once the runtime has a real region execution boundary;
- carry the same fence tokens into the target session front-end to lease-owner forwarding contract so connected front-end pods can forward region-owned work without becoming implicit region owners;
- carry the same fencing model into later downstream/domain-specific effect families as they are introduced.

##### source-02-18-9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice-1-160: Why This Slice Exists

The tick architecture already assumes stronger region ownership than the runtime currently enforces:

- one authoritative executor per region;
- region epochs that advance on reset/recovery;
- fencing tokens so stale workers cannot commit after ownership changes.

Before this slice started, that model was still more documented than implemented. Pause/resume and runtime ownership behavior were local or implicit, which was acceptable for the bounded runtime but not yet at the intended SaaS/operator bar.

##### source-02-18-9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice-1-160: Discussion Gate

The discussion gate has been cleared. The agreed first pass is:

- start with the current live queue boundary rather than inventing fake region partitioning;
- land one durable owner-of-record row plus epoch/fence tokens now;
- bind tick batches to that ownership snapshot immediately;
- leave stricter stale-commit rejection and later true region ownership for the unfinished part of this slice.

##### source-02-18-9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice-1-160: Suggested Direction

The first implementation adds one durable `runtime_region_status` record in `game-session-service` with:

- `tenantId`
- `gameInstanceId` as the current ownership scope
- `regionEpoch`
- `executorFence`
- current executor ownership marker
- paused state
- last committed batch reference

Redis remains useful for:

- live locks
- hot-path queue/pending coordination
- scheduling cadence

But durable runtime ownership now lives in PostgreSQL so:

- pause/resume can advance one explicit epoch/fence timeline;
- batches can bind to a durable ownership snapshot;
- operators do not need to infer current ownership only from volatile keys.

##### source-02-18-9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice-1-160: Locked Direction

- ownership state must live at the boundary where execution truth actually lives; later inherently region-owned work sources do not stay flattened forever under game-instance-wide ownership semantics once their runtime boundary becomes region-scoped.
- region/fence metadata carried by later timer, follow-up, or result-delivery work must be persisted as durable ownership facts, not inferred from whichever executor or queue happens to touch the work later.
- stale executors fail closed on ownership mismatch; the runtime does not keep ambiguous dual ownership or best-effort late commit semantics just because the current source family is only partly migrated.

##### source-02-18-9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice-1-160: Scope

- Define one durable current-boundary ownership/status record.
- Define epoch advancement rules for pause/resume and later recovery.
- Define the first executor fence snapshot carried into tick batches.
- Define the minimum operator/runtime status fields for ownership.

##### source-02-18-9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice-1-160: Out of Scope

- Full cluster-scheduler redesign.
- Full stale-executor rejection on every commit path.
- True region-partitioned ownership beyond the current game-instance queue boundary.
- Session front-end to lease-owner internal forwarding implementation beyond documenting that it must consume this ownership/fence model when true region execution lands.
- Full operator UI.

##### source-02-18-9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice-1-160: Target State

- Each active current-boundary runtime queue has one durable ownership/status record.
- Epoch changes are explicit, durable, and monotonic.
- Tick batches capture the epoch/fence they were created under.
- Pause/resume and later reset/recovery tooling advance epochs deliberately rather than leaving local-only ownership state.
- The runtime has one canonical owner-of-record row to build stricter fencing on top of.

##### source-02-18-9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice-1-160: Current First Model

The first pass now includes:

- durable runtime status row per `(tenantId, gameInstanceId)`
- explicit `regionEpoch`
- explicit `executorFence`
- explicit owner service and owner instance id
- explicit paused state
- explicit `lastCommittedTickBatchId`

The key principle is:

- Redis lock presence alone is not authoritative enough for recovery-critical ownership decisions.

##### source-02-18-9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice-1-160: Recommended Implementation Order

1. Land durable command status and tick-batch/effect ledger first.
2. Add durable ownership status.
3. Thread `regionEpoch` and `executorFence` through tick-batch creation.
4. Make pause/resume and later recovery bump epoch durably.
5. Finish the slice by rejecting stale batch finalization/effect-application attempts and exposing a bounded ownership-status surface.
6. Later, move the ownership surface from the current game-instance queue boundary to true runtime-region ownership.

##### source-02-18-9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice-1-160: Validation

- [x] Prove stale executors cannot commit the current batch-drain seam after ownership changes.
- [x] Prove pause/resume advances region epoch durably at the current boundary.
- [x] Prove tick-batch creation captures epoch/fence snapshots.
- [x] Prove region ownership survives process restart and Redis loss at the current owner-of-record level.
- [x] Prove operator/runtime diagnostics can identify current owner and current epoch at the current boundary.
- [x] Prove the same stale-fence guarantees still hold once later effect-application/idempotency work lands.
<!-- /migration-source -->

### source-02-18-9-1-task-list-operator-runtime-ownership-status-readback-vertical-slice-1-88

#### 02.18.9.1 Task List: Operator Runtime Ownership-Status Readback Vertical Slice - Audited primary runtime or service owner (source lines 1-88)

##### Preserved Source Text: source-02-18-9-1-task-list-operator-runtime-ownership-status-readback-vertical-slice-1-88

<!-- migration-source path="design/project-management/vertical-slices/02.18.9.1-task-list-operator-runtime-ownership-status-readback-vertical-slice.md" lines="1-88" sha256="6b0b0b0dde9e8f925c3161367132b684ee22c252369d77f97063f145909dee30" heading-offset="3" -->
#### source-02-18-9-1-task-list-operator-runtime-ownership-status-readback-vertical-slice-1-88: 02.18.9.1 Task List: Operator Runtime Ownership-Status Readback Vertical Slice

##### source-02-18-9-1-task-list-operator-runtime-ownership-status-readback-vertical-slice-1-88: Goal and Status

Goal: expose the canonical Game Session `GetRuntimeOwnershipStatus` query through Logging & Admin so operators can inspect current owner-of-record, fence/epoch state, and backlog pressure without dropping to gRPC or database reads. Status: complete at the current bounded boundary.

##### source-02-18-9-1-task-list-operator-runtime-ownership-status-readback-vertical-slice-1-88: Why This Slice Exists

`02.18.9` already landed the durable runtime owner row and the bounded control-plane ownership-status query. One operator seam still lagged behind:

- Game Session exposed `GetRuntimeOwnershipStatus`, including queue-pressure fields, but Logging & Admin had only pause/resume mutation routes;
- operators could interrupt ticks through REST but could not read the same canonical ownership/fence status through the adjacent operator surface;
- that left one read-model gap between the durable ownership substrate and the operator ingress that already owned remediation actions.

This slice closes that one readback gap without widening into broader operator dashboards or true region-partition redesign.

##### source-02-18-9-1-task-list-operator-runtime-ownership-status-readback-vertical-slice-1-88: Scope

- Logging & Admin client, service, controller, DTO, and OpenAPI support for runtime ownership status;
- tenant-guarded operator REST ingress that accepts exactly one canonical selector: `gameInstanceId` or `regionId`;
- focused proof for selector validation, successful status projection, and fail-closed response mismatch handling.

##### source-02-18-9-1-task-list-operator-runtime-ownership-status-readback-vertical-slice-1-88: Out of Scope

- changes to Game Session ownership/fence semantics or backlog calculation;
- new operator write flows beyond the existing pause/resume remediation routes;
- broader multi-region ownership UI or historical status reporting.

##### source-02-18-9-1-task-list-operator-runtime-ownership-status-readback-vertical-slice-1-88: Locked Direction

- Logging & Admin must consume the canonical `GetRuntimeOwnershipStatus` contract, not build a second ownership projection;
- operator readback must preserve the same selector rules as Game Session: exactly one of `gameInstanceId` or `regionId`;
- response tenant/scope mismatches are contract violations and must fail closed.

##### source-02-18-9-1-task-list-operator-runtime-ownership-status-readback-vertical-slice-1-88: Planned Work

###### source-02-18-9-1-task-list-operator-runtime-ownership-status-readback-vertical-slice-1-88: 1. Operator Read Surface

- [x] Add a Logging & Admin control-plane client method for `GetRuntimeOwnershipStatus`.
- [x] Add a tenant-qualified REST route for current ownership status by one canonical selector.
- [x] Map current owner, fence/epoch, and backlog fields onto a bounded Logging & Admin DTO.

###### source-02-18-9-1-task-list-operator-runtime-ownership-status-readback-vertical-slice-1-88: 2. Proof and Docs

- [x] Add focused controller/service proof for successful runtime ownership readback and tenant/scope guard paths.
- [x] Reuse the existing Game Session gRPC proof as the contract evidence for the upstream ownership-status query.
- [x] Update the `02.18.9` parent, slice indexes, and static OpenAPI contract so operator/runtime ownership readback is tracked explicitly.

##### source-02-18-9-1-task-list-operator-runtime-ownership-status-readback-vertical-slice-1-88: Acceptance Shape

- Logging & Admin exposes `GET /tick-remediation/status/{tenantId}?gameInstanceId=...` and `GET /tick-remediation/status/{tenantId}?regionId=...`;
- callers must provide exactly one selector, matching the canonical Game Session contract;
- the response exposes durable ownership truth directly, including owner identity, `regionEpoch`, `executorFence`, `lastCommittedTickBatchId`, and backlog/drain-lag fields;
- unauthorized callers and mismatched control-plane response payloads fail closed.

##### source-02-18-9-1-task-list-operator-runtime-ownership-status-readback-vertical-slice-1-88: Completion Notes

- `TickRemediationController` now exposes `GET /tick-remediation/status/{tenantId}` with query-param selector validation delegated to the service layer.
- `TickRemediationServiceImpl` now calls `GetRuntimeOwnershipStatus`, reuses the same fail-closed gRPC app-error mapping as pause/resume, validates returned tenant/scope identity, and maps the canonical ownership/backlog fields onto `RuntimeOwnershipStatusDto`.
- Logging & Admin `openapi.yaml` now documents the ownership-status read route and DTO shape alongside the existing tick-remediation write routes.

##### source-02-18-9-1-task-list-operator-runtime-ownership-status-readback-vertical-slice-1-88: Completion Evidence

- Logging & Admin implementation:
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/client/GameSessionControlPlaneClient.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/controller/TickRemediationController.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/TickRemediationService.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/impl/TickRemediationServiceImpl.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/dto/RuntimeOwnershipStatusDto.java`
  - `services/logging-admin-service/src/main/resources/openapi.yaml`
- Focused Logging & Admin proof:
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/TickRemediationControllerTest.java`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/TickRemediationServiceImplTest.java`
- Existing Game Session ownership-status contract proof reused by this operator surface:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcServiceTest.java`

##### source-02-18-9-1-task-list-operator-runtime-ownership-status-readback-vertical-slice-1-88: Validation

- `./gradlew :logging-admin-service:test --tests 'net.firedevops.firemud.loggingadmin.controller.TickRemediationControllerTest' --tests 'net.firedevops.firemud.loggingadmin.service.impl.TickRemediationServiceImplTest'`
- `./gradlew spotlessApply`
- `dev-tools/validation/run-locked-gradle.sh :logging-admin-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`

##### source-02-18-9-1-task-list-operator-runtime-ownership-status-readback-vertical-slice-1-88: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->
