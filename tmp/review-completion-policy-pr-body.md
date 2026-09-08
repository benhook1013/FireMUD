## Summary

Replace the mandatory two-clean-hosted-review streak with explicit review completion criteria. A coherent change can finish after substantive review, verified fixes, and required CI/runtime proof; further discovery needs a concrete risk or coverage gap.

## What Changed

- Make PR lifecycle the single owner of completion criteria and link the design-alignment guide to it.
- Preserve actionable finding resolution, zero unresolved current/outdated threads, substantive review of material changes, and truthful reporting of direct-fix follow-ups without final-head review.
- Preserve active-review publication safety, review limits, and the general human-only merge policy.

## Validation

- `./gradlew linkCheck lintMarkdown` passed: 6,717 links OK, zero link errors, and 535 Markdown files with zero lint issues.
- `git diff --check` passed.
- Documentation-only change; runtime tests were not required or run.
- One Luna agent made the edits under explicit main-task direction; the main task inspected the full diff and ran documentation validation.

## Notes

The repository owner explicitly authorized merging this small guidance change without CodeRabbit review. This is a specific exception for this PR; it does not change the general review or merge authority rules.

@coderabbitai ignore

## Checklist

- [x] Read the contribution guidance and listed actual validation.
- [x] Updated canonical workflow guidance and its secondary reference together.
- [x] Repository-owner/automation contribution exception applies.
- Architecture/capability completion and runtime-specific checklists are not applicable to this workflow-only change.
