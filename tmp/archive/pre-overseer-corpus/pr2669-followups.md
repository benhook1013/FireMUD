# PR 2669 follow-ups

Recorded 2026-09-01 during the Shared Runtime 5B spillover review. These are
confirmed pre-existing implementation/tooling defects and are intentionally
excluded from documentation PR #2669.

- `dev-tools/restores/restore-redis-aof.sh` computes `ROOT_DIR` as
  `dev-tools/`, so documented invocations look for the nonexistent
  `dev-tools/docker/docker-compose.yml` and fail before Docker execution.
- The same helper cannot restore Redis 7 multipart AOF backups: it deletes the
  target data and copies one supplied file to legacy `appendonly.aof`, without
  the manifest and base/increment files.
- `charts/firemud/templates/redis-aof-reset-job.yaml` is default-off and not the
  hosted deployment path, but enabling `resetAofOnHelmUpgrade=true` emits a
  destructive pre-install/pre-upgrade wipe that bypasses the canonical
  pause/fence/maintenance-lock/pre-wipe evidence boundary. It needs removal or
  hard gating in a separate implementation/reliability slice.

Independent Luna adjudication calibrated the helper defects as P2/P3 and the
latent chart wipe as P2. Do not widen PR #2669 to fix them.
