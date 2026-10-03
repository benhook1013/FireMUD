# PR Review SQLite Store and Backup

## Controller state and review records

Use these operations only after the controller change has merged and its version-matched tooling has been installed wherever the shared controller runs. Select `dev-tools/pr-review` from that trusted post-merge checkout, not from an older stacked worktree, and inspect its promoted version before any shared write:

```sh
dev-tools/pr-review --version
```

The output identifies the SQLite schema and writer build. The live shared cutover waits until this merged, version-matched entrypoint is installed and selected. This guide describes the explicit operation; it does not assert that JSON-to-SQLite cutover or migration proof has already happened.

For a posted Hosted request, `dev-tools/pr-review wait hosted --pr <number> --trigger-id <comment ID>` watches only that saved trigger. `--poll-seconds` defaults to 30 and `--max-wait-minutes` to 90. It returns JSON and exit status 0 for an attributable completion, 3 for another terminal result, or 4 when the wait expires; it never posts a request, grants review credit, or retires the trigger. The caller must read the result and use `records sync-hosted --pr <number>` before adjudicating a completed review. A wait expiration leaves the underlying trigger unchanged.

To require exactly a chosen number of further completed attributable results on one channel, use `dev-tools/pr-review decide allocation grant --pr <number> --channel hosted|cli --head <exact live SHA> --exact-additional-completed <N> --reason "<human judgment>"`. Use `allocation renew` instead of `grant` to replace an existing allocation or human stop explicitly. The command records the existing minimum and maximum as the same positive number; it does not start a fresh taper streak. Recording any human allocation grant or renewal requires the configured PR, channel, exact live head, and reason, but does not require stack reconciliation, local Git anchor proof, runnable selection, or cleared findings. The same recording boundary applies to one-result and minimum/maximum allocations. Available anchor facts are retained as audit context; unavailable Git facts remain null. Request admission still enforces attribution, exclusivity, identity warnings, and pending findings independently, so a saved allowance may remain held. Earlier completed evidence remains historical, while a verifiable request already admitted at the decision remains in flight and consumes one result only if it later completes attributably. Failures, rate limits, and other non-counting results consume none. Discovery continues until all `N` results complete even if ordinary taper was already satisfied or becomes satisfied early, then closes at the exact cap while accepted findings and fixes remain mandatory obligations.

### Review progress presentation

Controller status adds `review_progress` for each Hosted and CLI channel independently of ancestry preparation. Its progress label and governing rule use the existing policy and allocation counters; request `channels`, reconciliation, admission, explicit overrides and selected targets remain unchanged. Maximum and required rounds remaining subtract completed attributable rounds, including any running round; `request_slots_remaining` retains the separate free-slot count. Unknown counters are null, while known configured bounds remain visible. Maximum allocations may end under normal taper after their minimum is met; exact allocations require their remaining rounds. Completed and stopped projections name the applicable completion or human-stop reason.

The status-site adapter renders these labels once per channel card and omits controller reconciliation/preparation chatter from PR rows and cards. Missing older projections show unchecked progress/policy rather than deriving policy or displaying ancestry status. Findings and historical review content remain intact.

### Manual Hosted adoption

A human-posted Hosted CodeRabbit request can be incorporated without requesting another review: after the review completes, run `dev-tools/pr-review decide trigger-adopt-manual --pr <number> --trigger-id <GitHub comment ID> --head <current reviewed SHA>`. This verifies the immutable public command, completed response, and current anchor before writing a private attribution record. A queued PR uses its reconciled stack parent; an off-queue PR uses its actual live base branch after matching the live base and head tips to their remote refs. Off-queue adoption leaves the configured review queue unchanged. The command does not post a GitHub comment, create a result checkpoint, or grant taper by itself; adjudicate findings and post the normal public Hosted checkpoint next. A moved head or ambiguous response is refused.

The controller continues using its selected JSON state until cutover is explicitly requested. Inspect the current format, then migrate the existing controller state with the matching entrypoint:

```sh
dev-tools/pr-review state status --json
dev-tools/pr-review state migrate-sqlite --json
dev-tools/pr-review state status --json
```

Migration imports and reads back the validated JSON state, then atomically exchanges the JSON path for a private cutover directory and retains the original JSON at `<state-path>.migrated`. The original path becomes a directory with a versioned marker, so old JSON readers fail closed and old atomic JSON writers cannot replace it; cooperating writers are serialized across cutover. The operation requires Linux `renameat2(RENAME_EXCHANGE)` support and fails before cutover when the filesystem cannot provide that atomic exchange. Confirm the post-migration status reports a compatible SQLite state before continuing. This migrates the selected controller state; it does not import historical Hosted/CLI captures or old private model-review ledgers. Bootstrap or migrate the separate review-record schema with the version-matched entrypoint:

```sh
dev-tools/pr-review records bootstrap
dev-tools/pr-review records migrate
```

`records migrate` upgrades supported v4 through v8 review-record schemas to v9 in one transaction and raises the controller state's minimum writer build to 8, fencing build-7 state and records writers. Reads do not run this migration automatically. The v9 migration adds nullable retained severity to finding observations; existing findings remain unspecified rather than receiving an inferred label. Pre-v7 structured accepted runs, including v6 runs, have no source-finding resolution records; migration never invents them. After promotion, their accepted findings remain obligations until an operator records audited source resolutions.

For v9 promotion, compare the selected shared and site entrypoints and confirm both use the intended trusted checkout. The scheduled backup job must select that same compatible checkout and report writer build 8 before the records migration; a build-7 backup writer is fenced by the migration. After stopping older writers, migrate the promoted records and run the backup. Restore that named remote artifact into an isolated destination with the compatible entrypoint and verify its database readback before considering promotion complete.

If the process is killed before the atomic exchange, the JSON path remains a regular file and is still authoritative, but an imported SQLite target or `<state-path>.migrated` staging directory can make a retry refuse to start. Do not delete, overwrite, or adopt either artifact by filename alone. Stop all controller writers and confirm no migration is running; verify that the JSON path is a regular, non-symlink file and that `state status --json` reports `format: json` and `compatible: true`, which validates its state schema. Inspect the SQLite target and retention path without changing them, and establish that they belong to the interrupted attempt. If the retention path is a file, the JSON path is a directory, any path is a symlink or unexpected type, the source cannot be validated, or artifact provenance is uncertain, stop and investigate rather than retrying. Otherwise, move the orphan SQLite target and any staging directory into a separately protected quarantine, retaining their names and contents for audit, then rerun `state migrate-sqlite --json` with the version-matched entrypoint and read back the resulting status. This manual recovery is only for a confirmed pre-exchange interruption; it does not authorize replacing an installed cutover or removing the retained original JSON.

Before both the shared state directory has completed its SQLite cutover and the review-record schema has been bootstrapped or migrated, CLI reviews may still start with durable legacy capture. Those prerequisites gate recording a CLI attempt in SQLite and registering a new Hosted attempt, not the ability to start a CLI review. After both prerequisites, `run cli` records each attempt and complete provider JSON in SQLite, including failures and rate limits. A completed CLI result commits its attempt, source run, and link in one transaction. New Hosted requests register their attempt before posting. `records sync-hosted` observes already-posted requests and stores their terminal GitHub review, comment, and thread evidence; a completed result commits its attempt, source run, and link in one transaction. It also recovers older partial completions from their archived exact evidence. The status server runs sync during scheduled refresh when its configured controller supports the command. Sync does not post a review or change taper. Subagent passes use `records subagent start --model <actual-tool-model>` (and optional `--reasoning-effort <actual-effort>`), followed by `complete` or `fail`. New native attempts require the actual bounded model identifier from tool metadata in both the CLI and storage API; version names are not hardcoded. Historical attempts and exceptional curated imports may retain an unknown model and are never inferred from reviewer prose. Declared model/effort are projected once with the existing batched attempt history and displayed in the linked browser review header, without changing counts or admission; every finding supplied to a routine `complete` command requires an exact `severity` of `Critical`, `Major`, `Minor`, or `Trivial`, while a zero-finding completed pass remains valid. The label is persisted as `display_severity` on the finding and its structured routes, and never changes counts, taper, queue selection, or review admission. Historical imports may retain unspecified severity. Before both prerequisites are met, follow the pre-cutover capture and routing behavior described below and in the lifecycle guidance.

Set or correct display-only severity on an exact retained observation with `records source set-severity --run-id <run> --finding-key <key> --severity Critical|Major|Minor|Trivial`. This updates only the nullable observation field; immutable import payloads, provider artifacts, finding text, decisions, and counts remain unchanged. A stored value takes display precedence over provider-derived severity, while an unspecified value retains the existing provider projection.

Complete native CLI attempts with their linked source run, archived events and source decisions are read from SQLite before consulting retained capture files. Removing the original capture directory does not remove their attribution or verified source-fix proof. Retained observation projections must match the immutable original run payload. Where the original native writer recorded a nonempty detail projection, parsed archived findings must reproduce that detail and, for unredacted archives, its title before source decisions or fixes are associated with them. Older native title-only records retain their historical attribution semantics; deleting a modern observation detail cannot downgrade to that path. This is logical finding consistency rather than full raw-event byte attestation; titles truncated before redaction cannot be exactly reconstructed from redacted archives. An incomplete or conflicting modern association fails closed; historical captures without a native attempt association use the retained legacy validation path and explicit repair/import tooling. Older native metadata without a repository field uses the selected repository-scoped database context; an explicit conflicting repository is rejected. New native captures persist that field.

Use `records cli-decision`, `records source finalize`, and `records route` for new CLI findings only after the shared SQLite state directory is cut over and the review-record schema has been bootstrapped or migrated. Until both conditions hold, preserve `decisions.tsv` as the raw-positive evidence for each new CLI capture and use `decide route open` for routed observations. Once cut over, the source finding, target PR, decision, actor, and reason remain linked in SQLite, and routed decisions create their route atomically. `records cli-correct` appends an audited correction without rewriting the original decision. Legacy capture files remain audit/recovery sources until the historical comparison and cutover are proved; they are not the structured read path for new rounds after cutover.

Record an accepted source finding's verified fix with `records source resolve --source-pr <number> --run-id <run> --finding-key <key> --resolution-id <id> --fix-sha <full commit SHA> --actor <identity> --proof-note <bounded note>`. The command requires the exact completed, attributable, finalized run and an effectively accepted finding. Its immutable proof is tied to that source run and finding; it does not change source counts or taper. If an operator later verifies that the recorded SHA was transcribed incorrectly, use `records source correct-resolution --source-pr <number> --run-id <run> --finding-key <key> --resolution-id <id> --expected-fix-sha <current effective SHA> --fix-sha <corrected full SHA> --correction-id <unique id> --actor <identity> --reason <bounded reason> --proof-note <bounded proof>`. This appends an immutable correction event, retains the original resolution and every prior correction, and changes only the validated effective proof SHA. The expected SHA is checked transactionally against the current effective SHA; stale competing corrections fail, and an exact correction ID replay is idempotent. Neither command changes source decisions, counts, or taper. Controller finding obligations clear only when the exact public checkpoint binds to the same SQLite source run and every accepted finding has a readable matching resolution and valid correction chain. Historical imported CLI markers bind to their immutable imported run through the exact recorded provider origin. Stored checkpoint snapshots must reproduce their original fingerprint; subsequent parsed checkpoints must retain the same semantic identity, counts, linkage, and duration, while edit timestamps and formatting alone are neutral. Hosted imports made before checkpoint posting bind through the immutable provider review ID and candidate SHA retained on the completed attempt; no checkpoint identity is fabricated. Missing, partial, mismatched, or unreadable proof remains pending. Checkpoints without an exact structured source association retain the existing legacy anchored-evidence handling. The fix SHA is retained as audited proof identity and is not revalidated against the current branch ancestry.

New Hosted imports preserve separately fingerprinted findings bundled into one inline comment, retaining their common comment/thread identity. Existing finalized comment-grouped records remain immutable; report a historical multiplicity mismatch rather than silently rewriting counts or importing a duplicate provider round.

Allocation finding-clearance audits distinguish an attributable terminal Hosted rate limit from unresolved findings, retaining its exact proof after cooldown expiry. The Hosted selector still enforces the cooldown. CLI may overlap an already-posted Hosted review only when the fresh audit proves the same published head and complete stack anchor; unknown reservations, mismatched identities, pending findings, and unpublished corrections remain held.

Hosted and CLI preflight briefly serialize on admission locks. A busy Hosted admission is retried through bounded fresh target selection; if contention persists, the command reports admission contention without treating the lock as proof of an active provider request. A lock refusal occurs before the Hosted reservation and POST.

For a historical or missed provider round, name its exact checkpoint comment. The repair command previews an isolated SQLite copy by default, validates the immutable provider identity and source decisions, and imports bounded/redacted source artifacts on `--apply`. Use `--all --continue-on-error` to report each checkpoint separately when some old evidence is incomplete. Missing historical evidence remains labelled; the command never invents an attributable review or changes taper. Repeat an exact import safely; conflicting evidence is refused. A verified reply-only completion observed after an immutable ambiguous Hosted attempt can append its exact source run and checkpoint origin; the initial attempt and archived evidence remain unchanged. `records migrate` reports three distinct CLI capture outcomes while preserving run identity and source fingerprints: failed legacy captures become non-counting SQL attempts; a complete successful native capture is recovered as a completed source run only when it matches its existing started SQL attempt and proves one exact successful result; and an incomplete native capture matching its exact started SQL attempt is terminally classified as failed or timed out, with no source run or taper credit. Changed or conflicting evidence is reported instead of overwriting an earlier import. Normal history reads do not scan those files.

```sh
dev-tools/pr-review records repair-provider \
  --pr <number> --checkpoint-id <comment-id> \
  --actor <operator> --scope broad|narrow [--coverage-limit <limit>]
# After checking the preview, repeat with --apply.
```

`records import-run --input <file>` remains an exceptional recovery/import path for old curated manual or subagent evidence. Routine new subagent passes use the start/complete/fail commands. Do not put credentials in review findings or evidence files.

These history and route queries, `state status`, and controller `status` are read-only. `records history` and `records routes` read structured review records and read through migrated legacy controller routes from the same SQLite snapshot. Returned routes label their origin as `review_records` or `legacy_controller`, keeping the two sources distinct:

`records history` and `records history-batch` read completed runs, attempts, decisions, routes, and historical evidence gaps from one SQLite snapshot. Failed and rate-limited attempts are visible but never enter taper counts. Full source artifacts are kept in SQLite with recognizable credentials redacted; the ordinary history response exposes bounded metadata and findings, not the raw archive bytes.

Historical Hosted findings with wrapper or metadata titles may include an optional `display_title`, derived from the complete SQL archive and exact immutable comment/finding key. Hosted findings may also include `display_detail`, a redacted Markdown excerpt of up to 8,000 characters that retains issue paragraphs, inline code and links while removing provider badges, diagnostic/script and AI-prompt blocks, and a repeated selected headline. Source and incoming structured routes expose the same proven display projections. An archived security classification title may be replaced only when its directly associated complete provider security header and adjacent exploitability/CWE metadata prove a distinct authored remediation headline in the same comment. `display_title_is_excerpt: true` marks an unheaded prose title only when the stored title exactly matches its canonical bounded projection; consumers retain the full display body instead of inventing a heading. Authored headings are unaffected. An invalid individual archived comment does not hide independently valid sibling comments. An existing historical aggregate comment key may expose all valid archived sections with an explicit aggregate note; this creates no second finding or decision. Aggregate display text shares the 8,000-character bound fairly among labelled sections, reports truncated section excerpts, and explicitly counts omitted sections when all cannot fit. The stored title/detail, identities, counts, decisions, routes, and finalization remain unchanged; missing, conflicting, or unusable evidence leaves the field absent.

Hosted findings and their associated source/incoming routes expose `display_severity` only from explicit provider badges in the exact archived finding section, excluding ordinary code samples and diagnostic/prompt blocks. Provider labels are preserved without priority mappings. Evaluated missing or conflicting labels return authoritative `null`; unavailable archive provenance leaves the field absent. CLI findings and associated routes expose the same field from exact validated completed native SQL captures or checkpoint-linked imported events, matched by immutable finding ordinal; known explicit string labels are normalized for capitalization without priority mappings. Unknown values are null and unproven associations omit the field. Stored source fields and review counts remain unchanged.

Provider runs may include `duration_seconds` from an exact completed attempt or an identity-validated retained checkpoint/capture; evaluated missing, invalid or conflicting duration evidence returns `duration_seconds: null`, which is authoritative unknown. Consumers may use legacy fallback only when the field is absent, never when it is null. Import event timestamps are never treated as elapsed review time. Reads make no provider requests or historical corrections.

```sh
dev-tools/pr-review records history --pr <number>
dev-tools/pr-review records routes
dev-tools/pr-review records routes --status resolved --target-pr <number>
dev-tools/pr-review records routes --status all --source-pr <number>
dev-tools/pr-review records routes --unassigned
```

`records routes` defaults to open routes and lists assigned and unassigned routes when no assignment filter is supplied. Use `--status open|resolved|all` to choose outstanding routes, terminal routes (`accepted_fixed` or `rejected`), or both. `--target-pr` filters by current receiving PR, `--source-pr` filters by the PR where the finding was raised, and `--unassigned` selects routes without a current target. Source and target filters can be combined; `--target-pr` and `--unassigned` are mutually exclusive. Structured routes include their latest finding title, so the worklist can be triaged without opening every source PR. Every query includes structured SQLite routes and migrated controller routes, with the controller's current route taking precedence over a same-ID SQLite shadow before filters are applied. Omitting `--status` preserves the existing open-only worklist behavior.

Receiving owners use `records route decide` and `records route resolve` (or `records route retarget`) for routes whose origin is `review_records`; those target decisions do not rewrite source counts. Routes whose origin is `legacy_controller` remain owned by the controller, including any SQLite shadow row with the same route ID. Update those routes with the canonical `dev-tools/pr-review decide route` command; SQLite target-side writes reject legacy-owned IDs and direct operators to that command. Provider history and independent manual/subagent runs are records, not CodeRabbit policy input: they never grant, reset, block, or substitute for either channel's taper. Keep the private database free of credentials and raw secret material.

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
value, so operators must still keep credentials and unredacted source captures
out of this database. The controller stores bounded, redacted provider artifacts.

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

When restoring a backup that contains an older review-record schema, restore it to an isolated destination first, using the version-matched old entrypoint that can read and validate that schema. Do not restore an old-schema artifact into the promoted live destination or let a newer entrypoint reinterpret it. After the isolated restore is verified, stop old writers before migrating the promoted review records with the compatible promoted entrypoint.

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
