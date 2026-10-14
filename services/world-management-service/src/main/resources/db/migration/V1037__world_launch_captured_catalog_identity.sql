-- Retain the exact Game Session launch-captured realm/catalog tuple. Historical World rows and
-- original preparation bytes remain unchanged; only new canonical associations/preparations can
-- carry this complete identity.
ALTER TABLE world_instance
    ADD COLUMN canonical_realm_id UUID,
    ADD COLUMN catalog_revision BIGINT,
    ADD CONSTRAINT ck_world_instance_launch_catalog_identity CHECK (
        (canonical_realm_id IS NULL AND catalog_revision IS NULL)
        OR (canonical_realm_id IS NOT NULL
            AND canonical_realm_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND catalog_revision IS NOT NULL
            AND catalog_revision > 0)
    );

ALTER TABLE world_instance
    ADD CONSTRAINT uq_world_instance_canonical_catalog_association_fk
        UNIQUE (
            id,
            canonical_game_instance_id,
            canonical_target_namespace,
            canonical_tenant_id,
            canonical_world_slug,
            playable_state_namespace_id,
            playable_state_scope,
            public_production,
            control_plane_request_id,
            canonical_launch_binding_operation_id,
            canonical_realm_id,
            catalog_revision
        );

ALTER TABLE world_canonical_instance_association
    ADD COLUMN canonical_realm_id UUID,
    ADD COLUMN catalog_revision BIGINT;

ALTER TABLE world_canonical_instance_association
    DROP CONSTRAINT ck_world_canonical_instance_association_schema;

ALTER TABLE world_canonical_instance_association
    ADD CONSTRAINT ck_world_canonical_instance_association_schema CHECK (
        (schema_version = 1 AND canonical_realm_id IS NULL AND catalog_revision IS NULL)
        OR (schema_version = 2
            AND canonical_realm_id IS NOT NULL
            AND canonical_realm_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND catalog_revision IS NOT NULL
            AND catalog_revision > 0)
    );

ALTER TABLE world_canonical_instance_association
    ADD CONSTRAINT fk_world_canonical_instance_association_catalog_world_row
        FOREIGN KEY (
            world_instance_id,
            canonical_game_instance_id,
            canonical_target_namespace,
            canonical_tenant_id,
            canonical_world_slug,
            playable_state_namespace_id,
            playable_state_scope,
            public_production,
            control_plane_request_id,
            canonical_launch_binding_operation_id,
            canonical_realm_id,
            catalog_revision
        ) REFERENCES world_instance (
            id,
            canonical_game_instance_id,
            canonical_target_namespace,
            canonical_tenant_id,
            canonical_world_slug,
            playable_state_namespace_id,
            playable_state_scope,
            public_production,
            control_plane_request_id,
            canonical_launch_binding_operation_id,
            canonical_realm_id,
            catalog_revision
        ) ON DELETE RESTRICT;

-- Keep V34 insert-born immutability, while allowing old retained rows to stay NULL and requiring
-- the complete captured pair on every new canonical World instance insert.
-- [jooq ignore start]
DO $migration$
DECLARE
    original TEXT;
    null_identity_anchor TEXT := $anchor$                OR NEW.canonical_launch_binding_operation_id IS NOT NULL THEN$anchor$;
    immutable_anchor TEXT := $anchor$            OR OLD.canonical_launch_binding_operation_id
                IS DISTINCT FROM NEW.canonical_launch_binding_operation_id THEN$anchor$;
    insert_anchor TEXT := $anchor$    IF NEW.canonical_game_instance_id IS NULL THEN
        RETURN NEW;
    END IF;$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_validate_canonical_prepare_identity()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,null_identity_anchor,'')))/length(null_identity_anchor) <> 1
        OR (length(original)-length(replace(original,immutable_anchor,'')))/length(immutable_anchor) <> 1
        OR (length(original)-length(replace(original,insert_anchor,'')))/length(insert_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one V34 canonical World identity trigger anchor';
    END IF;
    original := replace(original,
        null_identity_anchor,
        $replacement$                OR NEW.canonical_launch_binding_operation_id IS NOT NULL
                OR NEW.canonical_realm_id IS NOT NULL
                OR NEW.catalog_revision IS NOT NULL THEN$replacement$);
    original := replace(original,
        immutable_anchor,
        $replacement$            OR OLD.canonical_launch_binding_operation_id
                IS DISTINCT FROM NEW.canonical_launch_binding_operation_id
            OR OLD.canonical_realm_id IS DISTINCT FROM NEW.canonical_realm_id
            OR OLD.catalog_revision IS DISTINCT FROM NEW.catalog_revision THEN$replacement$);
    original := replace(original,
        insert_anchor,
        $replacement$    IF NEW.canonical_game_instance_id IS NULL THEN
        RETURN NEW;
    END IF;
    IF NEW.canonical_realm_id IS NULL
        OR NEW.canonical_realm_id = '00000000-0000-0000-0000-000000000000'::UUID
        OR NEW.catalog_revision IS NULL
        OR NEW.catalog_revision <= 0 THEN
        RAISE EXCEPTION 'New canonical World instance requires launch-captured realm and catalog revision'
            USING ERRCODE = '23514';
    END IF;$replacement$);
    EXECUTE original;
END;
$migration$;

-- Association schema 1 remains historical and incomplete; all new association inserts use schema 2
-- and must exactly match the catalog identity on their insert-born World row.
DO $migration$
DECLARE
    original TEXT;
    begin_anchor TEXT := $anchor$BEGIN
    SELECT * INTO instance FROM world_instance WHERE id = NEW.world_instance_id FOR KEY SHARE;$anchor$;
    equality_anchor TEXT := $anchor$        OR instance.public_production IS DISTINCT FROM NEW.public_production$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_validate_canonical_instance_association()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,begin_anchor,'')))/length(begin_anchor) <> 1
        OR (length(original)-length(replace(original,equality_anchor,'')))/length(equality_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one V34 canonical association trigger anchor';
    END IF;
    original := replace(original,
        begin_anchor,
        $replacement$BEGIN
    IF NEW.schema_version IS DISTINCT FROM 2
        OR NEW.canonical_realm_id IS NULL
        OR NEW.canonical_realm_id = '00000000-0000-0000-0000-000000000000'::UUID
        OR NEW.catalog_revision IS NULL
        OR NEW.catalog_revision <= 0 THEN
        RAISE EXCEPTION 'New canonical World association requires complete launch-captured catalog identity'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO instance FROM world_instance WHERE id = NEW.world_instance_id FOR KEY SHARE;$replacement$);
    original := replace(original,
        equality_anchor,
        $replacement$        OR instance.public_production IS DISTINCT FROM NEW.public_production
        OR instance.canonical_realm_id IS DISTINCT FROM NEW.canonical_realm_id
        OR instance.catalog_revision IS DISTINCT FROM NEW.catalog_revision$replacement$);
    EXECUTE original;
END;
$migration$;

-- Keep V62's protected execution wrapper intact. Widen only its private V61 implementation, which
-- retains the V35/V40/V42/V61 owner guards, plus the selector verifier they call.
DO $migration$
DECLARE
    original TEXT;
    schema_anchor TEXT := $anchor$    IF p->>'schemaVersion' IS NULL OR p->>'schemaVersion' NOT IN ('1','2') THEN$anchor$;
    identity_anchor TEXT := $anchor$        OR ident->>'publicProduction' IS DISTINCT FROM gs_evidence->>'publicProduction'$anchor$;
    row_anchor TEXT := $anchor$            'canonical_launch_binding_operation_id', binding_row.binding_operation_id));$anchor$;
    retry_anchor TEXT := $anchor$        IF p->>'schemaVersion' = '2' AND NOT EXISTS ($anchor$;
    release_profile_anchor TEXT := $anchor$                AND binding_row.release_attestation_json::JSONB->>'schemaVersion' IN ('2', '3')
                AND participant->>'digestSchemaVersion' = CASE
                    binding_row.release_attestation_json::JSONB->>'schemaVersion'
                    WHEN '2' THEN '3' WHEN '3' THEN '4' ELSE NULL END
                AND binding_row.release_attestation_json::JSONB->'worldStartLocationEvidence'->'request'->>'digestSchemaVersion'
                    = capture_row.freeze_request_json::JSONB->>'digestSchemaVersion'$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_prepare_canonical_instance_v61_impl(text,text)'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,schema_anchor,'')))/length(schema_anchor) <> 1
        OR (length(original)-length(replace(original,identity_anchor,'')))/length(identity_anchor) <> 1
        OR (length(original)-length(replace(original,row_anchor,'')))/length(row_anchor) <> 1
        OR (length(original)-length(replace(original,retry_anchor,'')))/length(retry_anchor) <> 1
        OR (length(original)-length(replace(original,release_profile_anchor,'')))/length(release_profile_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one V61 implementation preparation guard anchor';
    END IF;
    original := replace(original,schema_anchor,
        $replacement$    IF p->>'schemaVersion' IS NULL OR p->>'schemaVersion' NOT IN ('3','4') THEN$replacement$);
    original := replace(original,identity_anchor,
        $replacement$        OR ident->>'publicProduction' IS DISTINCT FROM gs_evidence->>'publicProduction'
        OR ident->>'canonicalRealmId' IS NULL
        OR ident->>'canonicalRealmId' IS DISTINCT FROM gs_evidence->>'canonicalRealmId'
        OR (ident->>'canonicalRealmId')::UUID = '00000000-0000-0000-0000-000000000000'::UUID
        OR ident->>'canonicalRealmId' IS DISTINCT FROM (ident->>'canonicalRealmId')::UUID::TEXT
        OR jsonb_typeof(ident->'catalogRevision') IS DISTINCT FROM 'number'
        OR ident->>'catalogRevision' !~ '^[1-9][0-9]*$'
        OR ident->>'catalogRevision' IS DISTINCT FROM gs_evidence->>'catalogRevision'$replacement$);
    original := replace(original,row_anchor,
        $replacement$            'canonical_launch_binding_operation_id', binding_row.binding_operation_id,
            'canonical_realm_id', (ident->>'canonicalRealmId')::UUID,
            'catalog_revision', (ident->>'catalogRevision')::BIGINT));$replacement$);
    original := replace(original,retry_anchor,
        $replacement$        IF p->>'schemaVersion' = '4' AND NOT EXISTS ($replacement$);
    original := replace(original,release_profile_anchor,
        $replacement$                AND binding_row.release_attestation_json::JSONB->>'schemaVersion' IN ('2', '3', '4')
                AND participant->>'digestSchemaVersion' = CASE
                    binding_row.release_attestation_json::JSONB->>'schemaVersion'
                    WHEN '2' THEN '3' WHEN '3' THEN '4' WHEN '4' THEN '4' ELSE NULL END
                AND binding_row.release_attestation_json::JSONB->'worldStartLocationEvidence'->'request'->>'digestSchemaVersion'
                    = capture_row.freeze_request_json::JSONB->>'digestSchemaVersion'$replacement$);
    EXECUTE original;
END;
$migration$;

-- Preserve the V61 retained schema-2/3 selector semantics and add the accepted selected-full
-- schema-4 attestation with its exact World/4 selector. Historical preparation v1/v2 remain inert.
DO $migration$
DECLARE
    original TEXT;
    no_location_anchor TEXT := $anchor$    IF input->>'schemaVersion' = '1' THEN$anchor$;
    with_location_anchor TEXT := $anchor$    IF input->>'schemaVersion' IS DISTINCT FROM '2'
        OR binding->>'schemaVersion' IS NULL
        OR binding->>'schemaVersion' NOT IN ('2', '3')
        OR input->>'worldStartLocationEvidenceBase64' IS NULL$anchor$;
    selector_digest_anchor TEXT := $anchor$        OR selected->>'digestSchemaVersion' IS DISTINCT FROM (CASE binding->>'schemaVersion'
            WHEN '2' THEN '3' WHEN '3' THEN '4' ELSE NULL END)$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_require_preparation_start_location(jsonb,jsonb,uuid)'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,no_location_anchor,'')))/length(no_location_anchor) <> 1
        OR (length(original)-length(replace(original,with_location_anchor,'')))/length(with_location_anchor) <> 1
        OR (length(original)-length(replace(original,selector_digest_anchor,'')))/length(selector_digest_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one V42 preparation selector schema anchor';
    END IF;
    original := replace(original,no_location_anchor,
        $replacement$    IF input->>'schemaVersion' = '3' THEN$replacement$);
    original := replace(original,
        with_location_anchor,
        $replacement$    IF input->>'schemaVersion' IS DISTINCT FROM '4'
        OR binding->>'schemaVersion' IS NULL
        OR binding->>'schemaVersion' NOT IN ('2', '3', '4')
        OR input->>'worldStartLocationEvidenceBase64' IS NULL$replacement$);
    original := replace(original,selector_digest_anchor,
        $replacement$        OR selected->>'digestSchemaVersion' IS DISTINCT FROM (CASE binding->>'schemaVersion'
            WHEN '2' THEN '3' WHEN '3' THEN '4' WHEN '4' THEN '4' ELSE NULL END)$replacement$);
    EXECUTE original;
END;
$migration$;

-- Keep the V42 start-location retention/readback trigger scoped to schema 4 only.
DO $migration$
DECLARE
    original TEXT;
    retain_anchor TEXT := $anchor$    IF NEW.input_json::JSONB->>'schemaVersion' = '1' THEN RETURN NEW; END IF;$anchor$;
    guard_anchor TEXT := $anchor$    IF original->>'schemaVersion' IS DISTINCT FROM '2'$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_retain_preparation_start_location()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,retain_anchor,'')))/length(retain_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one V42 start-location retention anchor';
    END IF;
    original := replace(original,retain_anchor,
        $replacement$    IF NEW.input_json::JSONB->>'schemaVersion' = '3' THEN RETURN NEW; END IF;$replacement$);
    EXECUTE original;

    SELECT pg_get_functiondef('"${serviceSchema}".world_guard_preparation_start_location()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,guard_anchor,'')))/length(guard_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one V42 start-location guard anchor';
    END IF;
    original := replace(original,guard_anchor,
        $replacement$    IF original->>'schemaVersion' IS DISTINCT FROM '4'$replacement$);
    EXECUTE original;
END;
$migration$;

REVOKE ALL ON FUNCTION world_validate_canonical_prepare_identity() FROM PUBLIC;
REVOKE ALL ON FUNCTION world_validate_canonical_instance_association() FROM PUBLIC;
REVOKE ALL ON FUNCTION world_prepare_canonical_instance(TEXT,TEXT) FROM PUBLIC;
-- [jooq ignore stop]
