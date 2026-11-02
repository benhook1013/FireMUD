-- Historical evidence remains untouched. Only new successful evidence commits require a keyed
-- credential-replay proof; the MAC is not a credential verifier for a new authentication grant.
CREATE TABLE account_recent_reauthentication_replay_credentials (
    evidence_id UUID PRIMARY KEY,
    request_id UUID NOT NULL,
    account_uuid UUID NOT NULL,
    purpose VARCHAR(48) NOT NULL CHECK (purpose = 'SELF_SERVICE_GAME_CREATION'),
    creation_operation_id UUID NOT NULL,
    key_id VARCHAR(32) NOT NULL CHECK (key_id ~ '^[A-Za-z0-9_-]{1,32}$'),
    credential_mac VARCHAR(64) NOT NULL CHECK (credential_mac ~ '^[0-9a-f]{64}$'),
    FOREIGN KEY (evidence_id, request_id, account_uuid, purpose, creation_operation_id)
        REFERENCES account_recent_reauthentication_evidence
            (evidence_id, request_id, account_uuid, purpose, creation_operation_id)
        ON DELETE RESTRICT
);

-- [jooq ignore start]
CREATE FUNCTION account_recent_reauthentication_replay_same_transaction()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    evidence_xid BIGINT;
BEGIN
    SELECT xmin::TEXT::BIGINT INTO STRICT evidence_xid
        FROM account_recent_reauthentication_evidence WHERE evidence_id = NEW.evidence_id;
    -- xmin is the creating 32-bit transaction identity. This checks atomic storage only;
    -- it is neither authentication authority nor a replacement for keyed verification.
    IF evidence_xid <> txid_current() % 4294967296 THEN
        RAISE EXCEPTION 'Historical reauthentication evidence cannot acquire replay credentials'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_recent_reauthentication_replay_same_transaction
    BEFORE INSERT ON account_recent_reauthentication_replay_credentials
    FOR EACH ROW EXECUTE FUNCTION account_recent_reauthentication_replay_same_transaction();

CREATE FUNCTION account_recent_reauthentication_require_replay_credentials()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM account_recent_reauthentication_replay_credentials proof
        WHERE proof.evidence_id = NEW.evidence_id AND proof.request_id = NEW.request_id
            AND proof.account_uuid = NEW.account_uuid AND proof.purpose = NEW.purpose
            AND proof.creation_operation_id = NEW.creation_operation_id) THEN
        RAISE EXCEPTION 'New reauthentication evidence requires atomic replay credentials'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER account_recent_reauthentication_require_replay_credentials
    AFTER INSERT ON account_recent_reauthentication_evidence DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_recent_reauthentication_require_replay_credentials();
CREATE TRIGGER account_recent_reauthentication_replay_immutable
    BEFORE UPDATE OR DELETE ON account_recent_reauthentication_replay_credentials
    FOR EACH ROW EXECUTE FUNCTION account_recent_reauthentication_no_mutation();
CREATE TRIGGER account_recent_reauthentication_replay_no_truncate
    BEFORE TRUNCATE ON account_recent_reauthentication_replay_credentials
    FOR EACH STATEMENT EXECUTE FUNCTION account_recent_reauthentication_no_mutation();
-- [jooq ignore stop]
