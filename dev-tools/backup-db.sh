#!/usr/bin/env bash
exec "$(dirname "$0")/backups/backup-db.sh" "$@"
