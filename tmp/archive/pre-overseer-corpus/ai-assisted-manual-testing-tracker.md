# AI-Assisted Manual Testing Tracker

Temporary human-visible tracker for establishing repeatable AI-assisted testing against FireMUD's hosted pre-v1 environments. This is working material, not canonical architecture or implementation status.

## Status

Discussed and investigated, but the end-to-end testing path has not yet been established.

The first useful milestone is for an AI worker to connect to one current hosted target, complete the player bootstrap/login/`PLAY`/`LOOK` path through a real user-facing transport, and return reproducible evidence tied to the deployed commit.

## Known Hosted Paths

- A PR targeting `main` or `develop` can receive its own eligible `pr-<number>` preview environment.
- The shared dev-demo environment follows `develop` and is reconciled toward the current develop commit.
- Runtime images are published with fixed commit-SHA tags before hosted deployment consumes them.
- Hosted deployment workflows already exercise rollout readiness and a TCP login/`LOOK` smoke path.
- The T3 collaborative browser is the preferred browser surface when the hosted web client needs interactive testing.

## Work To Establish The First Manual Test

1. Select a current hosted target: an eligible active PR preview when testing branch behaviour, or dev-demo when testing the current `develop` state.
2. Resolve the deployed head SHA, HTTPS host, TCP port, and safe demo credentials from the authoritative workflow summary or preview comment.
3. Confirm the environment is actually reconciled to that SHA and that its hosted smoke completed.
4. Exercise the smallest player-facing path:
   - account/bootstrap entry
   - login
   - `PLAY`
   - `LOOK`
5. Capture the target identity, time, expected behaviour, actual transcript or screenshot, and any failure stage.
6. Add one further gameplay behaviour at a time, guided by the existing player playtest and login/session smoke documentation.
7. Establish how test accounts and mutable game state are reset so repeated checks remain safe and reproducible.

## Candidate Tool Surfaces

- T3 collaborative browser for the first-party web interface.
- HTTPS requests for readiness and bootstrap diagnosis.
- Direct TCP/Telnet interaction for classic-client gameplay behaviour.
- WebSocket interaction when browser behaviour needs transport-level confirmation.
- GitHub Actions summaries and logs for deployment identity and failure evidence.

## Evidence Contract

Each reported result should include:

- PR number or `develop` target;
- deployed head SHA;
- environment hostname and transport;
- exact behaviour tested;
- expected and actual result;
- transcript, screenshot, or relevant hosted-workflow evidence;
- confirmed, partial, unavailable, or failed status.

## Relevant Repository Guidance

- `design/developer-workflows/player-playtest-checklist.md`
- `design/developer-workflows/login-session-smoke-tests.md`
- `design/architecture/infrastructure/README.md`
- `design/architecture/infrastructure/deployment-environments.md`
- `.github/workflows/preview.yml`
- `.github/workflows/dev-demo.yml`

## Next Decision

Choose the first active PR preview or dev-demo deployment to use, then perform one read-only connectivity and gameplay pass before designing a larger recurring test matrix.
