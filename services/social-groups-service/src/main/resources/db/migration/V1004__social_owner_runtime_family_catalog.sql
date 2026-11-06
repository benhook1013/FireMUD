-- This additive owner-local source records installed schema/classification evidence only. It does
-- not change social payload, relationship, replay, history, sequence, Saga, or authority rows.
CREATE TABLE social_runtime_family_classification (
    family_id VARCHAR(127) PRIMARY KEY,
    relation_name VARCHAR(63) NOT NULL,
    component_name VARCHAR(63) NOT NULL DEFAULT '',
    family_role VARCHAR(40) NOT NULL CHECK (family_role IN
        ('GUILD_DEFINITION_ACL','GUILD_MEMBERSHIP_ACL','SOCIAL_RELATIONSHIP','OWNERSHIP_DRIFT',
         'PLAYER_HISTORY','EFFECT_REPLAY','ACCOUNT_IDENTITY_CARRIER','OPERATIONAL_EVIDENCE',
         'MIGRATION_HISTORY','CATALOG')),
    fresh_disposition VARCHAR(72) NOT NULL CHECK (fresh_disposition IN
        ('GUILD_SUBJECT_TYPE_UNSUPPORTED','GUILD_CHILD_TENANT_IDENTITY_UNPROVEN',
         'RELATIONSHIP_BLOCK_LIFECYCLE_UNIMPLEMENTED','ACCOUNT_PAIR_SCOPE_DRIFT',
         'ENTITY_VALUE_OWNERSHIP_DRIFT_BLOCKING',
         'CHAT_HISTORY_RETENTION_VIEW_AND_WHISPER_SCOPE_UNPROVEN',
         'CHAT_EFFECT_REPLAY_DIGEST_AND_HORIZON_UNPROVEN',
         'MAIL_HISTORY_RETENTION_AND_ACKNOWLEDGEMENT_UNPROVEN',
         'ACCOUNT_IDENTITY_CARRIER_ONLY','SAGA_ADOPTER_UNRESOLVED',
         'MIGRATION_HISTORY_ONLY','CATALOG_ONLY')),
    target_owner VARCHAR(40) NOT NULL CHECK (target_owner IN
        ('NONE','ACCOUNT_SERVICE','ENTITY_MANAGEMENT_SERVICE')),
    UNIQUE (relation_name, component_name),
    CHECK (relation_name ~ '^[a-z][a-z0-9_]{0,62}$'),
    CHECK (component_name = '' OR component_name ~ '^[a-z][a-z0-9_]{0,62}$'),
    CHECK (family_id = relation_name || CASE WHEN component_name = '' THEN '' ELSE '.' || component_name END),
    CHECK (
        (fresh_disposition = 'GUILD_SUBJECT_TYPE_UNSUPPORTED'
            AND family_role IN ('GUILD_DEFINITION_ACL','GUILD_MEMBERSHIP_ACL') AND target_owner='NONE')
        OR (fresh_disposition IN ('GUILD_CHILD_TENANT_IDENTITY_UNPROVEN',
                                  'RELATIONSHIP_BLOCK_LIFECYCLE_UNIMPLEMENTED',
                                  'ACCOUNT_PAIR_SCOPE_DRIFT')
            AND family_role='SOCIAL_RELATIONSHIP' AND target_owner='NONE')
        OR (fresh_disposition='ENTITY_VALUE_OWNERSHIP_DRIFT_BLOCKING'
            AND family_role='OWNERSHIP_DRIFT' AND target_owner='ENTITY_MANAGEMENT_SERVICE')
        OR (fresh_disposition IN ('CHAT_HISTORY_RETENTION_VIEW_AND_WHISPER_SCOPE_UNPROVEN',
                                  'MAIL_HISTORY_RETENTION_AND_ACKNOWLEDGEMENT_UNPROVEN')
            AND family_role='PLAYER_HISTORY' AND target_owner='NONE')
        OR (fresh_disposition='CHAT_EFFECT_REPLAY_DIGEST_AND_HORIZON_UNPROVEN'
            AND family_role='EFFECT_REPLAY' AND target_owner='NONE')
        OR (fresh_disposition='ACCOUNT_IDENTITY_CARRIER_ONLY'
            AND family_role='ACCOUNT_IDENTITY_CARRIER' AND target_owner='ACCOUNT_SERVICE')
        OR (fresh_disposition='SAGA_ADOPTER_UNRESOLVED'
            AND family_role='OPERATIONAL_EVIDENCE' AND target_owner='NONE')
        OR (fresh_disposition='MIGRATION_HISTORY_ONLY'
            AND family_role='MIGRATION_HISTORY' AND target_owner='NONE')
        OR (fresh_disposition='CATALOG_ONLY' AND family_role='CATALOG' AND target_owner='NONE')
    )
);

CREATE TABLE social_runtime_family_catalog_epoch (
    catalog_epoch BIGINT PRIMARY KEY CHECK (catalog_epoch > 0),
    catalog_bytes BYTEA NOT NULL CHECK (octet_length(catalog_bytes) BETWEEN 1 AND 2097152),
    catalog_digest VARCHAR(71) NOT NULL CHECK
        (catalog_digest = 'sha256:' || encode(sha256(catalog_bytes), 'hex')),
    installed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE social_runtime_family_catalog_snapshot (
    read_request_id UUID PRIMARY KEY CHECK (read_request_id <> '00000000-0000-0000-0000-000000000000'),
    target_namespace VARCHAR(63) NOT NULL CHECK
        (target_namespace ~ '^[a-z0-9]([-a-z0-9]*[a-z0-9])?$'),
    request_bytes BYTEA NOT NULL CHECK (octet_length(request_bytes) BETWEEN 1 AND 1024),
    request_digest VARCHAR(71) NOT NULL CHECK
        (request_digest = 'sha256:' || encode(sha256(request_bytes), 'hex')),
    snapshot_id UUID NOT NULL UNIQUE CHECK (snapshot_id <> '00000000-0000-0000-0000-000000000000'),
    catalog_epoch BIGINT NOT NULL REFERENCES social_runtime_family_catalog_epoch(catalog_epoch),
    catalog_digest VARCHAR(71) NOT NULL,
    evidence_bytes BYTEA NOT NULL CHECK (octet_length(evidence_bytes) BETWEEN 1 AND 2101248),
    evidence_digest VARCHAR(71) NOT NULL CHECK
        (evidence_digest = 'sha256:' || encode(sha256(evidence_bytes), 'hex')),
    retained_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- Every installed Social-family table receives one owner-local role/disposition. Specialized
-- column families make Account UUID carriers and the chat-only effect replay key visible without
-- pretending those columns prove a row-level FRESH manifest or initialized empty target.
-- [jooq ignore start]
INSERT INTO social_runtime_family_classification
    (family_id, relation_name, component_name, family_role, fresh_disposition, target_owner)
VALUES
    ('guilds','guilds','','GUILD_DEFINITION_ACL','GUILD_SUBJECT_TYPE_UNSUPPORTED','NONE'),
    ('guilds.owner_account_id','guilds','owner_account_id','ACCOUNT_IDENTITY_CARRIER','ACCOUNT_IDENTITY_CARRIER_ONLY','ACCOUNT_SERVICE'),
    ('guild_members','guild_members','','GUILD_MEMBERSHIP_ACL','GUILD_SUBJECT_TYPE_UNSUPPORTED','NONE'),
    ('guild_members.account_id','guild_members','account_id','ACCOUNT_IDENTITY_CARRIER','ACCOUNT_IDENTITY_CARRIER_ONLY','ACCOUNT_SERVICE'),
    ('guild_alliances','guild_alliances','','SOCIAL_RELATIONSHIP','GUILD_CHILD_TENANT_IDENTITY_UNPROVEN','NONE'),
    ('friend_links','friend_links','','SOCIAL_RELATIONSHIP','RELATIONSHIP_BLOCK_LIFECYCLE_UNIMPLEMENTED','NONE'),
    ('friend_links.account_id','friend_links','account_id','ACCOUNT_IDENTITY_CARRIER','ACCOUNT_IDENTITY_CARRIER_ONLY','ACCOUNT_SERVICE'),
    ('friend_links.friend_account_id','friend_links','friend_account_id','ACCOUNT_IDENTITY_CARRIER','ACCOUNT_IDENTITY_CARRIER_ONLY','ACCOUNT_SERVICE'),
    ('account_friend_links','account_friend_links','','SOCIAL_RELATIONSHIP','ACCOUNT_PAIR_SCOPE_DRIFT','NONE'),
    ('account_friend_links.account_id','account_friend_links','account_id','ACCOUNT_IDENTITY_CARRIER','ACCOUNT_IDENTITY_CARRIER_ONLY','ACCOUNT_SERVICE'),
    ('account_friend_links.friend_account_id','account_friend_links','friend_account_id','ACCOUNT_IDENTITY_CARRIER','ACCOUNT_IDENTITY_CARRIER_ONLY','ACCOUNT_SERVICE'),
    ('guild_storage_items','guild_storage_items','','OWNERSHIP_DRIFT','ENTITY_VALUE_OWNERSHIP_DRIFT_BLOCKING','ENTITY_MANAGEMENT_SERVICE'),
    ('chat_messages','chat_messages','','PLAYER_HISTORY','CHAT_HISTORY_RETENTION_VIEW_AND_WHISPER_SCOPE_UNPROVEN','NONE'),
    ('chat_messages.sender_account_id','chat_messages','sender_account_id','ACCOUNT_IDENTITY_CARRIER','ACCOUNT_IDENTITY_CARRIER_ONLY','ACCOUNT_SERVICE'),
    ('chat_messages.recipient_account_id','chat_messages','recipient_account_id','ACCOUNT_IDENTITY_CARRIER','ACCOUNT_IDENTITY_CARRIER_ONLY','ACCOUNT_SERVICE'),
    ('chat_messages.effect_id','chat_messages','effect_id','EFFECT_REPLAY','CHAT_EFFECT_REPLAY_DIGEST_AND_HORIZON_UNPROVEN','NONE'),
    ('mail_messages','mail_messages','','PLAYER_HISTORY','MAIL_HISTORY_RETENTION_AND_ACKNOWLEDGEMENT_UNPROVEN','NONE'),
    ('mail_messages.sender_account_id','mail_messages','sender_account_id','ACCOUNT_IDENTITY_CARRIER','ACCOUNT_IDENTITY_CARRIER_ONLY','ACCOUNT_SERVICE'),
    ('mail_messages.recipient_account_id','mail_messages','recipient_account_id','ACCOUNT_IDENTITY_CARRIER','ACCOUNT_IDENTITY_CARRIER_ONLY','ACCOUNT_SERVICE'),
    ('saga_instance','saga_instance','','OPERATIONAL_EVIDENCE','SAGA_ADOPTER_UNRESOLVED','NONE'),
    ('saga_step','saga_step','','OPERATIONAL_EVIDENCE','SAGA_ADOPTER_UNRESOLVED','NONE'),
    ('social_runtime_family_classification','social_runtime_family_classification','','CATALOG','CATALOG_ONLY','NONE'),
    ('social_runtime_family_catalog_epoch','social_runtime_family_catalog_epoch','','CATALOG','CATALOG_ONLY','NONE'),
    ('social_runtime_family_catalog_snapshot','social_runtime_family_catalog_snapshot','','CATALOG','CATALOG_ONLY','NONE'),
    ('${flyway:table}','${flyway:table}','','MIGRATION_HISTORY','MIGRATION_HISTORY_ONLY','NONE');

-- This function deliberately uses Flyway 13.12's actual configured history-table placeholder.
-- The own V1004 history row is post-transaction, represented by the stable installation marker;
-- the installed-row check below separately requires its exact successful script identity.
CREATE FUNCTION social_runtime_family_catalog_installed() RETURNS BOOLEAN
LANGUAGE SQL STABLE
SET search_path = "${serviceSchema}", pg_catalog
AS $$
    SELECT EXISTS (SELECT 1 FROM "${flyway:table}"
        WHERE version='1004'
          AND script='V1004__social_owner_runtime_family_catalog.sql'
          AND success);
$$;

CREATE FUNCTION social_runtime_family_catalog_bytes(p_epoch BIGINT) RETURNS BYTEA
LANGUAGE plpgsql STABLE
SET search_path = "${serviceSchema}", pg_catalog
AS $$
DECLARE
    v_families JSONB;
    v_facts JSONB;
BEGIN
    IF p_epoch IS NULL OR p_epoch <= 0 THEN
        RAISE EXCEPTION 'Positive Social catalog epoch required' USING ERRCODE='23514';
    END IF;
    IF EXISTS (
        SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='${serviceSchema}' AND c.relkind IN ('r','p','f')
        EXCEPT SELECT relation_name FROM social_runtime_family_classification WHERE component_name=''
    ) OR EXISTS (
        SELECT 1 FROM social_runtime_family_classification f
        LEFT JOIN pg_namespace n ON n.nspname='${serviceSchema}'
        LEFT JOIN pg_class c ON c.relnamespace=n.oid AND c.relname=f.relation_name AND c.relkind IN ('r','p','f')
        WHERE c.oid IS NULL OR (f.component_name<>'' AND NOT EXISTS (
            SELECT 1 FROM pg_attribute a WHERE a.attrelid=c.oid AND a.attname=f.component_name
                AND a.attnum>0 AND NOT a.attisdropped))
    ) THEN
        RAISE EXCEPTION 'Unclassified or changed Social schema family' USING ERRCODE='23514';
    END IF;

    SELECT jsonb_agg(jsonb_build_object('familyId',family_id,'relation',relation_name,
        'component',component_name,'role',family_role,'freshDisposition',fresh_disposition,
        'targetOwner',target_owner) ORDER BY family_id COLLATE "C")
    INTO v_families FROM social_runtime_family_classification;

    WITH relations AS (
        SELECT c.* FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='${serviceSchema}'
    ), definitions(kind,identity,definition) AS (
        SELECT 'TABLE',c.relname::TEXT,jsonb_build_array(c.relkind,c.relpersistence,
            c.relrowsecurity,c.relforcerowsecurity,c.reloptions,pg_get_partkeydef(c.oid))::TEXT
        FROM relations c WHERE c.relkind IN ('r','p','f')
        UNION ALL
        SELECT 'COLUMN',c.relname||'.'||a.attname,jsonb_build_array(a.attnum,
            format_type(a.atttypid,a.atttypmod),a.attnotnull,a.attidentity,a.attgenerated,
            pg_get_expr(d.adbin,d.adrelid),co.collname)::TEXT
        FROM relations c JOIN pg_attribute a ON a.attrelid=c.oid
        LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
        LEFT JOIN pg_collation co ON co.oid=a.attcollation
        WHERE c.relkind IN ('r','p','f','v','m') AND a.attnum>0 AND NOT a.attisdropped
        UNION ALL
        SELECT 'CONSTRAINT',c.relname||'.'||k.conname,
            jsonb_build_array(pg_get_constraintdef(k.oid,true),k.convalidated,k.condeferrable,k.condeferred)::TEXT
        FROM relations c JOIN pg_constraint k ON k.conrelid=c.oid
        UNION ALL
        SELECT 'INDEX',c.relname::TEXT,jsonb_build_array(pg_get_indexdef(c.oid),i.indisvalid,i.indisready)::TEXT
        FROM relations c JOIN pg_index i ON i.indexrelid=c.oid
        UNION ALL
        SELECT 'TRIGGER',c.relname||'.'||t.tgname,jsonb_build_array(pg_get_triggerdef(t.oid,true),t.tgenabled)::TEXT
        FROM relations c JOIN pg_trigger t ON t.tgrelid=c.oid WHERE NOT t.tgisinternal
        UNION ALL
        SELECT 'POLICY',c.relname||'.'||p.polname,
            jsonb_build_array(p.polcmd,p.polpermissive,
                (SELECT jsonb_agg(r.rolname ORDER BY r.rolname COLLATE "C") FROM pg_roles r WHERE r.oid=ANY(p.polroles)),
                pg_get_expr(p.polqual,p.polrelid),pg_get_expr(p.polwithcheck,p.polrelid))::TEXT
        FROM relations c JOIN pg_policy p ON p.polrelid=c.oid
        UNION ALL
        SELECT 'RULE',c.relname||'.'||r.rulename,pg_get_ruledef(r.oid,true)
        FROM relations c JOIN pg_rewrite r ON r.ev_class=c.oid
        UNION ALL
        SELECT 'ROUTINE',p.proname||'('||pg_get_function_identity_arguments(p.oid)||')',pg_get_functiondef(p.oid)
        FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
        WHERE n.nspname='${serviceSchema}' AND p.prokind IN ('f','p')
        UNION ALL
        SELECT 'SEQUENCE',c.relname::TEXT,(to_jsonb(s)-'seqrelid')::TEXT
        FROM relations c JOIN pg_sequence s ON s.seqrelid=c.oid
        UNION ALL
        SELECT 'VIEW',c.relname::TEXT,pg_get_viewdef(c.oid,true)
        FROM relations c WHERE c.relkind IN ('v','m')
        UNION ALL
        SELECT 'TYPE',t.typname::TEXT,jsonb_build_array(t.typtype,t.typnotnull,t.typdefault,
            (SELECT jsonb_agg(e.enumlabel ORDER BY e.enumsortorder) FROM pg_enum e WHERE e.enumtypid=t.oid))::TEXT
        FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='${serviceSchema}'
        UNION ALL
        SELECT 'MIGRATION',installed_rank::TEXT||':'||coalesce(version,'repeatable')||':'||script,
            jsonb_build_array(version,type,script,checksum,success)::TEXT
        FROM "${flyway:table}" WHERE version IS DISTINCT FROM '1004'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1004','V1004__social_owner_runtime_family_catalog.sql'
    )
    SELECT jsonb_agg(jsonb_build_object('kind',kind,'identity',identity,
        'definitionDigest','sha256:'||encode(sha256(convert_to(definition,'UTF8')),'hex'))
        ORDER BY (kind||'/'||identity) COLLATE "C") INTO v_facts FROM definitions;

    IF jsonb_array_length(v_families)>512 OR jsonb_array_length(v_facts)>16384 THEN
        RAISE EXCEPTION 'Complete Social catalog exceeds source bounds' USING ERRCODE='23514';
    END IF;
    RETURN convert_to(jsonb_build_object('schema','social-runtime-family-catalog/v1',
        'owner','social-groups-service','catalogEpoch',p_epoch::TEXT,
        'families',v_families,'schemaFacts',v_facts,
        'unimplementedContracts',jsonb_build_array(
            'CHAT_RESTRICTION_AND_REVISION',
            'DIRECTIONAL_BLOCK_LIFECYCLE',
            'ENTITY_ESCROW_REFERENCE_FAMILIES',
            'GAMEPLAY_WHISPER_NAMESPACE_SCOPE',
            'SAFETY_EVIDENCE_AND_CONTENT_FREE_RECEIPTS',
            'SOCIAL_PLAYER_STANDING_OWNER',
            'SOCIAL_SAGA_ADOPTER_CLASSIFICATION'))::TEXT,'UTF8');
END;
$$;

CREATE FUNCTION social_runtime_family_catalog_immutable() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Social runtime catalog evidence is immutable' USING ERRCODE='23514';
END;
$$;

CREATE TRIGGER social_runtime_family_classification_retained
BEFORE UPDATE OR DELETE OR TRUNCATE ON social_runtime_family_classification
FOR EACH STATEMENT EXECUTE FUNCTION social_runtime_family_catalog_immutable();
CREATE TRIGGER social_runtime_family_epoch_retained
BEFORE UPDATE OR DELETE OR TRUNCATE ON social_runtime_family_catalog_epoch
FOR EACH STATEMENT EXECUTE FUNCTION social_runtime_family_catalog_immutable();
CREATE TRIGGER social_runtime_family_snapshot_retained
BEFORE UPDATE OR DELETE OR TRUNCATE ON social_runtime_family_catalog_snapshot
FOR EACH STATEMENT EXECUTE FUNCTION social_runtime_family_catalog_immutable();

CREATE FUNCTION social_runtime_family_catalog_epoch_guard() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = "${serviceSchema}", pg_catalog AS $$
BEGIN
    IF NEW.catalog_epoch<>coalesce((SELECT max(catalog_epoch) FROM social_runtime_family_catalog_epoch),0)+1
       OR NEW.catalog_bytes<>social_runtime_family_catalog_bytes(NEW.catalog_epoch) THEN
        RAISE EXCEPTION 'Social epoch must bind the exact classified installed schema' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER social_runtime_family_epoch_exact BEFORE INSERT ON social_runtime_family_catalog_epoch
FOR EACH ROW EXECUTE FUNCTION social_runtime_family_catalog_epoch_guard();

CREATE FUNCTION social_runtime_family_catalog_snapshot_guard() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = "${serviceSchema}", pg_catalog AS $$
DECLARE v_epoch social_runtime_family_catalog_epoch%ROWTYPE;
BEGIN
    SELECT * INTO STRICT v_epoch FROM social_runtime_family_catalog_epoch ORDER BY catalog_epoch DESC LIMIT 1;
    IF NEW.catalog_epoch<>v_epoch.catalog_epoch OR NEW.catalog_digest<>v_epoch.catalog_digest
       OR social_runtime_family_catalog_bytes(v_epoch.catalog_epoch)<>v_epoch.catalog_bytes
       OR NOT social_runtime_family_catalog_installed() THEN
        RAISE EXCEPTION 'Social schema/classification changed; new owner epoch required' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER social_runtime_family_snapshot_current BEFORE INSERT ON social_runtime_family_catalog_snapshot
FOR EACH ROW EXECUTE FUNCTION social_runtime_family_catalog_snapshot_guard();
-- [jooq ignore stop]

-- The current-owner seed is PostgreSQL-only because it invokes a pg_catalog census function.
-- Ordinary table/column DDL above remains visible to generated jOOQ schema access.
-- [jooq ignore start]
INSERT INTO social_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT 1,bytes,'sha256:'||encode(sha256(bytes),'hex')
FROM (SELECT social_runtime_family_catalog_bytes(1) AS bytes) seal;
-- [jooq ignore stop]
