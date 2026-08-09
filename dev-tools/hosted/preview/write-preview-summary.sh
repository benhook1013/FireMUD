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
  deploying)
    cat <<EOF
## ⏳ Preview Deploying

- PR: #${pr_number}
- Head SHA: \`${head_sha}\`
- Image tag: \`${image_tag}\`
- Web: https://${hostname}
- TCP: \`${hostname} ${telnet_port}\`
EOF
    ;;
  target)
    cat <<EOF
## ⏳ Preview Target

- PR: #${pr_number}
- Head SHA: \`${head_sha}\`
- Image tag: \`${image_tag}\`
- Web: https://${hostname}
- TCP: \`${hostname} ${telnet_port}\`
EOF
    ;;
  unavailable)
    cat <<EOF
## ⚠️ Preview Unavailable

- PR: #${pr_number}
- Head SHA: \`${head_sha}\`
- Image tag: \`${image_tag}\`
- Web: https://${hostname}
- TCP: \`telnet ${hostname} ${telnet_port}\`
- Unavailable stage: \`${failure_stage:-cluster-access}\`
EOF
    ;;
  success)
    cat <<EOF
## ✅ Preview Ready

- PR: #${pr_number}
- Head SHA: \`${head_sha}\`
- Image tag: \`${image_tag}\`
- Web: https://${hostname}
- TCP: \`telnet ${hostname} ${telnet_port}\`
EOF
    ;;
  cleanup)
    cat <<EOF
## ⏳ Preview Cleanup In Progress

- PR: #${pr_number}
- Head SHA: \`${head_sha}\`
- Previous host: https://${hostname}
- TCP: unavailable
EOF
    ;;
  removed)
    cat <<EOF
## ✅ Preview Removed

- PR: #${pr_number}
- Head SHA: \`${head_sha}\`
- Previous host: https://${hostname}
- TCP: unavailable
EOF
    ;;
  failure)
    cat <<EOF
## ❌ Preview Failed

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
