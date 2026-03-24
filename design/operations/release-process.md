# Release Process

This document defines the minimum release hygiene for FireMUD versions published from this repository.

## Release Model

FireMUD uses the Business Source License 1.1 on a per-release basis.

- Each official release has its own publication date.
- Each official release has its own Apache License 2.0 change date exactly two years after publication.
- The release-specific publication date and change date must be recorded in the NOTICE file distributed with that release.
- The repository copy of [`NOTICE.md`](../../NOTICE.md) is a stable repository notice, not the generated release-specific NOTICE artifact.

This matches the parameterization described in [`LICENSE.md`](../../LICENSE.md): the license applies separately to each version, and the change date may vary by version.

## Required Release Artifacts

Every official release must include:

- A release-specific `NOTICE` file generated from [`NOTICE.template.md`](../../NOTICE.template.md).
- A `/licenses` directory in the release artifact containing dependency notices for that release when third-party attribution is required.
- The published source archive or source reference covered by the release.

## Release Checklist

Before publishing a release:

1. Determine the release publication date.
2. Compute the change date as exactly two years after the publication date.
3. Generate the release `NOTICE` from [`NOTICE.template.md`](../../NOTICE.template.md) with:
   - `COPYRIGHT_YEAR` set to the release year
   - `RELEASE_DATE` set to the publication date
   - `CHANGE_DATE` set to the per-release Apache conversion date
4. Generate or assemble the `/licenses` directory for the release artifact if dependency notices are required for included third-party software.
5. Verify that [`LICENSE.md`](../../LICENSE.md), [`README.md`](../../README.md), and [`FAQ.md`](../../FAQ.md) still describe the current release licensing model accurately.
6. Verify that `NOTICE` in the release artifact matches the release metadata actually being published.
7. Verify that trademark wording remains current.

## Repository Maintenance

The repository should avoid baked-in dates that become stale between releases.

- Keep [`NOTICE.md`](../../NOTICE.md) generic and repository-scoped.
- Keep release-specific dates in generated release artifacts, not in long-lived source files.
- If release automation begins generating `NOTICE` or `/licenses`, update this document and the release workflow configuration in `config/release/`.
