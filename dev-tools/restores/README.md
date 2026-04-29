# Restore Helpers

This directory contains FireMUD restore and state-reset tooling.

The scripts here cover three related lanes:

- local PostgreSQL restore/reset helpers
- Redis/local state restore helpers
- Kubernetes or recovery-adjacent validation helpers

## Script Map

- `restore-db.sh`
  - Restores a PostgreSQL dump created by `dev-tools/backups/backup-db.sh`.

- `restore-latest-db.sh`
  - Downloads the most recent `pg_dump` from object storage and restores it into the local PostgreSQL database.

- `reset-service-db.sh`
  - Drops only the tables owned by one service's migrations plus that service's Flyway history table, then reruns that service's Flyway migrations.
  - Intended for service-scoped local rebuilds when full database wipe/clean is too broad.

- `restore-redis-aof.sh`
  - Replaces the local Coordination Redis persisted data with a provided AOF file and restarts the local Redis service.

- `restore-cluster.sh`
  - Starts a Velero restore and restarts cluster workloads afterward.
  - This is restore bootstrap tooling, not a full traffic-open or post-restore-hardening workflow by itself.

- `validate-external-credentials.sh`
  - Validates environment external-credential evidence after restore before traffic can reopen.

## Choosing The Right Script

- Use `restore-db.sh` or `restore-latest-db.sh` for local PostgreSQL restore flows.
- Use `reset-service-db.sh` for a service-scoped local schema rebuild.
- Use `restore-redis-aof.sh` only for local Redis debugging or reconstruction.
- Use `restore-cluster.sh` and `validate-external-credentials.sh` only when working on recovery or Kubernetes restore flows.

## Related Docs

- [system-architecture-backup-recovery.md](../../design/architecture/system-architecture-backup-recovery.md)
- [system-architecture-post-restore-hardening.md](../../design/architecture/system-architecture-post-restore-hardening.md)
