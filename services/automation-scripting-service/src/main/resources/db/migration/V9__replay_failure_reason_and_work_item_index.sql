-- Keep terminal recovery failures distinct from pre-claim rejections.  The
-- request/result ledger is durable idempotency evidence, so the distinction
-- must survive retries and control-plane readback.
ALTER TABLE script_dead_letter_replay_results
    ADD COLUMN failure_reason VARCHAR(256) NOT NULL DEFAULT '';
ALTER TABLE script_dead_letter_replay_results
    ADD COLUMN requested_work_item_id BIGINT;

-- Keep a requested identifier even when no owned parent row exists.  The
-- resolved work-item FK is nullable for that deterministic rejection case.
UPDATE script_dead_letter_replay_results
SET requested_work_item_id = work_item_id;
ALTER TABLE script_dead_letter_replay_results
    ALTER COLUMN requested_work_item_id SET NOT NULL;
ALTER TABLE script_dead_letter_replay_results
    ALTER COLUMN work_item_id DROP NOT NULL;
ALTER TABLE script_dead_letter_replay_results
    DROP CONSTRAINT uq_script_dead_letter_replay_result;
ALTER TABLE script_dead_letter_replay_results
    ADD CONSTRAINT uq_script_dead_letter_replay_result
        UNIQUE (replay_request_id, requested_work_item_id);

-- Replay-result cleanup and parent-retention checks probe this tenant-qualified
-- foreign key from the work-item side.  Keep the child lookup index aligned
-- with the FK so terminal parent disposal does not require a full child scan.
CREATE INDEX idx_script_dead_letter_replay_results_work_item
    ON script_dead_letter_replay_results (tenant_id, work_item_id);
