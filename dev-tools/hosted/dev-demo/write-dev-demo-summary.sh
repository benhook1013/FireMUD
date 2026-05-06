#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 5 ]]; then
  echo "usage: $0 <mode> <head_sha> <image_tag> <hostname> <telnet_port> [failure_stage]" >&2
  exit 1
fi

mode="$1"
head_sha="$2"
image_tag="$3"
hostname="$4"
telnet_port="$5"
failure_stage="${6:-}"

case "$mode" in
  target)
    cat <<EOF
## 🔵 Dev Demo Target

- Branch: \`develop\`
- Head SHA: \`${head_sha}\`
- Image tag: \`${image_tag}\`
- Web: https://${hostname}
- TCP: \`telnet ${hostname} ${telnet_port}\`
EOF
    ;;
  success)
    cat <<EOF
## 🟢 Dev Demo Ready

- Branch: \`develop\`
- Head SHA: \`${head_sha}\`
- Image tag: \`${image_tag}\`
- Web: https://${hostname}
- TCP: \`telnet ${hostname} ${telnet_port}\`
EOF
    ;;
  failure)
    cat <<EOF
## 🔴 Dev Demo Failed

- Branch: \`develop\`
- Head SHA: \`${head_sha}\`
- Image tag: \`${image_tag}\`
- Web: https://${hostname}
- TCP: \`telnet ${hostname} ${telnet_port}\`
- Failed stage: \`${failure_stage:-unknown}\`
EOF
    ;;
  destroyed)
    cat <<EOF
## ⚪ Dev Demo Destroyed

- Branch: \`develop\`
- Head SHA: \`${head_sha}\`
- Previous host: https://${hostname}
- TCP: unavailable
EOF
    ;;
  *)
    echo "unknown mode: ${mode}" >&2
    exit 1
    ;;
esac
