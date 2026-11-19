-- Fresh attempt identity is global, including durable refusals that never create a reservation.
-- Do not rewrite or discard retained evidence: conflicting historical rows require investigation.
CREATE UNIQUE INDEX account_capacity_origin_attempt_unique
    ON account_capacity_origin_decisions (admission_attempt_id);
