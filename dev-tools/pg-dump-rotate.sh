#!/usr/bin/env bash
exec "$(dirname "$0")/backups/pg-dump-rotate.sh" "$@"
