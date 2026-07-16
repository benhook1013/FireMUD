# Pull Request

## Summary

- describe the purpose of this PR and the user/operator-visible outcome
- reference related issues, capability trackers, or design docs

## What Changed

- summarize the main implementation areas
- call out any important contract, workflow, or doc updates

## Validation

- list the commands you actually ran
- include `./gradlew spotlessApply` for code changes
- include `./gradlew check` before hand-off
- include `./gradlew linkCheck lintMarkdown` when markdown or design docs changed
- include targeted service checks or other relevant verification as needed

## Notes

- optional follow-ups, risks, or reviewer context

## Checklist

- [ ] I have read the contribution guidelines
- [ ] I have listed the validation I actually ran
- [ ] I have referenced related issues, domain implementation trackers, and canonical design docs where relevant
- [ ] For architecture or capability/tracker completion changes, I checked the relevant domain implementation tracker, canonical design, proto/API contracts, service implementation, and focused tests before marking work complete or filing blockers
- [ ] For auth/session, scripting, or observability contract changes, I applied the relevant checklist in `design/project-management/review-checklists.md`
