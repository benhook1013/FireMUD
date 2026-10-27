-- V1034's source-event CHECK can evaluate UNKNOWN when event bytes are present but the digest is
-- NULL. A CHECK accepts UNKNOWN, so enforce the nullable pair invariant in a forward migration.
ALTER TABLE game_session_canonical_issuer_binding_local_operation
    ADD CONSTRAINT chk_gs_issuer_binding_local_operation_source_event_pair CHECK (
        (anchor_source_event_bytes IS NULL AND anchor_source_event_digest IS NULL)
        OR (anchor_source_event_bytes IS NOT NULL
            AND octet_length(anchor_source_event_bytes) > 0
            AND anchor_source_event_digest IS NOT NULL
            AND anchor_source_event_digest ~ '^sha256:[0-9a-f]{64}$')
    );
