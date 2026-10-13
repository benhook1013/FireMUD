-- Game Design retains a private, complete source binding beside the public minimal projection.
-- No old descriptor is backfilled: pre-V73 owners have no provable GD-allocated binding identity.
ALTER TABLE game_design_start_session_launch_descriptor_binding
    ADD CONSTRAINT uq_gd_start_session_launch_descriptor_owner
        UNIQUE (target_namespace, canonical_tenant_id, control_plane_request_id,
            launch_descriptor_row_id);

CREATE TABLE game_design_start_session_authored_actor_core_projection (
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    control_plane_request_id VARCHAR(255) NOT NULL,
    launch_descriptor_row_id BIGINT NOT NULL,
    policy_order INTEGER NOT NULL,
    binding_id UUID NOT NULL UNIQUE,
    source_binding_bytes BYTEA NOT NULL,
    source_binding_digest VARCHAR(71) NOT NULL,
    projection_bytes BYTEA NOT NULL,
    projection_digest VARCHAR(71) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gd_start_session_actor_core_projection PRIMARY KEY (
        target_namespace, canonical_tenant_id, control_plane_request_id,
        launch_descriptor_row_id, policy_order),
    CONSTRAINT fk_gd_start_session_actor_core_projection_owner FOREIGN KEY (
        target_namespace, canonical_tenant_id, control_plane_request_id, launch_descriptor_row_id)
        REFERENCES game_design_start_session_launch_descriptor_binding (
            target_namespace, canonical_tenant_id, control_plane_request_id, launch_descriptor_row_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_gd_start_session_actor_core_projection_descriptor FOREIGN KEY (
        launch_descriptor_row_id) REFERENCES launch_descriptor (id) ON DELETE RESTRICT,
    CONSTRAINT ck_gd_start_session_actor_core_projection_shape CHECK (
        target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
        AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND control_plane_request_id !~ '^[[:space:]]*$'
        AND launch_descriptor_row_id > 0
        AND policy_order BETWEEN 0 AND 127
        AND binding_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND octet_length(source_binding_bytes) BETWEEN 1 AND 16777216
        AND source_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND octet_length(projection_bytes) BETWEEN 1 AND 1048576
        AND projection_digest ~ '^sha256:[0-9a-f]{64}$')
);

-- [jooq ignore start]
-- Parse the same four-byte big-endian length frames used by the private Common source value.
CREATE FUNCTION gd_start_session_actor_core_source_frame_count(payload BYTEA) RETURNS INTEGER AS $$
DECLARE offset_bytes INTEGER := 0; frame_length BIGINT; frame_count INTEGER := 0;
BEGIN
    WHILE offset_bytes < octet_length(payload) LOOP
        IF offset_bytes + 4 > octet_length(payload) THEN
            RAISE EXCEPTION 'truncated authored actor-core source frame' USING ERRCODE = 'check_violation';
        END IF;
        frame_length := get_byte(payload, offset_bytes)::BIGINT * 16777216
            + get_byte(payload, offset_bytes + 1)::BIGINT * 65536
            + get_byte(payload, offset_bytes + 2)::BIGINT * 256
            + get_byte(payload, offset_bytes + 3);
        offset_bytes := offset_bytes + 4;
        IF frame_length > octet_length(payload) - offset_bytes THEN
            RAISE EXCEPTION 'invalid authored actor-core source frame length' USING ERRCODE = 'check_violation';
        END IF;
        offset_bytes := offset_bytes + frame_length::INTEGER;
        frame_count := frame_count + 1;
    END LOOP;
    IF offset_bytes <> octet_length(payload) THEN
        RAISE EXCEPTION 'trailing authored actor-core source bytes' USING ERRCODE = 'check_violation';
    END IF;
    RETURN frame_count;
END;
$$ LANGUAGE plpgsql IMMUTABLE STRICT;

CREATE FUNCTION guard_gd_start_session_actor_core_projection() RETURNS trigger AS $$
DECLARE owner game_design_start_session_launch_descriptor_binding%ROWTYPE;
    descriptor launch_descriptor%ROWTYPE;
    association game_design_selected_template_world_source_association%ROWTYPE;
    source_operation game_design_authored_world_source_operations%ROWTYPE;
    publication game_design_publication_operation%ROWTYPE;
    release published_release_bundle%ROWTYPE;
    commit_row game_design_draft_commit%ROWTYPE;
    policy_snapshot game_design_realm_policy_snapshot%ROWTYPE;
    policy_capture game_design_realm_policy_capture%ROWTYPE;
    gameplay_snapshot game_design_gameplay_rule_snapshot%ROWTYPE;
    private_source BYTEA; public_projection JSONB; policy_projection JSONB;
    disposition_projection JSONB; feedback_projection JSONB;
    disposition_binding JSONB; feedback_binding JSONB;
    policy_json TEXT; disposition_json TEXT; feedback_json TEXT; expected_order INTEGER;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'authored actor-core projection proofs are insert-only and retained'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT * INTO STRICT owner
      FROM game_design_start_session_launch_descriptor_binding
     WHERE target_namespace = NEW.target_namespace
       AND canonical_tenant_id = NEW.canonical_tenant_id
       AND control_plane_request_id = NEW.control_plane_request_id
       AND launch_descriptor_row_id = NEW.launch_descriptor_row_id;
    SELECT * INTO STRICT descriptor FROM launch_descriptor WHERE id = NEW.launch_descriptor_row_id;
    SELECT * INTO STRICT association
      FROM game_design_selected_template_world_source_association
     WHERE canonical_tenant_id = owner.canonical_tenant_id
       AND canonical_version_id = owner.canonical_version_id
       AND commit_id = owner.selected_commit_id
       AND template_id = owner.game_template_id;
    SELECT * INTO STRICT source_operation
      FROM game_design_authored_world_source_operations
     WHERE operation_id = owner.source_operation_id;
    SELECT * INTO STRICT publication
      FROM game_design_publication_operation
     WHERE publish_workflow_id = owner.publish_workflow_id;
    SELECT * INTO STRICT release FROM published_release_bundle WHERE id = owner.release_bundle_id;
    SELECT * INTO STRICT commit_row
      FROM game_design_draft_commit
     WHERE canonical_tenant_id = owner.canonical_tenant_id
       AND canonical_version_id = owner.canonical_version_id
       AND commit_id = owner.selected_commit_id;
    SELECT * INTO STRICT policy_snapshot
      FROM game_design_realm_policy_snapshot
     WHERE canonical_tenant_id = owner.canonical_tenant_id
       AND canonical_version_id = owner.canonical_version_id
       AND commit_id = owner.selected_commit_id;
    SELECT * INTO STRICT policy_capture
      FROM game_design_realm_policy_capture
     WHERE canonical_tenant_id = owner.canonical_tenant_id
       AND canonical_version_id = owner.canonical_version_id
       AND publish_workflow_id = owner.publish_workflow_id;
    SELECT * INTO STRICT gameplay_snapshot
      FROM game_design_gameplay_rule_snapshot
     WHERE canonical_tenant_id = owner.canonical_tenant_id
       AND canonical_version_id = owner.canonical_version_id
       AND commit_id = owner.selected_commit_id;

    private_source := NEW.source_binding_bytes;
    IF gd_start_session_actor_core_source_frame_count(private_source) <> 45
        OR NEW.source_binding_digest IS DISTINCT FROM 'sha256:' || encode(sha256(private_source), 'hex')
        OR NEW.projection_digest IS DISTINCT FROM 'sha256:' || encode(sha256(NEW.projection_bytes), 'hex') THEN
        RAISE EXCEPTION 'authored actor-core source or projection digest/frame count differs'
            USING ERRCODE = 'check_violation';
    END IF;
    public_projection := convert_from(NEW.projection_bytes, 'UTF8')::JSONB;
    IF jsonb_typeof(public_projection) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(public_projection)) <> 16
        OR (public_projection - ARRAY['schema', 'bindingId', 'bindingDigest', 'canonicalTenantId',
            'canonicalVersionId', 'worldSourceOperationId', 'worldSourceEvidenceDigest', 'worldSlug',
            'publicationWorkflowId', 'selectedCommitId', 'selectedCommitDigest', 'policy', 'core',
            'descriptor', 'disposition', 'safeFeedback']) IS DISTINCT FROM '{}'::JSONB THEN
        RAISE EXCEPTION 'authored actor-core public projection must have its closed minimal shape'
            USING ERRCODE = 'check_violation';
    END IF;

    IF convert_from(published_world_selector_frame(private_source, 0), 'UTF8')
            IS DISTINCT FROM 'game-design-authored-initial-actor-core-source-binding/v1'
        OR convert_from(published_world_selector_frame(private_source, 1), 'UTF8') IS DISTINCT FROM '1'
        OR convert_from(published_world_selector_frame(private_source, 2), 'UTF8')
            IS DISTINCT FROM owner.source_operation_id::TEXT
        OR convert_from(published_world_selector_frame(private_source, 3), 'UTF8')
            IS DISTINCT FROM owner.source_evidence_digest
        OR convert_from(published_world_selector_frame(private_source, 4), 'UTF8')
            IS DISTINCT FROM owner.world_slug
        OR convert_from(published_world_selector_frame(private_source, 5), 'UTF8')
            IS DISTINCT FROM owner.publish_workflow_id
        OR convert_from(published_world_selector_frame(private_source, 6), 'UTF8')
            IS DISTINCT FROM owner.canonical_tenant_id::TEXT
        OR convert_from(published_world_selector_frame(private_source, 7), 'UTF8')
            IS DISTINCT FROM owner.canonical_version_id::TEXT
        OR convert_from(published_world_selector_frame(private_source, 8), 'UTF8')
            IS DISTINCT FROM commit_row.game_design_version_row_id::TEXT
        OR convert_from(published_world_selector_frame(private_source, 9), 'UTF8')
            IS DISTINCT FROM commit_row.game_design_version_tenant_key
        OR convert_from(published_world_selector_frame(private_source, 10), 'UTF8')
            IS DISTINCT FROM commit_row.source_game_row_id::TEXT
        OR convert_from(published_world_selector_frame(private_source, 11), 'UTF8')
            IS DISTINCT FROM commit_row.source_game_tenant_key
        OR convert_from(published_world_selector_frame(private_source, 12), 'UTF8')
            IS DISTINCT FROM commit_row.source_provenance_kind
        OR convert_from(published_world_selector_frame(private_source, 13), 'UTF8')
            IS DISTINCT FROM owner.selected_commit_id::TEXT
        OR convert_from(published_world_selector_frame(private_source, 14), 'UTF8')
            IS DISTINCT FROM commit_row.input_digest
        OR published_world_selector_frame(private_source, 15) IS DISTINCT FROM convert_to(commit_row.binding_json, 'UTF8')
        OR published_world_selector_frame(private_source, 16) IS DISTINCT FROM publication.request_bytes
        OR published_world_selector_frame(private_source, 17) IS DISTINCT FROM publication.terminal_evidence_bytes
        OR published_world_selector_frame(private_source, 18) IS DISTINCT FROM convert_to(policy_snapshot.snapshot_json, 'UTF8')
        OR published_world_selector_frame(private_source, 27) IS DISTINCT FROM convert_to(gameplay_snapshot.snapshot_json, 'UTF8')
        OR policy_capture.commit_id IS DISTINCT FROM owner.selected_commit_id
        OR policy_capture.operation_bytes IS DISTINCT FROM publication.request_bytes
        OR policy_capture.terminal_evidence_bytes IS DISTINCT FROM publication.terminal_evidence_bytes
        OR policy_capture.release_content_bytes IS DISTINCT FROM publication.release_content_bytes
        OR publication.outcome IS DISTINCT FROM 'PUBLISHED'
        OR publication.version_id IS DISTINCT FROM commit_row.game_design_version_row_id
        OR publication.tenant_id IS DISTINCT FROM commit_row.game_design_version_tenant_key
        OR publication.release_content_bytes IS DISTINCT FROM publication_release_content(release)
        OR publication.published_release_bundle_digest IS DISTINCT FROM
            'sha256:' || encode(sha256(publication.release_content_bytes), 'hex')
        OR release.id IS DISTINCT FROM owner.release_bundle_id
        OR release.publish_workflow_id IS DISTINCT FROM owner.publish_workflow_id
        OR release.version_id IS DISTINCT FROM commit_row.game_design_version_row_id
        OR release.canonical_tenant_id IS DISTINCT FROM owner.canonical_tenant_id
        OR release.canonical_version_id IS DISTINCT FROM owner.canonical_version_id
        OR association.association_digest IS DISTINCT FROM owner.association_digest
        OR association.source_operation_id IS DISTINCT FROM owner.source_operation_id
        OR association.source_evidence_digest IS DISTINCT FROM owner.source_evidence_digest
        OR association.world_slug IS DISTINCT FROM owner.world_slug
        OR association.publish_workflow_id IS DISTINCT FROM owner.publish_workflow_id
        OR source_operation.target_namespace IS DISTINCT FROM owner.target_namespace
        OR source_operation.canonical_tenant_id IS DISTINCT FROM owner.canonical_tenant_id
        OR source_operation.world_slug IS DISTINCT FROM owner.world_slug
        OR source_operation.evidence_digest IS DISTINCT FROM owner.source_evidence_digest
        OR descriptor.target_namespace IS DISTINCT FROM owner.target_namespace
        OR descriptor.canonical_tenant_id IS DISTINCT FROM owner.canonical_tenant_id
        OR descriptor.control_plane_request_id IS DISTINCT FROM owner.control_plane_request_id
        OR descriptor.authored_world_source_operation_id IS DISTINCT FROM owner.source_operation_id
        OR descriptor.authored_world_source_evidence_digest IS DISTINCT FROM owner.source_evidence_digest
        OR descriptor.world_slug IS DISTINCT FROM owner.world_slug
        OR descriptor.outcome_status NOT IN ('SUCCESS', 'FAILED')
        OR (descriptor.outcome_status = 'SUCCESS' AND (
            owner.runtime_surface IS DISTINCT FROM 'EMPTY_V1'
            OR descriptor.release_bundle_id IS DISTINCT FROM owner.release_bundle_id
            OR descriptor.version_id IS DISTINCT FROM release.version_id))
        OR (descriptor.outcome_status = 'FAILED'
            AND owner.runtime_surface IS DISTINCT FROM 'UNSUPPORTED_CAPTURE_V1') THEN
        RAISE EXCEPTION 'private authored actor-core proof differs from descriptor, source, association, commit, or pinned release'
            USING ERRCODE = 'check_violation';
    END IF;

    policy_json := convert_from(published_world_selector_frame(private_source, 22), 'UTF8');
    disposition_json := convert_from(published_world_selector_frame(private_source, 30), 'UTF8');
    feedback_json := convert_from(published_world_selector_frame(private_source, 38), 'UTF8');
    policy_projection := public_projection->'policy';
    disposition_projection := public_projection->'disposition';
    feedback_projection := public_projection->'safeFeedback';
    disposition_binding := convert_from(published_world_selector_frame(private_source, 34), 'UTF8')::JSONB;
    feedback_binding := convert_from(published_world_selector_frame(private_source, 42), 'UTF8')::JSONB;
    SELECT (ordered.policy_order)::INTEGER INTO expected_order
      FROM (
          SELECT policy_item->>'commitId' AS commit_id,
                 policy_item->>'revisionId' AS revision_id,
                 policy_item->>'logicalRevisionId' AS logical_revision_id,
                 row_number() OVER (
                     ORDER BY policy_item->'policy'->>'worldSlug', policy_item->'policy'->>'realmSlug') - 1
                     AS policy_order
            FROM jsonb_array_elements(policy_snapshot.snapshot_json::JSONB->'policies') policy_item
           WHERE policy_item->'policy'->>'worldSlug' = owner.world_slug
             AND policy_item->'policy'->'schemaVersion' = '3'::JSONB
             AND policy_item->'policy'->>'initialActorCore' IS NOT NULL
      ) ordered
     WHERE ordered.commit_id = convert_from(published_world_selector_frame(private_source, 19), 'UTF8')
       AND ordered.revision_id = convert_from(published_world_selector_frame(private_source, 20), 'UTF8')
       AND ordered.logical_revision_id = convert_from(published_world_selector_frame(private_source, 21), 'UTF8');
    IF public_projection->>'schema' IS DISTINCT FROM 'game-design-authored-initial-actor-core-projection/v1'
        OR public_projection->>'bindingId' IS DISTINCT FROM NEW.binding_id::TEXT
        OR public_projection->>'bindingDigest' IS DISTINCT FROM NEW.source_binding_digest
        OR public_projection->>'canonicalTenantId' IS DISTINCT FROM owner.canonical_tenant_id::TEXT
        OR public_projection->>'canonicalVersionId' IS DISTINCT FROM owner.canonical_version_id::TEXT
        OR public_projection->>'worldSourceOperationId' IS DISTINCT FROM owner.source_operation_id::TEXT
        OR public_projection->>'worldSourceEvidenceDigest' IS DISTINCT FROM owner.source_evidence_digest
        OR public_projection->>'worldSlug' IS DISTINCT FROM owner.world_slug
        OR public_projection->>'publicationWorkflowId' IS DISTINCT FROM owner.publish_workflow_id
        OR public_projection->>'selectedCommitId' IS DISTINCT FROM owner.selected_commit_id::TEXT
        OR public_projection->>'selectedCommitDigest' IS DISTINCT FROM commit_row.input_digest
        OR (SELECT count(*) FROM jsonb_object_keys(policy_projection)) <> 5
        OR (policy_projection - ARRAY['commitId', 'revisionId', 'logicalRevisionId', 'canonicalPolicyJson', 'contentDigest'])
            IS DISTINCT FROM '{}'::JSONB
        OR policy_projection->>'commitId' IS DISTINCT FROM convert_from(published_world_selector_frame(private_source, 19), 'UTF8')
        OR policy_projection->>'revisionId' IS DISTINCT FROM convert_from(published_world_selector_frame(private_source, 20), 'UTF8')
        OR policy_projection->>'logicalRevisionId' IS DISTINCT FROM convert_from(published_world_selector_frame(private_source, 21), 'UTF8')
        OR policy_projection->>'canonicalPolicyJson' IS DISTINCT FROM policy_json
        OR policy_projection->>'contentDigest' IS DISTINCT FROM 'sha256:' || encode(sha256(convert_to(policy_json, 'UTF8')), 'hex')
        OR expected_order IS DISTINCT FROM NEW.policy_order
        OR NOT EXISTS (
            SELECT 1
              FROM jsonb_array_elements(policy_snapshot.snapshot_json::JSONB->'policies') policy_item
             WHERE policy_item->>'commitId' = convert_from(published_world_selector_frame(private_source, 19), 'UTF8')
               AND policy_item->>'revisionId' = convert_from(published_world_selector_frame(private_source, 20), 'UTF8')
               AND policy_item->>'logicalRevisionId' = convert_from(published_world_selector_frame(private_source, 21), 'UTF8')
               AND policy_item->'policy' = policy_json::JSONB)
        OR (public_projection->'core' - ARRAY['canonicalJson']) IS DISTINCT FROM '{}'::JSONB
        OR public_projection->'core'->>'canonicalJson' IS DISTINCT FROM convert_from(published_world_selector_frame(private_source, 26), 'UTF8')
        OR (public_projection->'descriptor' - ARRAY['canonicalJson', 'digest']) IS DISTINCT FROM '{}'::JSONB
        OR public_projection->'descriptor'->>'canonicalJson' IS DISTINCT FROM convert_from(published_world_selector_frame(private_source, 25), 'UTF8')
        OR public_projection->'descriptor'->>'digest' IS DISTINCT FROM
            'sha256:' || encode(sha256(published_world_selector_frame(private_source, 25)), 'hex')
        OR (SELECT count(*) FROM jsonb_object_keys(disposition_projection)) <> 7
        OR (disposition_projection - ARRAY['key', 'definitionJson', 'definitionDigest', 'sourceCommitId',
            'sourceCommitDigest', 'revisionId', 'revisionOrder']) IS DISTINCT FROM '{}'::JSONB
        OR disposition_projection->>'key' IS DISTINCT FROM convert_from(published_world_selector_frame(private_source, 29), 'UTF8')
        OR disposition_projection->>'definitionJson' IS DISTINCT FROM disposition_json
        OR convert_from(published_world_selector_frame(private_source, 31), 'UTF8') IS DISTINCT FROM 'DISPOSITIONS'
        OR published_world_selector_frame(private_source, 32) IS DISTINCT FROM
            published_world_selector_frame(private_source, 30)
        OR NOT EXISTS (
            SELECT 1
              FROM jsonb_array_elements(gameplay_snapshot.snapshot_json::JSONB->'entries') rule_entry
             WHERE rule_entry = jsonb_build_object(
                 'family', 'DISPOSITIONS',
                 'definitionJson', convert_from(published_world_selector_frame(private_source, 32), 'UTF8'),
                 'sourceBindingJson', convert_from(published_world_selector_frame(private_source, 34), 'UTF8'),
                 'sourceBindingDigest', convert_from(published_world_selector_frame(private_source, 33), 'UTF8'),
                 'revisionOrder', convert_from(published_world_selector_frame(private_source, 35), 'UTF8'),
                 'revisionId', convert_from(published_world_selector_frame(private_source, 36), 'UTF8')))
        OR disposition_projection->>'definitionDigest' IS DISTINCT FROM
            'sha256:' || encode(sha256(convert_to(disposition_json, 'UTF8')), 'hex')
        OR disposition_projection->>'sourceCommitId' IS DISTINCT FROM disposition_binding->>'commitId'
        OR disposition_projection->>'sourceCommitDigest' IS DISTINCT FROM convert_from(published_world_selector_frame(private_source, 33), 'UTF8')
        OR disposition_projection->>'revisionId' IS DISTINCT FROM convert_from(published_world_selector_frame(private_source, 36), 'UTF8')
        OR disposition_projection->>'revisionOrder' IS DISTINCT FROM convert_from(published_world_selector_frame(private_source, 35), 'UTF8')
        OR disposition_projection->>'sourceCommitDigest' IS DISTINCT FROM
            'sha256:' || encode(sha256(published_world_selector_frame(private_source, 34)), 'hex')
        OR (SELECT count(*) FROM jsonb_object_keys(feedback_projection)) <> 7
        OR (feedback_projection - ARRAY['key', 'definitionJson', 'definitionDigest', 'sourceCommitId',
            'sourceCommitDigest', 'revisionId', 'revisionOrder']) IS DISTINCT FROM '{}'::JSONB
        OR feedback_projection->>'key' IS DISTINCT FROM convert_from(published_world_selector_frame(private_source, 37), 'UTF8')
        OR feedback_projection->>'definitionJson' IS DISTINCT FROM feedback_json
        OR convert_from(published_world_selector_frame(private_source, 39), 'UTF8') IS DISTINCT FROM 'FEEDBACK'
        OR published_world_selector_frame(private_source, 40) IS DISTINCT FROM
            published_world_selector_frame(private_source, 38)
        OR NOT EXISTS (
            SELECT 1
              FROM jsonb_array_elements(gameplay_snapshot.snapshot_json::JSONB->'entries') rule_entry
             WHERE rule_entry = jsonb_build_object(
                 'family', 'FEEDBACK',
                 'definitionJson', convert_from(published_world_selector_frame(private_source, 40), 'UTF8'),
                 'sourceBindingJson', convert_from(published_world_selector_frame(private_source, 42), 'UTF8'),
                 'sourceBindingDigest', convert_from(published_world_selector_frame(private_source, 41), 'UTF8'),
                 'revisionOrder', convert_from(published_world_selector_frame(private_source, 43), 'UTF8'),
                 'revisionId', convert_from(published_world_selector_frame(private_source, 44), 'UTF8')))
        OR feedback_projection->>'definitionDigest' IS DISTINCT FROM
            'sha256:' || encode(sha256(convert_to(feedback_json, 'UTF8')), 'hex')
        OR feedback_projection->>'sourceCommitId' IS DISTINCT FROM feedback_binding->>'commitId'
        OR feedback_projection->>'sourceCommitDigest' IS DISTINCT FROM convert_from(published_world_selector_frame(private_source, 41), 'UTF8')
        OR feedback_projection->>'revisionId' IS DISTINCT FROM convert_from(published_world_selector_frame(private_source, 44), 'UTF8')
        OR feedback_projection->>'revisionOrder' IS DISTINCT FROM convert_from(published_world_selector_frame(private_source, 43), 'UTF8')
        OR feedback_projection->>'sourceCommitDigest' IS DISTINCT FROM
            'sha256:' || encode(sha256(published_world_selector_frame(private_source, 42)), 'hex') THEN
        RAISE EXCEPTION 'public actor-core projection differs from exact source or leaks an open proof shape'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION verify_gd_start_session_actor_core_projection_set() RETURNS trigger AS $$
DECLARE policy_snapshot game_design_realm_policy_snapshot%ROWTYPE;
    expected_count BIGINT; retained_count BIGINT;
BEGIN
    SELECT * INTO STRICT policy_snapshot
      FROM game_design_realm_policy_snapshot
     WHERE canonical_tenant_id = NEW.canonical_tenant_id
       AND canonical_version_id = NEW.canonical_version_id
       AND commit_id = NEW.selected_commit_id;
    SELECT count(*) INTO expected_count
      FROM jsonb_array_elements(policy_snapshot.snapshot_json::JSONB->'policies') policy_item
     WHERE policy_item->'policy'->>'worldSlug' = NEW.world_slug
       AND policy_item->'policy'->'schemaVersion' = '3'::JSONB
       AND policy_item->'policy'->>'initialActorCore' IS NOT NULL;
    SELECT count(*) INTO retained_count
      FROM game_design_start_session_authored_actor_core_projection proof
     WHERE proof.target_namespace = NEW.target_namespace
       AND proof.canonical_tenant_id = NEW.canonical_tenant_id
       AND proof.control_plane_request_id = NEW.control_plane_request_id
       AND proof.launch_descriptor_row_id = NEW.launch_descriptor_row_id;
    IF expected_count IS DISTINCT FROM retained_count THEN
        RAISE EXCEPTION 'new descriptor owner must retain every applicable authored actor-core proof in order'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_start_session_actor_core_projection
    BEFORE INSERT OR UPDATE OR DELETE ON game_design_start_session_authored_actor_core_projection
    FOR EACH ROW EXECUTE FUNCTION guard_gd_start_session_actor_core_projection();

CREATE FUNCTION reject_gd_start_session_actor_core_projection_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'authored actor-core projection proofs are retained' USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_start_session_actor_core_projection_no_truncate
    BEFORE TRUNCATE ON game_design_start_session_authored_actor_core_projection
    FOR EACH STATEMENT EXECUTE FUNCTION reject_gd_start_session_actor_core_projection_truncate();

CREATE CONSTRAINT TRIGGER trg_gd_start_session_actor_core_projection_complete_owner_set
    AFTER INSERT ON game_design_start_session_launch_descriptor_binding
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION verify_gd_start_session_actor_core_projection_set();
-- [jooq ignore stop]
