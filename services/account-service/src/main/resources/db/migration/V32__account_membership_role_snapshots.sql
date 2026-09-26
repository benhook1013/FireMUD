-- Account-owned tenant-role snapshots are intentionally not backfilled.  A snapshot
-- header is the authoritative presence marker, including for an authoritative empty
-- role set; retained membership rows without one remain unverified.
CREATE TABLE account_tenant_membership_role_snapshots (
    membership_id BIGINT PRIMARY KEY
        REFERENCES account_tenant_membership(id) ON DELETE RESTRICT,
    snapshot_version BIGINT NOT NULL,
    CONSTRAINT account_membership_role_snapshot_version_check
        CHECK (snapshot_version > 0),
    CONSTRAINT account_membership_role_snapshot_identity_unique
        UNIQUE (membership_id, snapshot_version)
);

CREATE TABLE account_tenant_membership_role_snapshot_roles (
    membership_id BIGINT NOT NULL,
    snapshot_version BIGINT NOT NULL,
    role_identifier VARCHAR(128) NOT NULL,
    CONSTRAINT account_membership_role_snapshot_roles_pk
        PRIMARY KEY (membership_id, snapshot_version, role_identifier),
    CONSTRAINT account_membership_role_snapshot_roles_snapshot_fk
        FOREIGN KEY (membership_id, snapshot_version)
        REFERENCES account_tenant_membership_role_snapshots(membership_id, snapshot_version)
        ON DELETE RESTRICT,
    CONSTRAINT account_membership_role_snapshot_role_identifier_check
        CHECK (role_identifier ~ '^[A-Za-z][A-Za-z0-9_-]*$')
);

CREATE INDEX idx_account_membership_role_snapshot_roles_lookup
    ON account_tenant_membership_role_snapshot_roles (membership_id, snapshot_version);
