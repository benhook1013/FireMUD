# AI Observations

Append-only notes for recurring friction, surprising behavior, environment issues, inefficient patterns, code smells, and "this should be shaped better" patterns discovered during AI work.

Entry dates use Pacific/Auckland unless stated otherwise.

Only keep entries whose lesson still matters after the immediate task is done. Do not use this file as a bug log for ordinary fixes that were completed in the same piece of work. Prefer logging reusable observations that suggest a better repo rule, CI guard, design refinement, or shared implementation pattern.

During ordinary autonomous work, this file is append-only: add dated reusable observations, and do not silently rewrite or delete prior entries. Workers should append reusable observations, helpers should send candidate entries to the owning worker, and ordinary bugs fixed in the same work should not become entries. Explicitly assigned Overseer stewardship may consume entries under the bounded process in [AI delegation and review](../developer-workflows/ai-delegation-and-review.md); a human-requested [repository health check](../developer-workflows/repository-health-check.md) separately authorizes the worker to remove an entry only after evidence shows that it is addressed, obsolete, or disproved. Retain an entry only when a genuine blocker or deliberate postponement remains, recording the reason and reconsideration trigger. A health-check pass should reduce this inbox toward zero.

Entry format:

- `YYYY-MM-DD`: short title
  - Context: where it appeared
  - Observation: what was surprising or wasteful
  - Expected pattern: what should happen instead

- `2026-09-09`: A public symmetric JWK is the signing credential
  - Context: hosted review proposed making a diagnostic `oct.k` correspond to the shared-HMAC Secret, but Account serves that document through the public JWKS route.
  - Observation: an `oct` JWK has no public-only representation; encoding the exact HMAC bytes would let every reader mint valid tokens. A mismatched diagnostic key must not be repaired by publishing the private material.
  - Expected pattern: trace the actual signer, validators, mounts, and routed JWKS endpoint before aligning key metadata. For the current diagnostic-only hosted path, publish an empty JWK Set plus non-secret correlation metadata; implement real verification only with the canonical asymmetric Account-published JWKS and custody boundary.

- `2026-09-09`: Bind cert-manager Secret bytes to durable issuance evidence
  - Context: a hosted identity controller initially treated a ready Certificate revision and a separately read TLS Secret as one issuance snapshot during renewal.
  - Observation: cert-manager writes the Secret before advancing Certificate status, so independent reads can combine different revisions; Ready status and Secret metadata do not prove that the observed certificate bytes belong to the recorded revision.
  - Expected pattern: select the uniquely owned CertificateRequest for the ready revision, require its issued certificate bytes to match the Secret exactly, carry those validated bytes forward, and re-read the Certificate snapshot before acceptance. Treat absent or ambiguous issuance evidence as retryable and remember that `CertificateRequest.status.ca` is optional.

- `2026-09-09`: CodeRabbit uncommitted review omits untracked files
  - Context: an uncommitted CLI review reported the tracked workflow refactor clean but did not include its new local composite action in `reviewedFiles`.
  - Observation: `coderabbit review --uncommitted` does not provide evidence for a new file while that file remains untracked, even when tracked callers of the file are reviewed.
  - Expected pattern: inspect `reviewedFiles` before treating an uncommitted review as complete; stage or intent-to-add new files before a later CLI cycle, or explicitly report the missing coverage and use focused proof for that file.

- `2026-09-09`: Post each adjudicated CLI review checkpoint before the next Hosted request
  - Context: an independent CLI cycle finished between two Hosted cycles, but its canonical found/accepted checkpoint was delayed until after the second Hosted request while accepted findings were being implemented.
  - Observation: implementation and verification do not need to finish before the review count is durable; once every CLI finding is adjudicated, delaying the count obscures the actual independent review cadence.
  - Expected pattern: promptly adjudicate a completed CLI cycle and post its canonical found/accepted count immediately, before fixes, verification, or the next Hosted trigger. Continue CLI, Hosted, CI, and fixes as independent parallel lanes.
