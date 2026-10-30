-- [jooq ignore start]
CREATE FUNCTION retain_required_first_new_issuer_obligation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'REQUIRED' THEN
        RAISE EXCEPTION 'Required first-NEW issuer obligation is retained'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'gs_first_new_issuer_release_obligation_retained';
    END IF;
    RETURN OLD;
END;
$$;

-- PostgreSQL runs same-kind triggers by name. Run before the existing release guard,
-- whose RETURN NEW on DELETE is NULL and would otherwise silently cancel this refusal.
-- Keep this independent of that function so later final-admission refinements retain it.
CREATE TRIGGER game_session_first_new_issuer_obligation_delete_refusal
    BEFORE DELETE ON game_session_canonical_binding_issuer_index_obligation
    FOR EACH ROW EXECUTE FUNCTION retain_required_first_new_issuer_obligation();
-- [jooq ignore stop]
