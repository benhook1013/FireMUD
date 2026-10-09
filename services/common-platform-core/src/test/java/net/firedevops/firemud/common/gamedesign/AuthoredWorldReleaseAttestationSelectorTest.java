package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AuthoredWorldReleaseAttestationSelectorTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void v2BindsCompleteOriginalEvidenceInItsOwnDomainAndSurvivesStoredReadback() throws Exception {
    var selector = selectorEvidence();
    var descriptor = descriptor(selector);
    var release = release(descriptor, selector);
    var stored = JSON.writeValueAsString(release);
    var readback = JSON.readValue(stored, AuthoredWorldReleaseAttestationEvidence.class);

    assertThat(release.schemaVersion()).isEqualTo(2);
    assertThat(readback).isEqualTo(release);
    readback.requireValid(descriptor);
    assertThat(
            new String(
                AuthoredWorldReleaseAttestationEvidence.evidencePreimage(release),
                StandardCharsets.UTF_8))
        .startsWith("49:game-design-authored-world-release-attestation/v2")
        .contains("worldStartLocationEvidence.canonicalBytesBase64")
        .endsWith(Base64.getEncoder().encodeToString(selector.canonicalBytes()));
    assertThat(readback.worldStartLocationEvidence().selectorReceiptBytes())
        .containsExactly(selector.selectorReceiptBytes());
    assertThat(readback.worldStartLocationEvidence().originalAccountBindingBytes())
        .containsExactly(selector.originalAccountBindingBytes());
    assertThat(readback.worldStartLocationEvidence().appliedResultBytes())
        .containsExactly(selector.appliedResultBytes());
  }

  @Test
  void v1BytesAndDigestRemainUnchangedAndCannotBePromotedBySupplyingASelector() throws Exception {
    var selector = selectorEvidence();
    var descriptor = descriptor(selector);
    var current = release(descriptor, selector);
    var old =
        AuthoredWorldReleaseAttestationEvidence.create(
            current.targetNamespace(),
            current.descriptorResultDigest(),
            current.canonicalTenantId(),
            current.canonicalVersionId(),
            current.worldSlug(),
            current.authoredWorldSourceOperationId(),
            current.authoredWorldSourceEvidenceDigest(),
            current.launchDescriptorId(),
            current.publishedReleaseBundleRef(),
            current.versionStateEpoch(),
            current.publishWorkflowId(),
            current.commitId(),
            current.participantDigests(),
            current.manifestHash(),
            current.manifestSchemaVersion(),
            current.requiredManifestAssetKeys(),
            current.artifactDigests(),
            current.commandDefinitions(),
            current.generationConfigRevision());
    String stored = JSON.writeValueAsString(old);
    assertThat(stored).doesNotContain("worldStartLocationEvidence");
    assertThat(
            JSON.writeValueAsString(
                JSON.readValue(stored, AuthoredWorldReleaseAttestationEvidence.class)))
        .isEqualTo(stored);
    assertThat(
            AuthoredWorldLaunchDescriptorGrpcCodec.toReleaseAttestation(old)
                .hasWorldStartLocationEvidence())
        .isFalse();
    assertThat(old.evidenceDigest()).isNotEqualTo(current.evidenceDigest());
    assertThatThrownBy(() -> copy(old, 1, selector)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> copy(current, 2, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> copy(current, 3, selector))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> copy(old, 2, selector).requireValid())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void selectorSubstitutionAtAnOtherwiseValidCheckpointChangesTheDigest() throws Exception {
    var selector = selectorEvidence();
    var descriptor = descriptor(selector);
    var release = release(descriptor, selector);
    var request = selector.request();
    var other =
        new WorldPublishedStartLocationEvidence(
            new WorldPublishedStartLocationEvidence.Request(
                request.targetNamespace(),
                request.canonicalTenantId(),
                request.canonicalVersionId(),
                request.intakeRequestId(),
                UUID.randomUUID(),
                request.publicationRequestId(),
                request.requestDigest(),
                request.versionStateEpoch(),
                request.publishWorkflowId(),
                request.appliedCommitId(),
                request.contentDigest(),
                request.digestSchemaVersion(),
                request.worldAffectedTuples()),
            selector.selectorReceiptBytes(),
            selector.originalAccountBindingBytes(),
            selector.appliedResultBytes());
    assertThat(release(descriptor, other).evidenceDigest()).isNotEqualTo(release.evidenceDigest());
    assertThatThrownBy(() -> copy(release, 2, other).requireValid())
        .isInstanceOf(IllegalArgumentException.class);
    var wrongCheckpoint =
        new WorldPublishedStartLocationEvidence(
            new WorldPublishedStartLocationEvidence.Request(
                request.targetNamespace(),
                request.canonicalTenantId(),
                request.canonicalVersionId(),
                request.intakeRequestId(),
                request.publicationFence(),
                request.publicationRequestId(),
                request.requestDigest(),
                request.versionStateEpoch(),
                "other-workflow",
                request.appliedCommitId(),
                "c".repeat(64),
                request.digestSchemaVersion(),
                request.worldAffectedTuples()),
            selector.selectorReceiptBytes(),
            selector.originalAccountBindingBytes(),
            selector.appliedResultBytes());
    assertThatThrownBy(() -> copy(release, 2, wrongCheckpoint))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static WorldPublishedStartLocationEvidence selectorEvidence() throws Exception {
    return AuthoringFixtures.startLocationEvidence();
  }

  static AuthoredWorldLaunchDescriptorEvidence descriptor(
      WorldPublishedStartLocationEvidence selector) {
    var request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            "test",
            "launch-control-request",
            selector.request().canonicalTenantId(),
            "synthetic-world",
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            "sha256:" + "b".repeat(64),
            19L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    return AuthoredWorldLaunchDescriptorEvidence.create(
        request,
        "descriptor",
        42L,
        false,
        null,
        "{}",
        "generation",
        9L,
        7L,
        "release",
        false,
        null);
  }

  static AuthoredWorldReleaseAttestationEvidence release(
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      WorldPublishedStartLocationEvidence selector) {
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        selector.request().appliedCommitId(),
                        "b".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "c".repeat(64) : null))
            .toList();
    return AuthoredWorldReleaseAttestationEvidence.create(
        descriptor.targetNamespace(),
        descriptor.resultDigest(),
        descriptor.canonicalTenantId(),
        selector.request().canonicalVersionId(),
        descriptor.worldSlug(),
        descriptor.authoredWorldSourceOperationId(),
        descriptor.authoredWorldSourceEvidenceDigest(),
        descriptor.launchDescriptorId(),
        descriptor.publishedReleaseBundleRef(),
        descriptor.versionStateEpoch(),
        selector.request().publishWorkflowId(),
        selector.request().appliedCommitId(),
        participants,
        "sha256:" + "d".repeat(64),
        1,
        List.of(),
        List.of(),
        List.of("LOOK"),
        descriptor.generationConfigRevision(),
        selector);
  }

  private static AuthoredWorldReleaseAttestationEvidence copy(
      AuthoredWorldReleaseAttestationEvidence old,
      int schema,
      WorldPublishedStartLocationEvidence selector) {
    return new AuthoredWorldReleaseAttestationEvidence(
        schema,
        old.targetNamespace(),
        old.descriptorResultDigest(),
        old.canonicalTenantId(),
        old.canonicalVersionId(),
        old.worldSlug(),
        old.authoredWorldSourceOperationId(),
        old.authoredWorldSourceEvidenceDigest(),
        old.launchDescriptorId(),
        old.publishedReleaseBundleRef(),
        old.versionStateEpoch(),
        old.publishWorkflowId(),
        old.commitId(),
        old.participantDigests(),
        old.manifestHash(),
        old.manifestSchemaVersion(),
        old.requiredManifestAssetKeys(),
        old.artifactDigests(),
        old.commandDefinitions(),
        old.generationConfigRevision(),
        old.evidenceDigest(),
        selector);
  }
}
