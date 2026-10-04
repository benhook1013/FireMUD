-- Account identity is canonical only when the owning Account service can prove it.
-- Social has no authoritative mapping from its retained numeric selectors to Account UUIDs.
-- Lock every affected table before checking any rows so concurrent writes cannot pass the gate.
-- [jooq ignore start]
LOCK TABLE guilds,
    guild_members,
    chat_messages,
    mail_messages,
    account_friend_links,
    friend_links
    IN ACCESS EXCLUSIVE MODE;
-- [jooq ignore stop]

-- A populated table is evidence that requires Account-owned authoritative recovery. Refuse the
-- entire transaction unchanged; never cast, derive, map, or discard an Account identity here.
-- [jooq ignore start]
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM guilds)
        OR EXISTS (SELECT 1 FROM guild_members)
        OR EXISTS (SELECT 1 FROM chat_messages)
        OR EXISTS (SELECT 1 FROM mail_messages)
        OR EXISTS (SELECT 1 FROM account_friend_links)
        OR EXISTS (SELECT 1 FROM friend_links) THEN
        RAISE EXCEPTION 'Social Account UUID cutover requires all six affected tables to be empty; retained rows require Account-owned authoritative recovery before retry'
            USING ERRCODE = '23514',
                CONSTRAINT = 'social_account_uuid_requires_empty_tables';
    END IF;
END;
$$;
-- [jooq ignore stop]

-- The locked empty-table precondition makes NULL a non-persisted cast expression. jOOQ's DDL
-- parser does not support PostgreSQL USING clauses, so keep only the actual conversions ignored
-- and mirror the resulting UUID column types below with parser-visible ALTER declarations.
-- [jooq ignore start]
ALTER TABLE guilds ALTER COLUMN owner_account_id TYPE UUID USING CAST(NULL AS UUID);
ALTER TABLE guild_members ALTER COLUMN account_id TYPE UUID USING CAST(NULL AS UUID);
ALTER TABLE chat_messages ALTER COLUMN sender_account_id TYPE UUID USING CAST(NULL AS UUID);
ALTER TABLE chat_messages ALTER COLUMN recipient_account_id TYPE UUID USING CAST(NULL AS UUID);
ALTER TABLE mail_messages ALTER COLUMN sender_account_id TYPE UUID USING CAST(NULL AS UUID);
ALTER TABLE mail_messages ALTER COLUMN recipient_account_id TYPE UUID USING CAST(NULL AS UUID);
ALTER TABLE account_friend_links ALTER COLUMN account_id TYPE UUID USING CAST(NULL AS UUID);
ALTER TABLE account_friend_links ALTER COLUMN friend_account_id TYPE UUID USING CAST(NULL AS UUID);
ALTER TABLE friend_links ALTER COLUMN account_id TYPE UUID USING CAST(NULL AS UUID);
ALTER TABLE friend_links ALTER COLUMN friend_account_id TYPE UUID USING CAST(NULL AS UUID);
-- [jooq ignore stop]

ALTER TABLE guilds ALTER COLUMN owner_account_id TYPE UUID;
ALTER TABLE guild_members ALTER COLUMN account_id TYPE UUID;
ALTER TABLE chat_messages ALTER COLUMN sender_account_id TYPE UUID;
ALTER TABLE chat_messages ALTER COLUMN recipient_account_id TYPE UUID;
ALTER TABLE mail_messages ALTER COLUMN sender_account_id TYPE UUID;
ALTER TABLE mail_messages ALTER COLUMN recipient_account_id TYPE UUID;
ALTER TABLE account_friend_links ALTER COLUMN account_id TYPE UUID;
ALTER TABLE account_friend_links ALTER COLUMN friend_account_id TYPE UUID;
ALTER TABLE friend_links ALTER COLUMN account_id TYPE UUID;
ALTER TABLE friend_links ALTER COLUMN friend_account_id TYPE UUID;

ALTER TABLE guilds
    ADD CONSTRAINT guilds_owner_account_id_non_nil_check
        CHECK (owner_account_id <> '00000000-0000-0000-0000-000000000000'::UUID);
ALTER TABLE guild_members
    ADD CONSTRAINT guild_members_account_id_non_nil_check
        CHECK (account_id <> '00000000-0000-0000-0000-000000000000'::UUID);
ALTER TABLE chat_messages
    ADD CONSTRAINT chat_messages_sender_account_id_non_nil_check
        CHECK (sender_account_id <> '00000000-0000-0000-0000-000000000000'::UUID);
ALTER TABLE chat_messages
    ADD CONSTRAINT chat_messages_recipient_account_id_non_nil_check
        CHECK (recipient_account_id <> '00000000-0000-0000-0000-000000000000'::UUID);
ALTER TABLE mail_messages
    ADD CONSTRAINT mail_messages_sender_account_id_non_nil_check
        CHECK (sender_account_id <> '00000000-0000-0000-0000-000000000000'::UUID);
ALTER TABLE mail_messages
    ADD CONSTRAINT mail_messages_recipient_account_id_non_nil_check
        CHECK (recipient_account_id <> '00000000-0000-0000-0000-000000000000'::UUID);
ALTER TABLE account_friend_links
    ADD CONSTRAINT account_friend_links_account_id_non_nil_check
        CHECK (account_id <> '00000000-0000-0000-0000-000000000000'::UUID);
ALTER TABLE account_friend_links
    ADD CONSTRAINT account_friend_links_friend_account_id_non_nil_check
        CHECK (friend_account_id <> '00000000-0000-0000-0000-000000000000'::UUID);
ALTER TABLE friend_links
    ADD CONSTRAINT friend_links_account_id_non_nil_check
        CHECK (account_id <> '00000000-0000-0000-0000-000000000000'::UUID);
ALTER TABLE friend_links
    ADD CONSTRAINT friend_links_friend_account_id_non_nil_check
        CHECK (friend_account_id <> '00000000-0000-0000-0000-000000000000'::UUID);
