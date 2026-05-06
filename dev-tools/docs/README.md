# Documentation Tooling

This directory contains FireMUD's documentation-generation and documentation-validation helpers.

The scripts here serve three different lanes:

- documentation site assembly
- generated reference-doc publication
- Markdown/link validation

## Script Map

- `build_pages_site.py`
  - Builds the staged documentation tree under `build/pages-docs/` for MkDocs and GitHub Pages.
  - Copies selected root docs plus the `design/` tree while excluding generated and build-only paths.
  - Used by the docs-site workflow rather than as a general authoring command.

- `generate-erd.sh`
  - Creates service ERD artifacts under `design/erd/` by running Flyway migrations against a temporary PostgreSQL container and then rendering diagrams with SchemaCrawler.
  - Used for database-reference artifact generation, not ordinary docs linting.

- `generate-grpc-docs.sh`
  - Regenerates `design/grpc-docs/grpc-api.md` from the checked-in `.proto` definitions.
  - Use this after changing proto contracts.

- `generate-platform-settings-docs.py`
  - Generates the checked-in platform settings schema and reference docs from Spring metadata plus the publication spec.
  - The normal entrypoint is `./gradlew updatePlatformSettingsDocs` or the matching check task, not calling the Python script directly unless you are debugging the generator.

- `link-check.sh`
  - Runs the repository documentation link checker with Lychee.
  - This is the script behind the Gradle `linkCheck` task and docs-link validation workflows.

## Choosing The Right Tool

- Use `generate-grpc-docs.sh` after changing protobuf contracts.
- Use `./gradlew updatePlatformSettingsDocs` after changing surfaced platform settings metadata or publication rules.
- Use `./gradlew linkCheck lintMarkdown` for normal Markdown/docs hygiene instead of calling `link-check.sh` directly.
- Treat `build_pages_site.py` and `generate-erd.sh` as workflow/support tooling unless you are intentionally working on the docs-site or ERD generation lanes.

## Related Docs

- [design/README.md](../../design/README.md)
- [mkdocs.yml](../../mkdocs.yml)
