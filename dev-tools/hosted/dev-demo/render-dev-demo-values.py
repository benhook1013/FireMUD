#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import json
import secrets
import sys
from pathlib import Path


USAGE = (
    "usage: render-dev-demo-values.py <template> <output> <namespace> "
    "<release_name> <hostname> <image_tag> <telnet_port>"
)


def replace_or_die(text: str, target: str, replacement: str) -> str:
    if target not in text:
        raise ValueError(f"expected template token or text not found: {target}")
    return text.replace(target, replacement)


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

    seed = f"{namespace}:{release_name}:dev-demo:{image_tag}"
    signing_key = hashlib.sha256((seed + ":" + secrets.token_hex(32)).encode("utf-8")).hexdigest()
    jwks_json = json.dumps(
        {
            "keys": [
                {
                    "kty": "oct",
                    "kid": f"{release_name}-dev-demo",
                    "k": hashlib.sha256(signing_key.encode("utf-8")).hexdigest(),
                    "alg": "HS256",
                    "use": "sig",
                }
            ]
        },
        separators=(",", ":"),
    )

    text = template_path.read_text(encoding="utf-8")
    replacements = {
        "__PR_NUMBER__": "0",
        "__NAMESPACE__": namespace,
        "__RELEASE_NAME__": release_name,
        "__HOSTNAME__": hostname,
        "__TELNET_PORT__": telnet_port,
        "__IMAGE_TAG__": image_tag,
        "__TLS_SECRET_NAME__": f"{release_name}-tls",
        "__JWT_SIGNING_KEY__": signing_key,
        "__JWKS_JSON__": jwks_json,
        "__SEED_GAME_NAME__": "Dev Demo Game",
        "__SEED_GAME_DESCRIPTION__": "Dev-demo bootstrap seed game.",
        "__SEED_VERSION_NOTES__": "Dev-demo seed version",
        "__SEED_TEMPLATE_NAME__": "Dev Demo Template",
        "__SEED_TEMPLATE_DESCRIPTION__": "Dev-demo bootstrap seed template.",
        "__SEED_WORKFLOW_ID__": "dev-demo-seed",
        "__SEED_MANIFEST_HASH__": "dev-demo-seed-manifest",
        "__SEED_GENERATION_CONFIG_REVISION__": "genrev:dev-demo-seed",
    }
    for target, replacement in replacements.items():
        text = replace_or_die(text, target, replacement)
    text = replace_or_die(
        text,
        "        # __TCP_PROXY_GATEWAY_BASE_URL_LINE__",
        "        TCP_PROXY_GATEWAY_BASE_URL: http://spring-cloud-gateway",
    )
    text = replace_or_die(
        text,
        "        # __TCP_PROXY_ADDITIONAL_SERVICE_PORTS__",
        "        - port: 8080\n          targetPort: 8080",
    )
    output_path.write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
