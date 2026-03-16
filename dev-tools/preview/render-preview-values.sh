#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 7 ]]; then
  echo "usage: $0 <template> <output> <pr_number> <namespace> <release_name> <hostname> <image_tag>" >&2
  exit 1
fi

TEMPLATE_PATH="$1"
OUTPUT_PATH="$2"
PR_NUMBER="$3"
NAMESPACE="$4"
RELEASE_NAME="$5"
HOSTNAME="$6"
IMAGE_TAG="$7"

python3 - "$TEMPLATE_PATH" "$OUTPUT_PATH" "$PR_NUMBER" "$NAMESPACE" "$RELEASE_NAME" "$HOSTNAME" "$IMAGE_TAG" <<'PY'
from pathlib import Path
import sys

template_path = Path(sys.argv[1])
output_path = Path(sys.argv[2])
pr_number = sys.argv[3]
namespace = sys.argv[4]
release_name = sys.argv[5]
hostname = sys.argv[6]
image_tag = sys.argv[7]

text = template_path.read_text()
text = text.replace("prNumber: 123", f"prNumber: {pr_number}")
text = text.replace("namespace: pr-123", f"namespace: {namespace}")
text = text.replace("releaseName: pr-123", f"releaseName: {release_name}")
text = text.replace("hostname: pr-123.preview.firedevops.net", f"hostname: {hostname}")
text = text.replace("defaultImageTag: pr-123-deadbeef", f"defaultImageTag: {image_tag}")
text = text.replace(
    "tlsSecretName: pr-123-preview-firedevops-net-tls",
    f"tlsSecretName: {release_name}-tls",
)
text = text.replace(
    "GATEWAY_WS_URL: wss://pr-123.preview.firedevops.net/ws/game",
    f"GATEWAY_WS_URL: wss://{hostname}/ws/game",
)
output_path.write_text(text)
PY
