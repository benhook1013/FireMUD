# Release Process

This document defines the minimum release hygiene for FireMUD versions published from this repository.

## Release Model

FireMUD uses the PolyForm Noncommercial License 1.0.0.

Benjamin James Hook is the current legal rights holder and operator. FireDevOps is the project brand, and firedevops.net is its website; neither is a separate legal entity.

- The repository and official releases remain under the same noncommercial license unless FireMUD explicitly publishes different terms for a future version.
- Each official release records its publication date in the NOTICE file distributed with that release.
- The repository copy of [`NOTICE.md`](../../NOTICE.md) is a stable repository notice, not the generated release-specific NOTICE artifact.

## Required Release Artifacts

Every official release must include:

- A release-specific `NOTICE` file generated from [`NOTICE.template.md`](../../NOTICE.template.md).
- A `/licenses` directory in the release artifact containing dependency notices for that release when third-party attribution is required.
- The published source archive or source reference covered by the release.

## Current Automated Notice Scope

The current automated dependency-notice workflow covers the package-managed ecosystems that make up the shipped product and release artifacts today:

- `Gradle`
- `NPM`

Repository scripting languages are broader than the current release notice scope. Bash and Python are supported for repository automation, but Python is not yet treated as a release dependency ecosystem because the repository does not currently ship a manifest-managed Python package surface as part of the official product artifacts.

If FireMUD later ships runtime or release-tool Python dependencies through a committed package manifest, the release `/licenses` automation must be expanded to cover that ecosystem as well.

## Release Checklist

Before publishing a release:

1. Determine the release publication date.
2. Generate the release `NOTICE` from [`NOTICE.template.md`](../../NOTICE.template.md) with:
   - `COPYRIGHT_YEAR` set to the release year
   - `RELEASE_DATE` set to the publication date
3. Generate or assemble the `/licenses` directory for the release artifact if dependency notices are required for included third-party software.
4. Verify that [`LICENSE.md`](../../LICENSE.md), [`LICENSING.md`](../../LICENSING.md), [`NOTICE.md`](../../NOTICE.md), [`NOTICE.template.md`](../../NOTICE.template.md), [`README.md`](../../README.md), [`FAQ.md`](../../FAQ.md), and [`TRADEMARKS.md`](../../TRADEMARKS.md) collectively identify Benjamin James Hook as the current legal rights holder and FireDevOps/firedevops.net as the project brand and website, while keeping the PolyForm, community no-money, and future hosted/commercial wording aligned.
5. Verify that `NOTICE` in the release artifact matches the release metadata actually being published.
6. Verify that the FireMUD and FireDevOps names and trademark wording remain consistent with [`TRADEMARKS.md`](../../TRADEMARKS.md) and do not imply that either mark is registered.

The collective legal/hosted wording alignment check also includes [`HOSTED_CONTENT_TERMS.md`](../../HOSTED_CONTENT_TERMS.md); the separate `NOTICE` and trademark checks remain unchanged.

## Release Automation

The tag workflow in [`.github/workflows/release-notes.yml`](../../.github/workflows/release-notes.yml) generates the release-specific `NOTICE` asset automatically and assembles the release `/licenses` bundle from ORT output.

- The workflow uses [`dev-tools/release/generate_notice.py`](../../dev-tools/release/generate_notice.py) and [`NOTICE.template.md`](../../NOTICE.template.md).
- The workflow runs ORT with plain-text notice reporters and assembles the resulting dependency notices with [`dev-tools/release/assemble_licenses_dir.py`](../../dev-tools/release/assemble_licenses_dir.py).
- The release publication date comes from the existing GitHub Release `publishedAt` timestamp when present, or falls back to the current UTC date during initial release creation.
- The generated file is uploaded to the GitHub Release as `NOTICE.md`.
- The assembled `/licenses` directory now includes:
  - plain-text notice reports
  - copied CycloneDX machine-readable inventory artifacts
  - an attribution index
  - per-package files grouped into runtime-like, non-runtime, and unknown-scope buckets
- The assembled `/licenses` directory is uploaded to the GitHub Release as `firemud-<tag>-licenses.zip`.
- A combined `NOTICE.md + /licenses` asset is uploaded as `firemud-<tag>-release-compliance.zip`.
- Release assembly fails if required notice reports are missing or if the machine-readable inventory is empty.

## Repository Maintenance

The repository should avoid baked-in dates that become stale between releases.

- Keep [`NOTICE.md`](../../NOTICE.md) generic and repository-scoped.
- Keep release-specific dates in generated release artifacts, not in long-lived source files.
- Keep the ORT-backed `/licenses` assembly aligned with the release workflow and adjust it if the dependency notice shape changes.
