package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import org.junit.jupiter.api.Test;

class PublishedReleaseBundleContractTest {
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("12345678-1234-4234-8234-123456789abc");
  private static final UUID CANONICAL_VERSION_ID =
      UUID.fromString("82345678-1234-4234-8234-123456789abc");
  private static final long VERSION_ID = 7L;
  private static final String TENANT_ID = "private-tenant-key";
  private static final String MANIFEST_HASH = "sha256:" + "a".repeat(64);
  private static final String ARTIFACT_DIGEST = "sha256:" + "b".repeat(64);
  private static final String ALT_ARTIFACT_DIGEST = "sha256:" + "c".repeat(64);

  private final ExportedAssetManifest exportedManifest = exportedManifest();

  @Test
  void exactRepairAcceptsCompleteSelectorV2Bundle() throws Exception {
    WorldPublishedStartLocationEvidence selector = selectorEvidence();
    assertDoesNotThrow(
        () ->
            PublishedReleaseBundleContract.requireExactRepairMatch(
                selectorBundle(selector, selectorParticipants(selector)), exportedManifest));
  }

  @Test
  void exactRepairRejectsMalformedSelectorV2Binding() throws Exception {
    WorldPublishedStartLocationEvidence selector = selectorEvidence();
    var incompleteParticipants = selectorParticipants(selector).subList(1, 5);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            PublishedReleaseBundleContract.requireExactRepairMatch(
                selectorBundle(selector, incompleteParticipants), exportedManifest));
  }

  @Test
  void exactRepairRetainsManifestSchemaDigestHashAndOrderedKeyChecks() throws Exception {
    WorldPublishedStartLocationEvidence selector = selectorEvidence();
    var validBundle = selectorBundle(selector, selectorParticipants(selector));

    assertRepairFailure(
        PublishedReleaseBundleContract.REPAIR_ATTESTATION_MISMATCH,
        validBundle,
        new ExportedAssetManifest(
            MANIFEST_HASH,
            2,
            exportedManifest.requiredManifestAssetKeys(),
            exportedManifest.artifactDigests()));
    assertRepairFailure(
        PublishedReleaseBundleContract.REPAIR_ATTESTATION_MISMATCH,
        validBundle,
        new ExportedAssetManifest(
            MANIFEST_HASH,
            1,
            List.of("branding/logo"),
            List.of(artifact("branding/logo", ALT_ARTIFACT_DIGEST))));
    assertRepairFailure(
        PublishedReleaseBundleContract.REPAIR_ATTESTATION_MISMATCH,
        validBundle,
        new ExportedAssetManifest(
            "sha256:" + "d".repeat(64),
            1,
            exportedManifest.requiredManifestAssetKeys(),
            exportedManifest.artifactDigests()));

    assertRepairFailure(
        PublishedReleaseBundleContract.REPAIR_ATTESTATION_MISMATCH,
        validBundle,
        new ExportedAssetManifest(
            MANIFEST_HASH,
            1,
            List.of("branding/other"),
            List.of(artifact("branding/other", ARTIFACT_DIGEST))));
  }

  @Test
  void exactRepairContinuesToAcceptV1AndRejectV3() {
    assertDoesNotThrow(
        () ->
            PublishedReleaseBundleContract.requireExactRepairMatch(
                bundle("v1", null, List.of()), exportedManifest));

    IllegalStateException unsupported =
        assertThrows(
            IllegalStateException.class,
            () ->
                PublishedReleaseBundleContract.requireExactRepairMatch(
                    bundle("v3", null, List.of()), exportedManifest));
    assertEquals(
        PublishedReleaseBundleContract.SCHEMA_VERSION_UNSUPPORTED,
        unsupported.getMessage().substring(0, unsupported.getMessage().indexOf(':')));
  }

  private static void assertRepairFailure(
      String expectedCode, PublishedReleaseBundleDto bundle, ExportedAssetManifest manifest) {
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> PublishedReleaseBundleContract.requireExactRepairMatch(bundle, manifest));
    assertEquals(
        expectedCode, failure.getMessage().substring(0, failure.getMessage().indexOf(':')));
  }

  private static PublishedReleaseBundleDto selectorBundle(
      WorldPublishedStartLocationEvidence selector,
      List<PublishParticipantDigestDto> participants) {
    return bundle("v2", selector, participants);
  }

  private static PublishedReleaseBundleDto bundle(
      String schema,
      WorldPublishedStartLocationEvidence selector,
      List<PublishParticipantDigestDto> participants) {
    return new PublishedReleaseBundleDto(
        11L,
        TENANT_ID,
        VERSION_ID,
        12,
        schema,
        selector == null ? "publish-workflow" : selector.request().publishWorkflowId(),
        MANIFEST_HASH,
        List.of("branding/logo"),
        participants,
        List.of(),
        "generation-config",
        false,
        null,
        null,
        CANONICAL_TENANT_ID,
        CANONICAL_VERSION_ID,
        "release-bundle-ref",
        1,
        exportedManifest().artifactDigests(),
        selector);
  }

  private static WorldPublishedStartLocationEvidence selectorEvidence() throws Exception {
    return PublishedWorldSelectorFixtures.evidence(
        new TargetProof(
            CANONICAL_TENANT_ID,
            CANONICAL_VERSION_ID,
            VERSION_ID,
            TENANT_ID,
            1L,
            TENANT_ID,
            "NEW_GAME_ROW"));
  }

  private static List<PublishParticipantDigestDto> selectorParticipants(
      WorldPublishedStartLocationEvidence selector) {
    return PublishedWorldSelectorFixtures.participants(VERSION_ID, selector).stream()
        .map(
            participant ->
                "GAME_DESIGN_CONTROL_PLANE".equals(participant.participantKey())
                    ? new PublishParticipantDigestDto(
                        participant.participantKey(),
                        participant.scopeValue(),
                        participant.baseVersionId(),
                        participant.appliedCommitId(),
                        participant.contentDigest(),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            participant.participantKey(),
                            AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION),
                        participant.abilitySchemaDigest(),
                        participant.errorCode(),
                        participant.errorMessage())
                    : participant)
        .toList();
  }

  private static ExportedAssetManifest exportedManifest() {
    return new ExportedAssetManifest(
        MANIFEST_HASH,
        1,
        List.of("branding/logo"),
        List.of(artifact("branding/logo", ARTIFACT_DIGEST)));
  }

  private static PublishedArtifactDigest artifact(String usageKey, String digest) {
    return new PublishedArtifactDigest(
        usageKey,
        "IMAGE",
        "artifacts/sha256/" + digest.substring("sha256:".length()),
        digest,
        "image/png",
        1);
  }
}
