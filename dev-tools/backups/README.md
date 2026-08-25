# Backup Helpers

This directory contains FireMUD backup tooling for three different lanes:

- local ad hoc PostgreSQL dumps
- scheduled rolling PostgreSQL dumps
- Kubernetes backup setup and verification

## Script Map

- `backup-db.sh`
  - Creates a local PostgreSQL custom-format logical dump with `pg_dump -Fc`.
  - This ad hoc `.dump` lane is paired only with `dev-tools/restores/restore-db.sh` and `pg_restore`; it is separate from the hosted scheduled artifact lane.
  - Uses `FIREMUD_POSTGRES_HOST`, `FIREMUD_POSTGRES_USER`, and `FIREMUD_POSTGRES_DB`.
  - Accepts an optional output directory argument and defaults to a local `backups/` folder.

- `pg-dump-rotate.sh`
  - Creates rolling plain-SQL PostgreSQL dumps (`pg_dump -Fp | gzip`) for the scheduled `pg-dump-cron` lane.
  - The hosted readiness artifact contract is immutable `.sql.gz` content once published; its scheduled restore consumer is `dev-tools/restores/restore-latest-db.sh`, which uses `gunzip -c | psql`.
  - Maintains 15-minute, daily, weekly, and monthly retention folders.
  - Optionally uploads dumps to S3 or MinIO when `PG_DUMP_BUCKET` is configured.
  - This script is built into the `pg-dump-cron` Docker image.

- `setup-local-backup.sh`
  - Bootstraps MinIO and Velero for local Kubernetes backup testing.
  - Intended for local cluster/operator workflows, not the normal Docker Compose lane.

- `verify-backups.sh`
  - Checks that Velero backups exist and that optional pg-dump object storage is reachable.
  - It does not prove immutable lineage, artifact readability, restore-tool compatibility, or player-facing readiness.
  - Used by the manual backup/restore workflow as existence/reachability evidence only.

## Choosing The Right Script

- Use `backup-db.sh` for a quick local PostgreSQL snapshot before a restore or experiment.
- The scheduled `pg-dump-rotate.sh` lane is the routine online-backup direction; it does not pause or resume gameplay and still needs separate immutable lineage, artifact-readability, restore-tool, erasure-replay, and controlled-reopen proof before player-facing readiness. Do not use the local custom-format `.dump`/`pg_restore` pair as hosted readiness evidence.
- Use `setup-local-backup.sh` and `verify-backups.sh` for Kubernetes backup drills and backup verification.
- Do not run `pg-dump-rotate.sh` manually unless you are intentionally testing the scheduled dump lane.

## Related Docs

- [system-architecture-backup-recovery.md](../../design/architecture/system-architecture-backup-recovery.md)
- [docker/README.md](../../docker/README.md)
