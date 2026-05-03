ALTER TABLE remote_followup
    ADD COLUMN payload_kind VARCHAR(80),
    ADD COLUMN requested_command VARCHAR(500);

ALTER TABLE remote_followup_result
    ADD COLUMN result_error_code VARCHAR(80);
