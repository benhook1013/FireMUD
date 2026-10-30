-- Bound join planning for the exact first-NEW abort completeness guard. This changes only the
-- function-local planner setting; callers retain their existing session configuration.
-- [jooq ignore start]
DO $$
BEGIN
    EXECUTE format(
        'ALTER FUNCTION %I.require_first_new_abort_source_complete() SET join_collapse_limit = 1',
        current_schema());
END $$;
-- [jooq ignore stop]
