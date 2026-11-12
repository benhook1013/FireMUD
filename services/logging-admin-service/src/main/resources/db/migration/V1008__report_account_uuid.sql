-- Numeric report Account references have no service-local canonical UUID mapping.
-- Refuse retained rows; never infer identities, backfill, delete or rewrite history.
-- [jooq ignore start]
LOCK TABLE player_reports IN ACCESS EXCLUSIVE MODE -- [jooq ignore stop]
;
-- [jooq ignore start]
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM player_reports) THEN
        RAISE EXCEPTION 'Cannot migrate populated legacy report Account references: Account-owned recovery is required';
    END IF;
END $$;
-- [jooq ignore stop]
ALTER TABLE player_reports ALTER COLUMN reporter_account_id TYPE UUID -- [jooq ignore start]
USING NULL::UUID -- [jooq ignore stop]
;
ALTER TABLE player_reports ALTER COLUMN target_account_id TYPE UUID -- [jooq ignore start]
USING NULL::UUID -- [jooq ignore stop]
;
ALTER TABLE player_reports ADD CONSTRAINT player_reports_reporter_account_id_non_nil
    CHECK (reporter_account_id <> '00000000-0000-0000-0000-000000000000'::UUID);
ALTER TABLE player_reports ADD CONSTRAINT player_reports_target_account_id_non_nil
    CHECK (target_account_id IS NULL OR target_account_id <> '00000000-0000-0000-0000-000000000000'::UUID);
