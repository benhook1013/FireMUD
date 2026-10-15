-- Align Account settlement with the exact World-owned terminal result digest.
-- The World V1038 guard and shared terminal codec require the sha256: prefix.
-- Retained rows, installed migration bytes and all other guard predicates are preserved.
-- [jooq ignore start]
DO $migration$
DECLARE
    original TEXT;
    digest_anchor TEXT := $anchor$terminal->>'activationResultSha256' IS DISTINCT FROM encode(sha256(result_bytes), 'hex')$anchor$;
    digest_replacement TEXT := $replacement$terminal->>'activationResultSha256' IS DISTINCT FROM 'sha256:' || encode(sha256(result_bytes), 'hex')$replacement$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".account_ss_activation_settlement_insert_guard()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original) - length(replace(original, digest_anchor, ''))) / length(digest_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one Account activation terminal result digest anchor';
    END IF;
    EXECUTE replace(original, digest_anchor, digest_replacement);
END;
$migration$;
-- [jooq ignore stop]
