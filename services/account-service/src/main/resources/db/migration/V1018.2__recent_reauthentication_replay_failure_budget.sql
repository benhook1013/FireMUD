-- Keep original evidence and keyed replay proof immutable. Credential mismatches append at most
-- five failures for the exact original request; successful retries never reset this history.
CREATE TABLE account_recent_reauthentication_replay_failures (
    evidence_id UUID NOT NULL,
    request_id UUID NOT NULL,
    account_uuid UUID NOT NULL,
    purpose VARCHAR(48) NOT NULL CHECK (purpose = 'SELF_SERVICE_GAME_CREATION'),
    creation_operation_id UUID NOT NULL,
    failure_number INTEGER NOT NULL CHECK (failure_number BETWEEN 1 AND 5),
    PRIMARY KEY (evidence_id, failure_number),
    FOREIGN KEY (evidence_id, request_id, account_uuid, purpose, creation_operation_id)
        REFERENCES account_recent_reauthentication_evidence
            (evidence_id, request_id, account_uuid, purpose, creation_operation_id)
        ON DELETE RESTRICT
);

-- [jooq ignore start]
CREATE FUNCTION account_recent_reauthentication_replay_failure_sequence()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    previous_failures INTEGER;
BEGIN
    -- Same hashtextextended seed as Java "recent-reauth-request".hashCode() in lockRequest.
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.request_id::TEXT, -699176241));
    SELECT count(*) INTO previous_failures
        FROM account_recent_reauthentication_replay_failures
        WHERE evidence_id = NEW.evidence_id;
    IF NEW.failure_number <> previous_failures + 1 OR previous_failures >= 5 THEN
        RAISE EXCEPTION 'Reauthentication replay failures require bounded monotonic history'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_recent_reauthentication_replay_failure_sequence
    BEFORE INSERT ON account_recent_reauthentication_replay_failures
    FOR EACH ROW EXECUTE FUNCTION account_recent_reauthentication_replay_failure_sequence();
CREATE TRIGGER account_recent_reauthentication_replay_failures_immutable
    BEFORE UPDATE OR DELETE ON account_recent_reauthentication_replay_failures
    FOR EACH ROW EXECUTE FUNCTION account_recent_reauthentication_no_mutation();
CREATE TRIGGER account_recent_reauthentication_replay_failures_no_truncate
    BEFORE TRUNCATE ON account_recent_reauthentication_replay_failures
    FOR EACH STATEMENT EXECUTE FUNCTION account_recent_reauthentication_no_mutation();
-- [jooq ignore stop]
