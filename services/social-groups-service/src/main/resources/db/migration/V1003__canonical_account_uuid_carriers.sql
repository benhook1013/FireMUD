-- Account owns the ordinary Account UUID. Social cannot reconstruct it from private BIGINT keys.
-- Populated legacy deployments require a genuine Account-owner mapping and retained-data disposition
-- before deploying this migration. No row is deleted, guessed, or copied into a parallel authority.
-- Flyway runs this migration transactionally: hold write-excluding locks through the emptiness guard
-- and every ALTER so no concurrent legacy write can cross the verified-empty source boundary.
-- [jooq ignore start]
LOCK TABLE guilds, guild_members, chat_messages, friend_links, mail_messages,
    account_friend_links IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM guilds)
        OR EXISTS (SELECT 1 FROM guild_members)
        OR EXISTS (SELECT 1 FROM chat_messages)
        OR EXISTS (SELECT 1 FROM friend_links)
        OR EXISTS (SELECT 1 FROM mail_messages)
        OR EXISTS (SELECT 1 FROM account_friend_links) THEN
        RAISE EXCEPTION 'SOCIAL_ACCOUNT_UUID_MIGRATION_BLOCKED: retained legacy Account references require genuine Account-owner mapping and disposition; no rows were changed';
    END IF;
END $$;
-- [jooq ignore stop]

ALTER TABLE guilds ALTER COLUMN owner_account_id TYPE UUID -- [jooq ignore start]
USING owner_account_id::text::uuid -- [jooq ignore stop]
;
ALTER TABLE guild_members ALTER COLUMN account_id TYPE UUID -- [jooq ignore start]
USING account_id::text::uuid -- [jooq ignore stop]
;
ALTER TABLE chat_messages ALTER COLUMN sender_account_id TYPE UUID -- [jooq ignore start]
USING sender_account_id::text::uuid -- [jooq ignore stop]
;
ALTER TABLE chat_messages ALTER COLUMN recipient_account_id TYPE UUID -- [jooq ignore start]
USING recipient_account_id::text::uuid -- [jooq ignore stop]
;
ALTER TABLE friend_links ALTER COLUMN account_id TYPE UUID -- [jooq ignore start]
USING account_id::text::uuid -- [jooq ignore stop]
;
ALTER TABLE friend_links ALTER COLUMN friend_account_id TYPE UUID -- [jooq ignore start]
USING friend_account_id::text::uuid -- [jooq ignore stop]
;
ALTER TABLE mail_messages ALTER COLUMN sender_account_id TYPE UUID -- [jooq ignore start]
USING sender_account_id::text::uuid -- [jooq ignore stop]
;
ALTER TABLE mail_messages ALTER COLUMN recipient_account_id TYPE UUID -- [jooq ignore start]
USING recipient_account_id::text::uuid -- [jooq ignore stop]
;
ALTER TABLE account_friend_links ALTER COLUMN account_id TYPE UUID -- [jooq ignore start]
USING account_id::text::uuid -- [jooq ignore stop]
;
ALTER TABLE account_friend_links ALTER COLUMN friend_account_id TYPE UUID -- [jooq ignore start]
USING friend_account_id::text::uuid -- [jooq ignore stop]
;

ALTER TABLE guilds ADD CONSTRAINT ck_guild_owner_account_uuid_non_nil
    CHECK (owner_account_id <> '00000000-0000-0000-0000-000000000000'::uuid);
ALTER TABLE guild_members ADD CONSTRAINT ck_guild_member_account_uuid_non_nil
    CHECK (account_id <> '00000000-0000-0000-0000-000000000000'::uuid);
ALTER TABLE chat_messages ADD CONSTRAINT ck_chat_sender_account_uuid_non_nil
    CHECK (sender_account_id <> '00000000-0000-0000-0000-000000000000'::uuid);
ALTER TABLE chat_messages ADD CONSTRAINT ck_chat_recipient_account_uuid_non_nil
    CHECK (recipient_account_id <> '00000000-0000-0000-0000-000000000000'::uuid);
ALTER TABLE friend_links ADD CONSTRAINT ck_friend_account_uuid_non_nil
    CHECK (account_id <> '00000000-0000-0000-0000-000000000000'::uuid);
ALTER TABLE friend_links ADD CONSTRAINT ck_friend_subject_uuid_non_nil
    CHECK (friend_account_id <> '00000000-0000-0000-0000-000000000000'::uuid);
ALTER TABLE mail_messages ADD CONSTRAINT ck_mail_sender_account_uuid_non_nil
    CHECK (sender_account_id <> '00000000-0000-0000-0000-000000000000'::uuid);
ALTER TABLE mail_messages ADD CONSTRAINT ck_mail_recipient_account_uuid_non_nil
    CHECK (recipient_account_id <> '00000000-0000-0000-0000-000000000000'::uuid);
ALTER TABLE account_friend_links ADD CONSTRAINT ck_account_friend_account_uuid_non_nil
    CHECK (account_id <> '00000000-0000-0000-0000-000000000000'::uuid);
ALTER TABLE account_friend_links ADD CONSTRAINT ck_account_friend_subject_uuid_non_nil
    CHECK (friend_account_id <> '00000000-0000-0000-0000-000000000000'::uuid);
