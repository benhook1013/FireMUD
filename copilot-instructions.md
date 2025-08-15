# Copilot PR Review Instructions

Automated reviewers must apply the same guidance given to human contributors.

## Core references

- [AGENTS.md](AGENTS.md) – root instructions for all AI usage in this repository.
- [Global AI Rules](design/project-management/ai-rules-global.md) – project-wide conventions and workflow requirements.
- [Local AI Rules](design/project-management/ai-rules-local.md) – Java-specific implementation details and testing expectations.

## Review checklist

- Verify changed files comply with any `AGENTS.md` within their directory tree.
- Ensure contributors ran `pre-commit run --files <changed files>` or `./gradlew check` and addressed all issues.
- Flag manual line wrapping; lines should flow naturally.
- Confirm additions or updates respect the architectural and style guidelines stated in the rules above.
