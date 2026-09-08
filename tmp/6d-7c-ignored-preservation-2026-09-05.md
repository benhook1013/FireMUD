# 6D/7C ignored-content preservation review

Reviewed 2026-09-05 from `/home/ben/src/FireMUD-project-direction` on branch `codex/project-direction`; the required path and branch guard passed. The inspected worktree is `/home/ben/src/FireMUD-corpus-review-6d-7c`, at local head `c2af10ee75d395172e8b10201f5ba8c3cd5cbf66` (`align recovery and incident proof authority`). This report covers ignored files only. It makes no ancestry or containment claim about this head; preservation of committed local/remote branch work is a separate checkout decision.

## Result

`git ls-files --others --ignored --exclude-standard` found 4,908 ignored files totaling 198,623,834 bytes. Every entry is under one of four generated-output roots: `.gradle`, `buildSrc`, `build`, or `dev-tools`. No ignored `tmp` directory, handoff/note file, user configuration, credential material, log, trace, transcript, or other unique scratch/evidence artifact was found by the aggregate path and metadata review.

| Root | Count | Bytes | Observed contents and classification |
| --- | ---: | ---: | --- |
| `.gradle` | 4,750 | 197,003,449 | Gradle execution/configuration caches and the downloaded Gradle-managed Node/npm distribution. Reproducible local tool/cache state. |
| `buildSrc` | 151 | 766,022 | Nested Gradle/build outputs, Kotlin compiler caches, generated classes, plugin descriptors, and `buildSrc.jar`. Reproducible from tracked build logic. |
| `build` | 3 | 609,798 | Three Gradle configuration-cache HTML reports dated 2026-08-29. Reproducible diagnostic output; no unique review or runtime evidence identified from filenames/metadata. |
| `dev-tools` | 4 | 244,565 | Python bytecode under `dev-tools/validation/__pycache__` for four tracked validation scripts. Reproducible interpreter output. |

If the root task wants to retain small human-readable historical diagnostics before checkout removal, the three report paths are:

- `build/reports/configuration-cache/5up33q025kd1hv4noghnjwly/8tjrrayf8k0lw5tarjhurv5k7/configuration-cache-report.html` (203,457 bytes)
- `build/reports/configuration-cache/7hgbrrax45tr84pr43w68lk0z/3su6m0qdnbjrysp9ktqq419yg/configuration-cache-report.html` (202,884 bytes)
- `build/reports/configuration-cache/8740nke5yykvf9iiv1sse8o96/758mu0cda4tuksqxv76hv3lpm/configuration-cache-report.html` (203,457 bytes)

Their paths and metadata identify them as generated Gradle configuration-cache diagnostics; their contents were not read, so their evidentiary value is unknown. No other ignored path was identified as worth copying.

The ignore rules confirm these categories: `.gradle` and `build` are covered by the repository's Gradle/build patterns, and the Python bytecode is covered by `__pycache__/`. The four `dev-tools` entries are the only ignored files outside the standard Gradle/build roots.

## Preservation classification

No checkout-specific ignored material requires preservation before retiring this clean worktree. The ignored files are build/cache/bytecode products and can be regenerated from tracked source and build logic. The three configuration-cache reports are the only human-readable reports, but their generated path/name and metadata provide no indication of unique user evidence; retain them only if a separately owned diagnostic investigation needs those historical reports.

This conclusion applies only to ignored contents. It does not authorize removal of the worktree's committed branch/ref, does not establish whether another branch contains equivalent commits, and does not inspect review, CI, PR, or branch dependency state. No files were removed.

## Checks deferred

No tests, formatting, Gradle validation, review/CI/PR inspection, fetch/prune, ref changes, or cleanup commands were run. File contents were not read beyond the repository ignore rules; credentials and configuration secrets were not inspected.

## Evidence basis

- `AGENTS.md`, `design/developer-workflows/pr-lifecycle.md`, and the existing `tmp/nonactive-scratch-purpose-2026-09-05.md` were read in the strategic worktree before inspection.
- The ignored-file count and byte totals were aggregated from NUL-delimited `git ls-files --others --ignored --exclude-standard` output without emitting the full list.
- Root/category metadata and selected generated filenames were inspected with Python and `find`; no recursive cache/build content scan was performed.

## Subsequent authorized disposition

The main task subsequently removed the inactive 6D/7C and downstream-seed checkouts after fresh preservation/use checks. Both local and remote branch tips remain intact. See `project-direction-notes.md` for the executed outcome; checkout paths in this dated report are historical.
