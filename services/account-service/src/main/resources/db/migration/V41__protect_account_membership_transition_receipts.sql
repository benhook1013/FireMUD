-- JOIN transition receipts are retained recovery evidence and may only be appended.
-- The independent stream head remains mutable so legitimate receipt appends can advance it.
-- [jooq ignore start]
CREATE FUNCTION reject_account_membership_transition_receipt_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Account membership transition receipts are append-only'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_membership_transition_receipts_append_only
    BEFORE UPDATE OR DELETE ON account_membership_transition_receipts
    FOR EACH ROW EXECUTE FUNCTION reject_account_membership_transition_receipt_mutation();
-- [jooq ignore stop]
