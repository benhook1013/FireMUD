# Release Please Configuration

Metadata used by `.github/workflows/release-please.yml` to automate versioning and changelog generation.

Release-time NOTICE generation and `/licenses` assembly are handled separately by [`.github/workflows/release-notes.yml`](../../.github/workflows/release-notes.yml), which uses [`dev-tools/release/generate_notice.py`](../../dev-tools/release/generate_notice.py), [`dev-tools/release/assemble_licenses_dir.py`](../../dev-tools/release/assemble_licenses_dir.py), and [`NOTICE.template.md`](../../NOTICE.template.md).

That release-time `/licenses` assembly currently covers the package-managed release ecosystems in this repository today: `Gradle` and `NPM`. If a new packaged runtime or release-tool ecosystem is introduced, update both the workflow and the release-process documentation.

The generated release bundle now includes:

- plain-text notice reports from ORT
- copied CycloneDX inventory artifacts
- an attribution index
- per-package files grouped into runtime-like, non-runtime, and unknown-scope buckets
- a combined `firemud-<tag>-release-compliance.zip` asset containing `NOTICE.md` plus `/licenses`

For release-time licensing and notice requirements, see [design/operations/release-process.md](../../design/operations/release-process.md).
