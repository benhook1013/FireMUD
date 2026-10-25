-- Permit the existing canonical quarantine state for PLAYER_CREATED actors without changing
-- their immutable creation provenance or admitting them through current roster readers.
/* [jooq ignore start] */
ALTER TABLE characters DROP CONSTRAINT ck_character_created_core;

ALTER TABLE characters ADD CONSTRAINT ck_character_created_core CHECK (
    (actor_entry_policy IS NULL AND creation_target_namespace IS NULL
        AND creation_operation_id IS NULL AND authored_initial_core_json IS NULL
        AND creation_descriptor_json IS NULL AND creation_descriptor_digest IS NULL
        AND account_id IS NOT NULL AND playable_state_key IS NOT NULL
        AND level IS NOT NULL AND experience IS NOT NULL AND strength IS NOT NULL
        AND agility IS NOT NULL AND intelligence IS NOT NULL AND stamina IS NOT NULL
        AND health IS NOT NULL AND mana IS NOT NULL AND body_layout_key IS NOT NULL)
    OR (actor_entry_policy = 'PLAYER_CREATED' AND creation_target_namespace IS NOT NULL
        AND creation_operation_id IS NOT NULL
        AND actor_identity_status IN ('OWNER_RESOLVED', 'QUARANTINED')
        AND account_id IS NULL AND playable_state_key IS NULL
        AND level IS NULL AND experience IS NULL AND strength IS NULL AND agility IS NULL
        AND intelligence IS NULL AND stamina IS NULL AND health IS NULL AND mana IS NULL
        AND body_layout_key IS NULL AND authored_initial_core_json IS NOT NULL
        AND creation_descriptor_json IS NOT NULL
        AND creation_descriptor_digest IS NOT NULL
        AND creation_descriptor_digest ~ '^sha256:[0-9a-f]{64}$'
        AND name = authored_initial_core_json::JSONB ->> 'displayName')
);
/* [jooq ignore stop] */

/* [jooq ignore start] */
DO $entity_created_actor_quarantine_guard$
DECLARE
    owner_schema TEXT := current_schema();
BEGIN
    IF owner_schema IS NULL THEN
        RAISE EXCEPTION 'Entity empty-source functions require an explicit installation schema';
    END IF;

    EXECUTE format($function$
        CREATE OR REPLACE FUNCTION %I.entity_guard_source_owner_row()
        RETURNS TRIGGER
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, %I
        AS $body$
        DECLARE
            old_value JSONB;
            new_value JSONB;
            old_tenant_key BIGINT;
            new_tenant_key BIGINT;
            old_version_key BIGINT;
            new_version_key BIGINT;
            old_is_owner_resolved_character BOOLEAN := FALSE;
            new_is_owner_resolved_character BOOLEAN := FALSE;
        BEGIN
            IF TG_OP IN ('UPDATE', 'DELETE') THEN
                old_value := to_jsonb(OLD);
                old_tenant_key := %I.entity_source_positive_numeric_key(old_value->>'tenant_id');
                old_version_key := %I.entity_source_positive_numeric_key(old_value->>'version_id');
                IF TG_TABLE_NAME = 'characters' THEN
                    PERFORM %I.entity_require_source_numeric_key_unreserved(
                        'TENANT', NULL::BIGINT);
                    SELECT EXISTS (
                        SELECT 1
                        FROM %I.entity_tenant_identities tenant_identity
                        JOIN %I.entity_playable_state_namespace_scopes namespace_scope
                            ON namespace_scope.tenant_uuid = tenant_identity.canonical_tenant_uuid
                        WHERE tenant_identity.entity_tenant_row_id = old_tenant_key
                            AND tenant_identity.canonical_tenant_uuid =
                                NULLIF(old_value->>'tenant_uuid', '')::UUID
                            AND namespace_scope.playable_state_namespace_id =
                                NULLIF(old_value->>'playable_state_namespace_id', '')::UUID
                            AND namespace_scope.playable_state_scope =
                                old_value->>'playable_state_scope'
                            AND ((old_value->>'actor_identity_status' = 'OWNER_RESOLVED'
                                    AND old_value->>'actor_identity_quarantine_reason' IS NULL)
                                OR (old_value->>'actor_entry_policy' = 'PLAYER_CREATED'
                                    AND old_value->>'actor_identity_status' = 'QUARANTINED'
                                    AND old_value->>'actor_identity_quarantine_reason' IS NOT NULL
                                    AND length(trim(old_value->>'actor_identity_quarantine_reason')) > 0))
                            AND NULLIF(old_value->>'character_uuid', '')::UUID <>
                                '00000000-0000-0000-0000-000000000000'::UUID
                            AND NULLIF(old_value->>'account_uuid', '')::UUID <>
                                '00000000-0000-0000-0000-000000000000'::UUID
                    ) INTO old_is_owner_resolved_character;
                    IF NOT old_is_owner_resolved_character THEN
                        PERFORM %I.entity_require_source_numeric_key_unreserved(
                            'TENANT', old_tenant_key);
                    END IF;
                ELSE
                    PERFORM %I.entity_require_source_numeric_key_unreserved(
                        'TENANT', old_tenant_key);
                END IF;
                PERFORM %I.entity_require_source_numeric_key_unreserved('VERSION', old_version_key);
            END IF;
            IF TG_OP IN ('INSERT', 'UPDATE') THEN
                new_value := to_jsonb(NEW);
                new_tenant_key := %I.entity_source_positive_numeric_key(new_value->>'tenant_id');
                new_version_key := %I.entity_source_positive_numeric_key(new_value->>'version_id');
                IF TG_TABLE_NAME = 'characters' THEN
                    PERFORM %I.entity_require_source_numeric_key_unreserved(
                        'TENANT', NULL::BIGINT);
                    SELECT EXISTS (
                        SELECT 1
                        FROM %I.entity_tenant_identities tenant_identity
                        JOIN %I.entity_playable_state_namespace_scopes namespace_scope
                            ON namespace_scope.tenant_uuid = tenant_identity.canonical_tenant_uuid
                        WHERE tenant_identity.entity_tenant_row_id = new_tenant_key
                            AND tenant_identity.canonical_tenant_uuid =
                                NULLIF(new_value->>'tenant_uuid', '')::UUID
                            AND namespace_scope.playable_state_namespace_id =
                                NULLIF(new_value->>'playable_state_namespace_id', '')::UUID
                            AND namespace_scope.playable_state_scope =
                                new_value->>'playable_state_scope'
                            AND ((new_value->>'actor_identity_status' = 'OWNER_RESOLVED'
                                    AND new_value->>'actor_identity_quarantine_reason' IS NULL)
                                OR (new_value->>'actor_entry_policy' = 'PLAYER_CREATED'
                                    AND new_value->>'actor_identity_status' = 'QUARANTINED'
                                    AND new_value->>'actor_identity_quarantine_reason' IS NOT NULL
                                    AND length(trim(new_value->>'actor_identity_quarantine_reason')) > 0))
                            AND NULLIF(new_value->>'character_uuid', '')::UUID <>
                                '00000000-0000-0000-0000-000000000000'::UUID
                            AND NULLIF(new_value->>'account_uuid', '')::UUID <>
                                '00000000-0000-0000-0000-000000000000'::UUID
                    ) INTO new_is_owner_resolved_character;
                    IF NOT new_is_owner_resolved_character THEN
                        PERFORM %I.entity_require_source_numeric_key_unreserved(
                            'TENANT', new_tenant_key);
                    END IF;
                ELSE
                    PERFORM %I.entity_require_source_numeric_key_unreserved(
                        'TENANT', new_tenant_key);
                END IF;
                PERFORM %I.entity_require_source_numeric_key_unreserved('VERSION', new_version_key);
            END IF;
            IF TG_OP = 'DELETE' THEN
                RETURN OLD;
            END IF;
            RETURN NEW;
        END;
        $body$
    $function$, owner_schema, owner_schema, owner_schema, owner_schema, owner_schema, owner_schema,
        owner_schema, owner_schema, owner_schema, owner_schema, owner_schema, owner_schema,
        owner_schema, owner_schema, owner_schema, owner_schema, owner_schema, owner_schema);
END;
$entity_created_actor_quarantine_guard$;
/* [jooq ignore stop] */
