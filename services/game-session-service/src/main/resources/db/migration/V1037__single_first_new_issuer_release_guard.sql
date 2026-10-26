-- Keep the V1035 reservation and Account-obligation guards as the single authorities for
-- proof-backed first-NEW release. V1036's reservation trigger duplicated the same release
-- predicate, and its Account-obligation trigger duplicated the V1035 deferred source check.
-- [jooq ignore start]
DROP TRIGGER game_session_first_new_issuer_release_reservation
    ON game_session_canonical_issuer_partition_reservation;
DROP FUNCTION require_first_new_issuer_release_reservation();

DROP TRIGGER game_session_first_new_issuer_release_account_atomic
    ON game_session_canonical_binding_account_index_obligation;
-- [jooq ignore stop]
