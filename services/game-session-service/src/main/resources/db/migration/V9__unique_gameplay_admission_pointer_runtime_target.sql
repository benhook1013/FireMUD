-- A runtime target has exactly one admission pointer. Before applying this index,
-- run the read-only duplicate preflight below and require an empty result. Do not
-- resolve duplicate rows automatically: an ambiguous authority must stop migration
-- for review.
-- SELECT tenant_id, game_instance_id, COUNT(*) AS pointer_count
-- FROM gameplay_admission_pointer
-- GROUP BY tenant_id, game_instance_id
-- HAVING COUNT(*) > 1;
CREATE UNIQUE INDEX uq_gameplay_admission_pointer_runtime_target
    ON gameplay_admission_pointer USING btree (tenant_id, game_instance_id);
