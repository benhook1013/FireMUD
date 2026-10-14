-- Keep the complete original operator tuple and exact source-backed catalog identity with each
-- newly created canonical launch. Retained V26 rows remain unqualified and are not backfilled.
ALTER TABLE game_session_canonical_instance_launch
    ADD COLUMN original_start_session_tuple BYTEA,
    ADD COLUMN catalog_creation_request_id UUID,
    ADD COLUMN catalog_revision BIGINT,
    ADD COLUMN catalog_source_intake_operation_id UUID,
    ADD COLUMN catalog_source_intake_request_id UUID,
    ADD CONSTRAINT chk_gs_canonical_instance_launch_original_tuple
        CHECK (original_start_session_tuple IS NULL
            OR octet_length(original_start_session_tuple) BETWEEN 1 AND 262144),
    ADD CONSTRAINT chk_gs_canonical_instance_launch_catalog_identity
        CHECK ((original_start_session_tuple IS NULL
                AND catalog_creation_request_id IS NULL
                AND catalog_revision IS NULL
                AND catalog_source_intake_operation_id IS NULL
                AND catalog_source_intake_request_id IS NULL)
            OR (original_start_session_tuple IS NOT NULL
                AND catalog_creation_request_id IS NOT NULL
                AND catalog_revision IS NOT NULL
                AND catalog_revision > 0
                AND catalog_source_intake_operation_id IS NOT NULL
                AND catalog_source_intake_request_id IS NOT NULL
                AND catalog_source_intake_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
                AND catalog_source_intake_request_id <> '00000000-0000-0000-0000-000000000000'::UUID));
ALTER TABLE game_session_canonical_realm_catalog
    ADD CONSTRAINT uq_gs_canonical_realm_catalog_exact_launch_key
        UNIQUE (target_namespace, canonical_tenant_id, realm_id, creation_request_id,
                catalog_revision, source_intake_operation_id, source_intake_request_id);

ALTER TABLE game_session_canonical_instance_launch
    ADD CONSTRAINT fk_gs_canonical_instance_launch_exact_catalog
        FOREIGN KEY (target_namespace, canonical_tenant_id, canonical_realm_id,
                     catalog_creation_request_id, catalog_revision,
                     catalog_source_intake_operation_id, catalog_source_intake_request_id)
        REFERENCES game_session_canonical_realm_catalog
            (target_namespace, canonical_tenant_id, realm_id, creation_request_id,
             catalog_revision, source_intake_operation_id, source_intake_request_id);

-- Historical rows are intentionally permitted to retain all five nullable fields. Every new
-- launch, however, must be source-backed and revision-qualified at insertion time.
-- [jooq ignore start]
CREATE FUNCTION require_complete_canonical_launch_catalog_identity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.original_start_session_tuple IS NULL
       OR octet_length(NEW.original_start_session_tuple) NOT BETWEEN 1 AND 262144
       OR NEW.catalog_creation_request_id IS NULL
       OR NEW.catalog_revision IS NULL
       OR NEW.catalog_revision <= 0
       OR NEW.catalog_source_intake_operation_id IS NULL
       OR NEW.catalog_source_intake_request_id IS NULL
       OR NEW.catalog_source_intake_operation_id = '00000000-0000-0000-0000-000000000000'::UUID
       OR NEW.catalog_source_intake_request_id = '00000000-0000-0000-0000-000000000000'::UUID THEN
        RAISE EXCEPTION 'New canonical instance launch requires the complete original tuple and exact catalog identity'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chk_gs_canonical_instance_launch_catalog_identity';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER gs_canonical_instance_launch_complete_catalog_identity
    BEFORE INSERT ON game_session_canonical_instance_launch
    FOR EACH ROW EXECUTE FUNCTION require_complete_canonical_launch_catalog_identity();
-- [jooq ignore stop]
