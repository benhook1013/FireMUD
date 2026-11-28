#!/usr/bin/env bash
set -euo pipefail

[[ $# == 6 ]] || exit 2
namespace="$1" expected_uid="$2" base="$3" head="$4" merge="$5" image="$6"
[[ "$namespace" =~ ^pr-[1-9][0-9]{0,50}$ &&
   "$expected_uid" =~ ^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$ &&
   "$base" =~ ^[a-f0-9]{40}$ && "$head" =~ ^[a-f0-9]{40}$ && "$merge" =~ ^[a-f0-9]{40}$ &&
   "$image" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] || exit 2

# Caller holds preview-allocation-lifecycle, including proof publication.
snapshot="$(kubectl get namespace "$namespace" --ignore-not-found -o json --request-timeout=10s)"
if [[ -z "$snapshot" ]]; then
  printf '%s\n' namespace-changed
  exit 0
fi

jq -ers --arg namespace "$namespace" --arg uid "$expected_uid" \
  --arg base "$base" --arg head "$head" --arg merge "$merge" --arg image "$image" '
  def tuple($annotations; $prefix):
    ["base-sha", "head-sha", "merge-sha", "image-tag"]
    | map($annotations[("firemud.dev/" + $prefix + "-preview-" + .)]
        | if . == null or type == "string" then . else error("invalid Namespace annotation") end);
  if length != 1 then error("unexpected Namespace response framing") else .[0] end
  | if .apiVersion != "v1" or .kind != "Namespace" or .metadata.name != $namespace
      or (.metadata.uid | type != "string" or length == 0)
      or ((.metadata.annotations // {}) | type != "object")
    then error("invalid Namespace evidence") else . end
  | (.metadata.annotations // {}) as $annotations
  | $annotations["firemud.dev/proof-preview-namespace-uid"] as $proof_uid
  | if $proof_uid == null or ($proof_uid | type == "string") then .
    else error("invalid proof Namespace UID annotation") end
  | [$base, $head, $merge, $image] as $expected
  | if .metadata.uid != $uid or .metadata.deletionTimestamp != null
      or tuple($annotations; "last") != $expected then "namespace-changed"
    elif tuple($annotations; "proof") == $expected
      and $proof_uid == $uid then "proof-complete"
    else "proof-missing" end
' <<<"$snapshot"
