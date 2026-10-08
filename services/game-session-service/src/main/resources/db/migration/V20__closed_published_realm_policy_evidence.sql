-- Preserve V14/V15 rows as historical evidence. Only newly written rows with the complete
-- canonical Game Design carrier and canonical Version UUID are eligible for owner reads.
ALTER TABLE gameplay_published_realm_catalog_snapshot
    ADD COLUMN canonical_version_id UUID,
    ADD COLUMN policy_set_evidence BYTEA,
    ADD COLUMN published_release_bundle_ref VARCHAR(1024),
    ADD CONSTRAINT chk_gs_published_realm_catalog_snapshot_closed_evidence
        CHECK (
            (canonical_version_id IS NULL
                AND policy_set_evidence IS NULL
                AND published_release_bundle_ref IS NULL)
            OR
            (canonical_version_id IS NOT NULL
                AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
                AND policy_set_evidence IS NOT NULL
                AND octet_length(policy_set_evidence) BETWEEN 1 AND 4194304
                AND published_release_bundle_ref IS NOT NULL
                AND char_length(published_release_bundle_ref) BETWEEN 1 AND 1024)
        );

-- V14's numeric-only uniqueness is retained within historical rows but cannot prevent a new
-- exact closed carrier for that same sealed private-row correlation from being stored.
ALTER TABLE gameplay_published_realm_catalog_snapshot
    DROP CONSTRAINT uq_gs_published_realm_catalog_snapshot_version;

CREATE UNIQUE INDEX uq_gs_published_realm_catalog_snapshot_legacy_version
    ON gameplay_published_realm_catalog_snapshot (target_namespace, tenant_id, version_id)
    WHERE canonical_version_id IS NULL;

CREATE UNIQUE INDEX uq_gs_published_realm_catalog_snapshot_closed_private_version
    ON gameplay_published_realm_catalog_snapshot (target_namespace, tenant_id, version_id)
    WHERE canonical_version_id IS NOT NULL;

CREATE UNIQUE INDEX uq_gs_published_realm_catalog_snapshot_canonical_version
    ON gameplay_published_realm_catalog_snapshot
        (target_namespace, canonical_tenant_id, canonical_version_id)
    WHERE canonical_version_id IS NOT NULL;

ALTER TABLE gameplay_published_realm_catalog_entry
    ADD COLUMN source_revision_uuid UUID;

ALTER TABLE gameplay_published_realm_catalog_entry
    ALTER COLUMN source_revision_id DROP NOT NULL;

ALTER TABLE gameplay_published_realm_catalog_entry
    DROP CONSTRAINT chk_gs_published_realm_catalog_entry_identity;

ALTER TABLE gameplay_published_realm_catalog_entry
    ADD CONSTRAINT chk_gs_published_realm_catalog_entry_closed_source_identity
        CHECK (
            (source_revision_id IS NOT NULL
                AND source_revision_id > 0
                AND source_revision_uuid IS NULL)
            OR
            (source_revision_id IS NULL
                AND source_revision_uuid IS NOT NULL
                AND source_revision_uuid <> '00000000-0000-0000-0000-000000000000'::UUID)
        );

ALTER TABLE gameplay_published_realm_catalog_entry
    ADD CONSTRAINT chk_gs_published_realm_catalog_entry_identity_v20
        CHECK (catalog_revision > 0
            AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND policy_id <> '00000000-0000-0000-0000-000000000000'::UUID);
