#!/usr/bin/env bash
exec "$(dirname "$0")/docs/generate-grpc-docs.sh" "$@"
