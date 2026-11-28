-- The legacy receipt had no rejection time. Require a fresh observation after this
-- migration for those receipts rather than deriving a rejection from mutable outbox timestamps.
ALTER TABLE recovery_control.initial_gameplay_release_attempt_rejection
  ADD COLUMN preparation_refresh_after timestamptz;

-- The ALTER's exclusive lock is held through this backfill and the migration commit.
-- [jooq ignore start]
UPDATE recovery_control.initial_gameplay_release_attempt_rejection
SET preparation_refresh_after = statement_timestamp();
-- [jooq ignore stop]

ALTER TABLE recovery_control.initial_gameplay_release_attempt_rejection
  ALTER COLUMN preparation_refresh_after SET NOT NULL;
