-- The canonical length-prefixed bindingRef for three UUID identifiers is 133 bytes.
-- Preserve the original timeline migration and align its carrier bound with the existing
-- issuer/account physical bindingRef codecs rather than rejecting a valid first-NEW identity.
ALTER TABLE game_session_region_timeline_preparation
    DROP CONSTRAINT ck_game_session_region_timeline_preparation_binding;

ALTER TABLE game_session_region_timeline_preparation
    ADD CONSTRAINT ck_game_session_region_timeline_preparation_binding CHECK (
        playable_state_scope = 'SHARED'
        AND runtime_game_instance_id > 0
        AND octet_length(binding_ref) BETWEEN 1 AND 6144
        AND session_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        AND session_id <> '00000000-0000-0000-0000-000000000000'
        AND binding_generation > 0);
