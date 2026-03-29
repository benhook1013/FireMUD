# Release Please Configuration

Metadata used by `.github/workflows/release-please.yml` to automate versioning and changelog generation.

Release-time NOTICE generation and `/licenses` assembly are handled separately by [`.github/workflows/release-notes.yml`](../../.github/workflows/release-notes.yml), which uses [`dev-tools/release/generate_notice.py`](../../dev-tools/release/generate_notice.py), [`dev-tools/release/assemble_licenses_dir.py`](../../dev-tools/release/assemble_licenses_dir.py), and [`NOTICE.template.md`](../../NOTICE.template.md).

For release-time licensing and notice requirements, see [design/operations/release-process.md](../../design/operations/release-process.md).
