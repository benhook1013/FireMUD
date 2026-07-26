# ADR 0017: Capability-Gated Operational Tracing

## Status

Accepted

## Implementation Status

The decision is accepted; implementation and proof remain partial. Metrics, structured logs, and generic gRPC spans exist, but named workflow spans and attributes, cross-transport propagation, sampler controls, collector tail sampling, and the end-to-end proof for any higher capability level remain incomplete. Environments must therefore claim only independently proved capability, not the full catalog. Acceptance records the target decision, not completion; the obligations below define the remaining proof.

## Decision Record

- Decision date: 2026-07-18
- Primary capability: `PO-4.1` Logging, metrics, tracing, dashboards, and alerting
- Affected capabilities: `PO-4.2`, `GR-1.2`, `AA-2.2`, `SF-1.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `TRACE-01`

## Context

FireMUD's tracing catalog defines valuable semantic spans for gameplay commands, ticks, TCP connections, cross-region work, backups, and recovery. It also describes service-scoped head-sampling escalation and tenant/game-instance/region-scoped collector tail sampling. These capabilities would make multi-service and asynchronous incidents substantially easier to diagnose.

The live repository does not implement that complete contract. It creates generic gRPC server spans, but the named workflow spans and required attributes are not present, cross-service context propagation is not proved, the manual SDK configuration does not consume the documented sampler variables, and the sample collector has no tail-sampling processor. A downstream tail sampler also cannot recover traces already discarded by an upstream head sampler. Runbooks must not turn target queries into operational or release promises before the complete path is proved.

## Decision

FireMUD retains the semantic span catalog as the target vocabulary but gates every operational tracing claim by independently proved capability level and workflow. Metrics and structured logs remain the dependable incident surface, and mitigation must never wait for trace availability.

### Capability Levels

1. **Baseline observability** provides metrics and structured logs. Generic RPC spans, export, and trace/log correlation are best-effort unless separately proved. This level does not promise named workflow traces or controllable incident sampling.
2. **Workflow tracing** may be claimed for a named workflow only after its semantic spans, bounded attributes, incoming and outgoing context propagation, collector ingestion, and supported queries are proved end to end.
3. **Service-scoped incident sampling** may be claimed only after the deployed SDK consumes the declared sampler controls and an increase, observation, and revert drill succeeds.
4. **Tenant/game-instance/region-scoped incident sampling** may be claimed only when the complete pipeline preserves candidate traces, propagates the matching attributes, applies bounded collector tail-sampling policies, supports safe time-limited enable/revert, and proves both increased scoped visibility and return to baseline.

Each environment advertises the highest supported level and the workflows covered at that level. Capability is not inferred from the presence of environment variables, example manifests, health alerts, or externally supplied evidence. Runbooks branch on the advertised and proved capability and use metrics and logs when a required trace capability is absent.

### Sampling and Data Safety

- A collector tail-sampling rule cannot override an earlier head-sampling decision. The end-to-end sampling design must ensure candidate tenant/game-instance/region traces reach the collector before scoped sampling is claimed.
- Sampling changes must be bounded in duration and volume, record operator and incident identity, and have a verified revert path.
- Trace attributes remain bounded and privacy controlled. Raw client addresses, user-provided text, and unbounded payloads are excluded.
- Traces are diagnostic evidence, not durable gameplay, recovery, or authorization authority.

## Consequences

- The eventual semantic tracing design remains intact and can be delivered workflow by workflow.
- Operational documentation becomes honest about what each environment can actually query and control.
- Some incidents continue to rely primarily on metrics and logs until enhanced levels are implemented, making phase-level and cross-service diagnosis slower.
- Environments may temporarily expose different tracing capabilities, so runbooks require explicit capability-aware branches.
- Full workflow and scoped-sampling support still carries instrumentation, propagation, collector, storage, privacy, rollout, and proof overhead, but that overhead is incurred only when the capability is claimed.

## Alternatives Considered

### Require the Full Catalog Everywhere Immediately

This provides one strong operational experience but turns broad instrumentation, propagation, collector policy, privacy controls, storage sizing, and recurring proof into an immediate readiness burden. Current implementation cannot support the claim.

### Permanently Limit Tracing to Generic RPC Spans

This is cheaper but loses tick-phase diagnosis, replay/effect causality, cross-region timelines, and precise incident collection. It is rejected because the semantic catalog has substantial operational value.

### Keep Target Prose Without Explicit Capability Gates

Implementation notes could continue to call the richer behavior future work. That still leaves configuration catalogs and runbooks describing controls and queries that appear actionable but are not wired. Explicit advertised levels are required.

## Implementation and Proof Obligations

- Prove baseline export and trace/log correlation before claiming them for an environment.
- Add and test context extraction and injection across every transport in a claimed workflow.
- Prove each claimed span name, bounded attribute set, parent/child relationship, collector ingestion, and query path.
- Wire sampler configuration before publishing it as supported, and prove baseline, escalation, and revert behavior.
- For tenant/game-instance/region sampling, prove candidate preservation before the collector, tail-policy bounds, safe reload, scoped visibility, volume limits, and cleanup.
- Keep fallback runbook steps executable when tracing, the collector, or Jaeger is unavailable.

## Reversibility and Revisit Triggers

Capability levels can converge as implementation matures without changing span names or incident principles. Revisit the level boundaries if the observability platform supplies centrally managed dynamic sampling or if a different tracing backend changes the proof surface. Do not collapse the levels merely because all current environments happen to reach the same implementation state.

## Required Documentation Alignment

- `design/architecture/system-architecture-tracing.md`
- `design/architecture/infrastructure/environment-and-secrets-catalog.md`
- `design/architecture/system-architecture-tick-incident-runbook.md`
- `design/architecture/system-architecture-observability-incident-runbook.md`
