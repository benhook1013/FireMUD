-- Bind the original Account, receipt, and APPLIED digest fields to their retained bytes.
-- Preserve both live trigger-function identities, grants, security settings, and all other guards.
-- [jooq ignore start]
DO $migration$
DECLARE
    original TEXT;
    receipt_anchor TEXT := $anchor$        OR NEW.graph_digest IS DISTINCT FROM ('sha256:' || graph_row.graph_sha256)
        OR NOT EXISTS ($anchor$;
    receipt_replacement TEXT := $replacement$        OR NEW.graph_digest IS DISTINCT FROM ('sha256:' || graph_row.graph_sha256)
        OR NEW.account_binding_digest IS DISTINCT FROM
            ('sha256:' || encode(sha256(NEW.account_binding_bytes), 'hex'))
        OR NEW.receipt_digest IS DISTINCT FROM
            ('sha256:' || encode(sha256(NEW.receipt_bytes), 'hex'))
        OR NOT EXISTS ($replacement$;
    application_anchor TEXT := $anchor$        OR receipt.graph_digest IS DISTINCT FROM ('sha256:' || graph_row.graph_sha256)
        OR receipt.receipt_digest !~ '^sha256:[0-9a-f]{64}$' THEN$anchor$;
    application_replacement TEXT := $replacement$        OR receipt.graph_digest IS DISTINCT FROM ('sha256:' || graph_row.graph_sha256)
        OR NEW.account_binding_digest IS DISTINCT FROM
            ('sha256:' || encode(sha256(NEW.account_binding_bytes), 'hex'))
        OR NEW.result_digest IS DISTINCT FROM
            ('sha256:' || encode(sha256(NEW.result_bytes), 'hex'))
        OR receipt.account_binding_digest IS DISTINCT FROM
            ('sha256:' || encode(sha256(receipt.account_binding_bytes), 'hex'))
        OR receipt.receipt_digest IS DISTINCT FROM
            ('sha256:' || encode(sha256(receipt.receipt_bytes), 'hex')) THEN$replacement$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_guard_draft_start_location_receipt()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original) - length(replace(original, receipt_anchor, '')))
            / length(receipt_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one World start-location receipt digest anchor';
    END IF;
    EXECUTE replace(original, receipt_anchor, receipt_replacement);

    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_guard_graph_application()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original) - length(replace(original, application_anchor, '')))
            / length(application_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one World graph-application digest anchor';
    END IF;
    EXECUTE replace(original, application_anchor, application_replacement);
END;
$migration$;
-- [jooq ignore stop]
