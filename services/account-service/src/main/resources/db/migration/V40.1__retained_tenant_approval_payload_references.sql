-- Retained approval payloads stay available while canonical Account membership or scope
-- evidence refers to their signed operation. Generated nullable keys keep other provenance
-- kinds outside this retention fence.
ALTER TABLE account_tenant_membership
    ADD COLUMN approved_tenant_payload_operation_id UUID
        /* [jooq ignore start] */
        GENERATED ALWAYS AS (
            CASE
                WHEN tenant_provenance_kind = 'APPROVED_RETAINED'
                THEN tenant_source_operation_id
                ELSE NULL
            END
        ) STORED
        /* [jooq ignore stop] */,
    ADD CONSTRAINT account_tenant_membership_approval_payload_fk
        FOREIGN KEY (approved_tenant_payload_operation_id)
        REFERENCES account_approved_legacy_tenant_association_payload(operation_id)
        ON DELETE RESTRICT;

CREATE INDEX account_tenant_membership_approved_payload_operation_idx
    ON account_tenant_membership (approved_tenant_payload_operation_id)
    WHERE approved_tenant_payload_operation_id IS NOT NULL;

ALTER TABLE account_connect_scope_records
    ADD COLUMN approved_tenant_payload_operation_id UUID
        /* [jooq ignore start] */
        GENERATED ALWAYS AS (
            CASE
                WHEN scope_digest_version = 2
                    AND tenant_provenance_kind = 'APPROVED_RETAINED'
                THEN tenant_source_operation_id
                ELSE NULL
            END
        ) STORED
        /* [jooq ignore stop] */,
    ADD CONSTRAINT account_connect_scope_approval_payload_fk
        FOREIGN KEY (approved_tenant_payload_operation_id)
        REFERENCES account_approved_legacy_tenant_association_payload(operation_id)
        ON DELETE RESTRICT;

CREATE INDEX account_connect_scope_records_approved_payload_operation_idx
    ON account_connect_scope_records (approved_tenant_payload_operation_id)
    WHERE approved_tenant_payload_operation_id IS NOT NULL;
