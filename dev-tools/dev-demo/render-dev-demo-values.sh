#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 7 ]]; then
  echo "usage: $0 <template> <output> <namespace> <release_name> <hostname> <image_tag> <telnet_port>" >&2
  exit 1
fi

TEMPLATE_PATH="$1"
OUTPUT_PATH="$2"
NAMESPACE="$3"
RELEASE_NAME="$4"
HOSTNAME="$5"
IMAGE_TAG="$6"
TELNET_PORT="$7"

python3 - "$TEMPLATE_PATH" "$OUTPUT_PATH" "$NAMESPACE" "$RELEASE_NAME" "$HOSTNAME" "$IMAGE_TAG" "$TELNET_PORT" <<'PY'
from pathlib import Path
import sys

template_path = Path(sys.argv[1])
output_path = Path(sys.argv[2])
namespace = sys.argv[3]
release_name = sys.argv[4]
hostname = sys.argv[5]
image_tag = sys.argv[6]
telnet_port = sys.argv[7]

text = template_path.read_text()
text = text.replace("namespace: dev", f"namespace: {namespace}")
text = text.replace("releaseName: dev", f"releaseName: {release_name}")
text = text.replace("hostname: dev.preview.firedevops.net", f"hostname: {hostname}")
text = text.replace("telnetPort: 32016", f"telnetPort: {telnet_port}")
text = text.replace("defaultImageTag: develop-deadbeef", f"defaultImageTag: {image_tag}")
text = text.replace(
    "tlsSecretName: dev-preview-firedevops-net-tls",
    f"tlsSecretName: {release_name}-tls",
)
output_path.write_text(text)
PY
