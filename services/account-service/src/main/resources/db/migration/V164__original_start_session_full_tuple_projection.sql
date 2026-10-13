-- Current receiving protection retains the exact Account original tuple as its projection.
-- Preserve applied migrations, immutable rows and every other current-source/owner/expiry guard.
-- Historical settlement remains a separate boundary; scalar history grants no current authority.
-- [jooq ignore start]
DO $migration$
DECLARE
    original_function REGPROCEDURE := '"${serviceSchema}".account_ss_admission_assert_current("${serviceSchema}".account_start_session_admission_protections,boolean)'::REGPROCEDURE;
    original_definition TEXT;
    projection_anchor TEXT := $anchor$    IF projection_value IS DISTINCT FROM jsonb_build_object(
        'projectionSchemaId', 'accountStartSessionRedemptionProjection',
        'projectionSchemaVersion', '1',
        'authorizationReferenceFingerprint', authorization_row.authorization_reference_fingerprint,
        'authorityEvidenceBundle', convert_from(authorization_row.authority_evidence_bundle, 'UTF8')::JSONB,
        'issuanceOperationId', authorization_row.issuance_operation_id::TEXT,
        'issuanceFence', authorization_row.issuance_fence)$anchor$;
    projection_replacement TEXT := $replacement$    IF original.account_redemption_projection IS DISTINCT FROM original.original_post_authorization_tuple
        OR projection_value #>> '{authorityEvidenceBundle,issuanceOperationIdentity,issuanceOperationId}'
            IS DISTINCT FROM authorization_row.issuance_operation_id::TEXT$replacement$;
BEGIN
    SELECT pg_get_functiondef(original_function) INTO STRICT original_definition;
    IF (length(original_definition) - length(replace(original_definition, projection_anchor, '')))
            / length(projection_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact original admission scalar projection guard';
    END IF;
    original_definition := replace(original_definition, projection_anchor, projection_replacement);
    IF (length(original_definition) - length(replace(original_definition, projection_replacement, '')))
            / length(projection_replacement) <> 1 THEN
        RAISE EXCEPTION 'Expected exact full-tuple admission projection guard';
    END IF;
    EXECUTE original_definition;
END;
$migration$;

DO $migration$
DECLARE
    original_function REGPROCEDURE := '"${serviceSchema}".account_ss_activation_assert_current(bytea,bytea,text,boolean)'::REGPROCEDURE;
    original_definition TEXT;
    projection_anchor TEXT := $anchor$        OR projection_value IS DISTINCT FROM jsonb_build_object(
            'projectionSchemaId', 'accountStartSessionRedemptionProjection',
            'projectionSchemaVersion', '1',
            'authorizationReferenceFingerprint', authorization_row.authorization_reference_fingerprint,
            'authorityEvidenceBundle', convert_from(authorization_row.authority_evidence_bundle, 'UTF8')::JSONB,
            'issuanceOperationId', authorization_row.issuance_operation_id::TEXT,
            'issuanceFence', authorization_row.issuance_fence)$anchor$;
    projection_replacement TEXT := $replacement$        OR projection_bytes IS DISTINCT FROM tuple_bytes
        OR projection_value #>> '{authorityEvidenceBundle,issuanceOperationIdentity,issuanceOperationId}'
            IS DISTINCT FROM authorization_row.issuance_operation_id::TEXT$replacement$;
BEGIN
    SELECT pg_get_functiondef(original_function) INTO STRICT original_definition;
    IF (length(original_definition) - length(replace(original_definition, projection_anchor, '')))
            / length(projection_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact original activation scalar projection guard';
    END IF;
    original_definition := replace(original_definition, projection_anchor, projection_replacement);
    IF (length(original_definition) - length(replace(original_definition, projection_replacement, '')))
            / length(projection_replacement) <> 1 THEN
        RAISE EXCEPTION 'Expected exact full-tuple activation projection guard';
    END IF;
    EXECUTE original_definition;
END;
$migration$;
-- [jooq ignore stop]
