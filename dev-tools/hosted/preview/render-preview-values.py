#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import json
import secrets
import sys
from pathlib import Path


USAGE = (
    "usage: render-preview-values.py <template> <output> <pr_number> "
    "<namespace> <release_name> <hostname> <image_tag> <telnet_port>"
)


def main() -> int:
    if len(sys.argv) != 9:
        print(USAGE, file=sys.stderr)
        return 1

    template_path = Path(sys.argv[1])
    output_path = Path(sys.argv[2])
    pr_number = sys.argv[3]
    namespace = sys.argv[4]
    release_name = sys.argv[5]
    hostname = sys.argv[6]
    image_tag = sys.argv[7]
    telnet_port = sys.argv[8]

    seed = f"{namespace}:{release_name}:{pr_number}:{image_tag}"
    signing_key = hashlib.sha256((seed + ":" + secrets.token_hex(32)).encode("utf-8")).hexdigest()
    jwks_json = json.dumps(
        {
            "keys": [
                {
                    "kty": "oct",
                    "kid": f"{release_name}-preview",
                    "k": hashlib.sha256(signing_key.encode("utf-8")).hexdigest(),
                    "alg": "HS256",
                    "use": "sig",
                }
            ]
        },
        separators=(",", ":"),
    )

    text = template_path.read_text(encoding="utf-8")
    text = text.replace("prNumber: 123", f"prNumber: {pr_number}")
    text = text.replace("namespace: pr-123", f"namespace: {namespace}")
    text = text.replace("releaseName: pr-123", f"releaseName: {release_name}")
    text = text.replace("hostname: pr-123.preview.firedevops.net", f"hostname: {hostname}")
    text = text.replace("telnetPort: 32000", f"telnetPort: {telnet_port}")
    text = text.replace("defaultImageTag: pr-123-deadbeef", f"defaultImageTag: {image_tag}")
    text = text.replace("signingKey: changeit-changeit-changeit-changeit", f"signingKey: {signing_key}")
    text = text.replace("jwksJson: '{\"keys\":[]}'", "jwksJson: '" + jwks_json + "'")
    text = text.replace(
        "tlsSecretName: pr-123-preview-firedevops-net-tls",
        f"tlsSecretName: {release_name}-tls",
    )
    text = text.replace("nodePort: 32000", f"nodePort: {telnet_port}")
    output_path.write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
