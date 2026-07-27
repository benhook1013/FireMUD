ALTER TABLE accounts
    ADD COLUMN lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'active';

ALTER TABLE accounts
    ADD CONSTRAINT accounts_lifecycle_state_check
    CHECK (lifecycle_state IN (
        'active',
        'security_locked',
        'deactivated_pending_delete',
        'deleted'
    ));
