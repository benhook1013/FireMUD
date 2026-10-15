-- Keep every copied canonical and fixed-preset field bound to its retained World row for fixtures.
-- [jooq ignore start]
DO $migration$
DECLARE
    original TEXT;
    copy_anchor TEXT := $anchor$            OR instance.game_design_version_id IS DISTINCT FROM NEW.game_design_version_id$anchor$;
    replacement TEXT := $replacement$            OR instance.game_design_version_id IS DISTINCT FROM NEW.game_design_version_id
            OR instance.canonical_realm_id IS DISTINCT FROM NEW.canonical_realm_id
            OR instance.catalog_revision IS DISTINCT FROM NEW.catalog_revision
            OR instance.playtest_lifecycle_id IS DISTINCT FROM NEW.playtest_lifecycle_id
            OR instance.playtest_state_generation IS DISTINCT FROM NEW.playtest_state_generation
            OR instance.script_patch_version IS DISTINCT FROM NEW.script_patch_version
            OR instance.runtime_flags_json IS DISTINCT FROM NEW.runtime_flags_json
            OR instance.generation_config_revision IS DISTINCT FROM NEW.generation_config_revision
            OR instance.release_bundle_id IS DISTINCT FROM NEW.release_bundle_id
            OR instance.published_release_bundle_ref IS DISTINCT FROM NEW.published_release_bundle_ref
            OR instance.remap_set_id IS DISTINCT FROM NEW.remap_set_id$replacement$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_validate_canonical_instance_association()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,copy_anchor,'')))/length(copy_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one run-owned fixture association World-copy check anchor';
    END IF;
    EXECUTE replace(original, copy_anchor, replacement);
END;
$migration$;
-- [jooq ignore stop]
