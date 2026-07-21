#!/usr/bin/env bash
# Pause ticks, wait until paused, run pg_dump, then resume ticks.
set -euo pipefail

ADDR=${FIREMUD_GAME_SESSION_GRPC_ADDR:-localhost:9090}
REASON=${1:-"backup"}

grpcurl -plaintext -d '{"reason":"'"$REASON"'"}' "$ADDR" game_session.v1.GameSessionService/PauseTicks

until grpcurl -plaintext -d '{}' "$ADDR" game_session.v1.GameSessionService/GetTickStatus | grep -q 'PAUSED'; do
  sleep 1
done

pg_dump -h "${FIREMUD_POSTGRES_HOST}" -U "${FIREMUD_POSTGRES_USER}" -d "${FIREMUD_POSTGRES_DB}" | gzip > "firemud_$(date +%Y%m%d%H%M%S).sql.gz"

grpcurl -plaintext -d '{"reason":"'"$REASON"'"}' "$ADDR" game_session.v1.GameSessionService/ResumeTicks

