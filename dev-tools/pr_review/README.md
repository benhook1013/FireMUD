# PR Review SQLite Store and Backup

## Controller state and review records

Use these operations only after the controller change has merged and its version-matched tooling has been installed wherever the shared controller runs. Select `dev-tools/pr-review` from that trusted post-merge checkout, not from an older stacked worktree, and inspect its promoted version before any shared write:

```sh
dev-tools/pr-review --version
```

The output identifies the SQLite schema and writer build. The live shared cutover waits until this merged, version-matched entrypoint is installed and selected. This guide describes the explicit operation; it does not assert that JSON-to-SQLite cutover or migration proof has already happened.

The controller continues using its selected JSON state until cutover is explicitly requested. Inspect the current format, then migrate the existing controller state with the matching entrypoint:

```sh
dev-tools/pr-review state status --json
dev-tools/pr-review state migrate-sqlite --json
dev-tools/pr-review state status --json
```

Migration imports and reads back the validated JSON state, then atomically exchanges the JSON path for a private cutover directory and retains the original JSON at `<state-path>.migrated`. The original path becomes a directory with a versioned marker, so old JSON readers fail closed and old atomic JSON writers cannot replace it; cooperating writers are serialized across cutover. The operation requires Linux `renameat2(RENAME_EXCHANGE)` support and fails before cutover when the filesystem cannot provide that atomic exchange. Confirm the post-migration status reports a compatible SQLite state before continuing. This migrates the selected controller state; it does not import historical Hosted/CLI captures or old private model-review ledgers. Bootstrap the separate structured review-record schema explicitly against that selected database:

```sh
dev-tools/pr-review records bootstrap
```

Import a completed provider run only by naming its exact checkpoint comment. The command reads existing checkpoint evidence and the linked private capture, validates their identity and recorded finding decisions, then stores bounded review metadata and findings. It does not trigger a review. Full captures and CLI stdout remain source evidence outside SQLite.

```sh
dev-tools/pr-review records import-provider \
  --pr <number> --channel hosted|cli --checkpoint-id <comment-id> \
  --actor <operator> --scope broad|narrow [--coverage-limit <limit>]
```

For an independent manual or subagent review, create a curated version-1 JSON batch and register it with `records import-run --input <file>`. Required top-level fields are `api_version`, `run`, and `findings`; optional top-level `decisions` supplies source decisions. Required `run` fields are `run_id`, `source_pr`, `channel` (`manual` or `subagent`), `reviewer`, `scope`, `coverage_limits`, and `outcome`; optional fields are `source_head`, `started_at`, and `finished_at`. Each finding requires `source_finding_key` and `title`, with optional `detail`, `disposition`, `target_pr`, and inline decision fields `decision_id`, `decision`, `actor`, `reason`, and `decided_at`. A complete batch with a completed outcome and exactly one decision per finding is imported and finalized atomically. Partial source decisions are rejected; batches without decisions remain unresolved for later adjudication with `records source decide` and finalization with `records source finalize`. Batch files are bounded to 512 KB and 200 findings/decisions. Do not put credentials, raw captures, or stdout in this file.

These history and route queries, `state status`, and controller `status` are read-only. `records history` and `records routes` read structured review records and read through migrated legacy controller routes from the same SQLite snapshot. Returned routes label their origin as `review_records` or `legacy_controller`, keeping the two sources distinct:

```sh
dev-tools/pr-review records history --pr <number>
dev-tools/pr-review records routes --target-pr <number>
dev-tools/pr-review records routes --unassigned
```

Receiving owners use `records route decide` and `records route resolve` (or `records route retarget`) to record their own outcome; those target decisions do not rewrite source counts. Provider history and independent manual/subagent runs are records, not CodeRabbit policy input: they never grant, reset, block, or substitute for either channel's taper. Keep the private database free of credentials and raw secret material.

## Separate one-shot backup and restore

`sqlite_backup.py` is an explicit one-shot job for copying a consistent SQLite
review-state snapshot to a separately provisioned SFTP destination. It is not
called by `pr-review` commands and must be scheduled or invoked independently.
The source must be the compatible FireMUD controller SQLite database with
the review-record schema explicitly bootstrapped. Before SFTP access, the job
checks the controller and record schema versions, exact allowlisted tables and
columns, SQLite integrity, logical controller state, and indexed review-history
and route-worklist readback. It screens persisted text for common credential
and raw-secret shapes before transfer. That screening is deliberately bounded:
it catches known patterns, not every possible encoding or semantically sensitive
value, so operators must still keep credentials and raw captures out of this
database.

The remote login must be a dedicated, unprivileged account restricted by the
server to SFTP with no shell, forwarding, or unrelated file access. Provision
the backup directory in that account's jail, owned by the account with mode
`0700`. The job checks the directory owner and mode through SFTP listings,
rejects symlinks and group/world-writable path components, and verifies the
uploaded artifact's visible owner and `0600` mode. Supply the remote account's
pinned numeric UID with `--remote-uid`; the job uses `sftp ls -ln` and fails
closed when any listed UID differs. Obtain the UID from the trusted host setup
(`id -u reviewbackup`) and update it if the account is recreated. The server's
forced-SFTP configuration remains a deployment prerequisite that the client
cannot attest.

Example invocation:

```sh
PYTHONPATH=dev-tools python3 -m pr_review.sqlite_backup /var/lib/firemud/pr-review-state.sqlite3 \
  --host review-backup@backup.example \
  --identity-file /etc/firemud/review-backup-key \
  --known-hosts-file /etc/firemud/review-backup-known-hosts \
  --remote-directory /backups/pr-review \
  --remote-uid 1001 \
  --report-file /var/lib/firemud/backup-status.json
```

Replace `1001` with the UID confirmed on the backup host.

Restore a named versioned artifact into a new destination with `--restore`.
Use the artifact filename from the local `lastSuccess` report. The command
requires the same host key, identity, remote directory, and pinned UID settings
as backup mode, and rejects an existing destination before SFTP access; the
database file is also created exclusively so a race cannot replace it.

```sh
PYTHONPATH=dev-tools python3 -m pr_review.sqlite_backup /var/lib/firemud/restored-state.sqlite3 \
  --restore 'VERSIONED_FILENAME_FROM_LAST_SUCCESS' \
  --host review-backup@backup.example \
  --identity-file /etc/firemud/review-backup-key \
  --known-hosts-file /etc/firemud/review-backup-known-hosts \
  --remote-directory /backups/pr-review \
  --remote-uid 1001
```

Replace the quoted filename with the exact `lastSuccess.filename` value.

The job snapshots through SQLite's online backup API, uploads a uniquely named
partial artifact, applies mode `0600`, reads the bytes back and checks the
digest, SQLite integrity, FireMUD schema, logical controller state, and indexed
review-record reads, then publishes with an SFTP rename. Restore repeats those
logical checks before reporting success. It retains the
newest 30 versioned artifacts by default; `--retention-count` changes that
positive count. Pruning runs only after the new artifact has passed readback
validation.

The local status report is atomically replaced, restricted to mode `0600`, and
keeps the latest attempt, the last successful artifact time/name/digest/size,
and the most recent failure time. It never contains database contents, key
material, or remote command output. A failed invocation exits nonzero. The
report path must be on durable local storage if it is expected to survive host
loss.
