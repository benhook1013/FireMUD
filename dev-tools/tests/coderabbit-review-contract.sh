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
              "body": "<!-- walkthrough_start -->",
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

cat >"$TMP_DIR/submitted-review.json" <<'JSON'
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
                "committedDate": "2026-07-19T05:30:00Z"
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
              "createdAt": "2026-07-19T05:45:00Z",
              "url": "https://example.test/review"
            },
            {
              "author": {
                "login": "coderabbitai"
              },
              "body": "<!-- walkthrough_start -->",
              "createdAt": "2026-07-17T14:16:20Z",
              "url": "https://example.test/retained-summary"
            },
            {
              "author": {
                "login": "coderabbitai"
              },
              "body": "**Actionable comments posted: 1**\n\n<summary>Duplicate comments (1)</summary>",
              "createdAt": "2026-07-19T05:50:00Z",
              "url": "https://example.test/old-actionable-summary"
            }
          ]
        },
        "reviews": {
          "nodes": [
            {
              "author": {
                "login": "coderabbitai"
              },
              "body": "**Actionable comments posted: 1**\n\n<summary>♻️ Duplicate comments (4)</summary>",
              "submittedAt": "2026-07-19T06:02:35Z",
              "url": "https://example.test/submitted-review"
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
              "body": "<!-- walkthrough_start -->",
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
              "body": "<!-- walkthrough_start -->",
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
              "body": "<!-- walkthrough_start -->",
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

cat >"$TMP_DIR/review-command-noop.json" <<'JSON'
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
            },
            {
              "author": {
                "login": "coderabbitai"
              },
              "body": "Review finished. Note: CodeRabbit does not re-review already reviewed commits.",
              "createdAt": "2026-07-03T02:40:05Z",
              "url": "https://example.test/noop"
            }
          ]
        }
      }
    }
  }
}
JSON

cat >"$TMP_DIR/review-rate-limited.json" <<'JSON'
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
            },
            {
              "author": {
                "login": "coderabbitai"
              },
              "body": "<!-- This is an auto-generated comment: rate limited by coderabbit.ai -->",
              "createdAt": "2026-07-03T02:40:05Z",
              "url": "https://example.test/rate-limited"
            }
          ]
        }
      }
    }
  }
}
JSON

cat >"$TMP_DIR/automatic-review-rate-limited.json" <<'JSON'
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
                "committedDate": "2099-07-03T02:31:07Z"
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
                "login": "coderabbitai"
              },
              "body": "<!-- This is an auto-generated comment: rate limited by coderabbit.ai -->",
              "createdAt": "2099-07-03T02:40:05Z",
              "url": "https://example.test/automatic-rate-limited"
            }
          ]
        }
      }
    }
  }
}
JSON

cat >"$TMP_DIR/review-rate-limit-expired.json" <<'JSON'
{
  "data": {
    "repository": {
      "pullRequest": {
        "headRefOid": "abc123",
        "commits": {"nodes": [{"commit": {"oid": "abc123", "committedDate": "2026-07-03T02:31:07Z"}}]},
        "reviewThreads": {"nodes": []},
        "comments": {"nodes": [
          {"author": {"login": "benhook1013"}, "body": "@coderabbitai review", "createdAt": "2026-07-03T02:40:00Z", "url": "https://example.test/review"},
          {"author": {"login": "coderabbitai"}, "body": "More reviews will be available in 1 minute.", "createdAt": "2026-07-03T02:40:05Z", "url": "https://example.test/rate-limited"}
        ]}
      }
    }
  }
}
JSON

cat >"$TMP_DIR/old-rate-limit-before-later-request.json" <<'JSON'
{
  "data": {
    "repository": {
      "pullRequest": {
        "headRefOid": "abc123",
        "commits": {"nodes": [{"commit": {"oid": "abc123", "committedDate": "2099-07-03T02:31:07Z"}}]},
        "reviewThreads": {"nodes": []},
        "comments": {"nodes": [
          {"author": {"login": "coderabbitai"}, "body": "More reviews will be available in 57 minutes.", "createdAt": "2099-07-03T02:20:05Z", "url": "https://example.test/old-rate-limited"},
          {"author": {"login": "benhook1013"}, "body": "@coderabbitai full review", "createdAt": "2099-07-03T02:40:00Z", "url": "https://example.test/full-review"}
        ]}
      }
    }
  }
}
JSON

cat >"$TMP_DIR/review-full-command-rate-limited.json" <<'JSON'
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
                "committedDate": "2099-07-03T02:31:07Z"
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
              "body": "@coderabbitai full review",
              "createdAt": "2099-07-03T02:40:00Z",
              "url": "https://example.test/full-review"
            },
            {
              "author": {
                "login": "coderabbitai"
              },
              "body": "Full review finished. More reviews will be available in 57 minutes.",
              "createdAt": "2099-07-03T02:40:05Z",
              "url": "https://example.test/full-review-rate-limited"
            }
          ]
        }
      }
    }
  }
}
JSON

cat >"$TMP_DIR/review-next-window-hours.json" <<'JSON'
{
  "data": {
    "repository": {
      "pullRequest": {
        "headRefOid": "abc123",
        "commits": {"nodes": [{"commit": {"oid": "abc123", "committedDate": "2099-07-03T02:31:07Z"}}]},
        "reviewThreads": {"nodes": []},
        "comments": {"nodes": [
          {"author": {"login": "benhook1013"}, "body": "@coderabbitai full review", "createdAt": "2099-07-03T02:40:00Z", "url": "https://example.test/full-review"},
          {"author": {"login": "coderabbitai"}, "body": "Next review available in: **2 hours**", "createdAt": "2099-07-03T02:40:05Z", "url": "https://example.test/next-review-window"}
        ]}
      }
    }
  }
}
JSON

cat >"$TMP_DIR/superseded-review-outcome.json" <<'JSON'
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
            },
            {
              "author": {
                "login": "coderabbitai"
              },
              "body": "<!-- This is an auto-generated comment: rate limited by coderabbit.ai -->",
              "createdAt": "2026-07-03T02:40:01Z",
              "url": "https://example.test/rate-limited"
            },
            {
              "author": {
                "login": "coderabbitai"
              },
              "body": "Review finished. Note: CodeRabbit does not re-review already reviewed commits.",
              "createdAt": "2026-07-03T02:40:02Z",
              "url": "https://example.test/noop"
            },
            {
              "author": {
                "login": "coderabbitai"
              },
              "body": "<!-- walkthrough_start -->",
              "createdAt": "2026-07-03T02:40:03Z",
              "url": "https://example.test/walkthrough"
            }
          ]
        }
      }
    }
  }
}
JSON

cat >"$TMP_DIR/outside-diff-actionable.json" <<'JSON'
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
            },
            {
              "author": {
                "login": "coderabbitai"
              },
              "body": "**Actionable comments posted: 1**\n\n<details>\n<summary>⚠️ Outside diff range comments (1)</summary>\n</details>",
              "createdAt": "2026-07-03T02:40:03Z",
              "url": "https://example.test/actionable"
            },
            {
              "author": {
                "login": "coderabbitai"
              },
              "body": "<!-- walkthrough_start -->",
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
grep -q "unresolved_total=0" <<<"$pass_output"
grep -q "explicit_review_after_latest_commit=true" <<<"$pass_output"
grep -q "review_finished_after_latest_request=true" <<<"$pass_output"
grep -q "substantive_review_after_latest_commit=true" <<<"$pass_output"
grep -q "latest_review_request_noop=false" <<<"$pass_output"
grep -q "retrigger_review_allowed=true" <<<"$pass_output"
grep -q "manual_thread_resolution_required=false" <<<"$pass_output"
grep -q "must_resolve_outdated_threads=false" <<<"$pass_output"
grep -q "ok=true" <<<"$pass_output"

expect_failure_output "$TMP_DIR/submitted-review.json" "$TMP_DIR/submitted-review.out"
[[ $EXPECT_FAILURE_STATUS -ne 0 ]]
grep -q "latest_coderabbit_review_finished_at=2026-07-19T06:02:35Z" "$TMP_DIR/submitted-review.out"
grep -q "review_finished_after_latest_request=true" "$TMP_DIR/submitted-review.out"
grep -q "substantive_review_after_latest_commit=true" "$TMP_DIR/submitted-review.out"
grep -q "latest_review_request_rate_limited=false" "$TMP_DIR/submitted-review.out"
grep -q "latest_review_request_noop=false" "$TMP_DIR/submitted-review.out"
grep -q "outside_diff_actionable_comments=0" "$TMP_DIR/submitted-review.out"
grep -q "duplicate_actionable_comments=4" "$TMP_DIR/submitted-review.out"
grep -q "latest_actionable_comment_url=https://example.test/submitted-review" "$TMP_DIR/submitted-review.out"
grep -q "reason=4 top-level duplicate CodeRabbit comment(s) remain from the latest review; verify and fix them before calling the PR review-clean" "$TMP_DIR/submitted-review.out"
grep -q "ok=false" "$TMP_DIR/submitted-review.out"

expect_failure_output "$TMP_DIR/unresolved-outdated.json" "$TMP_DIR/unresolved-outdated.out"
[[ $EXPECT_FAILURE_STATUS -ne 0 ]]
grep -q "unresolved_outdated=1" "$TMP_DIR/unresolved-outdated.out"
grep -q "unresolved_total=1" "$TMP_DIR/unresolved-outdated.out"
grep -q "retrigger_review_allowed=false" "$TMP_DIR/unresolved-outdated.out"
grep -q "manual_thread_resolution_required=true" "$TMP_DIR/unresolved-outdated.out"
grep -q "must_resolve_outdated_threads=true" "$TMP_DIR/unresolved-outdated.out"
grep -q "reason=1 unresolved outdated CodeRabbit thread(s) remain; verify their fixes in HEAD, then manually resolve only verified-addressed threads" "$TMP_DIR/unresolved-outdated.out"
grep -q "warning=UNRESOLVED OUTDATED CODERABBIT THREADS BLOCK MERGE. VERIFY EACH FINDING AGAINST HEAD, FIX LIVE ISSUES, THEN MANUALLY RESOLVE ONLY VERIFIED-ADDRESSED THREADS" "$TMP_DIR/unresolved-outdated.out"

expect_failure_output "$TMP_DIR/stale-review.json" "$TMP_DIR/stale-review.out"
[[ $EXPECT_FAILURE_STATUS -ne 0 ]]
grep -q "explicit_review_after_latest_commit=false" "$TMP_DIR/stale-review.out"
grep -q "retrigger_review_allowed=true" "$TMP_DIR/stale-review.out"
grep -q "warning=A SMALL FOLLOW-UP MAY MERGE WITHOUT A FRESH CODERABBIT RUN ONLY WHEN EVERY POST-REVIEW COMMIT DIRECTLY ADDRESSES REVIEW FINDINGS; VERIFY THAT SCOPE AND GREEN CI MANUALLY" "$TMP_DIR/stale-review.out"
grep -q "reason=no explicit CodeRabbit review request found after the latest PR commit" "$TMP_DIR/stale-review.out"

expect_failure_output "$TMP_DIR/unresolved-non-outdated.json" "$TMP_DIR/unresolved-non-outdated.out"
[[ $EXPECT_FAILURE_STATUS -ne 0 ]]
grep -q "unresolved_non_outdated=1" "$TMP_DIR/unresolved-non-outdated.out"
grep -q "unresolved_total=1" "$TMP_DIR/unresolved-non-outdated.out"
grep -q "retrigger_review_allowed=false" "$TMP_DIR/unresolved-non-outdated.out"
grep -q "manual_thread_resolution_required=true" "$TMP_DIR/unresolved-non-outdated.out"
grep -q "must_resolve_outdated_threads=false" "$TMP_DIR/unresolved-non-outdated.out"
grep -q "reason=1 unresolved non-outdated CodeRabbit thread(s) remain; verify their fixes in HEAD, then manually resolve only verified-addressed threads" "$TMP_DIR/unresolved-non-outdated.out"
grep -q "warning=UNRESOLVED CODERABBIT THREADS BLOCK MERGE. VERIFY EACH CURRENT AND OUTDATED FINDING AGAINST HEAD, FIX LIVE ISSUES, THEN MANUALLY RESOLVE ONLY VERIFIED-ADDRESSED THREADS" "$TMP_DIR/unresolved-non-outdated.out"

expect_failure_output "$TMP_DIR/review-not-finished.json" "$TMP_DIR/review-not-finished.out"
[[ $EXPECT_FAILURE_STATUS -ne 0 ]]
grep -q "review_finished_after_latest_request=false" "$TMP_DIR/review-not-finished.out"
grep -q "retrigger_review_allowed=false" "$TMP_DIR/review-not-finished.out"
grep -q "manual_thread_resolution_required=false" "$TMP_DIR/review-not-finished.out"
grep -q "reason=no substantive CodeRabbit review summary found after the latest explicit review request" "$TMP_DIR/review-not-finished.out"

expect_failure_output "$TMP_DIR/review-command-noop.json" "$TMP_DIR/review-command-noop.out"
[[ $EXPECT_FAILURE_STATUS -ne 0 ]]
grep -q "review_finished_after_latest_request=false" "$TMP_DIR/review-command-noop.out"
grep -q "substantive_review_after_latest_commit=false" "$TMP_DIR/review-command-noop.out"
grep -q "latest_review_request_noop=true" "$TMP_DIR/review-command-noop.out"
grep -q "retrigger_review_allowed=false" "$TMP_DIR/review-command-noop.out"
grep -q "manual_thread_resolution_required=false" "$TMP_DIR/review-command-noop.out"
grep -q "reason=latest explicit CodeRabbit review request was acknowledged without reviewing commits" "$TMP_DIR/review-command-noop.out"

expect_failure_output "$TMP_DIR/review-rate-limited.json" "$TMP_DIR/review-rate-limited.out"
[[ $EXPECT_FAILURE_STATUS -ne 0 ]]
grep -q "explicit_review_after_latest_commit=true" "$TMP_DIR/review-rate-limited.out"
grep -q "latest_review_request_rate_limited=true" "$TMP_DIR/review-rate-limited.out"
grep -q "retrigger_review_allowed=false" "$TMP_DIR/review-rate-limited.out"
grep -q "manual_thread_resolution_required=false" "$TMP_DIR/review-rate-limited.out"
grep -q "reason=latest CodeRabbit review attempt after the PR commit was rate limited; do not retrigger yet" "$TMP_DIR/review-rate-limited.out"

expect_failure_output "$TMP_DIR/automatic-review-rate-limited.json" "$TMP_DIR/automatic-review-rate-limited.out"
[[ $EXPECT_FAILURE_STATUS -ne 0 ]]
grep -q "latest_review_request_rate_limited=true" "$TMP_DIR/automatic-review-rate-limited.out"
grep -q "retrigger_review_allowed=false" "$TMP_DIR/automatic-review-rate-limited.out"
grep -q "reason=latest CodeRabbit review attempt after the PR commit was rate limited; do not retrigger yet" "$TMP_DIR/automatic-review-rate-limited.out"

expect_failure_output "$TMP_DIR/review-full-command-rate-limited.json" "$TMP_DIR/review-full-command-rate-limited.out"
[[ $EXPECT_FAILURE_STATUS -ne 0 ]]
grep -q "explicit_review_after_latest_commit=true" "$TMP_DIR/review-full-command-rate-limited.out"
grep -q "latest_review_request_rate_limited=true" "$TMP_DIR/review-full-command-rate-limited.out"
grep -q "review_rate_limit_until=2099-07-03T03:37:05+00:00" "$TMP_DIR/review-full-command-rate-limited.out"
grep -q "retrigger_review_allowed=false" "$TMP_DIR/review-full-command-rate-limited.out"
grep -q "reason=latest CodeRabbit review attempt after the PR commit was rate limited; do not retrigger yet" "$TMP_DIR/review-full-command-rate-limited.out"

expect_failure_output "$TMP_DIR/review-next-window-hours.json" "$TMP_DIR/review-next-window-hours.out"
[[ $EXPECT_FAILURE_STATUS -ne 0 ]]
grep -q "latest_review_request_rate_limited=true" "$TMP_DIR/review-next-window-hours.out"
grep -q "review_rate_limit_until=2099-07-03T04:40:05+00:00" "$TMP_DIR/review-next-window-hours.out"
grep -q "retrigger_review_allowed=false" "$TMP_DIR/review-next-window-hours.out"

expect_failure_output "$TMP_DIR/review-rate-limit-expired.json" "$TMP_DIR/review-rate-limit-expired.out"
[[ $EXPECT_FAILURE_STATUS -ne 0 ]]
grep -q "latest_review_request_rate_limited=false" "$TMP_DIR/review-rate-limit-expired.out"
grep -q "retrigger_review_allowed=true" "$TMP_DIR/review-rate-limit-expired.out"

expect_failure_output "$TMP_DIR/old-rate-limit-before-later-request.json" "$TMP_DIR/old-rate-limit-before-later-request.out"
[[ $EXPECT_FAILURE_STATUS -ne 0 ]]
grep -q "latest_review_request_rate_limited=false" "$TMP_DIR/old-rate-limit-before-later-request.out"
grep -q "retrigger_review_allowed=false" "$TMP_DIR/old-rate-limit-before-later-request.out"
if grep -q "rate limited; do not retrigger yet" "$TMP_DIR/old-rate-limit-before-later-request.out"; then
  exit 1
fi

superseded_outcome_output="$(python3 "$SCRIPT" --repo benhook1013/FireMUD --pr 2364 --input "$TMP_DIR/superseded-review-outcome.json")"
grep -q "review_finished_after_latest_request=true" <<<"$superseded_outcome_output"
grep -q "substantive_review_after_latest_commit=true" <<<"$superseded_outcome_output"
grep -q "latest_review_request_rate_limited=false" <<<"$superseded_outcome_output"
grep -q "latest_review_request_noop=false" <<<"$superseded_outcome_output"
grep -q "retrigger_review_allowed=true" <<<"$superseded_outcome_output"
grep -q "manual_thread_resolution_required=false" <<<"$superseded_outcome_output"
grep -q "ok=true" <<<"$superseded_outcome_output"

expect_failure_output "$TMP_DIR/outside-diff-actionable.json" "$TMP_DIR/outside-diff-actionable.out"
[[ $EXPECT_FAILURE_STATUS -ne 0 ]]
grep -q "outside_diff_actionable_comments=1" "$TMP_DIR/outside-diff-actionable.out"
grep -q "duplicate_actionable_comments=0" "$TMP_DIR/outside-diff-actionable.out"
grep -q "latest_actionable_comment_url=https://example.test/actionable" "$TMP_DIR/outside-diff-actionable.out"
grep -q "warning=TOP-LEVEL CODERABBIT ACTIONABLE COMMENTS CAN EXIST OUTSIDE INLINE REVIEW THREADS; VERIFY THE LATEST CODERABBIT SUMMARY COMMENT BEFORE CALLING THE PR REVIEW-CLEAN" "$TMP_DIR/outside-diff-actionable.out"
grep -q "reason=1 top-level outside-diff CodeRabbit comment(s) remain from the latest review; verify and fix them before calling the PR review-clean" "$TMP_DIR/outside-diff-actionable.out"

echo "coderabbitai review contract checks passed"
