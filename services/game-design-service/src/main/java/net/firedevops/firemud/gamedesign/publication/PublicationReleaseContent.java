package net.firedevops.firemud.gamedesign.publication;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import org.jooq.Record;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Maps the actual immutable release row, never a launch descriptor or a mutable Version read. */
final class PublicationReleaseContent {
  private PublicationReleaseContent() {}

  static GameDesignPublicationTerminalEvidence.ReleaseContent fromRow(Record row) {
    if (row == null
        || !Boolean.FALSE.equals(row.get("script_only", Boolean.class))
        || row.get("script_patch_version", String.class) != null) {
      throw new IllegalStateException("PUBLICATION_RELEASE_NOT_FULL_VERSION");
    }
    var mapper = new ObjectMapper();
    List<PublishParticipantDigestDto> participants =
        mapper.readValue(
            row.get("participant_digests_json", String.class), new TypeReference<>() {});
    return new GameDesignPublicationTerminalEvidence.ReleaseContent(
        row.get("canonical_tenant_id", UUID.class),
        row.get("canonical_version_id", UUID.class),
        row.get("published_release_bundle_ref", String.class),
        row.get("version_number", Integer.class),
        row.get("attestation_schema_version", String.class),
        row.get("publish_workflow_id", String.class),
        row.get("manifest_hash", String.class),
        row.get("manifest_schema_version", Integer.class),
        mapper.readValue(
            row.get("artifact_digests_json", String.class),
            new TypeReference<List<AuthoredWorldReleaseAttestationEvidence.Artifact>>() {}),
        mapper.readValue(
            row.get("required_manifest_asset_keys_json", String.class),
            new TypeReference<List<String>>() {}),
        participants.stream()
            .map(
                p ->
                    new GameDesignPublicationTerminalEvidence.Participant(
                        p.participantKey(),
                        p.scopeValue(),
                        p.baseVersionId(),
                        p.appliedCommitId(),
                        p.contentDigest(),
                        p.digestSchemaVersion(),
                        p.abilitySchemaDigest(),
                        p.errorCode(),
                        p.errorMessage()))
            .toList(),
        mapper.readValue(
            row.get("command_definitions_json", String.class),
            new TypeReference<List<String>>() {}),
        row.get("generation_config_revision", String.class),
        WorldPublishedStartLocationEvidence.fromStored(
            row.get("world_published_start_location_evidence_json", String.class)
                .getBytes(StandardCharsets.UTF_8)));
  }
}
