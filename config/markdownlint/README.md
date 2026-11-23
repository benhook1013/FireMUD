# Markdownlint Configuration

Run Markdown linting from the repository root via the canonical local Gradle wrapper:

```bash
bash dev-tools/validation/run-locked-gradle.sh lintMarkdown
```

To apply correctable fixes explicitly:

```bash
bash dev-tools/validation/run-locked-gradle.sh lintMarkdownFix
```

Both tasks depend on `installMarkdownLint`, which installs the development dependencies from the checked-in [npm lockfile](../openapi/package-lock.json) in [`config/openapi`](../openapi/package.json) with lifecycle scripts disabled. Update that manifest and regenerate that lock together. Gradle invokes the installed package's `markdownlint-cli2-bin.mjs` directly with the repository-selected Node runtime.

Lint execution stays rooted at the repository root, uses [the repository configuration](./.markdownlint-cli2.jsonc), and passes the literal `**/*.md` glob to the CLI. `lintMarkdown` remains non-fixing and is a dependency of `check`; only `lintMarkdownFix` adds `--fix`.
