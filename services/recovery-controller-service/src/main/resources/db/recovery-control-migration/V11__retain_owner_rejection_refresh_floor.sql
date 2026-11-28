-- A retained legacy receipt lacks an authoritative freshness floor. Reject
-- that database rather than deriving a replacement timestamp.
ALTER TABLE recovery_control.initial_gameplay_release_attempt_rejection
  ADD COLUMN preparation_refresh_after timestamptz NOT NULL;
