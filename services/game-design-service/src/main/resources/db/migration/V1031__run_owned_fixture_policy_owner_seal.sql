-- Distinct fixed-capability fixture seal in the existing policy owner header.
-- No retained row is rewritten; original selected-publication guards remain unchanged below.
ALTER TABLE game_design_published_realm_policy_set
    ADD COLUMN fixture_operation_id UUID UNIQUE,
    ADD COLUMN fixture_request_bytes BYTEA,
    ADD COLUMN fixture_request_digest VARCHAR(71),
    ADD COLUMN fixture_evidence_digest VARCHAR(71),
    ADD COLUMN fixture_binding_json JSONB,
    ADD COLUMN fixture_policy_json JSONB;
ALTER TABLE game_design_published_realm_policy_set
    ALTER COLUMN source_commit_id DROP NOT NULL;
ALTER TABLE game_design_published_realm_policy_set
    ALTER COLUMN source_epoch DROP NOT NULL;
ALTER TABLE game_design_published_realm_policy_set
    ALTER COLUMN target_proof_json DROP NOT NULL;
ALTER TABLE game_design_published_realm_policy_set
    ALTER COLUMN operation_bytes DROP NOT NULL;
ALTER TABLE game_design_published_realm_policy_set
    ALTER COLUMN capture_bytes DROP NOT NULL;
ALTER TABLE game_design_published_realm_policy_set
    ALTER COLUMN terminal_evidence_bytes DROP NOT NULL;
ALTER TABLE game_design_published_realm_policy_set
    ALTER COLUMN release_content_bytes DROP NOT NULL;
ALTER TABLE game_design_published_realm_policy_set
    ALTER COLUMN published_release_bundle_ref DROP NOT NULL;
ALTER TABLE game_design_published_realm_policy_set
    ALTER COLUMN published_release_bundle_digest DROP NOT NULL;
ALTER TABLE game_design_published_realm_policy_set
    ALTER COLUMN publish_workflow_id DROP NOT NULL;
ALTER TABLE game_design_published_realm_policy_set
    ALTER COLUMN manifest_hash DROP NOT NULL;
ALTER TABLE game_design_published_realm_policy_set
    ALTER COLUMN publication_version_state_epoch DROP NOT NULL;
ALTER TABLE game_design_published_realm_policy_set
    ALTER COLUMN policy_count DROP NOT NULL;
ALTER TABLE game_design_published_realm_policy_set
    ALTER COLUMN policy_set_digest DROP NOT NULL;
ALTER TABLE game_design_published_realm_policy_set
    ADD CONSTRAINT ck_realm_policy_owner_evidence_variant CHECK (
        (fixture_operation_id IS NULL AND fixture_request_bytes IS NULL AND fixture_request_digest IS NULL AND fixture_evidence_digest IS NULL AND fixture_binding_json IS NULL AND fixture_policy_json IS NULL
            AND source_commit_id IS NOT NULL AND source_epoch IS NOT NULL AND target_proof_json IS NOT NULL AND operation_bytes IS NOT NULL AND capture_bytes IS NOT NULL AND terminal_evidence_bytes IS NOT NULL AND release_content_bytes IS NOT NULL AND published_release_bundle_ref IS NOT NULL AND published_release_bundle_digest IS NOT NULL AND publish_workflow_id IS NOT NULL AND manifest_hash IS NOT NULL AND publication_version_state_epoch IS NOT NULL AND policy_count IS NOT NULL AND policy_set_digest IS NOT NULL)
        OR
        (fixture_operation_id IS NOT NULL AND fixture_request_bytes IS NOT NULL AND fixture_request_digest IS NOT NULL AND fixture_evidence_digest IS NOT NULL AND fixture_binding_json IS NOT NULL AND fixture_policy_json IS NOT NULL
            AND sealed AND source_commit_id IS NULL AND source_epoch IS NULL AND target_proof_json IS NULL AND operation_bytes IS NULL AND capture_bytes IS NULL AND terminal_evidence_bytes IS NULL AND release_content_bytes IS NULL AND published_release_bundle_ref IS NULL AND published_release_bundle_digest IS NULL AND publish_workflow_id IS NULL AND manifest_hash IS NULL AND publication_version_state_epoch IS NULL AND policy_count IS NULL AND policy_set_digest IS NULL));

-- Human source_commit_id remains mandatory under the closed variant check above; only
-- verified fixtures have NULL and may omit capture. Retained rows and guards are unchanged.
ALTER TABLE game_design_realm_policy_capture
    ADD CONSTRAINT uq_realm_policy_capture_exact_commit
        UNIQUE (canonical_tenant_id, canonical_version_id, commit_id);
-- PostgreSQL and H2 name the original implicit FK differently. Both drops stay strict.
-- The H2-only statement uses jOOQ's documented parser comment technique:
-- https://www.jooq.org/doc/latest/manual/sql-building/dsl-context/custom-settings/settings-parser/
-- CONSTRAINT_145D43CA_10 is the inspected old two-column header-to-capture relationship.
-- Pinning it makes simulator naming changes fail instead of retaining stale generated metadata.
-- [jooq ignore start]
ALTER TABLE game_design_published_realm_policy_set
    DROP CONSTRAINT game_design_published_realm__canonical_tenant_id_canonica_fkey1;
-- [jooq ignore stop]
/* [jooq ignore start] */ -- /* [jooq ignore stop] */ ALTER TABLE game_design_published_realm_policy_set DROP CONSTRAINT "CONSTRAINT_145D43CA_10";
ALTER TABLE game_design_published_realm_policy_set
    ADD CONSTRAINT fk_realm_policy_owner_exact_capture
        FOREIGN KEY (canonical_tenant_id, canonical_version_id, source_commit_id)
        REFERENCES game_design_realm_policy_capture
            (canonical_tenant_id, canonical_version_id, commit_id);

-- [jooq ignore start]
-- Insert only the closed fixture arm into the current owner functions. All existing Human
-- full-Version, capture, source, release/asset and commit guards remain byte-for-byte intact.
DO $migration$
DECLARE
    current_guard TEXT;
    declaration_anchor TEXT := $anchor$    row_count BIGINT; realm_count BIGINT; production_count BIGINT; target_proof_key_count BIGINT;$anchor$;
    begin_anchor TEXT := $anchor$BEGIN
    IF TG_OP = 'DELETE' THEN$anchor$;
BEGIN
    SELECT pg_get_functiondef('guard_published_realm_policy_set()'::REGPROCEDURE) INTO STRICT current_guard;
    IF (length(current_guard)-length(replace(current_guard,declaration_anchor,'')))/length(declaration_anchor) <> 1
        OR (length(current_guard)-length(replace(current_guard,begin_anchor,'')))/length(begin_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact current Human published-policy owner guard anchors';
    END IF;
    current_guard := replace(current_guard, declaration_anchor, declaration_anchor || E'\n'
        || '    fixture_parts JSONB[]; fixture_offset INTEGER; fixture_length INTEGER; fixture_index INTEGER;');
    current_guard := replace(current_guard, begin_anchor, $replacement$BEGIN
    IF TG_OP = 'UPDATE' AND (OLD.fixture_operation_id IS NOT NULL OR NEW.fixture_operation_id IS NOT NULL) THEN
        RAISE EXCEPTION 'fixture policy seal is immutable and cannot convert owner variants' USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.fixture_operation_id IS NOT NULL THEN
        fixture_offset := 0;
        FOR fixture_index IN 1..4 LOOP
            IF fixture_offset + 4 > octet_length(NEW.fixture_request_bytes) THEN
                RAISE EXCEPTION 'fixture policy request framing is incomplete' USING ERRCODE = 'check_violation';
            END IF;
            fixture_length := (get_byte(NEW.fixture_request_bytes, fixture_offset) << 24)
                + (get_byte(NEW.fixture_request_bytes, fixture_offset + 1) << 16)
                + (get_byte(NEW.fixture_request_bytes, fixture_offset + 2) << 8)
                + get_byte(NEW.fixture_request_bytes, fixture_offset + 3);
            fixture_offset := fixture_offset + 4;
            IF fixture_length < 1 OR fixture_offset + fixture_length > octet_length(NEW.fixture_request_bytes) THEN
                RAISE EXCEPTION 'fixture policy request frame is invalid' USING ERRCODE = 'check_violation';
            END IF;
            fixture_parts[fixture_index] := convert_from(substring(NEW.fixture_request_bytes
                FROM fixture_offset + 1 FOR fixture_length), 'UTF8')::JSONB;
            IF convert_to('{' || (SELECT string_agg(to_json(key)::TEXT || ':' || value::TEXT,
                ',' ORDER BY key COLLATE "C") FROM jsonb_each(fixture_parts[fixture_index])) || '}', 'UTF8')
                IS DISTINCT FROM substring(NEW.fixture_request_bytes FROM fixture_offset + 1 FOR fixture_length) THEN
                RAISE EXCEPTION 'fixture policy request requires closed canonical JSON without duplicate fields' USING ERRCODE = 'check_violation';
            END IF;
            fixture_offset := fixture_offset + fixture_length;
        END LOOP;
        IF fixture_offset <> octet_length(NEW.fixture_request_bytes)
            OR (SELECT count(*) FROM jsonb_object_keys(fixture_parts[1])) <> 22
            OR fixture_parts[1]->>'schema' IS DISTINCT FROM 'firemud.run-owned-initial-admission-fixture.v1'
            OR jsonb_typeof(fixture_parts[1]->'runId') IS DISTINCT FROM 'string'
            OR fixture_parts[1]->>'runId' !~ '^[a-z0-9][a-z0-9-]{0,127}$'
            OR jsonb_typeof(fixture_parts[1]->'composeProjectName') IS DISTINCT FROM 'string'
            OR fixture_parts[1]->>'composeProjectName' !~ '^[a-z0-9][a-z0-9-]{0,127}$'
            OR jsonb_typeof(fixture_parts[1]->'claimDigest') IS DISTINCT FROM 'string'
            OR fixture_parts[1]->>'claimDigest' !~ '^[0-9a-f]{64}$'
            OR jsonb_typeof(fixture_parts[1]->'gameSessionLeafSha256') IS DISTINCT FROM 'string'
            OR fixture_parts[1]->>'gameSessionLeafSha256' !~ '^[0-9a-f]{64}$'
            OR jsonb_typeof(fixture_parts[1]->'worldManagementLeafSha256') IS DISTINCT FROM 'string'
            OR fixture_parts[1]->>'worldManagementLeafSha256' !~ '^[0-9a-f]{64}$'
            OR jsonb_typeof(fixture_parts[1]->'caCertificateSha256') IS DISTINCT FROM 'string'
            OR fixture_parts[1]->>'caCertificateSha256' !~ '^[0-9a-f]{64}$'
            OR fixture_parts[1]->>'operationId' !~ '^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
            OR fixture_parts[1]->>'gameSessionUriSan' IS DISTINCT FROM 'spiffe://firemud/ns/dev/sa/game-session-service'
            OR fixture_parts[1]->>'worldManagementUriSan' IS DISTINCT FROM 'spiffe://firemud/ns/dev/sa/world-management-service'
            OR fixture_parts[1]->>'operationId' IS DISTINCT FROM NEW.fixture_binding_json->>'fixtureOperationId'
            OR fixture_parts[1]->>'operationId' IS DISTINCT FROM NEW.fixture_operation_id::TEXT
            OR fixture_parts[2]->>'operationId' IS DISTINCT FROM NEW.fixture_binding_json->>'creationOperationId'
            OR fixture_parts[2]->>'evidenceDigest' IS DISTINCT FROM NEW.fixture_binding_json->>'creationEvidenceDigest'
            OR fixture_parts[2]->>'canonicalTenantId' IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
            OR fixture_parts[3]->>'operationId' IS DISTINCT FROM NEW.fixture_binding_json->>'sourceOperationId'
            OR fixture_parts[3]->>'evidenceDigest' IS DISTINCT FROM NEW.fixture_binding_json->>'sourceEvidenceDigest'
            OR fixture_parts[3]->>'canonicalTenantId' IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
            OR fixture_parts[1]->'gameTemplateId' IS DISTINCT FROM NEW.fixture_policy_json->'gameTemplateId'
            OR (fixture_parts[1] - ARRAY['schema', 'runId', 'composeProjectName', 'operationId', 'claimDigest',
                'gameSessionLeafSha256', 'gameSessionUriSan', 'caCertificateSha256',
                'worldManagementUriSan', 'worldManagementLeafSha256']) IS DISTINCT FROM NEW.fixture_policy_json
            OR fixture_parts[4]->'gameTemplateId' IS DISTINCT FROM NEW.fixture_policy_json->'gameTemplateId'
            OR fixture_parts[4]->>'schema' IS DISTINCT FROM 'firemud.run-owned-v1-launch-read-evidence.v1'
            OR (SELECT count(*) FROM jsonb_object_keys(fixture_parts[4])) <> 6 THEN
            RAISE EXCEPTION 'fixture policy request differs from its complete immutable projections' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.fixture_operation_id = '00000000-0000-0000-0000-000000000000'::UUID
            OR octet_length(NEW.fixture_request_bytes) NOT BETWEEN 1 AND 16384
            OR NEW.fixture_request_digest IS DISTINCT FROM 'sha256:' || encode(sha256(NEW.fixture_request_bytes), 'hex')
            OR NEW.fixture_evidence_digest IS DISTINCT FROM 'sha256:' || encode(sha256(
                int4send(octet_length('firemud.run-owned-fixture-policy-seal.v1'))
                || convert_to('firemud.run-owned-fixture-policy-seal.v1', 'UTF8')
                || int4send(36) || convert_to(NEW.fixture_operation_id::TEXT, 'UTF8')
                || int4send(71) || convert_to(NEW.fixture_request_digest, 'UTF8')), 'hex')
            OR jsonb_typeof(NEW.fixture_binding_json) IS DISTINCT FROM 'object'
            OR (SELECT count(*) FROM jsonb_object_keys(NEW.fixture_binding_json)) <> 17
            OR NEW.fixture_policy_json IS DISTINCT FROM jsonb_build_object(
                'worldSlug', 'demo', 'worldDisplayName', 'Demo World', 'realmSlug', 'production',
                'realmDisplayName', 'Live Realm', 'visible', TRUE, 'publicProductionRealm', TRUE,
                'requiresCharacterSelection', FALSE, 'stateScope', 'SHARED', 'characterCreationPolicy', 'ALLOW_NEW',
                'tenantId', 1, 'gameTemplateId', (NEW.fixture_binding_json->>'gameTemplateId')::BIGINT, 'ownerAccountId', 1)
            OR NOT NEW.sealed THEN
            RAISE EXCEPTION 'fixture policy seal requires closed exact fixed-capability evidence' USING ERRCODE = 'check_violation';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM version v
            JOIN game g ON g.id=v.identity_source_game_row_id AND g.tenant_id=v.identity_source_game_tenant_key
            JOIN game_tenant_creation_operations creation ON creation.source_game_row_id=g.id
            JOIN game_design_authored_world_source_operations source ON source.source_game_row_id=g.id
            JOIN game_templates template ON template.id=(NEW.fixture_binding_json->>'gameTemplateId')::BIGINT
            JOIN published_release_bundle bundle ON bundle.id=(NEW.fixture_binding_json->>'releaseBundleId')::BIGINT
            JOIN version_asset_artifact artifact ON artifact.id=(NEW.fixture_binding_json->>'artifactRowId')::BIGINT
            JOIN launch_descriptor descriptor ON descriptor.launch_descriptor_id=NEW.fixture_binding_json->>'launchDescriptorId'
            WHERE v.id=NEW.game_design_version_row_id AND v.canonical_tenant_id=NEW.canonical_tenant_id
                AND v.canonical_version_id=NEW.canonical_version_id AND v.version_number=NEW.version_number
                AND v.tenant_id='1' AND g.tenant_id='1' AND g.canonical_tenant_id=NEW.canonical_tenant_id
                AND g.tenant_identity_provenance_kind='NEW_GAME_ROW'
                AND v.identity_source_provenance_kind='NEW_GAME_ROW' AND v.version_state='PUBLISHED'
                AND v.version_state_epoch=(NEW.fixture_binding_json->>'versionStateEpoch')::BIGINT
                AND creation.operation_id=(NEW.fixture_binding_json->>'creationOperationId')::UUID
                AND creation.creation_request_id=(fixture_parts[2]->>'creationRequestId')::UUID
                AND creation.target_namespace=NEW.fixture_binding_json->>'targetNamespace'
                AND creation.status='COMPLETED' AND creation.canonical_tenant_id=NEW.canonical_tenant_id
                AND creation.source_game_tenant_key='1' AND creation.provenance_kind='NEW_GAME_ROW'
                AND creation.evidence_digest=NEW.fixture_binding_json->>'creationEvidenceDigest'
                AND fixture_parts[2] = jsonb_build_object('schemaVersion', creation.schema_version,
                    'targetNamespace', creation.target_namespace, 'creationRequestId', creation.creation_request_id,
                    'operationId', creation.operation_id, 'requestDigest', creation.request_digest,
                    'canonicalTenantId', creation.canonical_tenant_id, 'sourceGameRowId', creation.source_game_row_id,
                    'sourceGameTenantKey', creation.source_game_tenant_key, 'provenanceKind', creation.provenance_kind,
                    'evidenceDigest', creation.evidence_digest)
                AND g.id=(NEW.fixture_binding_json->>'sourceGameRowId')::BIGINT
                AND NEW.fixture_binding_json->>'sourceGameTenantKey'='1'
                AND source.operation_id=(NEW.fixture_binding_json->>'sourceOperationId')::UUID
                AND source.registration_request_id=(fixture_parts[1]->>'operationId')::UUID
                AND source.target_namespace=creation.target_namespace AND source.target_namespace='dev'
                AND source.canonical_tenant_id=NEW.canonical_tenant_id AND source.source_game_tenant_key='1'
                AND source.provenance_kind='NEW_GAME_ROW' AND source.world_slug='demo'
                AND source.world_display_name='Demo World'
                AND source.evidence_digest=NEW.fixture_binding_json->>'sourceEvidenceDigest'
                AND fixture_parts[3] = jsonb_build_object('schemaVersion', source.schema_version,
                    'targetNamespace', source.target_namespace, 'registrationRequestId', source.registration_request_id,
                    'operationId', source.operation_id, 'requestDigest', source.request_digest,
                    'canonicalTenantId', source.canonical_tenant_id, 'tenantSlug', source.tenant_slug,
                    'worldSlug', source.world_slug, 'worldDisplayName', source.world_display_name,
                    'sourceGameRowId', source.source_game_row_id, 'sourceGameTenantKey', source.source_game_tenant_key,
                    'provenanceKind', source.provenance_kind, 'evidenceDigest', source.evidence_digest)
                AND template.tenant_id='1' AND template.default_version_id=v.id AND template.name='Default Template'
                AND bundle.tenant_id='1' AND bundle.version_id=v.id AND bundle.canonical_tenant_id=v.canonical_tenant_id
                AND bundle.canonical_version_id=v.canonical_version_id
                AND bundle.published_release_bundle_ref=NEW.fixture_binding_json->>'publishedReleaseBundleRef'
                AND artifact.tenant_id='1' AND artifact.version_id=v.id AND artifact.artifact_state='PUBLISHED'
                AND artifact.state_epoch=(NEW.fixture_binding_json->>'artifactStateEpoch')::BIGINT
                AND artifact.manifest_hash=bundle.manifest_hash
                AND descriptor.target_namespace=source.target_namespace AND descriptor.canonical_tenant_id=NEW.canonical_tenant_id
                AND descriptor.authored_world_source_operation_id=source.operation_id
                AND descriptor.authored_world_source_evidence_digest=source.evidence_digest
                AND descriptor.outcome_status='SUCCESS' AND descriptor.game_template_id=template.id
                AND descriptor.version_id=v.id AND descriptor.version_state_epoch=v.version_state_epoch
                AND descriptor.release_bundle_id=bundle.id
                AND descriptor.control_plane_request_id=fixture_parts[1]->>'operationId'
                AND descriptor.request_digest=NEW.fixture_binding_json->>'descriptorRequestDigest'
                AND descriptor.result_digest=NEW.fixture_binding_json->>'descriptorResultDigest'
        ) THEN
            RAISE EXCEPTION 'fixture policy seal lacks actual creation source and launch owner rows' USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN$replacement$);
    EXECUTE current_guard;
END;
$migration$;

DO $migration$
DECLARE
    current_guard TEXT;
    count_anchor TEXT := $anchor$    SELECT count(*), count(DISTINCT realm_slug),$anchor$;
BEGIN
    SELECT pg_get_functiondef('verify_published_realm_policy_set_commit()'::REGPROCEDURE) INTO STRICT current_guard;
    IF (length(current_guard)-length(replace(current_guard,count_anchor,'')))/length(count_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact current Human published-policy commit guard anchor';
    END IF;
    current_guard := replace(current_guard, count_anchor, $replacement$    IF set_row.fixture_operation_id IS NOT NULL THEN
        IF NOT set_row.sealed OR EXISTS (SELECT 1 FROM game_design_published_realm_policy
            WHERE canonical_tenant_id=NEW.canonical_tenant_id AND canonical_version_id=NEW.canonical_version_id) THEN
            RAISE EXCEPTION 'fixture policy seal cannot carry human policy children' USING ERRCODE = 'check_violation';
        END IF;
        RETURN NULL;
    END IF;
    SELECT count(*), count(DISTINCT realm_slug),$replacement$);
    EXECUTE current_guard;
END;
$migration$;
-- The current inventory-v2 trigger runs before the policy-header guard. Reuse the exact
-- closed fixture arm installed above so both guards validate real fixture owners independently;
-- a fixture never supplies a surrogate Human v2 operation or gains selected inventory authority.
DO $migration$
DECLARE
    header_guard TEXT;
    inventory_guard TEXT;
    fixture_start TEXT := $anchor$    IF TG_OP = 'UPDATE' AND (OLD.fixture_operation_id IS NOT NULL OR NEW.fixture_operation_id IS NOT NULL) THEN$anchor$;
    fixture_end TEXT := $anchor$    IF TG_OP = 'DELETE' THEN$anchor$;
    inventory_anchor TEXT := $anchor$BEGIN
    IF selected_inventory_operation_nth(NEW.operation_bytes, 0)$anchor$;
    fixture_arm TEXT;
    start_position INTEGER;
    end_position INTEGER;
BEGIN
    SELECT pg_get_functiondef('guard_published_realm_policy_set()'::REGPROCEDURE)
        INTO STRICT header_guard;
    SELECT pg_get_functiondef('guard_published_policy_inventory_v2()'::REGPROCEDURE)
        INTO STRICT inventory_guard;
    IF (length(header_guard)-length(replace(header_guard,fixture_start,'')))/length(fixture_start) <> 1
        OR (length(header_guard)-length(replace(header_guard,fixture_end,'')))/length(fixture_end) <> 1
        OR (length(inventory_guard)-length(replace(inventory_guard,inventory_anchor,'')))/length(inventory_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact current fixture-header and Human inventory-v2 guard anchors';
    END IF;
    start_position := strpos(header_guard, fixture_start);
    end_position := strpos(header_guard, fixture_end);
    IF end_position <= start_position THEN
        RAISE EXCEPTION 'Closed fixture-header guard arm has invalid boundaries';
    END IF;
    fixture_arm := substring(header_guard FROM start_position FOR end_position - start_position);
    inventory_guard := replace(inventory_guard, inventory_anchor,
        $replacement$DECLARE
    fixture_parts JSONB[]; fixture_offset INTEGER; fixture_length INTEGER; fixture_index INTEGER;
BEGIN
$replacement$ || fixture_arm || $replacement$    IF selected_inventory_operation_nth(NEW.operation_bytes, 0)$replacement$);
    EXECUTE inventory_guard;
END;
$migration$;

-- [jooq ignore stop]
