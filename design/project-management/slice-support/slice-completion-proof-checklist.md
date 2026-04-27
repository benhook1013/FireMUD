# Slice Completion Proof Checklist

Use this checklist before marking any vertical slice complete or changing its status to "completed at the current boundary."

The point is to stop slice-doc truth from drifting ahead of actual public contracts or implementation seams.

## Required Proof

- Verify the slice doc's claimed outcome against every public contract it owns.
  - Check HTTP/OpenAPI surfaces.
  - Check gRPC/proto surfaces.
  - Check any event, outbox, or operator-facing contract the slice explicitly claims.
- Verify the live implementation seam, not only adjacent architecture direction.
  - Read the concrete service implementation.
  - Read any controller, handler, or orchestration seam the slice claims to have converged.
  - Confirm the "current canonical owner" is actually the owner in code, not only in docs.
- Verify focused tests exist for the claimed seam.
  - Prefer narrow unit/integration/cross-service proof over unrelated broad test pass interpretation.
  - If the slice claims fail-closed or replay-safe behavior, verify the negative-path tests too.
- Verify supporting docs match the closure claim.
  - Update the slice doc.
  - Update `vertical-slices/README.md`.
  - Update `00-slice-progress.md` when queue priority or remaining work changed.

## Closure Questions

Answer "yes" before closing the slice:

1. Does the public API schema match the slice claim?
2. Does the proto/gRPC contract match the slice claim?
3. Does the implementation actually route through the canonical owner and not a local fallback?
4. Do focused tests cover the exact seam being marked complete?
5. Do queue docs describe only the remaining work that is still real?

## If Any Answer Is "No"

- Leave the slice `in progress` or `completed at the current boundary`, not fully complete.
- Add an explicit `Implementation Notes` or `Current Remaining Work` entry naming the unresolved seam.
- Queue the follow-up slice work instead of relying on a doc-only completion claim.
