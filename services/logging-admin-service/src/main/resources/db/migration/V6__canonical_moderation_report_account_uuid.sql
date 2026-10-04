-- Preserve the published numeric schema and every retained policy/report row.
-- Without Account-owned authoritative recovery, no old identity may be recast.
-- The complete affected table set is fenced before any emptiness check or DDL.
-- [jooq ignore start]
LOCK TABLE moderation_actions, player_reports IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM moderation_actions)
        OR EXISTS (SELECT 1 FROM player_reports) THEN
        RAISE EXCEPTION 'Account-owned recovery is required before migrating retained moderation or report identities to UUID'
            USING ERRCODE = '23514',
                CONSTRAINT = 'moderation_report_account_uuid_requires_empty_tables';
    END IF;
END;
$$;
-- [jooq ignore stop]

-- These tables are locked and proved empty. NULL is only a type-conversion
-- expression, never a persisted replacement identity or fabricated mapping.
-- jOOQ cannot parse USING; the simple declarations below expose the same types.
-- [jooq ignore start]
ALTER TABLE moderation_actions
    ALTER COLUMN account_id TYPE UUID USING CAST(NULL AS UUID);
ALTER TABLE player_reports
    ALTER COLUMN reporter_account_id TYPE UUID USING CAST(NULL AS UUID),
    ALTER COLUMN target_account_id TYPE UUID USING CAST(NULL AS UUID);
-- [jooq ignore stop]

ALTER TABLE moderation_actions ALTER COLUMN account_id TYPE UUID;
ALTER TABLE player_reports ALTER COLUMN reporter_account_id TYPE UUID;
ALTER TABLE player_reports ALTER COLUMN target_account_id TYPE UUID;

ALTER TABLE moderation_actions
    ADD CONSTRAINT moderation_actions_account_uuid_non_nil
        CHECK (account_id <> '00000000-0000-0000-0000-000000000000'::UUID);
ALTER TABLE player_reports
    ADD CONSTRAINT player_reports_reporter_uuid_non_nil
        CHECK (reporter_account_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    ADD CONSTRAINT player_reports_target_uuid_non_nil
        CHECK (target_account_id IS NULL
            OR target_account_id <> '00000000-0000-0000-0000-000000000000'::UUID);
