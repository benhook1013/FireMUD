-- Forward alignment of the existing location guard to complete typed first-open outcomes.
-- Retained legacy holds are not promoted or accepted as canonical placement proof.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION world_guard_initial_character_location()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    operation_row "${serviceSchema}".world_canonical_initial_player_location_operation%ROWTYPE;
    request JSONB;
    lifecycle JSONB;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Initial placement is immutable; a separate World movement owner must be implemented' USING ERRCODE = '55000';
    END IF;
    IF TG_OP <> 'INSERT' THEN RETURN NEW; END IF;

    SELECT * INTO STRICT operation_row
    FROM "${serviceSchema}".world_canonical_initial_player_location_operation
    WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND playable_state_namespace_id = NEW.playable_state_namespace_id
        AND canonical_game_instance_id = NEW.canonical_game_instance_id
        AND operation_id = NEW.initial_location_operation_id;
    request := convert_from(operation_row.request_bytes, 'UTF8')::JSONB;
    lifecycle := convert_from(NEW.initial_lifecycle_evidence_bytes, 'UTF8')::JSONB;

    IF operation_row.outcome IS DISTINCT FROM 'APPLIED'
        OR operation_row.request_digest IS DISTINCT FROM NEW.initial_location_request_digest
        OR request->>'canonicalTenantId' IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
        OR request->>'playableStateNamespaceId' IS DISTINCT FROM NEW.playable_state_namespace_id::TEXT
        OR request->>'canonicalGameInstanceId' IS DISTINCT FROM NEW.canonical_game_instance_id::TEXT
        OR request->>'playableStateScope' IS DISTINCT FROM NEW.playable_state_scope
        OR request->>'canonicalAccountId' IS DISTINCT FROM NEW.canonical_account_id::TEXT
        OR request->>'characterId' IS DISTINCT FROM NEW.character_id::TEXT
        OR request->>'entityAssignmentOperationId' IS DISTINCT FROM NEW.entity_assignment_operation_id::TEXT
        OR request->>'entityAssignmentDigest' IS DISTINCT FROM NEW.entity_assignment_digest
        OR request->>'realmId' IS DISTINCT FROM NEW.realm_id::TEXT
        OR request->>'worldSlug' IS DISTINCT FROM NEW.world_slug
        OR request->>'initialAdmissionHoldId' IS DISTINCT FROM NEW.initial_admission_hold_id::TEXT
        OR request->>'initialAdmissionHoldFence' IS DISTINCT FROM NEW.initial_admission_hold_fence::TEXT
        OR request->>'initialAdmissionOrigin' IS NULL
        OR request->>'initialAdmissionOrigin' NOT IN ('NO_PRIOR_POINTER', 'EXPECT_CLOSED')
        OR request->>'initialAdmissionRequestId' IS DISTINCT FROM NEW.initial_admission_request_id
        OR request->>'initialAdmissionRequestDigest' IS DISTINCT FROM NEW.initial_admission_request_digest
        OR request->>'initialAdmissionOwnerProofId' IS DISTINCT FROM NEW.initial_admission_owner_proof_id
        OR request->>'initialAdmissionOwnerProofDigest' IS DISTINCT FROM NEW.initial_admission_owner_proof_digest
        OR request->>'pointerAuditId' IS DISTINCT FROM NEW.pointer_audit_id
        OR request->>'pointerVersion' IS DISTINCT FROM NEW.pointer_version::TEXT
        OR request->>'catalogRevision' IS DISTINCT FROM NEW.catalog_revision::TEXT
        OR request->'activeLifecycleEvidence' IS NULL
        OR (request->'activeLifecycleEvidence' #- '{request,readRequestId}')
            IS DISTINCT FROM (lifecycle #- '{request,readRequestId}')
        OR NEW.initial_lifecycle_evidence_digest IS DISTINCT FROM ('sha256:' || encode(sha256(NEW.initial_lifecycle_evidence_bytes), 'hex'))
        OR lifecycle->>'schema' IS DISTINCT FROM 'world-canonical-instance-lifecycle-evidence/v1'
        OR lifecycle->>'lifecycleStatus' IS DISTINCT FROM 'ACTIVE'
        OR lifecycle->>'lifecycleEpoch' IS DISTINCT FROM NEW.active_lifecycle_epoch::TEXT
        OR lifecycle->'request'->>'canonicalGameInstanceId' IS DISTINCT FROM NEW.canonical_game_instance_id::TEXT
        OR lifecycle->'request'->>'canonicalTenantId' IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
        OR lifecycle->'request'->>'playableStateNamespaceId' IS DISTINCT FROM NEW.playable_state_namespace_id::TEXT
        OR lifecycle->'request'->>'playableStateScope' IS DISTINCT FROM NEW.playable_state_scope
        OR lifecycle->'startLocation'->>'tenantId' IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
        OR lifecycle->'startLocation'->>'versionId' IS DISTINCT FROM NEW.canonical_version_id::TEXT
        OR lifecycle->'startLocation'->>'roomTemplateId' IS DISTINCT FROM NEW.initial_room_template_id::TEXT
        OR lifecycle->>'runtimeRoomInstanceId' IS DISTINCT FROM NEW.initial_runtime_room_instance_id::TEXT
        OR NEW.runtime_room_instance_id IS DISTINCT FROM NEW.initial_runtime_room_instance_id THEN
        RAISE EXCEPTION 'World initial placement differs from its retained operation or lifecycle proof' USING ERRCODE = '23514';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM "${serviceSchema}".world_canonical_instance_association a
        JOIN "${serviceSchema}".world_instance w ON w.id = a.world_instance_id
        JOIN "${serviceSchema}".world_canonical_preparation_start_location s
            ON s.canonical_game_instance_id = a.canonical_game_instance_id
            AND s.world_instance_id = a.world_instance_id
        JOIN "${serviceSchema}".world_canonical_instance_topology_identity m
            ON m.world_instance_id = a.world_instance_id
            AND m.canonical_game_instance_id = a.canonical_game_instance_id
            AND m.family = 'ROOM' AND m.template_id = s.room_template_id
            AND m.runtime_room_instance_id = s.runtime_room_instance_id
        JOIN "${serviceSchema}".room_instance r
            ON r.id = m.runtime_row_id AND r.room_instance_row_id = s.runtime_room_instance_id
        JOIN "${serviceSchema}".initial_admission_bind_hold h
            ON h.hold_id = NEW.initial_admission_hold_id
        WHERE a.canonical_game_instance_id = NEW.canonical_game_instance_id
            AND a.world_instance_id = NEW.world_instance_id
            AND a.canonical_tenant_id = NEW.canonical_tenant_id
            AND a.canonical_world_slug = NEW.world_slug
            AND a.playable_state_namespace_id = NEW.playable_state_namespace_id
            AND a.playable_state_scope = NEW.playable_state_scope
            AND a.canonical_version_id = NEW.canonical_version_id
            AND w.canonical_game_instance_id = a.canonical_game_instance_id
            AND w.canonical_tenant_id = NEW.canonical_tenant_id
            AND w.canonical_world_slug = NEW.world_slug
            AND w.playable_state_namespace_id = NEW.playable_state_namespace_id
            AND w.playable_state_scope = NEW.playable_state_scope
            AND w.status = 'ACTIVE' AND w.lifecycle_epoch = NEW.active_lifecycle_epoch
            AND s.canonical_tenant_id = NEW.canonical_tenant_id
            AND s.canonical_version_id = NEW.canonical_version_id
            AND s.room_template_id = NEW.initial_room_template_id
            AND s.runtime_room_instance_id = NEW.initial_runtime_room_instance_id
            AND r.tenant_id = w.tenant_id AND r.game_instance_id = w.game_instance_id
            AND h.tenant_id = w.tenant_id AND h.game_instance_id = w.game_instance_id
            AND h.realm_uuid = NEW.realm_id
            AND h.playable_state_namespace_uuid = NEW.playable_state_namespace_id
            AND h.playable_state_scope = NEW.playable_state_scope
            AND h.version_id = w.version_id AND h.active_lifecycle_epoch = w.lifecycle_epoch
            AND h.status = 'COMMITTED'
            AND h.canonical_target_namespace = a.canonical_target_namespace
            AND h.canonical_tenant_id = NEW.canonical_tenant_id
            AND h.canonical_world_slug = NEW.world_slug
            AND h.canonical_game_instance_id = NEW.canonical_game_instance_id
            AND h.canonical_version_id = NEW.canonical_version_id
            AND h.canonical_request_bytes IS NOT NULL
            AND h.hold_binding_digest = ('sha256:' || encode(sha256(h.canonical_request_bytes), 'hex'))
            AND h.canonical_owner_proof_bytes IS NOT NULL
            AND h.canonical_owner_proof_digest = ('sha256:' || encode(sha256(h.canonical_owner_proof_bytes), 'hex'))
            AND h.initial_admission_origin = request->>'initialAdmissionOrigin'
            AND (
                (h.initial_admission_origin = 'NO_PRIOR_POINTER'
                    AND h.expected_no_prior_pointer IS TRUE
                    AND h.expected_prior_pointer_version IS NULL
                    AND h.owner_pointer_version = 1)
                OR
                (h.initial_admission_origin = 'EXPECT_CLOSED'
                    AND h.expected_no_prior_pointer IS FALSE
                    AND h.expected_prior_pointer_version IS NOT NULL
                    AND h.expected_prior_pointer_version < 9223372036854775807
                    AND h.owner_pointer_version = h.expected_prior_pointer_version + 1)
            )
            AND h.initial_admission_request_id = NEW.initial_admission_request_id
            AND h.request_digest = NEW.initial_admission_request_digest
            AND h.expected_catalog_revision = NEW.catalog_revision
            AND h.owner_proof_id = NEW.initial_admission_owner_proof_id
            AND h.owner_proof_digest = NEW.initial_admission_owner_proof_digest
            AND h.owner_pointer_audit_id = NEW.pointer_audit_id
            AND h.owner_pointer_version = NEW.pointer_version
            AND h.hold_fence = NEW.initial_admission_hold_fence
    ) THEN
        RAISE EXCEPTION 'World initial location lacks exact current association, V2 ROOM mapping, or committed first-open proof' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION world_guard_initial_character_location() FROM PUBLIC;
-- [jooq ignore stop]
