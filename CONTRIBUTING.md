# Contributing to FireMUD

Thank you for your interest in improving the FireMUD Game Platform! This document outlines our workflow and expectations for code contributions.

## Branching Strategy

- The `main` branch contains the latest stable code.
- `develop` is the working integration branch; `main` contains stable code.
- Create feature branches from `develop` using the format `feature/<short-description>` or `bugfix/<issue-number>`.
- Keep your branch up to date by regularly pulling from `develop` and rebasing or merging as needed.
- Open pull requests (PRs) against `develop` when your changes are ready for review.

## Onboarding

If you're setting up the project for the first time, follow these steps:

1. Review prerequisites in [**Developer Setup**](DEVELOPER_SETUP.md) and install Java 21, Docker, Node.js, and other tools.
2. Clone the repository and confirm the root Gradle wrapper works with `./gradlew help`.
3. If you want to customize local Docker Compose settings, copy `.env.sample` to `.env` and adjust values as needed.
4. Start the local stack with `./gradlew devUp` when you need the full source-built environment.
5. Explore [design/README.md](design/README.md) and the architecture docs under `design/`.

Once your environment is running you can create a feature branch and submit a PR as described below.

## Contributor Licence Agreement

Before a pull request containing a contribution from an external individual or legal entity can be merged, an accepted [FireMUD Contributor Licence Agreement](CONTRIBUTOR_LICENSE_AGREEMENT.md) must be on file. Complete the applicable signature fields and send a signed electronic copy privately to [licensing@firedevops.net](mailto:licensing@firedevops.net). Benjamin James Hook will countersign or provide written acceptance or confirmation. Do not commit a completed agreement or personal details to the repository.

An individual contributor, or the human signing for a legal entity, must be at least 18 years old and have legal capacity to enter the agreement; an entity signer must also have authority to bind that entity. Contributions by minors are deferred pending New Zealand legal review.

The agreement preserves contributor ownership while granting the rights needed to use accepted contributions in FireMUD. Its detailed grant terms govern inbound contributions; the repository's outbound terms remain governed by [LICENSE.md](LICENSE.md). Contributions must contain only material the contributor owns or is authorized to submit. Third-party or mixed material must be identified and reviewed in the pull request or through the licensing contact. Contributor-authored portions remain subject to the CLA when the contributor owns or controls the rights needed for its grant; third-party portions remain under their own terms or a separate direct grant from the relevant rights holder and are not made Contributions by approval. Documented permission and written approval from Benjamin James Hook or a Permitted Successor must be on file before third-party portions are merged or accepted into Repository Material.

Work authored by Benjamin James Hook in his current rights-holder capacity does not require a new CLA. Rights-cleared repository metadata or mechanical output produced by configured dependency or workflow automation is also exempt from the CLA signature workflow. This automation exception does not create an inbound-rights grant: automation-produced source, documentation, assets, or other copyright-bearing or external material requires documented provenance and a compatible licence or written approval before merge. A bot cannot sign a CLA. The PR template records these exceptions; checking that box alone does not execute a CLA.

## Code Style Summary

- Follow the repository guidance in [AGENTS.md](./AGENTS.md), with conditional procedures indexed in [developer workflows](./design/developer-workflows/README.md).
- Use four spaces for indentation and avoid trailing whitespace.
- Favor immutable data structures, clear method names, and concise classes.
- Backend code targets Java 21+ with Spring Boot 4.x; frontend code follows standard React/TypeScript conventions.
- Document public methods and classes with brief Javadoc comments.
- Install the repository pre-commit hooks with `pre-commit install` if you want lightweight local formatting and file-scoped lint checks on commit.

## Pre-commit Hooks

The repository includes a `.pre-commit-config.yaml` for lightweight local commit hygiene. Install the pre-commit tool and set up the hooks with:

```bash
pip install pre-commit
pre-commit install
```

Hooks run `spotlessApply` on commit and then run file-scoped fixes/checks for Markdown, shell scripts, and Dockerfiles when those file types are staged. Heavier validation stays in explicit local proof commands and CI.

## Testing Requirements

- All functionality must be covered by unit tests.
- Use **JUnit** with **Mockito** for backend unit tests and **Jest** for frontend components.
- Integration tests rely on **Spring Test**, and we use **Gatling** (see `dev-tools/load-testing`) for load testing.
- Run the validation path that matches your change scope rather than relying only on `./gradlew test`.
- For service code, prefer `./gradlew :<service>:check -PfullCheck`.
- For Markdown or design-doc changes, run `./gradlew linkCheck lintMarkdown`.
- For runtime/bootstrap/smoke-sensitive changes, run the canonical smoke proof under `dev-tools/`.

## How to Submit a PR

1. Fork the repository and clone your fork locally.
2. Create your feature branch: `git checkout -b feature/my-change`.
3. Make your code and documentation changes.
4. Run the test suite locally and ensure all checks pass.
5. Commit using clear, descriptive messages and push your branch to your fork.
6. Open a pull request against the `develop` branch of this repository.
7. Fill out the PR template, describing your changes and referencing any related issues.
8. Participate in the review process by addressing feedback promptly.

## Code Review Expectations

- Run the appropriate local proof before pushing. At minimum this usually means `spotlessApply` plus the relevant `check` or doc-validation tasks for your scope.
- Reference related issue numbers in your PR description.
- Link to relevant design documents when adding new features.
- Keep commits focused and descriptive so reviewers can understand the intent.
- Expect automated review signals on pull requests: CodeRabbit provides advisory review comments and walkthrough summaries, and Codecov publishes patch-coverage status for service coverage uploads. Treat these as part of normal PR hygiene alongside human review.

Following these guidelines helps keep the project consistent and makes the review process smoother. We appreciate your contributions!
