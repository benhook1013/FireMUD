# FireMUD Design-Alignment Program

Local ignored execution plan for completing the post-ADR design-alignment mini-project. Canonical status lives in `design/project-management/design-alignment/README.md`; this file describes sequencing and review mechanics only.

## Overall Phases

1. **Foundation — complete.** Capability taxonomy and allocation, consequential-decision inventory, ten implementation trackers, code/proof reconciliation, structural gates, and the first authority-consolidation baseline were established.
2. **Human adversarial decision review — complete.** All historical queue/navigation rows were reviewed and dispositioned in the source archive.
3. **Selective decision import — complete on the active licensing branch.** The program integrated all 182 historical decision keys plus three direct licensing/hosted-service decisions through ADRs 0179–0181. PR #2658 is the final current merge/review boundary for this phase.
4. **Whole-corpus authority review — next.** Review the complete product and architecture corpus, including non-ADR contracts, for correctness, completeness, one canonical owner per contract, residual conflicts, missing consequences, and harmful normative duplication.
5. **Reconciliation and closeout — after the authority pass.** Update live implementation trackers only where target, implementation, proof, or remaining-gap status changed; validate the complete corpus; resolve any genuine residual consequential decisions with Ben; then declare the post-ADR design-alignment mini-project complete.

Later runtime implementation is a separate program driven by the ten live implementation trackers. The authority pass must not silently expand into implementing every accepted target contract.

## Review Scale

PR size and reviewer cognitive scope are different constraints:

- A **cognitive review unit** is one coherent contract family with its canonical owner, directly relevant ADR rationale, selected consumers, product consequences, and owning tracker. It normally contains about 8–18 substantive documents and may be smaller for dense hubs.
- A **PR parcel** packages multiple already-reviewed adjacent units for integration, hosted CodeRabbit review, CI, and human merge. It retains comfortable headroom below CodeRabbit's 100-file ceiling.
- A **parcel seam review** checks cross-unit handoffs and ownership edges. It does not ask one reviewer to re-read every file in a 50–80-file PR as one mental model.

File and PR-count limits are packaging concerns only. They never justify suppressing, weakening, deferring, or stopping a verified coherent fix, nor keeping a corpus PR documentation-only. If packaging becomes unwieldy, preserve the finding and solve the shape mechanically through commit, stack, or merge topology without distorting review scope.

Correctness is primary. Reviewers first establish the intended contract and owner, then test consumers, handoffs, product consequences, trackers, and missing decisions. Deduplication is a secondary lens: flag competing normative authority and copied rules likely to drift, while preserving useful local explanation, API/storage/transport/operational consequences, examples, evidence schemas, and proof status.

## Whole-Section Review Boundary

Every corpus pass is an independent **whole-section-unrestricted** review of the complete declared unit manifest and its material handoffs. The reviewer may follow a material claim into unchanged canonical authority, ADRs, implementation, schemas/migrations, tests, trackers, and proof. A named architecture, runtime, persistence, or cross-owner lens is **narrow-focused**: it may supplement broad discovery, but it is never the only active mode and neither its findings nor its zero count toward whole-section terminality. Record the mode for every pass; terminality requires the planned floor plus the recorded current-head taper of genuinely useful findings from unrestricted whole-section passes, unless Ben explicitly stops earlier.

Discovery is unrestricted, but implementation scope is adjudicated per finding. Corpus PRs may fix defects introduced by the PR and may admit a pre-existing correction when it is bounded, low-risk, coherent with the section, and simpler than adding extensive drift prose or tracker debt. Defer or split a finding when it becomes a substantial capability slice whose persistence, migration, concurrency, design, or proof cost would materially displace corpus review; file count alone is not determinative. Record deferred implementation gaps separately from fixes admitted to the corpus PR. The main worker scopes, adjudicates, inspects delegated diffs, verifies, and coordinates; it does not directly implement repository fixes. When known fixes are queued, normally reserve one subagent lane for delegated fixes and use remaining lanes for unrestricted whole-section discovery.

## Provisional Cognitive Units

The current partition contains 23 units nested inside seven likely PR parcels. Counts are cognitive-density estimates, not mechanical directory splits, and should be recalibrated after the first unit.

1. **Access and experience/social**
   - 1A identity, entitlement, and hosted terms — about 18 documents, dense
   - 1B admission, session continuity, and reconnect — about 16
   - 1C commands, output, and frontend presentation — about 14
   - 1D social, communication, and moderation-facing UX — about 12
2. **Gameplay, world, and session**
   - 2A tick scheduling and region authority — about 16, dense
   - 2B mutation and spatial authority — about 15, highest risk
   - 2C gameplay entities, effects, and economy — about 11
3. **Authoring and release**
   - 3A authoring content and extension packaging — about 18
   - 3B settings, policy, and effective configuration — about 14
   - 3C release lifecycle and activation — about 16, dense
4. **Automation and scripting**
   - 4A script ingress, sandbox, and runtime execution — about 18, dense
   - 4B scheduling, quotas, reload, and operational fairness — about 17
5. **Shared runtime**
   - 5A API, message, identifier, tenant, time, and authorization primitives — about 17, highest fan-out
   - 5B SQL, migration, schema, and retention — about 18
   - 5C Redis roles and cache/rate-limit semantics — about 17, dense
   - 5D idempotency, outbox, replay, saga, and workflow patterns — about 17
6. **Edge and platform delivery**
   - 6A Gateway route, traffic-plane, sharding, and close taxonomy — about 15
   - 6B WebSocket, Telnet, protocol bridge, and session transport — about 14
   - 6C Logging & Admin/operator ingress and action authorization — about 15, highest risk
   - 6D environment, deployment, assets, backup, and delivery — about 15
7. **Observability and proof**
   - 7A logging, metrics, tracing, SLOs, and degraded operation — about 16, dense
   - 7B verification boundaries, recovery evidence, and compliance — about 12
   - 7C incident and operational proof surfaces — about 10

## Review Algorithm

1. Build the exact unit manifest from the existing capability allocation and canonical owner links. Record only owner, attached sources, inbound dependencies, outbound handoffs, and unresolved seam questions; do not create a new giant decision ledger.
2. Run three successive fresh unrestricted whole-section Luna passes for each ordinary unit and four for high-risk units 2B, 5A, and 6C. These are floors, not caps. Prefer corrected successive heads over same-head duplication; use same-head independent review only for a suspiciously quiet result, unresolved interpretation, or explicit high-risk variance check. Narrow-focused passes may supplement this sequence but cannot replace it. Luna discovery prioritizes stable authority, correctness, safety, ownership, identity/scope, API and proof contracts, and consequential deduplication; exhaustive point-in-time implementation-status alignment is lower priority.
3. Normalize findings by semantic contract issue rather than reviewer count. The main worker verifies source evidence, rejects stylistic churn or duplicate-authority proposals, and adjudicates design ambiguity with Ben only when consequential alternatives genuinely remain. Fix a verified implementation-status finding when it surfaces so later reviewers do not repeatedly rediscover it, but do not turn the static review into an exhaustive synchronization of temporary status prose unless the drift creates an unsafe instruction, false implementation claim, impossible proof gate, or stable-contract contradiction.
4. Delegate one bounded fix batch for accepted findings. Subagents make repository edits; the main worker inspects every edit and runs proportionate validation without directly implementing fixes.
5. Accumulate adjacent active units into one coherent parcel. CodeRabbit CLI and hosted review always inspect the whole active PR diff; the main worker attributes each finding back to its unit and tracks unit progress independently.
6. After a Luna correction batch, commit locally and cycle full-PR CodeRabbit CLI review until it reports zero genuinely useful work for that unit and every other active unit affected by the batch. Only then buy the next fresh Luna pass. A CLI zero proves diff cleanliness, not static-corpus correctness.
7. Start hosted review at meaningful parcel checkpoints rather than waiting for every provisional unit. While hosted runs, continue local Luna, fix, and CLI cycles against a stable local head, but do not push review-invalidating commits. Adjudicate hosted findings into the combined batch after it terminates.
8. A unit reaches static terminal only after its unrestricted whole-section Luna floor, all accepted findings are fixed or explicitly deferred, a fresh unrestricted full-unit pass produces zero genuinely useful findings, PR-wide CLI is clean for its current consequences, and its owner/consumer seams are coherent. Narrow-focused zeroes never satisfy this gate. Any useful unrestricted finding resets this taper. Confirm a zero-useful terminal with another fresh unrestricted pass for 2B, 5A, 6C, or an anomalously quiet result, unless Ben explicitly stops earlier.
9. At parcel integration, compare inbound/outbound seam pairs and conflicting ownership or lifecycle language. After all parcels, run the final whole-corpus owner/secondary/tracker consistency pass and complete-corpus validation.

## First Unit Calibration

Start with unit 5A, Shared Runtime primitives, because it has high fan-out and will reveal the practical context limit early. This is not a separate program phase. It is the first unit of phase 4.

Use its four-pass high-risk floor and measure substantive files actually reasoned across, unique versus repeated useful findings, cross-reference expansion, and context/token pressure. Apply the Luna -> fix -> PR-wide CLI-to-zero -> next fresh Luna sequence. If a reviewer must follow more than two attached secondary domains or loses owner/consumer relationships, split the unit by primitive family. Use the result to tune later units toward roughly 12–16 documents rather than mechanically preserving the initial estimates.

## Retained Source Follow-Ups

Remote source branch `design/adversarial-decision-review` is retained only for three Ben-authored post-import semantics in commit `1c028e5f28bf292007e54ffa4001b4d34dd341d4` that are not present in `develop`. They are review inputs, not automatically accepted new authority:

- Unit 2B with a 5A seam: decide whether Weather selects one region-scoped aggregate solely from authoritative room-to-region membership; current canonical docs deliberately leave the aggregate selector unresolved and keep Weather writes non-mutating.
- Units 5A/5D with a 2B seam: adjudicate deterministic plan-root identity derived from admitted command identity plus a stable persisted plan ordinal, rather than participant tuples allocating identity.
- Units 6D/7C: adjudicate destructive reset/smoke isolation by unique tenant/game-instance namespace, namespace-only mutation, disposable-environment requirements for environment-wide reset, and retention of failed transcripts/logs/metrics/reset status before cleanup.

Delete the remote source branch after these three items are either integrated into their canonical owners or explicitly rejected with the disposition retained here. No other source-only commit currently justifies retaining that branch.

## Parallelism

- Keep at most two cognitive units in flight: one active correction/re-review unit and one read-only staged unit. Never let parallel workers edit overlapping files.
- Luna reviews units individually. CodeRabbit CLI and hosted review inspect all sections accumulated in the active PR; route findings back to unit-local ledgers and invalidate a unit's clean checkpoint when another unit changes a shared owner or seam.
- Keep 2A and 2B sequential or tightly coordinated because scheduling, executor, mutation, spatial, and causal-read authority overlap.
- Establish 5A terminology before downstream shared-runtime units; 5B, 5C, and 5D may then proceed in parallel.
- Coordinate 1A and 6C explicitly because identity/terms authority and operator ingress meet at authorization boundaries.
- Parallelize read-only discovery more aggressively than fixes. The main worker adjudicates before any shared correction batch.
- Reuse an agent only for direct same-domain continuation. Use fresh context for independent reviews and verification.
- When safe useful work exists, keep the hosted review, the single permitted CLI review, disjoint delegated fix and verification lanes, and preparation of the next dependency-safe local slice running concurrently. Do not delay publication after primary validation is green merely for redundant verification; publish the coherent parcel so hosted review and CI can overlap the remaining independent checks. While hosted review is active, continue safe local work but do not push a review-invalidating change.
- Preserve one preemptible lane for accepted edits or verification. If findings require edits while all lanes are occupied, wait for a lane or interrupt the least valuable preemptible review; the main worker scopes, adjudicates, inspects, and validates but never performs bulk/manual fixes. Do not duplicate reviews or manufacture work to fill a slot. If a lane is intentionally idle while useful parallel work appears available, record the concrete dependency, overlap, adjudication, or validation reason in the handover/tracker.

## Finding Quality And Completion

Useful authority-pass findings are direct contradictions, competing canonical ownership, missing consequential requirements, target/current drift, harmful normative duplication, or tracker/proof mismatch. Repeated explanation is legitimate when it serves the local document's actual purpose.

Unit static review and PR merge review have separate terminals. Continue Luna beyond its three/four-pass floor whenever the latest full-unit pass produces useful stable-contract work; stop only when a fresh pass contains only invalid, already-fixed, duplicate, optional-style, or ordinary point-in-time status findings with no correctness or authority value. For PR merge readiness, record hosted findings as stable-contract work versus status-maintenance work and judge taper by finding quality, severity, and corrective scope rather than raw count. Two consecutive current-head checkpoints with zero stable-contract findings are the default terminal even when verified status-maintenance findings are fixed from those rounds; one status-only round after stable findings have already reached zero may qualify with main-worker judgment because later implementation and corpus passes will refresh status. CLI duplication does not substitute for hosted evidence.

## Non-Goals

- Do not reopen completed human decisions without concrete contradictory evidence.
- Do not create ADRs merely to organize the review.
- Do not create a second canonical inventory or heavyweight governance system.
- Do not remove useful repeated explanation merely because text appears in more than one document.
- Do not absorb a substantial runtime capability slice into the authority pass or claim unimplemented target contracts as proven; adjudicate bounded, low-risk, section-coherent fixes per finding under the implementation boundary above.
