-- An aborted attempt may have an unknown binding decision. Its exact cleanup identity remains
-- mandatory pending work, never proof of decision absence, cleanup delivery or token retirement.
-- V87's existing rows, immutable evidence, deadline, digest and allocation guards are unchanged.

-- V87 declared this table-level CHECK without an explicit name. Resolve only its exact three
-- constrained columns; fail rather than removing an unrelated or ambiguous constraint.
-- PostgreSQL catalog lookup is excluded only from jOOQ's schema simulation, not runtime proof.
-- [jooq ignore start]
DO $$
DECLARE
    original_shape_constraint TEXT;
BEGIN
    SELECT c.conname INTO STRICT original_shape_constraint
    FROM pg_constraint c
    WHERE c.conrelid = 'account_gameplay_admission_lease_operations'::regclass
        AND c.contype = 'c'
        AND ARRAY(SELECT a.attname::text
                  FROM pg_attribute a
                  WHERE a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
                  ORDER BY a.attname) = ARRAY['binding_decision_id', 'orphan_cleanup_id', 'status'];
    EXECUTE format('ALTER TABLE account_gameplay_admission_lease_operations DROP CONSTRAINT %I',
        original_shape_constraint);
END;
$$;
-- [jooq ignore stop]

ALTER TABLE account_gameplay_admission_lease_operations
    ADD CONSTRAINT account_admission_lease_operation_shape_check
    CHECK ((status = 'PENDING' AND binding_decision_id IS NULL AND orphan_cleanup_id IS NULL)
        OR (status = 'COMMITTED' AND binding_decision_id IS NOT NULL AND orphan_cleanup_id IS NULL)
        OR (status = 'ABORTED' AND orphan_cleanup_id IS NOT NULL));
