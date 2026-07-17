# Recurring Code Review Sweeps

This is the standing guide for repeated review patterns found by CodeRabbit, human, or AI review. It identifies reusable sweep families; it is not permission for speculative repo-wide churn. [AGENTS.md](../../AGENTS.md) owns always-on authority and safety; [AI delegation and review](../developer-workflows/ai-delegation-and-review.md) and [validation and runtime proof](../developer-workflows/validation-and-runtime-proof.md) own their conditional procedures. [review-checklists.md](./review-checklists.md) and [testing-focus-areas.md](./testing-focus-areas.md) supply broader completion and regression-risk context.

## 1. Evidence and Execution Rules

- A search hit is a candidate, not a finding. Before widening, record the repeated concrete defect, fragility, inconsistency, or operational cost.
- Declare the package, service, workflow family, or contract seam; name adjacent risks intentionally excluded. Do not mix independent safety seams into a convenience cleanup.
- `Aggressive` means standardize throughout the touched cluster when the pattern is clear. `Bounded` means keep the change in the declared seam and prove its local assumption. `Needs proof` means correctness, external contract, schema, routing, CI, or observability behavior is at stake; never treat it as style cleanup.
- Preserve target-state behavior directly. FireMUD is pre-v1: replace obsolete paths and update their callers, tests, and docs rather than adding compatibility scaffolding unless it is explicitly required.
- Keep delegated write scopes disjoint. A worker may perform `Aggressive` work in its assigned cluster and obvious local `Bounded` work, but must not self-direct a repo-wide `Needs proof` sweep.
- Use [validation and runtime proof](../developer-workflows/validation-and-runtime-proof.md): formatting and affected code checks for code changes; `linkCheck lintMarkdown` for documentation; canonical fresh smoke proof for runtime wiring, startup, auth, persistence, migrations, or packaged behavior. Diagnostic output and partial test runs are not completion evidence.
- Record completed commands, the proof surface inspected, and any excluded danger. For an exhaustive external audit, require a per-item coverage ledger, named source/design documents read, and an incomplete-review gate; an unsupported "no findings" result is not evidence.

## 2. Recurring Sweep Matrix

| Family | Mode and risk | Evidence threshold | Expected proof |
| --- | --- | --- | --- |
| Controller and DTO binding assertions | `Aggressive`; collaborator-only tests miss binding drift. | Repeated controller or passthrough tests use broad matchers without asserting meaningful request fields. | Assert high-signal fields with `argThat(...)` or a captor; run affected tests. |
| Mockito JUnit 5 lifecycle | `Aggressive`; manual lifecycle setup drifts and obscures intent. | Plain JUnit 5 Mockito tests use `openMocks(this)` and have no intentional alternate bootstrap. | Replace with `@ExtendWith(MockitoExtension.class)`; run affected tests. |
| Large same-type constructor calls | `Bounded`; positional DTO/record arguments can silently swap. | Repeated construction shape has adjacent same-type fields or has already produced mapping ambiguity. | Introduce a local named fixture/factory helper; prove mapped values in focused tests. |
| Mapping contract coverage | `Aggressive`; happy-path identity assertions miss contract drift. | Mapping tests omit contract-relevant error, realm/world, payload, canonical-id, or empty-list cases. | Assert the full high-signal field set and relevant success, error, and empty branches. |
| Gateway predicate assertions | `Aggressive`; predicate-index assertions break when route order changes. | Route tests access predicates by numeric position where order is not contractual. | Locate predicates by name or semantic content; run route tests. |
| Tenant, scope, and epoch boundaries | `Bounded`; identity mismatch behavior is under-tested. | A seam enforces tenant, scope, epoch, owner, authority, or passthrough semantics but lacks negative/boundary coverage. | Cover valid, mismatch, empty/blank, zero, and epoch-boundary cases relevant to that contract. |
| Deterministic selection | `Bounded`; unordered `findFirst()` creates unstable behavior. | Source iteration order is undefined or semantically irrelevant and selection ambiguity is not intentionally surfaced. | Establish ordering/ranking before selection and test deterministic winner behavior. |
| No-op fast paths | `Bounded`; unnecessary external or hot-path work creates noise. | A repeated no-op case performs a materially expensive or operationally noisy read, lookup, or call. | Demonstrate the no-op path skips work without changing required output or side effects. |
| Fail-closed input and identity handling | `Needs proof`; fail-open or fallback behavior can grant access or misroute work. | A defined invariant covers malformed claims, partial routing bundles, replay identity mismatch, or blank/non-positive authority IDs. | Change one invariant seam at a time; prove focused negative paths and all current adopters fail closed. |
| Strict parsing and diagnostic preservation | `Needs proof`; normalization or exception changes alter accepted input and incident visibility. | Parsing/normalization silently accepts blanks or typos, swallows context, or rethrows parsing failures without their cause. | Cover malformed input and diagnostic context; verify intended accepted-input contract remains strict. |
| API, gateway, and security parity | `Needs proof`; an endpoint can work locally yet be unreachable or over-exposed at ingress. | API/query additions or public/internal/admin/session route-family changes cross an edge boundary. | Prove controller/API behavior, gateway route exposure, and security classification together. |
| Baseline and forward migration parity | `Needs proof`; fresh bootstrap and upgraded installations can diverge. | Schema/entity changes appear only in a baseline or lack matching forward migration. | Audit one service at a time; prove fresh bootstrap and forward upgrade paths together. |
| CI and workflow hardening | `Needs proof`; mechanical security edits can break automation. | Workflow findings involve unpinned actions, write credentials, broad permissions, or expression/shell interpolation. | Review one workflow family at a time; validate syntax, credential/permission intent, and required CI behavior. |
| Observability and alert semantics | `Needs proof`; alerts or docs can encode misleading failure and cardinality contracts. | Failure alerts include success states, label suggestions are unbounded, or examples diverge from runtime metric labels. | Verify runtime labels first, then align alert selectors, metric-cardinality policy, docs, and focused observability proof. |

## 3. Autonomous Orchestration Recipe

1. The orchestrator selects one cluster or invariant seam, reads its authoritative architecture/service docs, and records the candidate pattern, boundary, exclusions, mode, and expected proof.
2. The investigator inventories occurrences with the appendix commands and returns a coverage ledger that distinguishes confirmed instances from search noise.
3. The implementer receives a disjoint scope and explicit success conditions. The orchestrator retains design decisions, integration, and all `Needs proof` ownership. Select and supervise roles according to [AI delegation and review](../developer-workflows/ai-delegation-and-review.md).
4. The verifier checks the exact public contract, implementation, and focused proof. For runtime, schema, routing, authentication, or packaging changes, it requires the relevant canonical fresh proof rather than stale local state.
5. The orchestrator records completed validation and exclusions, then either converges every in-scope adopter or leaves a narrowly described follow-up; do not claim a broad sweep from partial coverage.

## 4. Search Commands Appendix

### Test and mapping convergence

```bash
rg -n "ArgumentMatchers\\.any\\(|org\\.mockito\\.ArgumentCaptor|argThat\\(|openMocks\\(this\\)" services -g '*Test.java'
rg -n "new [A-Za-z0-9_]+Dto\\(" services design -g '*.java'
rg -n "scope|tenant|epoch|owner|authority|passthrough" services/*/src/test/java -g '*Test.java'
rg -n "assertThat\\(|assertEquals\\(" services/*/src/test/java -g '*Test.java'
```

### Gateway and API contracts

```bash
rg -n "getPredicates\\(\\)\\[[0-9]+\\]|Method=GET|/api/admin/|/api/session/" services/spring-cloud-gateway design -g '*.java' -g '*.md'
rg -n "GetMapping\\(|PostMapping\\(|PatchMapping\\(|PutMapping\\(|DeleteMapping\\(" services -g '*.java'
```

### Selection, parsing, and no-op paths

```bash
rg -n "findFirst\\(|findAny\\(|Collections\\.sort|Comparator\\.|sort\\(" services -g '*.java'
rg -n "catch \\(NumberFormatException|parseLong|trim\\(|normalize\\(" services -g '*.java'
rg -n "isBlank\\(|isEmpty\\(|Optional\\.empty|getActivePluginVersions|Map\\.of\\(\\)" services -g '*.java'
```

### Migrations, workflows, and observability

```bash
rg -n "uses:\\s*[^@]+@v|persist-credentials|permissions:|\\$\\{\\{" .github/workflows -g '*.yml'
rg -n "V[0-9]+__.*\\.sql|baseline|ALTER TABLE" services -g '*.sql'
rg -n "grpc\\.app_error|alert|metric|label|cardinality" services design -g '*.java' -g '*.md' -g '*.yml'
```
