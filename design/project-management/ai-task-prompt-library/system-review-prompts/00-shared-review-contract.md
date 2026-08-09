# Shared Review Contract

Apply this contract to every system-review and engineering-maintenance prompt in this library.

## Authority

Preserve this direction of authority:

1. Product documents define intended outcomes and observable behavior.
2. Architecture documents define target-state technical contracts.
3. Accepted ADRs explain consequential decisions; pending ADRs do not define accepted behavior.
4. Implementation trackers record current implementation and proof status.
5. Code, schemas, configuration, tests, and validation tooling demonstrate the implemented boundary.
6. Environment- and event-bound evidence demonstrates a particular deployment, release, traffic-opening, or recovery event.

A lower layer may reveal drift or a missing decision, but it does not silently redefine a higher layer. When two target states compete or a consequential decision lacks accepted authority, report the ambiguity for human resolution.

## Default Permissions

The default review is static and read-only.

Unless the invoking task gives exact additional permission, do not:

- edit repository or temporary files;
- update AI observations, trackers, or working documents;
- run tests, builds, formatters, linters, Gradle, Docker, deployments, or live checks;
- install dependencies or invoke external providers;
- inspect or change pull-request, review, CI, or release state; or
- commit, push, open, merge, close, or retarget a pull request.

An invocation may provide an exact write scope, command allowlist, environment, and required validation. Permission for one action does not imply permission for a substitute or wider action.

## Review Boundary

Read the prompt's named canonical starting sources and the current repository instructions. Follow a referenced document when it owns a contract needed to judge the stated scope. Record material sources added to the prompt boundary.

Do not infer full coverage from a directory scan, search result, tracker statement, broad test result, or a small code sample. Distinguish:

- target design;
- current implementation;
- focused executable proof; and
- live or release-specific evidence.

Explicitly identify unavailable evidence and target-only behavior. Static repository review cannot prove live controller, cluster, certificate, backup, restore, monitoring-provider, payment-provider, or traffic behavior.

## Coverage

For an exhaustive review, maintain a response-local coverage table containing:

- the capability, journey, service, invariant, workflow, route, persistence boundary, environment, or evidence item reviewed;
- its canonical owner;
- sources inspected;
- negative or failure paths checked;
- coverage state: `covered`, `excluded` with rationale, or `blocked`; and
- related finding references.

A focused commissioned review may use a smaller table matching its declared boundary. Working coverage tables remain ephemeral unless the human explicitly requests a retained artifact.

## Findings

Report findings before optional commentary. Do not spend output praising or summarizing material that is already clear.

Each finding includes:

- severity and practical impact;
- finding class: product gap, target-design conflict, implementation drift, proof gap, operational gap, security/privacy risk, user-experience gap, or release-evidence gap;
- canonical owner and supporting file references;
- affected capabilities, services, workflows, routes, or personas;
- expected target behavior and the conflicting or missing evidence;
- relevant negative or failure path;
- recommended direction or next decision; and
- whether human product, architecture, risk, or release judgment is required.

Within a coordinated review, identify duplicates using the canonical owner, affected capability or workflow, finding class, and failure mode. Stable references help synthesis but do not create a permanent findings registry.

## Completion

End with exactly one review state:

- `complete` – every declared item was covered or explicitly excluded with an acceptable rationale;
- `incomplete` – the review was sampled, a required area was skipped, or necessary proof or live evidence was unavailable; or
- `blocked` – a missing authority, competing target state, unavailable required source, or human decision prevents the review from continuing.

`No findings` does not imply `complete`. A static review may be complete within its declared static boundary while live evidence remains unavailable, but it must not make an unqualified readiness claim.

## Rereview

Reread current sources from disk. Prior findings are evidence to reconcile, not authority. Classify them as still present, resolved, changed, superseded, or not reproducible, and do not silently drop them.

Stop and request human direction rather than choosing between competing product outcomes, target architectures, security or privacy risk acceptance, release scope, or traffic-opening decisions.
