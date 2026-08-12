# ADR 0059: Causal-Floor Cross-Service Presentation Reads

## Status

Accepted

## Implementation Status

The current LOOK adapter carries floor-free scope markers only. Game Session floor allocation, participant served-through evidence, opaque component versions, and their focused retry/rejection proof remain target-state implementation gaps; marker equality is not evidence of causal ordering.

## Canonical Design

- [Identifier Glossary](../system-architecture-identifier-glossary.md#cross-service-causal-read-fence-identity)
- [System Architecture Overview](../system-architecture-overview.md#canonical-room-runtime-contract)

## Decision Record

- Decision date: 2026-07-19
- Decision key: `ID-02`
- Primary capability: `SF-1.2` shared identity and context contracts
- Affected capabilities: `SF-2.3`, `GR-2.1`, `GR-3.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review with independent validation and exact-snapshot/best-effort alternative analysis
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `ID-02`

## Context

World Management and Entity Management own separate databases and component-version sequences. Requiring their snapshot identifiers to be equal either compares unrelated values or turns a shared scope token into false evidence of temporal equality. Ordinary presentation reads need a trustworthy causal boundary without the availability and retention cost of distributed historical snapshots.

## Decision

Cross-service presentation reads such as `LOOK` use a common causal floor containing at least `(tenantId, gameInstanceId, regionId, roomInstanceId, regionEpoch, committedTickId)`, with `regionId` supplied by Game Session's durable region authority rather than inferred from a World row identifier.

Each participant must:

- match the requested tenant, game instance, `regionId`, room, and region epoch;
- prove that it has applied through at least the requested committed tick; and
- return its own actual component version.

World and Entity component versions are not compared for equality. Bounded skew newer than the causal floor is allowed and remains visible in the composite snapshot identity. The operation remains bounded by its read deadline and feature-specific freshness policy; service-local version numbers do not define a meaningful global numeric skew.

Callers reject or retry mixed scope or epoch, a component below the floor, unavailable version evidence, or expiry of the bounded read/freshness policy using the read-fence error family. Mere component-version inequality is not an error.

Correctness-sensitive mutations use exact owner-specific scope, epoch, location, holder, or aggregate-version preconditions. They do not use the presentation causal floor as proof that a mutation is safe.

A feature requiring all components from one exact historical instant must adopt a separate historical-snapshot contract with coordinated token issuance, retention, pruning, lag, and failover proof.

## Consequences

- Presentation cannot mix data older than its initiating causal point while retaining bounded availability.
- Composite identities honestly expose the component versions that were rendered.
- A composed response may contain newer component states that did not coexist at one exact instant; this is acceptable for ordinary presentation only.
- The current same-token/snapshot-equality seam must evolve to carry an applied-through floor and distinct component versions.

## Alternatives Considered

### Exact Coordinated Historical Snapshot

Rejected for ordinary presentation because it requires cross-service watermarks, historical retention, pruning rules, and slowest-participant availability. It remains the required separate design for features that genuinely need exact read-as-of semantics.

### Best-Effort Independently Fresh Reads

Rejected because it can compose states that precede the initiating action, provides no stable composite identity, and is unsafe to reuse as a mutation precondition.

### Equal World and Entity Snapshot Identifiers

Rejected because independent component versions are not comparable. Making equality meaningful would require the coordinated historical-snapshot design above.

## Implementation and Proof Obligations

The request and response contracts must carry the causal floor, applied-through evidence, and distinct component versions. Proof must cover same-floor reads, bounded newer skew, mixed scope and epoch, below-floor lag, unavailable evidence, deadline/freshness expiry, cache identity, and the prohibition on using presentation fences as mutation preconditions.

## Reversibility and Revisit Triggers

Floor and component-version fields can evolve compatibly. Revisit ordinary presentation only if measured skew harms gameplay; adopt a separate exact-snapshot contract if a concrete feature requires one historical instant.
