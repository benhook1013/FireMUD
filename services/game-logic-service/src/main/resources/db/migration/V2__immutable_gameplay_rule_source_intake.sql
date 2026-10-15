CREATE TABLE game_logic_gameplay_rule_intake_terminal (
    operation_id UUID PRIMARY KEY,
    target_namespace VARCHAR(63) NOT NULL CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    actor_account_id UUID NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    selected_commit_id UUID NOT NULL,
    operation_digest VARCHAR(71) NOT NULL CHECK (operation_digest ~ '^sha256:[0-9a-f]{64}$'),
    authorization_digest VARCHAR(71) NOT NULL CHECK (authorization_digest ~ '^sha256:[0-9a-f]{64}$'),
    authorization_bytes BYTEA NOT NULL CHECK (octet_length(authorization_bytes) BETWEEN 1 AND 4194304),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('RETAINED', 'ABORTED')),
    selected_source_bytes BYTEA,
    selected_source_digest VARCHAR(71),
    manifest_bytes BYTEA,
    manifest_digest VARCHAR(71),
    terminal_bytes BYTEA NOT NULL CHECK (octet_length(terminal_bytes) BETWEEN 1 AND 16777216),
    terminal_digest VARCHAR(71) NOT NULL CHECK (terminal_digest ~ '^sha256:[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (
        (outcome = 'RETAINED'
            AND selected_source_bytes IS NOT NULL
            AND octet_length(selected_source_bytes) BETWEEN 1 AND 4194304
            AND selected_source_digest IS NOT NULL
            AND selected_source_digest ~ '^sha256:[0-9a-f]{64}$'
            AND manifest_bytes IS NOT NULL
            AND octet_length(manifest_bytes) BETWEEN 1 AND 4194304
            AND manifest_digest IS NOT NULL
            AND manifest_digest ~ '^sha256:[0-9a-f]{64}$')
        OR
        (outcome = 'ABORTED'
            AND selected_source_bytes IS NULL
            AND selected_source_digest IS NULL
            AND manifest_bytes IS NULL
            AND manifest_digest IS NULL)
    )
);

-- [jooq ignore start]
CREATE FUNCTION reject_game_logic_gameplay_rule_intake_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Game Logic gameplay-rule intake terminals are immutable' USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER game_logic_gameplay_rule_intake_immutable
    BEFORE UPDATE OR DELETE ON game_logic_gameplay_rule_intake_terminal
    FOR EACH ROW EXECUTE FUNCTION reject_game_logic_gameplay_rule_intake_mutation();

CREATE TRIGGER game_logic_gameplay_rule_intake_no_truncate
    BEFORE TRUNCATE ON game_logic_gameplay_rule_intake_terminal
    FOR EACH STATEMENT EXECUTE FUNCTION reject_game_logic_gameplay_rule_intake_mutation();
-- [jooq ignore stop]
