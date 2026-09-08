## Summary

- align shared runtime authority around root and child effect identity, typed mutation guards, and collision-safe replay storage
- use `playableStateNamespaceId` for durable state and scripting identities while retaining `playableStateScope` as validation, routing, and fence evidence
- reconcile SQL/current-inventory and Redis role, reset, eviction, key-family, replay-envelope, and consumer-link authority
- reconcile transaction, replay, retention, Saga/Temporal, workflow-identity, and durable-outbox target/current boundaries
- keep the directly coupled smoke/reset proof seam isolated to run-owned deployments and retain owner-specific consequences without duplicating its testing authority
- distinguish target contracts from current implementation and proof status in the owning trackers

## Scope

This focused parcel contains Shared Runtime Units 5A–5D and only the owner/consumer seams needed to make those contracts coherent. It covers shared API, identifier, tenant, time, and authorization primitives; SQL, migrations, schema, and retention; Redis role and recovery semantics; and idempotency, outbox, replay, Saga, and workflow patterns. The retained smoke/reset files remain because their root-effect, durable-proof, and replay/reset boundaries are directly coupled to those shared contracts.

The pre-split branch also contained first-pass corrections for downstream access, gameplay, authoring, automation, and edge units. Those corrections are preserved on the unpublished stacked `codex/corpus-review-downstream-seed` branch and are not part of this PR or its review/taper claims. No new downstream unit will be activated until Units 5A–5D reach their semantic terminals.

## Review progress

- All four units previously reached their Luna terminal, then were correctly reopened when later full-diff review changed their source material. Only unchanged-current-revision zeroes count again.
- Unit 5A is active at revision 30 after 34 Luna passes and requires two independent `0/0` confirmations on this revision.
- Unit 5B is active at revision 9 after 7 Luna passes and requires one `0/0` confirmation on this revision.
- Unit 5C is active at revision 23 after 21 Luna passes and requires one `0/0` confirmation on this revision.
- Unit 5D is active at revision 26 after 21 Luna passes and requires one `0/0` confirmation on this revision.
- Fifteen completed full-diff CLI rounds have produced `6/6`, `6/6`, `4/4`, `8/7`, `7/5`, `8/6`, `5/5`, `4/4`, `5/5`, `14/12`, `14/13`, `9/9`, `8/7`, `14/12`, and `16/12` raw/useful findings. CLI round 16 is running on `ad9831be2`.
- Five hosted rounds have produced `8/6`, `17/11`, `8/7`, `8/7`, and `15/9` raw/useful findings. The fifth round reviewed `5fe14f101`; its accepted findings and the later CLI corrections are published in `ad9831be2`.

## Validation

- `./gradlew linkCheck lintMarkdown`
- `bash dev-tools/tests/architecture-doc-contracts.sh` (216 tests)
- `python3 dev-tools/validation/check-implementation-capability-tracking.py`
- `git diff --check`
