-- Validate the original framed selector digest and its exact canonical receipt bytes.
-- Preserve both installed guard identities, grants, security settings, and all other predicates.
-- [jooq ignore start]
CREATE FUNCTION world_start_location_receipt_matches(
    p_target_namespace TEXT,
    p_operation_id UUID,
    p_request_id UUID,
    p_commit_id UUID,
    p_authorization_fence_id UUID,
    p_account_binding_digest TEXT,
    p_binding_digest TEXT,
    p_canonical_tenant_id UUID,
    p_canonical_version_id UUID,
    p_room_template_id UUID,
    p_graph_digest TEXT,
    p_receipt_digest TEXT,
    p_receipt_bytes BYTEA
) RETURNS BOOLEAN
LANGUAGE plpgsql IMMUTABLE SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    segment TEXT;
    segment_bytes BYTEA;
    digest_input BYTEA := ''::BYTEA;
    expected_receipt_digest TEXT;
    expected_receipt_bytes BYTEA;
BEGIN
    IF p_target_namespace IS NULL
        OR p_operation_id IS NULL
        OR p_request_id IS NULL
        OR p_commit_id IS NULL
        OR p_authorization_fence_id IS NULL
        OR p_account_binding_digest IS NULL
        OR p_binding_digest IS NULL
        OR p_canonical_tenant_id IS NULL
        OR p_canonical_version_id IS NULL
        OR p_room_template_id IS NULL
        OR p_graph_digest IS NULL
        OR p_receipt_digest IS NULL
        OR p_receipt_bytes IS NULL THEN
        RETURN FALSE;
    END IF;

    FOREACH segment IN ARRAY ARRAY[
        'world-draft-start-location-receipt/v1',
        p_target_namespace,
        p_operation_id::TEXT,
        p_request_id::TEXT,
        p_commit_id::TEXT,
        p_authorization_fence_id::TEXT,
        p_account_binding_digest,
        p_binding_digest,
        p_canonical_tenant_id::TEXT,
        p_canonical_version_id::TEXT,
        p_room_template_id::TEXT,
        p_graph_digest
    ] LOOP
        segment_bytes := convert_to(segment, 'UTF8');
        digest_input := digest_input || int4send(octet_length(segment_bytes)) || segment_bytes;
    END LOOP;

    expected_receipt_digest := 'sha256:' || encode(sha256(digest_input), 'hex');
    expected_receipt_bytes := convert_to(
        '{"accountBindingDigest":"' || p_account_binding_digest
        || '","authorizationFenceId":"' || p_authorization_fence_id::TEXT
        || '","bindingDigest":"' || p_binding_digest
        || '","commitId":"' || p_commit_id::TEXT
        || '","graphDigest":"' || p_graph_digest
        || '","operationId":"' || p_operation_id::TEXT
        || '","receiptDigest":"' || expected_receipt_digest
        || '","requestId":"' || p_request_id::TEXT
        || '","schema":"world-draft-start-location-receipt/v1","startLocation":{"roomTemplateId":"'
        || p_room_template_id::TEXT
        || '","tenantId":"' || p_canonical_tenant_id::TEXT
        || '","versionId":"' || p_canonical_version_id::TEXT
        || '"},"targetNamespace":"' || p_target_namespace || '"}',
        'UTF8');

    RETURN p_receipt_digest IS NOT DISTINCT FROM expected_receipt_digest
        AND p_receipt_bytes IS NOT DISTINCT FROM expected_receipt_bytes;
END;
$$;
REVOKE ALL ON FUNCTION world_start_location_receipt_matches(
    TEXT, UUID, UUID, UUID, UUID, TEXT, TEXT, UUID, UUID, UUID, TEXT, TEXT, BYTEA) FROM PUBLIC;
DO $permissions$
DECLARE
    function_owner TEXT;
BEGIN
    FOR function_owner IN
        SELECT DISTINCT pg_get_userbyid(p.proowner)
        FROM pg_catalog.pg_proc p
        JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = '${serviceSchema}'
          AND p.proname IN (
              'world_guard_draft_start_location_receipt',
              'world_guard_graph_application')
    LOOP
        EXECUTE format(
            'GRANT EXECUTE ON FUNCTION %I.world_start_location_receipt_matches'
            || '(text,uuid,uuid,uuid,uuid,text,text,uuid,uuid,uuid,text,text,bytea) TO %I',
            '${serviceSchema}', function_owner);
    END LOOP;
END;
$permissions$;

DO $migration$
DECLARE
    original TEXT;
    receipt_anchor TEXT := $anchor$        OR NEW.receipt_digest IS DISTINCT FROM
            ('sha256:' || encode(sha256(NEW.receipt_bytes), 'hex'))
        OR NOT EXISTS ($anchor$;
    receipt_replacement TEXT := $replacement$        OR NOT world_start_location_receipt_matches(
            NEW.target_namespace, NEW.operation_id, NEW.request_id, NEW.commit_id,
            NEW.authorization_fence_id, NEW.account_binding_digest, NEW.binding_digest,
            NEW.canonical_tenant_id, NEW.canonical_version_id, NEW.room_template_id,
            NEW.graph_digest, NEW.receipt_digest, NEW.receipt_bytes)
        OR NOT EXISTS ($replacement$;
    application_anchor TEXT := $anchor$        OR receipt.receipt_digest IS DISTINCT FROM
            ('sha256:' || encode(sha256(receipt.receipt_bytes), 'hex')) THEN$anchor$;
    application_replacement TEXT := $replacement$        OR NOT world_start_location_receipt_matches(
            receipt.target_namespace, receipt.operation_id, receipt.request_id, receipt.commit_id,
            receipt.authorization_fence_id, receipt.account_binding_digest, receipt.binding_digest,
            receipt.canonical_tenant_id, receipt.canonical_version_id, receipt.room_template_id,
            receipt.graph_digest, receipt.receipt_digest, receipt.receipt_bytes) THEN$replacement$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_guard_draft_start_location_receipt()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original) - length(replace(original, receipt_anchor, '')))
            / length(receipt_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one World start-location receipt byte-digest predicate';
    END IF;
    EXECUTE replace(original, receipt_anchor, receipt_replacement);

    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_guard_graph_application()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original) - length(replace(original, application_anchor, '')))
            / length(application_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one World APPLIED receipt byte-digest predicate';
    END IF;
    EXECUTE replace(original, application_anchor, application_replacement);
END;
$migration$;
-- [jooq ignore stop]
