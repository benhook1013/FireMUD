# Recent Codex task history reader

`codex_recent_history.py` streams one exact Codex session JSONL file and prints a bounded wall-clock slice. By default it prints only user and assistant message text; it excludes developer instructions, reasoning, and tool calls/results.

```bash
python3 tmp/tools/codex_recent_history.py \
  --thread 01a07037-e403-7b91-a500-34fc9f876a12 \
  --minutes 20
```

`--until 2026-09-05T07:00:00Z` anchors a historical interval, and `--since` overrides the computed start. `--delegations` instead shows assignment targets and any plaintext prompt from `spawn_agent` and `followup_task` calls. Current Codex logs encrypt those prompt arguments, so the reader labels the prompt unavailable rather than printing the opaque ciphertext. Use `--limit` and `--max-chars` to tighten the default bounds. Pass `--sessions-root` when sessions live outside `CODEX_HOME`, `~/.codex/sessions`, or WSL-mounted Windows user profiles.

The reader never changes session data. It requires the UUID in the filename and `session_meta` to match exactly. If an interval is empty, it reports the newest session record and newest eligible event rather than showing stale text. Malformed records, including a partial trailing JSONL line, are skipped and counted.
