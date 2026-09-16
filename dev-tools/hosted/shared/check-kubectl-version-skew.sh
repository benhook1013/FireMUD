#!/usr/bin/env bash
set -euo pipefail

command -v kubectl >/dev/null 2>&1 || {
  echo "kubectl is required for client/server version skew validation" >&2
  exit 1
}
command -v python3 >/dev/null 2>&1 || {
  echo "python3 is required for client/server version skew validation" >&2
  exit 1
}

version_json="$(kubectl version --output=json)"
read -r client_major client_minor server_major server_minor client_version server_version < <(
  python3 -c '
import json
import re
import sys

data = json.load(sys.stdin)
client = data.get("clientVersion", {}).get("gitVersion", "")
server = data.get("serverVersion", {}).get("gitVersion", "")
pattern = re.compile(r"^v(?P<major>[0-9]+)\.(?P<minor>[0-9]+)(?:\.|$)")
client_match = pattern.match(client)
server_match = pattern.match(server)
if client_match is None or server_match is None:
    raise SystemExit("kubectl version output lacks parseable client and server gitVersion values")
print(
    client_match.group("major"),
    client_match.group("minor"),
    server_match.group("major"),
    server_match.group("minor"),
    client,
    server,
)
' <<<"$version_json"
)

if [[ "$client_major" != "$server_major" ]]; then
  echo "kubectl client/server major version skew is unsupported: client=${client_version}, server=${server_version}" >&2
  exit 1
fi

minor_delta=$((client_minor - server_minor))
if (( minor_delta < -1 || minor_delta > 1 )); then
  echo "kubectl client/server minor skew is unsupported: client=${client_version}, server=${server_version}" >&2
  exit 1
fi

echo "kubectl client/server version skew is supported: client=${client_version}, server=${server_version}"
