#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 6 ]]; then
  echo "usage: $0 <mode> <pr_number> <head_sha> <image_tag> <hostname> <telnet_port> [failure_stage]" >&2
  exit 1
fi

mode="$1"
pr_number="$2"
head_sha="$3"
image_tag="$4"
hostname="$5"
telnet_port="$6"
failure_stage="${7:-}"

case "$mode" in
  target)
    cat <<EOF
## Preview Target

- PR: #${pr_number}
- Head SHA: \`${head_sha}\`
- Image tag: \`${image_tag}\`
- Web: https://${hostname}
- TCP: \`${hostname} ${telnet_port}\`
EOF
    ;;
  success)
    cat <<EOF
## Preview Ready

- PR: #${pr_number}
- Head SHA: \`${head_sha}\`
- Image tag: \`${image_tag}\`
- Web: https://${hostname}
- TCP: \`telnet ${hostname} ${telnet_port}\`
EOF
    ;;
  failure)
    cat <<EOF
## Preview Failed

- PR: #${pr_number}
- Head SHA: \`${head_sha}\`
- Image tag: \`${image_tag}\`
- Web: https://${hostname}
- TCP: \`telnet ${hostname} ${telnet_port}\`
- Failed stage: \`${failure_stage:-unknown}\`
EOF
    ;;
  *)
    echo "unknown mode: ${mode}" >&2
    exit 1
    ;;
esac
