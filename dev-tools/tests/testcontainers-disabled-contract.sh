#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SAGA_TEST="$ROOT_DIR/services/common-saga/src/test/java/integration/net/firedevops/firemud/common/saga/persistence/SagaPersistenceRepositoryIntegrationTest.java"
IN_PROCESS_TEST="services/tcp-proxy-service/src/test/java/crossservice/net/firedevops/firemud/tcpproxy/TelnetGatewayGameSessionCrossServiceIntegrationTest.java"

grep -Eq '^@Testcontainers\(disabledWithoutDocker = true\)$' "$SAGA_TEST"
if grep -Eq 'POSTGRES\.(start|stop)\(' "$SAGA_TEST"; then
  echo "common-saga repository integration test must use the Testcontainers lifecycle extension" >&2
  exit 1
fi

bare_annotations=()
while IFS= read -r -d '' java_file; do
  if grep -Eq '^[[:space:]]*@Testcontainers[[:space:]]*$' "$java_file"; then
    bare_annotations+=("${java_file#"$ROOT_DIR/"}")
  fi
done < <(find "$ROOT_DIR/services" -type f -name '*.java' -print0)
if ((${#bare_annotations[@]} != 1)) || [[ "${bare_annotations[0]}" != "$IN_PROCESS_TEST" ]]; then
  printf 'unexpected bare @Testcontainers annotation(s):\n' >&2
  printf '  %s\n' "${bare_annotations[@]}" >&2
  exit 1
fi

echo "Testcontainers disabledWithoutDocker contract passed"
