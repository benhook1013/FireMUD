-- Independent request-local abuse accounting. No Account lock, authority, issuance, OTP,
-- credential-MAC, response or recovery deadline is changed by a failed replay.
CREATE TABLE account_control_ui_issuance_replay_failures (
    operation_id UUID NOT NULL REFERENCES account_control_ui_issuance_operations(operation_id),
    request_id UUID NOT NULL,
    failure_number INTEGER NOT NULL CHECK (failure_number BETWEEN 1 AND 5),
    PRIMARY KEY (operation_id, failure_number)
);

-- [jooq ignore start]
CREATE FUNCTION account_control_ui_issuance_replay_failure_sequence()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    original_request UUID;
    previous_failures INTEGER;
BEGIN
    SELECT request_id INTO STRICT original_request
        FROM account_control_ui_issuance_operations WHERE operation_id = NEW.operation_id;
    IF NEW.request_id IS DISTINCT FROM original_request THEN
        RAISE EXCEPTION 'Issuance replay failure requires the exact original request'
            USING ERRCODE = '23514';
    END IF;
    -- Same seed as Java "control-ui-issuance-replay".hashCode() in lockReplayRequest.
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.request_id::TEXT, -2094251312));
    SELECT count(*) INTO previous_failures
        FROM account_control_ui_issuance_replay_failures WHERE operation_id = NEW.operation_id;
    IF NEW.failure_number <> previous_failures + 1 OR previous_failures >= 5 THEN
        RAISE EXCEPTION 'Issuance replay failures require bounded monotonic history'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_control_ui_issuance_replay_failure_sequence
    BEFORE INSERT ON account_control_ui_issuance_replay_failures
    FOR EACH ROW EXECUTE FUNCTION account_control_ui_issuance_replay_failure_sequence();
CREATE TRIGGER account_control_ui_issuance_replay_failures_immutable
    BEFORE UPDATE OR DELETE ON account_control_ui_issuance_replay_failures
    FOR EACH ROW EXECUTE FUNCTION account_control_ui_response_immutable_guard();
CREATE TRIGGER account_control_ui_issuance_replay_failures_no_truncate
    BEFORE TRUNCATE ON account_control_ui_issuance_replay_failures
    FOR EACH STATEMENT EXECUTE FUNCTION account_control_ui_response_immutable_guard();
-- [jooq ignore stop]
