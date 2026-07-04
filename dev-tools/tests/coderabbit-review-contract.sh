#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/dev-tools/validation/check-coderabbit-review.py"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

cat >"$TMP_DIR/pass.json" <<'JSON'
{
  "data": {
    "repository": {
      "pullRequest": {
        "headRefOid": "abc123",
        "commits": {
          "nodes": [
            {
              "commit": {
                "oid": "abc123",
                "committedDate": "2026-07-03T02:31:07Z"
              }
            }
          ]
        },
        "reviewThreads": {
          "nodes": [
            {
              "isResolved": true,
              "isOutdated": false,
              "path": "file.txt",
              "line": 1,
              "comments": {
                "nodes": []
              }
            }
          ]
        },
        "comments": {
          "nodes": [
            {
              "author": {
                "login": "benhook1013"
              },
              "body": "@coderabbitai review",
              "createdAt": "2026-07-03T02:40:00Z",
              "url": "https://example.test/review"
            },
            {
              "author": {
                "login": "coderabbitai"
              },
              "body": "Review finished.",
              "createdAt": "2026-07-03T02:40:05Z",
              "url": "https://example.test/finished"
            }
          ]
        }
      }
    }
  }
}
JSON

cat >"$TMP_DIR/unresolved-outdated.json" <<'JSON'
{
  "data": {
    "repository": {
      "pullRequest": {
        "headRefOid": "abc123",
        "commits": {
          "nodes": [
            {
              "commit": {
                "oid": "abc123",
                "committedDate": "2026-07-03T02:31:07Z"
              }
            }
          ]
        },
        "reviewThreads": {
          "nodes": [
            {
              "isResolved": false,
              "isOutdated": true,
              "path": "file.txt",
              "line": 1,
              "comments": {
                "nodes": []
              }
            }
          ]
        },
        "comments": {
          "nodes": [
            {
              "author": {
                "login": "benhook1013"
              },
              "body": "@coderabbitai review",
              "createdAt": "2026-07-03T02:40:00Z",
              "url": "https://example.test/review"
            },
            {
              "author": {
                "login": "coderabbitai"
              },
              "body": "Review finished.",
              "createdAt": "2026-07-03T02:40:05Z",
              "url": "https://example.test/finished"
            }
          ]
        }
      }
    }
  }
}
JSON

cat >"$TMP_DIR/stale-review.json" <<'JSON'
{
  "data": {
    "repository": {
      "pullRequest": {
        "headRefOid": "abc123",
        "commits": {
          "nodes": [
            {
              "commit": {
                "oid": "abc123",
                "committedDate": "2026-07-03T02:31:07Z"
              }
            }
          ]
        },
        "reviewThreads": {
          "nodes": []
        },
        "comments": {
          "nodes": [
            {
              "author": {
                "login": "benhook1013"
              },
              "body": "@coderabbitai review",
              "createdAt": "2026-07-03T02:20:00Z",
              "url": "https://example.test/review"
            },
            {
              "author": {
                "login": "coderabbitai"
              },
              "body": "Review finished.",
              "createdAt": "2026-07-03T02:20:05Z",
              "url": "https://example.test/finished"
            }
          ]
        }
      }
    }
  }
}
JSON

pass_output="$(python3 "$SCRIPT" --repo benhook1013/FireMUD --pr 2364 --input "$TMP_DIR/pass.json")"
grep -q "unresolved_non_outdated=0" <<<"$pass_output"
grep -q "unresolved_outdated=0" <<<"$pass_output"
grep -q "explicit_review_after_latest_commit=true" <<<"$pass_output"
grep -q "review_finished_after_latest_request=true" <<<"$pass_output"
grep -q "ok=true" <<<"$pass_output"

set +e
outdated_output="$(python3 "$SCRIPT" --repo benhook1013/FireMUD --pr 2364 --input "$TMP_DIR/unresolved-outdated.json" 2>&1)"
outdated_status=$?
set -e
[[ $outdated_status -ne 0 ]]
grep -q "unresolved_outdated=1" <<<"$outdated_output"
grep -q "reason=1 unresolved outdated CodeRabbit thread(s) remain" <<<"$outdated_output"

set +e
stale_output="$(python3 "$SCRIPT" --repo benhook1013/FireMUD --pr 2364 --input "$TMP_DIR/stale-review.json" 2>&1)"
stale_status=$?
set -e
[[ $stale_status -ne 0 ]]
grep -q "explicit_review_after_latest_commit=false" <<<"$stale_output"
grep -q "reason=no explicit CodeRabbit review request found after the latest PR commit" <<<"$stale_output"

echo "coderabbitai review contract checks passed"
