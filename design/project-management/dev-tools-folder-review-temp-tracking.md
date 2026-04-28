# Dev Tools Folder Review Temp Tracking

Purpose: track the once-off `dev-tools/` cleanup so the reorganization lands as one coherent batch instead of a series of partial path moves.

## Checklist

- [x] Review the current `dev-tools/` ownership shape and distinguish canonical root entrypoints from support scripts.
- [x] Add a `dev-tools/README.md` index so the folder is navigable without reopening scripts.
- [x] Move repo policy and static checker scripts into `dev-tools/validation/`.
- [x] Move certificate helper scripts under `dev-tools/certs/` and fix their default path contract.
- [x] Move non-canonical analysis and maintenance helpers out of the `dev-tools/` root.
- [x] Remove clearly stale per-service Gradle-wrapper tooling.
- [x] Update Gradle, workflows, and docs to the new paths in the same change.
- [x] Keep only canonical human-facing entrypoints at the `dev-tools/` root.

## Outcome

The `dev-tools/` root now keeps the small set of direct operator/developer entrypoints, while category-specific helpers live under `validation/`, `deploy/`, `certs/`, and `maintenance/`. The generated local certificate outputs remain ignored, but the helper scripts that own that lane are now tracked and documented.
