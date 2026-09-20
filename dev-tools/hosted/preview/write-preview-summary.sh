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
exposure_mode="${PREVIEW_EXPOSURE_MODE:-public}"

if [[ "$exposure_mode" != private && "$exposure_mode" != public ]]; then
  echo "PREVIEW_EXPOSURE_MODE must be private or public" >&2
  exit 1
fi

private_bridge_line() {
  local state="$1"
  case "$state" in
    pending) echo "- TCP: private Gateway ↔ TCP Proxy bridge pending" ;;
    ready) echo "- TCP: private Gateway ↔ TCP Proxy bridge verified (no public Telnet)" ;;
    unavailable) echo "- TCP: private Gateway ↔ TCP Proxy bridge unavailable (no public Telnet)" ;;
    *) echo "- TCP: private Gateway ↔ TCP Proxy bridge ${state} (no public Telnet)" ;;
  esac
}

public_tcp_line() {
  local state="$1"
  case "$state" in
    pending) echo "- TCP: pending" ;;
    target) echo "- TCP: \`${hostname} ${telnet_port}\`" ;;
    unavailable) echo "- TCP: \`telnet ${hostname} ${telnet_port}\`" ;;
    ready) echo "- TCP: \`telnet ${hostname} ${telnet_port}\`" ;;
    *) echo "- TCP: \`${state}\`" ;;
  esac
}

case "$mode" in
  deploying)
    cat <<EOF
## ⏳ Preview Deploying

- PR: #${pr_number}
- Head SHA: \`${head_sha}\`
- Image tag: \`${image_tag}\`
- Web: pending
EOF
    if [[ "$exposure_mode" == private ]]; then
      private_bridge_line pending
    else
      public_tcp_line pending
    fi
    ;;
  target)
    cat <<EOF
## ⏳ Preview Target

- PR: #${pr_number}
- Head SHA: \`${head_sha}\`
- Image tag: \`${image_tag}\`
- Web: https://${hostname}
EOF
    if [[ "$exposure_mode" == private ]]; then
      private_bridge_line pending
    else
      public_tcp_line target
    fi
    ;;
  unavailable)
    cat <<EOF
## ⚠️ Preview Unavailable

- PR: #${pr_number}
- Head SHA: \`${head_sha}\`
- Image tag: \`${image_tag}\`
- Web: https://${hostname}
EOF
    if [[ "$exposure_mode" == private ]]; then
      private_bridge_line unavailable
    else
      public_tcp_line unavailable
    fi
    echo "- Unavailable stage: \`${failure_stage:-cluster-access}\`"
    ;;
  success)
    cat <<EOF
## ✅ Preview Ready

- PR: #${pr_number}
- Head SHA: \`${head_sha}\`
- Image tag: \`${image_tag}\`
- Web: https://${hostname}
EOF
    if [[ "$exposure_mode" == private ]]; then
      private_bridge_line ready
    else
      public_tcp_line ready
    fi
    ;;
  reclaimed)
    cat <<EOF
## ⚠️ Preview Slot Reassigned

- PR: #${pr_number}
- Current hosted environment: unavailable
- Reassigned to priority PR: #${failure_stage}
- Previous head SHA: \`${head_sha:-unavailable}\`
- Previous image tag: \`${image_tag:-unavailable}\`
- Previous host: https://${hostname}
- Historical proof: retained below when a prior workflow summary was available
EOF
    ;;
  cleanup)
    cat <<EOF
## ⏳ Preview Cleanup In Progress

- PR: #${pr_number}
- Head SHA: \`${head_sha}\`
- Previous host: https://${hostname}
EOF
    if [[ "$exposure_mode" == private ]]; then
      private_bridge_line unavailable
    else
      echo "- TCP: unavailable"
    fi
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
EOF
    if [[ "$exposure_mode" == private ]]; then
      private_bridge_line unavailable
    else
      public_tcp_line unavailable
    fi
    echo "- Failed stage: \`${failure_stage:-unknown}\`"
    ;;
  *)
    echo "unknown mode: ${mode}" >&2
    exit 1
    ;;
esac
