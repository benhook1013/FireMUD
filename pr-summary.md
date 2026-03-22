## Summary
- bump the Checkstyle toolchain again as a normal upstream dependency refresh
- keep the earlier SpotBugs/tool-version alignment in place from the previous follow-up commit
- reduce the remaining ORT advisory surface without adding transitive override hacks
- make the weekly ORT workflow report-only so it stays useful while the remaining accepted transitive/tooling debt is still unresolved

## What changed
- updates the root Checkstyle tool version in `build.gradle.kts` from `12.1.1` to `13.3.0`
- keeps Checkstyle / SpotBugs version alignment centralized in the root build so analysis-tool dependencies do not drift between root and subprojects
- changes `.github/workflows/ort-advisory.yml` so the ORT scan still evaluates `fail-on: violations`, but the workflow itself does not stay permanently red on the currently accepted remaining findings
- preserves ORT artifact/report generation so the weekly job remains a useful monitoring signal
- intentionally stops short of forcing recursive transitive overrides for the remaining ORT findings (`commons-lang3` under Doxia, SpotBugs/log4j, and the older Netty path)

## Validation
- `./gradlew :account-service:dependencyInsight --dependency commons-beanutils --configuration checkstyle`
- `./gradlew :account-service:dependencyInsight --dependency commons-lang3 --configuration checkstyle`
- `./gradlew :account-service:checkstyleMain -PfullCheck`
- `./gradlew spotlessApply`
- `./gradlew check`

## Notes
- This is a follow-on PR after `#2170` merged.
- The clean Checkstyle bump moved `commons-beanutils` to `1.11.0`.
- Remaining ORT issues now look like deeper transitive/tooling debt rather than straightforward version bumps, so they were deliberately left out of this PR.
- The ORT workflow change is meant to avoid training everyone to ignore a permanently red weekly job while still keeping the advisory output visible.
