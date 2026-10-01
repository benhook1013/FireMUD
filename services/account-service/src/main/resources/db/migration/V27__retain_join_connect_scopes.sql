-- JOIN operation rows are durable replay evidence and always depend on the exact scope snapshot.
-- Restrictive FK semantics serialize scope reclamation against concurrent intent insertion.
ALTER TABLE account_join_operations
    ADD CONSTRAINT account_join_operation_scope_reference_fk
    FOREIGN KEY (scope_token_hash)
    REFERENCES account_connect_scope_records(scope_token_hash)
    ON DELETE RESTRICT;

CREATE INDEX idx_account_join_operations_scope_token_hash
    ON account_join_operations(scope_token_hash);
