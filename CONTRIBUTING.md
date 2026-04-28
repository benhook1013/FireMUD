# Contributing to FireMUD

Thank you for your interest in improving the FireMUD Game Platform! This document outlines our workflow and expectations for code contributions.

## Branching Strategy

- The `main` branch contains the latest stable code.
- Create feature branches from `main` using the format `feature/<short-description>` or `bugfix/<issue-number>`.
- Keep your branch up to date by regularly pulling from `main` and rebasing or merging as needed.
- Open pull requests (PRs) against `main` when your changes are ready for review.

## Onboarding

If you're setting up the project for the first time, follow these steps:

1. Review prerequisites in [**Developer Setup**](DEVELOPER_SETUP.md) and install Java 21, Docker, Node.js, and other tools.
2. Clone the repository and confirm the root Gradle wrapper works with `./gradlew help`.
3. If you want to customize local Docker Compose settings, copy `.env.sample` to `.env` and adjust values as needed.
4. Start the local stack with `./gradlew devUp` when you need the full source-built environment.
5. Explore [design/README.md](design/README.md) and the architecture docs under `design/`.

Once your environment is running you can create a feature branch and submit a PR as described below.

## Code Style Summary

- Follow the patterns in [AGENTS.md](./AGENTS.md). The `.windsurfrules` file in the repository root should reference this document for IDE integration.
- Use four spaces for indentation and avoid trailing whitespace.
- Favor immutable data structures, clear method names, and concise classes.
- Backend code targets Java 21+ with Spring Boot 4.x; frontend code follows standard React/TypeScript conventions.
- Document public methods and classes with brief Javadoc comments.
- Install the git hook in `config/git-hooks/pre-commit` to automatically format and lint your commits.

## Pre-commit Hooks

The repository includes a `.pre-commit-config.yaml` that runs Spotless,
markdownlint, Checkstyle, SpotBugs, and ShellCheck. Install the pre-commit tool and set up the hooks with:

```bash
pip install pre-commit
pre-commit install
```

Hooks automatically format code, lint Markdown, run ShellCheck on our `dev-tools` scripts, and perform other static analysis before each commit.

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
6. Open a pull request against the `main` branch of this repository.
7. Fill out the PR template, describing your changes and referencing any related issues.
8. Participate in the review process by addressing feedback promptly.

## Code Review Expectations

- Run the appropriate local proof before pushing. At minimum this usually means `spotlessApply` plus the relevant `check` or doc-validation tasks for your scope.
- Reference related issue numbers in your PR description.
- Link to relevant design documents when adding new features.
- Keep commits focused and descriptive so reviewers can understand the intent.

Following these guidelines helps keep the project consistent and makes the review process smoother. We appreciate your contributions!
