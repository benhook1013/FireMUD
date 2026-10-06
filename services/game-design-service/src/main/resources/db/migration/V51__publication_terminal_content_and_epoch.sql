-- Retained V50 terminal rows keep their original bytes and gain no unverifiable epoch.
ALTER TABLE game_design_publication_operation ADD COLUMN terminal_evidence_bytes BYTEA;
ALTER TABLE game_design_publication_operation ADD COLUMN publication_version_state_epoch BIGINT;
ALTER TABLE game_design_publication_operation ADD COLUMN release_content_bytes BYTEA;
ALTER TABLE game_design_publication_operation ADD COLUMN published_release_bundle_digest VARCHAR(71);
ALTER TABLE game_design_publication_operation ADD CONSTRAINT publication_terminal_extension_shape CHECK (
    (terminal_evidence_bytes IS NULL AND publication_version_state_epoch IS NULL
        AND release_content_bytes IS NULL AND published_release_bundle_digest IS NULL)
    OR (terminal_evidence_bytes IS NOT NULL AND octet_length(terminal_evidence_bytes) > 0 AND
        ((outcome = 'NO_PUBLICATION' AND publication_version_state_epoch IS NULL
            AND release_content_bytes IS NULL AND published_release_bundle_digest IS NULL)
        OR (outcome = 'PUBLISHED' AND publication_version_state_epoch IS NOT NULL AND publication_version_state_epoch > 0
            AND release_content_bytes IS NOT NULL AND octet_length(release_content_bytes) > 0
            AND published_release_bundle_digest IS NOT NULL AND published_release_bundle_digest ~ '^sha256:[0-9a-f]{64}$'))));

-- [jooq ignore start]
-- ADR byte-length framing for complete immutable release content, distinct from launch evidence.
CREATE FUNCTION publication_content_frame(value BYTEA) RETURNS BYTEA AS $$
    SELECT convert_to(octet_length(value)::TEXT || ':', 'UTF8') || value;
$$ LANGUAGE SQL IMMUTABLE STRICT;
CREATE FUNCTION publication_content_text(value TEXT) RETURNS BYTEA AS $$
    SELECT publication_content_frame(convert_to(value, 'UTF8'));
$$ LANGUAGE SQL IMMUTABLE STRICT;
CREATE FUNCTION publication_content_optional(value TEXT) RETURNS BYTEA AS $$
    SELECT publication_content_text(CASE WHEN value IS NULL THEN 'false' ELSE 'true' END)
        || publication_content_text(COALESCE(value, ''));
$$ LANGUAGE SQL IMMUTABLE;
CREATE FUNCTION publication_content_strings(values_json JSONB) RETURNS BYTEA AS $$
DECLARE result BYTEA; value JSONB;
BEGIN
    result := publication_content_text(jsonb_array_length(values_json)::TEXT);
    FOR value IN SELECT * FROM jsonb_array_elements(values_json) LOOP
        result := result || publication_content_text(value #>> '{}');
    END LOOP;
    RETURN result;
END;
$$ LANGUAGE plpgsql IMMUTABLE STRICT;

CREATE FUNCTION publication_release_content(release published_release_bundle) RETURNS BYTEA AS $$
DECLARE result BYTEA; entry BYTEA; item JSONB;
BEGIN
    IF release.script_only OR release.script_patch_version IS NOT NULL
        OR release.attestation_schema_version <> 'v2' THEN
        RAISE EXCEPTION 'terminal publication requires complete full-version v2 content' USING ERRCODE = 'check_violation';
    END IF;
    result := publication_content_text('game-design-published-release-bundle/v1')
        || publication_content_text(release.canonical_tenant_id::TEXT)
        || publication_content_text(release.canonical_version_id::TEXT)
        || publication_content_text(release.published_release_bundle_ref)
        || publication_content_text(release.version_number::TEXT)
        || publication_content_text(release.attestation_schema_version)
        || publication_content_text(release.publish_workflow_id)
        || publication_content_text(release.manifest_hash)
        || publication_content_text(release.manifest_schema_version::TEXT)
        || publication_content_text(jsonb_array_length(release.artifact_digests_json::JSONB)::TEXT);
    FOR item IN SELECT * FROM jsonb_array_elements(release.artifact_digests_json::JSONB) LOOP
        entry := publication_content_text(item->>'usageKey') || publication_content_text(item->>'artifactKind')
            || publication_content_text(item->>'immutableObjectKey') || publication_content_text(item->>'contentDigest')
            || publication_content_text(item->>'contentType') || publication_content_text(item->>'artifactSchemaVersion');
        result := result || publication_content_frame(entry);
    END LOOP;
    result := result || publication_content_strings(release.required_manifest_asset_keys_json::JSONB)
        || publication_content_text(jsonb_array_length(release.participant_digests_json::JSONB)::TEXT);
    FOR item IN SELECT * FROM jsonb_array_elements(release.participant_digests_json::JSONB) LOOP
        entry := publication_content_text(item->>'participantKey') || publication_content_text(item->>'scopeValue')
            || publication_content_optional(item->>'baseVersionId') || publication_content_text(item->>'appliedCommitId')
            || publication_content_text(item->>'contentDigest') || publication_content_text(item->>'digestSchemaVersion')
            || publication_content_optional(item->>'abilitySchemaDigest') || publication_content_optional(item->>'errorCode')
            || publication_content_optional(item->>'errorMessage');
        result := result || publication_content_frame(entry);
    END LOOP;
    RETURN result || publication_content_strings(release.command_definitions_json::JSONB)
        || publication_content_text(release.generation_config_revision)
        || publication_content_text(release.world_published_start_location_evidence_json)
        || publication_content_text('false') || publication_content_optional(NULL);
END;
$$ LANGUAGE plpgsql STABLE STRICT;

CREATE FUNCTION guard_publication_terminal_extension() RETURNS trigger AS $$
DECLARE release published_release_bundle%ROWTYPE; owner_version version%ROWTYPE;
    expected BYTEA; content BYTEA; freeze_epoch BIGINT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.terminal_evidence_bytes IS NOT NULL THEN
            RAISE EXCEPTION 'new publication operation must remain pending' USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    -- This extension is captured only at the original seal, never backfilled onto V50 history.
    IF OLD.outcome <> 'PENDING' OR OLD.terminal_evidence_bytes IS NOT NULL
        OR NEW.outcome = 'PENDING' OR NEW.terminal_evidence_bytes IS NULL THEN
        RAISE EXCEPTION 'publication terminal extension is immutable and cannot be backfilled' USING ERRCODE = 'check_violation';
    END IF;
    expected := int4send(octet_length(convert_to('game-design-publication-terminal/v1', 'UTF8')))
        || convert_to('game-design-publication-terminal/v1', 'UTF8')
        || int4send(octet_length(NEW.request_bytes)) || NEW.request_bytes
        || int4send(octet_length(convert_to(NEW.outcome, 'UTF8'))) || convert_to(NEW.outcome, 'UTF8');
    IF NEW.outcome = 'PUBLISHED' THEN
        SELECT * INTO STRICT release FROM published_release_bundle
            WHERE tenant_id = NEW.tenant_id AND version_id = NEW.version_id;
        SELECT * INTO STRICT owner_version FROM version
            WHERE tenant_id = NEW.tenant_id AND id = NEW.version_id FOR UPDATE;
        freeze_epoch := ((convert_from(published_world_selector_frame(NEW.request_bytes, 2), 'UTF8')::JSONB)
            ->'request'->>'versionStateEpoch')::BIGINT;
        content := publication_release_content(release);
        IF owner_version.version_state <> 'PUBLISHED'
            OR owner_version.version_state_epoch <> freeze_epoch + 1
            OR NEW.publication_version_state_epoch IS DISTINCT FROM owner_version.version_state_epoch
            OR NEW.release_content_bytes IS DISTINCT FROM content
            OR NEW.published_release_bundle_digest IS DISTINCT FROM 'sha256:' || encode(sha256(content), 'hex')
            OR release.publish_workflow_id IS DISTINCT FROM NEW.publish_workflow_id
            OR to_jsonb(release)::TEXT IS DISTINCT FROM NEW.release_row_json THEN
            RAISE EXCEPTION 'publication terminal content or committed epoch differs from owner seal' USING ERRCODE = 'check_violation';
        END IF;
        expected := expected || int4send(octet_length(content)) || content
            || int4send(octet_length(convert_to(NEW.publication_version_state_epoch::TEXT, 'UTF8')))
            || convert_to(NEW.publication_version_state_epoch::TEXT, 'UTF8');
    END IF;
    IF NEW.terminal_evidence_bytes IS DISTINCT FROM expected THEN
        RAISE EXCEPTION 'publication terminal bytes differ from original owner result' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER publication_terminal_extension_guard BEFORE INSERT OR UPDATE
    ON game_design_publication_operation FOR EACH ROW EXECUTE FUNCTION guard_publication_terminal_extension();

CREATE FUNCTION verify_publication_terminal_epoch_commit() RETURNS trigger AS $$
BEGIN
    IF NEW.outcome = 'PUBLISHED' AND NEW.terminal_evidence_bytes IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM version WHERE tenant_id = NEW.tenant_id AND id = NEW.version_id
            AND version_state = 'PUBLISHED' AND version_state_epoch = NEW.publication_version_state_epoch) THEN
        RAISE EXCEPTION 'publication terminal epoch lacks its exact atomic Version commit' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER publication_terminal_epoch_commit_guard AFTER UPDATE
    ON game_design_publication_operation DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION verify_publication_terminal_epoch_commit();
-- [jooq ignore stop]
