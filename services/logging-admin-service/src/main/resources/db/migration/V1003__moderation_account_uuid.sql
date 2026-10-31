-- Existing numeric Account references have no service-local canonical UUID mapping.
-- Deployment is blocked while any legacy moderation row exists. No row is deleted or mapped.
-- [jooq ignore start]
LOCK TABLE moderation_actions IN ACCESS EXCLUSIVE MODE -- [jooq ignore stop]
;
-- [jooq ignore start]
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM moderation_actions) THEN
        RAISE EXCEPTION 'Cannot migrate populated legacy moderation Account references without canonical Account identity disposition';
    END IF;
END $$;
-- [jooq ignore stop]
ALTER TABLE moderation_actions ALTER COLUMN account_id TYPE UUID -- [jooq ignore start]
USING NULL::UUID -- [jooq ignore stop]
;
ALTER TABLE moderation_actions ADD CONSTRAINT moderation_actions_account_id_non_nil
    CHECK (account_id <> '00000000-0000-0000-0000-000000000000'::UUID);
