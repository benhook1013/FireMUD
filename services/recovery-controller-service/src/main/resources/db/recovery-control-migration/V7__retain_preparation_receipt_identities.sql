-- A V6 observation may have overwritten earlier definitive identities. Refuse to infer
-- complete history from its latest slot. Existing installations follow the guarded reset.
-- [jooq ignore start]
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM recovery_control.initial_gameplay_release_outbox
        WHERE preparation_observation_payload IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'Preparation receipt history is incomplete for retained V6 observations';
    END IF;
END;
$$;
-- [jooq ignore stop]

CREATE TABLE recovery_control.initial_gameplay_release_preparation_receipt (
    outbox_id uuid NOT NULL REFERENCES recovery_control.initial_gameplay_release_outbox(outbox_id),
    identity_digest character(64) NOT NULL,
    PRIMARY KEY (outbox_id, identity_digest)
);
