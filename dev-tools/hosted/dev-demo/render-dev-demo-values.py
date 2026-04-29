#!/usr/bin/env python3

from __future__ import annotations

import sys
from pathlib import Path


USAGE = (
    "usage: render-dev-demo-values.py <template> <output> <namespace> "
    "<release_name> <hostname> <image_tag> <telnet_port>"
)


def main() -> int:
    if len(sys.argv) != 8:
        print(USAGE, file=sys.stderr)
        return 1

    template_path = Path(sys.argv[1])
    output_path = Path(sys.argv[2])
    namespace = sys.argv[3]
    release_name = sys.argv[4]
    hostname = sys.argv[5]
    image_tag = sys.argv[6]
    telnet_port = sys.argv[7]

    text = template_path.read_text(encoding="utf-8")
    text = text.replace("namespace: dev", f"namespace: {namespace}")
    text = text.replace("releaseName: dev", f"releaseName: {release_name}")
    text = text.replace("hostname: dev.preview.firedevops.net", f"hostname: {hostname}")
    text = text.replace("telnetPort: 32016", f"telnetPort: {telnet_port}")
    text = text.replace("defaultImageTag: develop-deadbeef", f"defaultImageTag: {image_tag}")
    text = text.replace(
        "tlsSecretName: dev-preview-firedevops-net-tls",
        f"tlsSecretName: {release_name}-tls",
    )
    output_path.write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
