#!/usr/bin/env bash
exec "$(dirname "$0")/restores/restore-latest-db.sh" "$@"
