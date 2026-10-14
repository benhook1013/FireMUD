-- Preserve the complete immutable Game Design publication policy set that authorized a fresh
-- public-production realm. Historical V23 catalog rows remain readable as historical catalog
-- state but are deliberately not reconstructed or adopted as complete published evidence.
ALTER TABLE game_session_canonical_realm_catalog
    ADD COLUMN complete_policy_set_evidence BYTEA;

ALTER TABLE game_session_canonical_realm_catalog
    ADD CONSTRAINT chk_gs_canonical_realm_catalog_complete_policy_evidence
        CHECK (complete_policy_set_evidence IS NULL OR octet_length(complete_policy_set_evidence) > 0);

-- [jooq ignore start]
CREATE FUNCTION require_game_session_canonical_realm_complete_policy_evidence()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.complete_policy_set_evidence IS NULL
       OR octet_length(NEW.complete_policy_set_evidence) = 0 THEN
        RAISE EXCEPTION 'New canonical realm catalog row requires complete published policy evidence'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chk_gs_canonical_realm_catalog_complete_policy_evidence';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER game_session_canonical_realm_requires_complete_policy_evidence
    BEFORE INSERT ON game_session_canonical_realm_catalog
    FOR EACH ROW EXECUTE FUNCTION require_game_session_canonical_realm_complete_policy_evidence();
-- [jooq ignore stop]
