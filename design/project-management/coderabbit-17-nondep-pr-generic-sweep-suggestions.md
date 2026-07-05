# CodeRabbit Recurring Sweep Guide

This document is a standing future-work guide for repeated CodeRabbit-style findings that are worth sweeping deliberately across the repo.

The key split is:

- `Convergence / polish sweeps`: broad standardization work that can usually be applied aggressively inside touched clusters.
- `Safety / contract sweeps`: correctness, routing, schema, or CI hardening that should not be delegated blindly to a cheap worker unless the seam is narrow and validation is explicit.

Use this as a source for future bounded sweep prompts, not as permission to do repo-wide speculative churn without proof.

## How To Use This Guide

### Default application mode

- `Aggressive`: can be applied broadly within the touched package, service, or test cluster for homogeneity/polish.
- `Bounded`: can be applied within the current seam, but should still stay inside the touched package or slice boundary.
- `Needs proof`: do not treat as style cleanup; only apply when the target seam is explicit and focused validation proves no behavior regression.

### Cheap-worker rule

- Cheap workers may apply `Aggressive` items broadly inside one touched cluster.
- Cheap workers may apply `Bounded` items when the repeated pattern is obvious and the change remains local.
- Cheap workers should not self-direct `Needs proof` items as a repo-wide sweep. Those belong in a later explicitly bounded task.

## A. Convergence / Polish Sweeps

These are the best standing sweep targets when the goal is a more homogeneous, reusable, polished codebase.

### A1. Prefer explicit controller request-binding assertions in tests

- Application mode: `Aggressive`
- Why it repeats: controller tests often verify only "service method was called" and miss actual DTO/query/body binding drift.
- Pattern:
  - replace broad matcher-only stubs and verifies with `argThat(...)`, `ArgumentCaptor`, or equivalent explicit field assertions
  - assert high-signal request fields, not just one token field
- Good targets:
  - `*ControllerTest.java`
  - service tests that verify DTO/request passthrough into a collaborator
- Suggested commands:
  - `rg -n "ArgumentMatchers\\.any\\(|org\\.mockito\\.ArgumentCaptor|argThat\\(" services -g '*Test.java'`

### A2. Prefer `@ExtendWith(MockitoExtension.class)` for touched Mockito unit tests

- Application mode: `Aggressive`
- Why it repeats: many older tests still bootstrap Mockito manually and drift in lifecycle/stubbing style.
- Pattern:
  - replace `MockitoAnnotations.openMocks(this)` with `@ExtendWith(MockitoExtension.class)` when the suite is already a plain Mockito/JUnit 5 test
  - do not rewrite suites that intentionally use a different bootstrap pattern
- Suggested commands:
  - `rg -n "openMocks\\(this\\)|MockitoAnnotations\\.openMocks" services -g '*Test.java'`

### A3. Reduce large same-type positional constructor fragility

- Application mode: `Bounded`
- Why it repeats: large record/DTO constructors with many adjacent `String` or numeric fields are easy to mis-order silently.
- Pattern:
  - introduce named helper/factory methods for repeated construction shapes
  - prefer a local helper over inventing a large new builder abstraction everywhere
- Good targets:
  - repeated test fixture construction
  - repeated mapping/conversion seams
- Suggested commands:
  - `rg -n "new [A-Za-z0-9_]+Dto\\(" services design -g '*.java'`

### A4. Tighten mapping tests to cover the full high-signal field set

- Application mode: `Aggressive`
- Why it repeats: mapping tests often assert only the happy-path identity fields and miss failure/error branch drift.
- Pattern:
  - add assertions for failure codes, world/realm identity, payload details, canonical ids, and empty-list branches where those are part of the contract
- Suggested commands:
  - `rg -n "assertThat\\(|assertEquals\\(" services/*/src/test/java -g '*Test.java'`

### A5. Make gateway and routing tests resilient to predicate ordering

- Application mode: `Aggressive`
- Why it repeats: assertions by predicate index become brittle as routes evolve.
- Pattern:
  - identify predicates by name or semantic content
  - avoid positional indexing where order is not the contract
- Suggested commands:
  - `rg -n "getPredicates\\(\\)\\[[0-9]+\\]" services/spring-cloud-gateway -g '*Test.java'`

### A6. Add explicit boundary tests for tenant, scope, and epoch logic

- Application mode: `Bounded`
- Why it repeats: tests often cover success and miss mismatch/boundary semantics.
- Pattern:
  - include positive, negative, and boundary cases where the seam already cares about tenant/scope/epoch identity
  - especially tenant mismatch, empty scope, epoch mismatch, and zero/blank identity fields where relevant
- Suggested commands:
  - `rg -n "scope|tenant|epoch|owner|authority|passthrough" services/*/src/test/java -g '*Test.java'`

### A7. Improve deterministic selection in unordered data paths

- Application mode: `Bounded`
- Why it repeats: `findFirst()` over unordered inputs creates unstable behavior and brittle tests.
- Pattern:
  - sort or rank before choosing a winner
  - avoid ambiguity-driven first-match behavior unless the ambiguity is intentionally surfaced
- Suggested commands:
  - `rg -n "findFirst\\(|findAny\\(|stream\\(\\).*findFirst|Collections\\.sort|Comparator\\.|sort\\(" services -g '*.java'`

### A8. Add fast-path and short-circuit cleanup where repeated no-op cases are obvious

- Application mode: `Bounded`
- Why it repeats: many seams do unnecessary work before discovering there is nothing to do.
- Pattern:
  - skip expensive reads/lookups when the no-op path is already known
  - do not introduce speculative micro-optimizations without a clear repeated pattern
- Suggested commands:
  - `rg -n "isBlank\\(|isEmpty\\(|Optional\\.empty|getActivePluginVersions|Map\\.of\\(\\)" services -g '*.java'`

### A9. Keep canonical docs synchronized with actual current behavior

- Application mode: `Aggressive`
- Why it repeats: completed slice docs and architecture docs often retain stale rollout wording or outdated examples.
- Pattern:
  - teach one canonical current state
  - move checked "remaining work" phrasing to a neutral deferred-follow-up shape
  - keep validation sections aligned with repo requirements
- Suggested commands:
  - `rg -n "Current Remaining Work|Deferred Follow-ups|linkCheck lintMarkdown|public|canonical|scope" design -g '*.md'`

## B. Safety / Contract Sweeps

These were recurring findings too, but they should not be treated as casual style cleanup. Keep them for bounded future sweeps with explicit validation.

### B1. Preserve fail-closed behavior on malformed or partial inputs

- Application mode: `Needs proof`
- Why it repeats: repeated correctness hazards came from fail-open or silent fallback behavior.
- Risk: easy to change product behavior, not just polish implementation.
- Typical seam types:
  - plugin owner resolution
  - authority/routing identity reuse
  - replay payload parsing
  - quota class or enum fallback handling

### B2. Keep parsing and normalization paths strict while preserving diagnostics

- Application mode: `Needs proof`
- Why it repeats: trimming, parsing, and rethrow behavior often drifts.
- Risk: can change accepted inputs or surface area of production errors.
- Typical seam types:
  - `NumberFormatException` rethrow without cause
  - silent normalization of blank or typo-laden values
  - swallowing parse context needed for incident triage

### B3. Keep API and query surface changes in sync with gateway/security routing

- Application mode: `Needs proof`
- Why it repeats: endpoints can function locally while still being unreachable or over-exposed at the edge.
- Risk: can unintentionally expose or break ingress.
- Typical seam types:
  - new `/api/admin/**`
  - new `/api/session/**`
  - public-vs-internal family route changes

### B4. Data migration and schema drift parity

- Application mode: `Needs proof`
- Why it repeats: baseline changes and forward migrations can diverge silently.
- Risk: hosted/upgraded environments can drift from fresh bootstrap assumptions.
- Typical seam types:
  - new columns appearing only in baseline
  - entity changes without forward migration parity

### B5. CI and workflow security hardening

- Application mode: `Needs proof`
- Why it repeats: recurring workflow hygiene findings are high-value but potentially disruptive.
- Risk: easy to break CI or change repo automation behavior if applied too mechanically.
- Typical seam types:
  - unpinned actions
  - unnecessary write credentials
  - over-broad permissions
  - unsafe expression interpolation

### B6. Observability and alerting semantic focus

- Application mode: `Needs proof`
- Why it repeats: alert selectors and metric semantics often drift toward noisy or misleading behavior.
- Risk: can teach the wrong contract or create noisy/failing observability proof.
- Typical seam types:
  - success states in failure alerts
  - unbounded label suggestions
  - doc examples that no longer match metric-cardinality policy

## C. Suggested Sweep Order

If the goal is high homogeneity and reuse:

1. `A1`, `A2`, `A4`, `A5`, `A9`
2. `A3`, `A6`, `A7`, `A8`
3. only then bounded passes from section `B`

If the goal is highest product-risk reduction:

1. `B1`, `B3`, `B4`, `B5`
2. `B2`, `B6`
3. use section `A` to clean up the touched proof and docs around those changes

## D. Commands By Cluster

### Test and mapping convergence

```bash
rg -n "ArgumentMatchers\\.any\\(|org\\.mockito\\.ArgumentCaptor|argThat\\(|openMocks\\(this\\)" services -g '*Test.java'
rg -n "new [A-Za-z0-9_]+Dto\\(" services design -g '*.java'
rg -n "scope|tenant|epoch|owner|authority|passthrough" services/*/src/test/java -g '*Test.java'
```

### Gateway and route-test convergence

```bash
rg -n "getPredicates\\(\\)\\[[0-9]+\\]|Method=GET|/api/admin/|/api/session/" services/spring-cloud-gateway design -g '*.java' -g '*.md'
rg -n "GetMapping\\(|PostMapping\\(|PatchMapping\\(|PutMapping\\(|DeleteMapping\\(" services -g '*.java'
```

### Parsing, selection, and helper convergence

```bash
rg -n "findFirst\\(|findAny\\(|Collections\\.sort|Comparator\\.|sort\\(" services -g '*.java'
rg -n "catch \\(NumberFormatException|parseLong|trim\\(|normalize\\(" services -g '*.java'
rg -n "isBlank\\(|isEmpty\\(|Optional\\.empty|getActivePluginVersions" services -g '*.java'
```

### Workflow, migration, and doc clusters

```bash
rg -n "uses:\\s*[^@]+@v|persist-credentials|permissions:|\\$\\{\\{" .github/workflows -g '*.yml'
rg -n "V[0-9]+__.*\\.sql|baseline|ALTER TABLE" services -g '*.sql'
rg -n "Current Remaining Work|Deferred Follow-ups|linkCheck lintMarkdown|public|canonical|scope" design -g '*.md'
```

## E. Deferred High-Risk Sweep Queue For Later Expansion

These are the recurring dangerous/wider sweep families that should be turned into their own explicit future tasks when there is time to orchestrate them properly.

### E1. Fail-closed input and identity hardening expansion

- Related recurring family:
  - malformed claims
  - partial routing bundles
  - replay identity mismatch
  - blank/non-positive authority ids
- Good future targeting:
  - grow `02.1.7-task-list-auth-session-and-routing-guardrail-follow-through-vertical-slice.md`
  - use one invariant seam per batch, not repo-wide scavenging
- Suggested future shape:
  - choose one canonical reader/helper
  - move all current adopters to it
  - add focused negative-path proof

### E2. Gateway/security contract parity expansion

- Related recurring family:
  - public/admin/session edge drift
  - route exposure mismatch
  - route tests depending on implementation order
- Good future targeting:
  - `02.15.7`
  - `02.15.7.1`
  - later `09.1` routing authority follow-through when relevant
- Suggested future shape:
  - pair controller/API additions with gateway route and route-proof updates in one change

### E3. Schema and forward-migration parity expansion

- Related recurring family:
  - baseline updated without forward migration
  - entity shape drift from hosted upgrade paths
- Good future targeting:
  - dedicated persistence or service-scoped migration sweeps
  - `02.19` descendants when applicable
- Suggested future shape:
  - audit one service at a time
  - verify fresh bootstrap plus forward migration path together

### E4. Workflow and CI hardening expansion

- Related recurring family:
  - action pinning
  - permissions minimization
  - credential persistence
  - expression/shell safety
- Good future targeting:
  - one workflow family at a time
  - separate platform/CI sweep, not mixed into ordinary feature PRs
- Suggested future shape:
  - preview workflows
  - release/publish workflows
  - validation/security workflows

### E5. Observability semantics and alerting policy expansion

- Related recurring family:
  - failure-vs-success alert confusion
  - noisy or policy-breaking metric-label examples
  - docs diverging from actual metric contract
- Good future targeting:
  - scripting observability slices such as `10.3.2`
  - architecture observability docs
  - metrics cardinality proof surfaces
- Suggested future shape:
  - verify runtime metric labels in code first
  - then align alert snippets, architecture docs, and slice docs

## F. Execution Status Template

- [ ] Not started
- [ ] In progress
- [ ] Blocked by environment or merge dependencies
- [ ] Complete for current bounded sweep

When using this guide for a real batch, always record:

- the cluster chosen
- whether it was a convergence sweep or safety sweep
- validation actually run
- any intentionally skipped dangerous adjacent item
