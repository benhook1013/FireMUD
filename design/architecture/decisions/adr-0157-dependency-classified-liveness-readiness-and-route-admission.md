# ADR 0157: Dependency-Classified Liveness, Readiness, and Route Admission

## Status

Accepted

## Implementation Status

This decision is not implemented. Dependency readiness classes, strict process-local liveness, bounded probes, and safe route-admission enforcement remain gaps.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `HEALTH-01`
- Decision date: 2026-07-20
- Decision key: `HEALTH-01`
- Primary capability: `PO-4.2` health, readiness, reliability policy, SLOs, and degraded operation
- Affected capabilities: `PO-2.2`, `GR-1.1`, `PO-3.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of process liveness, initialization, dependency-aware readiness, route admission, existing-session safety, dependency cascades, and readiness-probe implementation evidence

## Context

Health signals have different operational meanings. Liveness determines whether a process should be restarted. Startup probes allow bounded initialization to complete. Readiness and route admission determine whether new work can safely reach a running process. Combining those meanings can turn a dependency outage into restart churn, remove healthy features from service because one bounded route is impaired, or admit players to a path that cannot complete its immediate contract.

Dependency-aware readiness is already partially implemented for gameplay paths, but the current boundary is not consistently classified. Some checks aggregate a chain of dependencies into whole-pod readiness even where only a bounded route may be affected. Existing probes also use plausible gameplay identifiers such as `"1"` despite the documented requirement for explicitly reserved readiness-only sentinels. The target state needs to protect admission without constructing a transitive health graph in which every service becomes unavailable whenever any ideal downstream dependency is degraded.

## Decision

### Liveness Is Strictly Process-Local

Liveness reports only whether the local process is alive and capable of making progress. It does not fail because PostgreSQL, Redis, another FireMUD service, an observability backend, or any other remote dependency is unavailable or slow. A remote outage therefore cannot cause Kubernetes to restart an otherwise functioning process.

### Startup Probes Cover Initialization Only

Startup probes protect bounded initialization that may legitimately take longer than steady-state health evaluation. They stop suppressing liveness and readiness treatment when initialization is complete. They do not become a permanent dependency-health check and do not replace steady-state admission decisions.

### Every Dependency Has One Readiness Class

Each service classifies every dependency used by its health or admission logic as one of:

- **admission-critical**: failure makes the whole pod contract or a specifically identified route unsafe for new admission;
- **feature-degradable**: failure disables or degrades only the feature that requires it while unrelated routes remain usable;
- **background/control-plane**: failure affects asynchronous, administrative, or control-plane work and is surfaced operationally without automatically removing unrelated request paths from admission; or
- **startup-only**: the dependency is required to complete initialization but is not a continuing steady-state readiness dependency after startup succeeds.

The classification follows the concrete service and route contract. A dependency is not admission-critical merely because it is normally desirable, appears somewhere downstream, or improves the experience.

### Pod Readiness Represents Whole-Pod Safety

Pod readiness becomes false only when the pod is unsafe to receive all traffic represented by its Kubernetes Service. A dependency failure affecting only one bounded route or feature does not remove the entire pod from unrelated traffic.

When one bounded route has stricter admission requirements, enforce those requirements through a route-specific gate or a distinct Kubernetes Service with its own admission/readiness boundary. Route-specific failure returns the bounded unavailable outcome for that route while the remaining safe surface stays admitted. If a service cannot safely separate those surfaces, whole-pod readiness may represent the combined contract, but that coupling must be explicit rather than accidental.

### Dependency Checks Are Bounded and Stable

Dependency-aware checks use bounded, side-effect-free probes with explicitly aligned deadlines. Probe timeouts, caches, hysteresis, Kubernetes probe cadence, edge admission behavior, and client-visible timeout budgets must be configured as one coherent timing model.

Short-lived dependency noise must not flap admission continuously, and stale cached success must not outlive the safety window for new admission. Health evaluation must remain bounded even when a dependency hangs. These controls do not turn readiness into a background availability promise; they make transitions stable enough for admission routing to act predictably.

### Readiness Primarily Protects New Admission

Readiness and route gates primarily stop new players, sessions, sockets, or requests from entering a path that cannot safely complete its immediate contract. An existing session may continue while admission is closed only when its already-established path remains safe and authoritative operations can still complete correctly.

If continued processing would be unsafe, the owning runtime applies its documented pause, rejection, drain, or disconnect behavior. Readiness alone does not imply that every existing session must be terminated, nor does it justify continuing an unsafe session merely to preserve availability.

### Readiness Does Not Form a Transitive Ideal-Dependency Cascade

Each service evaluates the direct capabilities needed for the admission contract it owns. It does not recursively import every downstream service's complete readiness state or require every dependency to have all optional features healthy. An application-level response may satisfy a side-effect-free canary only when hop-level evidence proves that the required downstream handler actually executed. A rejection produced by the probing service's own validation or authorization path—including local `INVALID_ARGUMENT`, `NOT_FOUND`, or `AUTH_INVALID_CREDENTIALS`—does not prove downstream readiness. A deliberate downstream rejection counts only when the reserved probe contract returns bounded evidence that identifies the downstream hop without authorizing a real operation or side effect.

This rule prevents an optional or route-local failure deep in the graph from making every upstream pod unready. A direct dependency still fails the owning admission boundary when the concrete operation needed by that boundary cannot complete safely within its bounded timeout.

### Readiness Probes Use Reserved Synthetic Identity

Operation-shaped readiness canaries use identifiers explicitly reserved for readiness-only traffic, such as clear string sentinels or dedicated out-of-band numeric ranges. They do not borrow plausible tenant, game-instance, player, session, room, or entity identifiers such as `0` or `1` that could collide with real state.

Readiness requests remain side-effect free. Reserved identity must be recognized and constrained by the owning probe contract; it does not confer gameplay identity, authorization, or access to real tenant data.

## Consequences

- Dependency outages cannot trigger process restart storms through liveness.
- New traffic is removed from paths that cannot safely serve it without unnecessarily disabling unrelated routes.
- Existing sessions can survive temporary admission closure where their continued operation remains safe.
- Services must maintain an explicit dependency classification and timing model rather than adding arbitrary downstream checks to aggregate readiness.
- Route-specific admission may require additional Gateway logic or distinct Kubernetes Services when one process exposes traffic with different safety dependencies.
- Caching and hysteresis reduce readiness flapping but add bounded delay before admission closes or reopens.
- Readiness probes cannot reuse ordinary-looking fixture identifiers and therefore require reserved sentinel support throughout the exercised RPC path.

## Alternatives Considered

### Use Process Liveness as Readiness

This is simple but admits traffic to instances whose immediate route dependencies cannot safely complete the contract. It is rejected.

### Put Every Dependency in Liveness

This would restart healthy processes during remote outages and amplify failures through restart cascades. It is rejected.

### Put Every Direct and Transitive Dependency in Whole-Pod Readiness

This fails closed broadly but turns route-local and optional degradation into platform-wide admission loss. It is rejected in favor of dependency classification and the narrowest safe admission boundary.

### Ignore Dependencies and Rely Only on Request Failures

This avoids probe complexity but knowingly admits new players into paths already known to be unable to complete. Request-level failure handling remains necessary, but it is not a substitute for bounded admission protection.

## Implementation and Proof Obligations

Select and report the required checks and evidence under the shared [Validation and Runtime Proof](../../developer-workflows/validation-and-runtime-proof.md) workflow; record execution results in PR/CI evidence or the owning implementation tracker rather than in this decision record.

Current shared readiness result shapes, transition metrics, local liveness separation, and focused Game Session, Game Logic, Gateway, and TCP Proxy checks provide partial implementation. They do not prove the complete classification and admission model.

Implementation must inventory existing health checks and classify each dependency; separate route-local failures from whole-pod readiness; add route gates or distinct Services where required; align probe deadlines, cache age, hysteresis, Kubernetes thresholds, and client-visible timeouts; and document safe existing-session behavior for each admission-critical failure.

Current gameplay readiness code uses plausible sentinel identifiers such as tenant, session, player, and game-instance `"1"`. These are implementation gaps and must be replaced with explicitly reserved readiness-only identities that cannot collide with real gameplay state. Current startup probes that merely target the liveness endpoint must also prove initialization-only semantics rather than treating process life as evidence that initialization has completed.

Focused proof must independently exercise local process failure, startup delay, every dependency class, brief and sustained dependency failure, stale cached results, hysteresis transitions, whole-pod removal, route-only rejection, safe continuation of existing sessions, unsafe-session handling, recovery and readmission, and a downstream optional-feature failure that does not cascade transitively. Proof must also demonstrate that readiness canaries cannot read or mutate real tenant/game state and that plausible real identifiers are never used as probe sentinels.

## Reversibility and Revisit Triggers

Revisit a dependency classification when a service or route contract changes, when production evidence shows that a supposedly degradable dependency is required for correctness, or when a whole-pod gate causes avoidable blast radius. Revisit timing and hysteresis after measured failure and recovery behavior, not by making liveness dependency-aware.

## Required Documentation Alignment

- [design/architecture/infrastructure/deployment-environments.md](../infrastructure/deployment-environments.md)
- [design/architecture/system-architecture-deploy-preflight-policy.md](../system-architecture-deploy-preflight-policy.md)
- [design/architecture/system-architecture-logging-monitoring.md](../system-architecture-logging-monitoring.md)
- affected service health and admission documentation under [design/architecture/microservices/](../microservices/)
