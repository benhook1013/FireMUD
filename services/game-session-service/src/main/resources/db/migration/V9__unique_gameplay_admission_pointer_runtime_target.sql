-- A runtime target has exactly one admission pointer.  Do not resolve duplicate
-- rows automatically: an ambiguous authority must stop migration for review.
CREATE UNIQUE INDEX uq_gameplay_admission_pointer_runtime_target
    ON gameplay_admission_pointer USING btree (tenant_id, game_instance_id);
