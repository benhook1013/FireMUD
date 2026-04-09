# AI Observations

Append-only notes for recurring friction, surprising behavior, environment issues, inefficient patterns, and code smells discovered during AI work.

Only keep entries whose lesson still matters after the immediate task is done. Do not use this file as a bug log for ordinary fixes that were completed in the same piece of work.

Entry format:

- `YYYY-MM-DD`: short title
  - Context: where it appeared
  - Observation: what was surprising or wasteful
  - Expected pattern: what should happen instead

- `2026-04-09`: Smoke fixes need full local smoke validation
  - Context: repeated PR smoke failures were patched from CI logs plus targeted service tests, but later smoke runs exposed adjacent readiness and websocket-contract mismatches that a full local smoke run would have surfaced together.
  - Observation: treating smoke breakages like ordinary unit-level regressions leads to under-validation and serial CI churn.
  - Expected pattern: when fixing a smoke-specific failure, run the full local smoke workflow or compose-equivalent stack before pushing, not only targeted tests.

- `2026-04-09`: Docker Compose env interpolation can resolve from the compose directory, not the repo root
  - Context: local smoke runs against `docker/docker-compose.smoke-images.override.yml` ignored `SMOKE_IMAGE_TAG` in the shell and repo-root `.env`, but resolved correctly once the variable was written to `docker/.env`.
  - Observation: assuming shell exports or repo-root `.env` will drive Compose interpolation here wastes time and tokens when the compose files live under `docker/`.
  - Expected pattern: before running local smoke with image-tag overrides, verify `docker compose ... config` and, if needed, write the tag into `docker/.env` so the compose project resolves the expected images.

- 2026-04-09: In this WSL + Docker Desktop environment, long `docker compose build/up --wait` flows can hang or return stale/wrong orchestration outcomes after the real work is done. Prefer direct `docker build`, targeted `docker compose up -d`, and explicit container/log inspection when debugging smoke/runtime failures.
