# Backup Helpers

This directory contains FireMUD backup tooling for three different lanes:

- local ad hoc PostgreSQL dumps
- legacy gameplay-pause maintenance experiments
- Kubernetes backup setup and verification

## Script Map

- `backup-db.sh`
  - Creates a local PostgreSQL logical dump with `pg_dump`.
  - Uses `FIREMUD_POSTGRES_HOST`, `FIREMUD_POSTGRES_USER`, and `FIREMUD_POSTGRES_DB`.
  - Accepts an optional output directory argument and defaults to a local `backups/` folder.

- `firemud-backup.sh`
  - Pauses gameplay ticks through Game Session, waits for pause confirmation, runs `pg_dump`, then resumes ticks.
  - Legacy/maintenance-only helper; its process-local pause path, missing bounded cleanup, and non-environment-wide scope do not satisfy routine backup or player-facing recovery readiness.
  - Uses `grpcurl` plus the same PostgreSQL environment variables as `backup-db.sh`.

- `pg-dump-rotate.sh`
  - Creates rolling PostgreSQL dumps for the scheduled `pg-dump-cron` lane.
  - Maintains 15-minute, daily, weekly, and monthly retention folders.
  - Optionally uploads dumps to S3 or MinIO when `PG_DUMP_BUCKET` is configured.
  - This script is built into the `pg-dump-cron` Docker image.

- `setup-local-backup.sh`
  - Bootstraps MinIO and Velero for local Kubernetes backup testing.
  - Intended for local cluster/operator workflows, not the normal Docker Compose lane.

- `verify-backups.sh`
  - Verifies that Velero backups exist and that optional pg-dump object storage is reachable.
  - Used by the manual backup/restore workflow and operational verification lanes.

## Choosing The Right Script

- Use `backup-db.sh` for a quick local PostgreSQL snapshot before a restore or experiment.
- Do not use `firemud-backup.sh` as routine backup or readiness evidence. It may be used only for explicitly quarantined maintenance experiments while its limitations are recorded.
- The scheduled `pg-dump-rotate.sh` lane is the routine online-backup direction; it still needs complete lineage and restore-readability proof before player-facing readiness.
- Use `setup-local-backup.sh` and `verify-backups.sh` for Kubernetes backup drills and backup verification.
- Do not run `pg-dump-rotate.sh` manually unless you are intentionally testing the scheduled dump lane.

## Related Docs

- [system-architecture-backup-recovery.md](../../design/architecture/system-architecture-backup-recovery.md)
- [docker/README.md](../../docker/README.md)
