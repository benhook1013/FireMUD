-- Keep Game Design's complete original Account publication order bound to the exact World
-- terminal operation, rather than substituting the older Account Draft authorization receipt.
-- [jooq ignore start]
CREATE FUNCTION world_publication_operation_frame(operation_bytes BYTEA, frame_offset INTEGER)
RETURNS BYTEA
LANGUAGE plpgsql IMMUTABLE STRICT
AS $$
DECLARE
    frame_length BIGINT;
    remaining_bytes BIGINT;
BEGIN
    IF frame_offset < 0 OR frame_offset > octet_length(operation_bytes) - 4 THEN
        RAISE EXCEPTION 'World publication operation contains a truncated frame header'
            USING ERRCODE = '23514';
    END IF;

    frame_length :=
        get_byte(operation_bytes, frame_offset)::BIGINT * 16777216
        + get_byte(operation_bytes, frame_offset + 1)::BIGINT * 65536
        + get_byte(operation_bytes, frame_offset + 2)::BIGINT * 256
        + get_byte(operation_bytes, frame_offset + 3)::BIGINT;
    remaining_bytes := octet_length(operation_bytes)::BIGINT - frame_offset::BIGINT - 4;
    IF frame_length > 4194304 OR frame_length > remaining_bytes THEN
        RAISE EXCEPTION 'World publication operation contains a negative or oversized frame'
            USING ERRCODE = '23514';
    END IF;
    RETURN substring(operation_bytes FROM frame_offset + 5 FOR frame_length::INTEGER);
END;
$$;
REVOKE ALL ON FUNCTION world_publication_operation_frame(BYTEA, INTEGER) FROM PUBLIC;

CREATE FUNCTION world_require_publication_operation_account_binding(
    p_operation_bytes BYTEA,
    p_publication_fence UUID,
    p_world_evidence_bytes BYTEA
)
RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    frame_offset INTEGER := 0;
    schema_bytes BYTEA;
    account_binding_bytes BYTEA;
    framed_world_evidence BYTEA;
    retained_account_binding BYTEA;
    retained_account_digest VARCHAR(71);
BEGIN
    IF p_operation_bytes IS NULL
        OR octet_length(p_operation_bytes) > 4194304
        OR octet_length(p_operation_bytes) < 4 THEN
        RAISE EXCEPTION 'World publication operation is empty or oversized'
            USING ERRCODE = '23514';
    END IF;

    schema_bytes := world_publication_operation_frame(p_operation_bytes, frame_offset);
    IF schema_bytes IS DISTINCT FROM convert_to('game-design-publication-operation/v1', 'UTF8') THEN
        RAISE EXCEPTION 'World publication operation has an unsupported schema frame'
            USING ERRCODE = '23514';
    END IF;
    frame_offset := frame_offset + 4 + octet_length(schema_bytes);

    account_binding_bytes := world_publication_operation_frame(p_operation_bytes, frame_offset);
    frame_offset := frame_offset + 4 + octet_length(account_binding_bytes);

    framed_world_evidence := world_publication_operation_frame(p_operation_bytes, frame_offset);
    frame_offset := frame_offset + 4 + octet_length(framed_world_evidence);
    IF frame_offset <> octet_length(p_operation_bytes) THEN
        RAISE EXCEPTION 'World publication operation contains trailing frames or bytes'
            USING ERRCODE = '23514';
    END IF;
    IF framed_world_evidence IS DISTINCT FROM p_world_evidence_bytes THEN
        RAISE EXCEPTION 'World publication operation differs from its exact World evidence'
            USING ERRCODE = '23514';
    END IF;

    SELECT q.account_binding_bytes, q.account_binding_digest
        INTO STRICT retained_account_binding, retained_account_digest
    FROM "${serviceSchema}".world_design_publication_account_binding q
    WHERE q.publication_fence = p_publication_fence;
    IF encode(sha256(retained_account_binding), 'hex')
        IS DISTINCT FROM substring(retained_account_digest FROM 8) THEN
        RAISE EXCEPTION 'World publication Account qualification has a corrupt binding digest'
            USING ERRCODE = '23514';
    END IF;

    RETURN account_binding_bytes IS NOT DISTINCT FROM retained_account_binding;
END;
$$;
REVOKE ALL ON FUNCTION world_require_publication_operation_account_binding(BYTEA, UUID, BYTEA)
    FROM PUBLIC;

-- Add only the exact original-order guard to V44's terminal validator. The original body remains
-- the source of every existing owner, APPLIED, selector, digest, release and outcome check.
DO $migration$
DECLARE
    original TEXT;
    operation_anchor TEXT := $anchor$    world_evidence := convert_from(NEW.world_evidence_bytes, 'UTF8')::JSONB;$anchor$;
    operation_guard TEXT := $replacement$    IF NOT world_require_publication_operation_account_binding(
        NEW.operation_bytes, NEW.publication_fence, NEW.world_evidence_bytes) THEN
        RAISE EXCEPTION 'World terminal operation differs from the exact qualified Account publication binding'
            USING ERRCODE = '23514';
    END IF;
    world_evidence := convert_from(NEW.world_evidence_bytes, 'UTF8')::JSONB;$replacement$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_validate_publication_terminal()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original) - length(replace(original, operation_anchor, '')))
        / length(operation_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one V44 World terminal evidence decode anchor';
    END IF;
    EXECUTE replace(original, operation_anchor, operation_guard);
END;
$migration$;
-- [jooq ignore stop]
