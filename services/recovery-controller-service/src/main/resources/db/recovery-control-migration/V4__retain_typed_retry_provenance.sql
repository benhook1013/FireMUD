-- Disposable-only V4 candidate; no production allocation, grant, or rollout authority.
ALTER TABLE recovery_control.initial_gameplay_release_outbox
  ADD COLUMN retry_provenance text,
  ADD CONSTRAINT ck_release_outbox_retry_provenance_shape CHECK (
    retry_provenance IS NULL OR
    (state = 'retryable' AND retry_provenance IN ('local_no_send', 'owner_rejected', 'owner_closure'))
  );

CREATE TABLE recovery_control.initial_gameplay_release_attempt_local_no_send (
  outbox_id uuid NOT NULL REFERENCES recovery_control.initial_gameplay_release_outbox(outbox_id) ON DELETE RESTRICT,
  attempt_id uuid NOT NULL,
  attempt_ordinal integer NOT NULL CHECK (attempt_ordinal > 0),
  dispatched_at timestamptz NOT NULL,
  recorded_at timestamptz NOT NULL CHECK (recorded_at >= dispatched_at),
  PRIMARY KEY (outbox_id, attempt_id, attempt_ordinal)
);
