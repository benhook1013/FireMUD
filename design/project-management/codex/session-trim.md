# Codex Session Trim Runbook

Use this runbook when a long-running Codex chat becomes unreliable to load because its session file has grown too large.

## Scope

This process is for local Codex session maintenance under `~/.codex`. It is not a product feature, repository build step, or normal chat workflow.

## When to Use It

Use this process when all of the following are true:

- a specific long-running Codex chat is slow or unreliable to open
- the chat already contains one or more `compacted` records
- you want to keep the latest compacted context summary and recent turns
- you want a full backup before modifying anything

Do not trim a session that has no `compacted` record unless you are intentionally willing to discard older context instead of preserving the latest summary.

## Relevant Paths

- Codex state root: `~/.codex`
- Live session files: `~/.codex/sessions/YYYY/MM/DD/rollout-...jsonl`
- Archived session files: `~/.codex/archived_sessions/rollout-...jsonl`
- Thread index DB: `~/.codex/state_5.sqlite`

## What Worked

For an oversized live session, the reliable trim point was:

1. keep the first `session_meta` line
2. keep the most recent `task_started` immediately before the last `compacted` record
3. keep the last `compacted` record
4. keep everything after that point
5. also keep a meaningful recent history window before that point when the file is still excessively large after compaction alone
6. back up the full original file and the removed prefix before rewriting the live file

This preserves the latest compacted summary plus the newest turns while removing older raw history that is no longer needed for active context loading.

Do not default to the most aggressive possible trim. If the chat is still usable with more recent raw history preserved, keep it. In practice, keeping a few hundred lines before the last compaction point is a reasonable default starting point, then trimming harder only if the file still remains too large or unreliable.

## Identify Active Top-Level VS Code Chats

If `sqlite3` is not installed, Python is enough:

```bash
python3 - <<'PY'
import sqlite3, os
conn = sqlite3.connect(os.path.expanduser('~/.codex/state_5.sqlite'))
cur = conn.cursor()
rows = cur.execute("""
SELECT id, title, datetime(updated_at,'unixepoch'), archived, source, rollout_path, tokens_used
FROM threads
WHERE archived=0 AND agent_nickname IS NULL AND source='vscode'
ORDER BY updated_at_ms DESC
""").fetchall()
print('active_top_level_vscode', len(rows))
for row in rows:
    print(row)
PY
```

## Inspect Session Size and Compaction State

```bash
python3 - <<'PY'
import json
from pathlib import Path

p = Path('/home/ben/.codex/sessions/YYYY/MM/DD/rollout-EXAMPLE.jsonl')
print('size_bytes', p.stat().st_size)

last_compacted = None
with p.open('r', encoding='utf-8') as f:
    for idx, line in enumerate(f, 1):
        obj = json.loads(line)
        if obj.get('type') == 'compacted':
            last_compacted = idx

print('last_compacted_line', last_compacted)
PY
```

Only continue if `last_compacted_line` is present.

## Trim Procedure

This exact pattern was used successfully:

```bash
python3 - <<'PY'
from pathlib import Path
import shutil, json

p = Path('/home/ben/.codex/sessions/YYYY/MM/DD/rollout-EXAMPLE.jsonl')
backup_dir = Path('/home/ben/.codex/session_backups')
backup_dir.mkdir(exist_ok=True)

full_backup = backup_dir / (p.name + '.full-backup')
prefix_backup = backup_dir / (p.name + '.pretrim-prefix')

with p.open('r', encoding='utf-8') as f:
    lines = f.readlines()

last_compacted = None
for i, line in enumerate(lines, 1):
    obj = json.loads(line)
    if obj.get('type') == 'compacted':
        last_compacted = i

if last_compacted is None:
    raise SystemExit('No compacted record found; refusing to trim')

# Keep some recent raw history before the last compaction point so the chat
# does not lose all short-term grounding. Adjust this window based on file size.
recent_history_window = 300
keep_start = max(2, last_compacted - recent_history_window)
trimmed_lines = [lines[0]] + lines[keep_start - 1:]
prefix_lines = lines[:keep_start - 1]

if not full_backup.exists():
    shutil.copy2(p, full_backup)
if not prefix_backup.exists():
    with prefix_backup.open('w', encoding='utf-8') as f:
        f.writelines(prefix_lines)

for line in trimmed_lines:
    json.loads(line)

with p.open('w', encoding='utf-8') as f:
    f.writelines(trimmed_lines)

print('original_lines', len(lines))
print('last_compacted_line', last_compacted)
print('keep_start_line', keep_start)
print('recent_history_window', recent_history_window)
print('trimmed_lines', len(trimmed_lines))
print('full_backup', full_backup)
print('prefix_backup', prefix_backup)
print('new_size', p.stat().st_size)
PY
```

Start with a moderate window such as `200` to `500` lines before the last `compacted` record. Only fall back to a tighter trim if the chat still fails to load reliably.

## Validate After Rewrite

```bash
python3 - <<'PY'
import json
from pathlib import Path

p = Path('/home/ben/.codex/sessions/YYYY/MM/DD/rollout-EXAMPLE.jsonl')
with p.open('r', encoding='utf-8') as f:
    lines = f.readlines()

print('line_count', len(lines))
for line in lines:
    json.loads(line)

print('valid_jsonl', True)
PY
```

Then reopen the chat in Codex and confirm it loads normally.

## Notes

- The `threads` table may contain many stale `archived=0` rows for old helper threads or subagents. Filter to top-level `source='vscode'` rows before assuming there are multiple active user chats.
- Keep the full backup until the trimmed chat has been reopened and used successfully.
- If needed, the original session can be restored by copying the full backup back over the live session file.
