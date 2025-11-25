# FireMUD AI Contribution Guide

Please read the following documents before using AI tooling or submitting code changes:

- [Global AI Rules](design/project-management/ai-rules-global.md)
- [Local AI Rules](design/project-management/ai-rules-local.md)

Gradle project paths do **not** include the `services:` prefix even though the modules live under the `services/` directory. Use commands like `./gradlew :tcp-proxy-service:test` instead of `./gradlew :services:tcp-proxy-service:test` when running or referencing tasks.

Do not manually wrap lines. Let lines flow naturally so content displays cleanly on GitHub and in
editors.

These files contain the full coding conventions and workflow requirements. The
`.windsurfrules` file in this repository simply points to `ai-rules-local.md`
for compatibility with Windsurf.
