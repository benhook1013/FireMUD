-- Retire only the unwired V120 fresh temporal-confirmation writer. Retained rows, its historical
-- independent durability reader and immutable guards remain unchanged. V120 original-finalizer
-- XID/binding stamps and V121 pending fences are still required by genuine V122 ACK receipts.
-- [jooq ignore start]
CREATE FUNCTION account_gameplay_admission_confirmation_writes_retired()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Experimental Account admission confirmation writes are retired'
        USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_writes_retired';
END;
$$;

-- PostgreSQL executes same-event triggers by name: deny before the old INSERT proof guard.
CREATE TRIGGER account_admission_confirmation_00_retired_insert
    BEFORE INSERT ON account_gameplay_admission_commit_confirmations FOR EACH ROW
    EXECUTE FUNCTION account_gameplay_admission_confirmation_writes_retired();

CREATE OR REPLACE FUNCTION account_gameplay_admission_confirm_committed(
    requested_request_id UUID, expected_sha TEXT, expected_decision UUID)
RETURNS SETOF account_gameplay_admission_commit_confirmations LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Experimental Account admission confirmation writes are retired'
        USING ERRCODE = '23514', CONSTRAINT = 'account_admission_confirmation_writes_retired';
END;
$$;
-- [jooq ignore stop]
