# Contributing to FireMUD

Thank you for your interest in improving the FireMUD Game Platform! This document outlines our workflow and expectations for code contributions.

## Branching Strategy

- The `main` branch contains the latest stable code.
- Create feature branches from `main` using the format `feature/<short-description>` or `bugfix/<issue-number>`.
- Keep your branch up to date by regularly pulling from `main` and rebasing or merging as needed.
- Open pull requests (PRs) against `main` when your changes are ready for review.

## Onboarding

If you're setting up the project for the first time, follow these steps:

1. Review prerequisites in [**Developer Setup**](DEVELOPER_SETUP.md) and install Java 17, Docker, Node.js, and other tools.
2. Clone the repository and generate Gradle wrappers if needed with `./gradlew wrapper` (or the PowerShell script on Windows).
3. Build all modules using `./gradlew build`.
4. Start the local stack with `docker compose up --build`.
5. Explore the docs under `design/` to understand the architecture.

Once your environment is running you can create a feature branch and submit a PR as described below.

## Code Style Summary

- Follow the patterns described in the repository's `.windsurfrules` file for Java Spring projects.
- Use four spaces for indentation and avoid trailing whitespace.
- Favor immutable data structures, clear method names, and concise classes.
- Backend code targets Java 17+ with Spring Boot 3.x; frontend code follows standard React/TypeScript conventions.
- Document public methods and classes with brief Javadoc comments.
- Install the git hook in `config/git-hooks/pre-commit` to automatically format and lint your commits.

## Testing Requirements

- All functionality must be covered by unit tests.
- Use **JUnit** with **Mockito** for backend unit tests and **Jest** for frontend components.
- Integration tests rely on **Spring Test**, and we use **Gatling** for load testing.
- Tests should run successfully with `./gradlew test`.
- Ensure new tests pass and existing tests are not broken before submitting a PR.

## How to Submit a PR

1. Fork the repository and clone your fork locally.
2. Create your feature branch: `git checkout -b feature/my-change`.
3. Make your code and documentation changes.
4. Run the test suite locally and ensure all checks pass.
5. Commit using clear, descriptive messages and push your branch to your fork.
6. Open a pull request against the `main` branch of this repository.
7. Fill out the PR template, describing your changes and referencing any related issues.
8. Participate in the review process by addressing feedback promptly.

Following these guidelines helps keep the project consistent and makes the review process smoother. We appreciate your contributions!
