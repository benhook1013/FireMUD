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

cat >"$TMP_DIR/unresolved-non-outdated.json" <<'JSON'
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

cat >"$TMP_DIR/review-not-finished.json" <<'JSON'
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
              "createdAt": "2026-07-03T02:40:00Z",
              "url": "https://example.test/review"
            }
          ]
        }
      }
    }
  }
}
JSON

EXPECT_FAILURE_STATUS=0

expect_failure_output() {
  local fixture_path="$1"
  local output_path="$2"
  if python3 "$SCRIPT" --repo benhook1013/FireMUD --pr 2364 --input "$fixture_path" >"$output_path" 2>&1; then
    EXPECT_FAILURE_STATUS=0
  else
    EXPECT_FAILURE_STATUS=$?
  fi
}

pass_output="$(python3 "$SCRIPT" --repo benhook1013/FireMUD --pr 2364 --input "$TMP_DIR/pass.json")"
grep -q "unresolved_non_outdated=0" <<<"$pass_output"
grep -q "unresolved_outdated=0" <<<"$pass_output"
grep -q "explicit_review_after_latest_commit=true" <<<"$pass_output"
grep -q "review_finished_after_latest_request=true" <<<"$pass_output"
grep -q "ok=true" <<<"$pass_output"

expect_failure_output "$TMP_DIR/unresolved-outdated.json" "$TMP_DIR/unresolved-outdated.out"
[[ $EXPECT_FAILURE_STATUS -ne 0 ]]
grep -q "unresolved_outdated=1" "$TMP_DIR/unresolved-outdated.out"
grep -q "reason=1 unresolved outdated CodeRabbit thread(s) remain" "$TMP_DIR/unresolved-outdated.out"

expect_failure_output "$TMP_DIR/stale-review.json" "$TMP_DIR/stale-review.out"
[[ $EXPECT_FAILURE_STATUS -ne 0 ]]
grep -q "explicit_review_after_latest_commit=false" "$TMP_DIR/stale-review.out"
grep -q "reason=no explicit CodeRabbit review request found after the latest PR commit" "$TMP_DIR/stale-review.out"

expect_failure_output "$TMP_DIR/unresolved-non-outdated.json" "$TMP_DIR/unresolved-non-outdated.out"
[[ $EXPECT_FAILURE_STATUS -ne 0 ]]
grep -q "unresolved_non_outdated=1" "$TMP_DIR/unresolved-non-outdated.out"
grep -q "reason=1 unresolved non-outdated CodeRabbit thread(s) remain" "$TMP_DIR/unresolved-non-outdated.out"

expect_failure_output "$TMP_DIR/review-not-finished.json" "$TMP_DIR/review-not-finished.out"
[[ $EXPECT_FAILURE_STATUS -ne 0 ]]
grep -q "review_finished_after_latest_request=false" "$TMP_DIR/review-not-finished.out"
grep -q "reason=no completed CodeRabbit review found after the latest explicit review request" "$TMP_DIR/review-not-finished.out"

echo "coderabbitai review contract checks passed"
