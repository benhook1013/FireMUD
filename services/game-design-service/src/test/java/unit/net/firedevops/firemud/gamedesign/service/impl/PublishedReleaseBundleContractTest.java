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
  void exactRepairAcceptsFull4VectorAndRejectsLegacyParticipantSchemas() throws Exception {
    var selector = selectorEvidence(4);
    var participants =
        PublishedWorldSelectorFixtures.participants(
            VERSION_ID,
            selector,
            AuthoredWorldReleaseAttestationEvidence.SELECTED_FULL_SCHEMA_VERSION);
    var full = bundle("v4", selector, participants);
    assertDoesNotThrow(
        () -> PublishedReleaseBundleContract.requireExactRepairMatch(full, exportedManifest));

    var legacyAutomation = participants.get(3);
    var mixed =
        replaceParticipant(
            participants,
            3,
            new PublishParticipantDigestDto(
                legacyAutomation.participantKey(),
                legacyAutomation.scopeValue(),
                legacyAutomation.baseVersionId(),
                legacyAutomation.appliedCommitId(),
                legacyAutomation.contentDigest(),
                5,
                legacyAutomation.abilitySchemaDigest(),
                legacyAutomation.errorCode(),
                legacyAutomation.errorMessage()));
    assertSelectorRepairRejected(
        bundle("v4", selector, mixed),
        "Selected-full release requires all exact successful ordered release participants");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PublishedReleaseBundleContract.requireFreshSelectedFullParticipantVector(
                VERSION_ID,
                selector.request().appliedCommitId(),
                PublishedWorldSelectorFixtures.participants(
                    VERSION_ID,
                    selector,
                    AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION)));
  }

  @Test
  void exactRepairRejectsMalformedSelectorV2Binding() throws Exception {
    WorldPublishedStartLocationEvidence selector = selectorEvidence();
    var incompleteParticipants = selectorParticipants(selector).subList(1, 5);

    IllegalArgumentException denied =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                PublishedReleaseBundleContract.requireExactRepairMatch(
                    selectorBundle(selector, incompleteParticipants), exportedManifest));
    assertEquals("World selector release lacks required participants", denied.getMessage());
  }

  @Test
  void exactRepairRejectsUnsupportedSelectorManifestAndArtifactSchemas() throws Exception {
    var selector = selectorEvidence();
    var validBundle = selectorBundle(selector, selectorParticipants(selector));

    assertSelectorRepairRejected(
        withBundle(validBundle, 2, null, null),
        "World selector differs from complete immutable release binding");
    var unsupportedArtifact =
        new PublishedArtifactDigest(
            "branding/logo",
            "IMAGE",
            "artifacts/sha256/" + "b".repeat(64),
            ARTIFACT_DIGEST,
            "image/png",
            2);
    assertSelectorRepairRejected(
        withBundle(validBundle, 1, List.of(unsupportedArtifact), null),
        "World selector contains an unsupported artifact schema");
  }

  @Test
  void exactRepairRejectsOutOfOrderSelectorParticipants() throws Exception {
    var selector = selectorEvidence();
    var participants = selectorParticipants(selector);
    assertSelectorRepairRejected(
        withBundle(
            selectorBundle(selector, participants),
            null,
            null,
            List.of(
                participants.get(1),
                participants.get(0),
                participants.get(2),
                participants.get(3),
                participants.get(4))),
        "World selector requires all exact successful ordered release participants");
  }

  @Test
  void exactRepairRejectsParticipantErrorContentAndWrongAbilitySchemaOwner() throws Exception {
    var selector = selectorEvidence();
    var participants = selectorParticipants(selector);
    var first = participants.getFirst();
    var errorContent =
        new PublishParticipantDigestDto(
            first.participantKey(),
            first.scopeValue(),
            first.baseVersionId(),
            first.appliedCommitId(),
            first.contentDigest(),
            first.digestSchemaVersion(),
            first.abilitySchemaDigest(),
            first.errorCode(),
            "unexpected participant error detail");
    assertSelectorRepairRejected(
        withBundle(
            selectorBundle(selector, participants),
            null,
            null,
            replaceParticipant(participants, 0, errorContent)),
        "World selector requires all exact successful ordered release participants");

    var nonGameLogic = participants.get(1);
    var wrongAbilityOwner =
        new PublishParticipantDigestDto(
            nonGameLogic.participantKey(),
            nonGameLogic.scopeValue(),
            nonGameLogic.baseVersionId(),
            nonGameLogic.appliedCommitId(),
            nonGameLogic.contentDigest(),
            nonGameLogic.digestSchemaVersion(),
            "sha256:" + "e".repeat(64),
            nonGameLogic.errorCode(),
            nonGameLogic.errorMessage());
    assertSelectorRepairRejected(
        withBundle(
            selectorBundle(selector, participants),
            null,
            null,
            replaceParticipant(participants, 1, wrongAbilityOwner)),
        "World selector ability schema proof must be owned by Game Logic");
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

  private void assertSelectorRepairRejected(
      PublishedReleaseBundleDto bundle, String expectedMessage) {
    IllegalArgumentException denied =
        assertThrows(
            IllegalArgumentException.class,
            () -> PublishedReleaseBundleContract.requireExactRepairMatch(bundle, exportedManifest));
    assertEquals(expectedMessage, denied.getMessage());
  }

  private static PublishedReleaseBundleDto withBundle(
      PublishedReleaseBundleDto bundle,
      Integer manifestSchemaVersion,
      List<PublishedArtifactDigest> artifactDigests,
      List<PublishParticipantDigestDto> participantDigests) {
    return new PublishedReleaseBundleDto(
        bundle.id(),
        bundle.tenantId(),
        bundle.versionId(),
        bundle.versionNumber(),
        bundle.attestationSchemaVersion(),
        bundle.publishWorkflowId(),
        bundle.manifestHash(),
        bundle.requiredManifestAssetKeys(),
        participantDigests == null ? bundle.participantDigests() : participantDigests,
        bundle.commandDefinitions(),
        bundle.generationConfigRevision(),
        bundle.scriptOnly(),
        bundle.scriptPatchVersion(),
        bundle.publishedAt(),
        bundle.canonicalTenantId(),
        bundle.canonicalVersionId(),
        bundle.publishedReleaseBundleRef(),
        manifestSchemaVersion == null ? bundle.manifestSchemaVersion() : manifestSchemaVersion,
        artifactDigests == null ? bundle.artifactDigests() : artifactDigests,
        bundle.worldPublishedStartLocationEvidence());
  }

  private static List<PublishParticipantDigestDto> replaceParticipant(
      List<PublishParticipantDigestDto> participants,
      int index,
      PublishParticipantDigestDto replacement) {
    var updated = new java.util.ArrayList<>(participants);
    updated.set(index, replacement);
    return List.copyOf(updated);
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

  private static WorldPublishedStartLocationEvidence selectorEvidence(int digestSchemaVersion)
      throws Exception {
    var base = selectorEvidence();
    var original = base.request();
    var request =
        new WorldPublishedStartLocationEvidence.Request(
            original.targetNamespace(),
            original.canonicalTenantId(),
            original.canonicalVersionId(),
            original.intakeRequestId(),
            original.publicationFence(),
            original.publicationRequestId(),
            original.requestDigest(),
            original.versionStateEpoch(),
            original.publishWorkflowId(),
            original.appliedCommitId(),
            original.contentDigest(),
            digestSchemaVersion,
            original.worldAffectedTuples());
    return new WorldPublishedStartLocationEvidence(
        request,
        base.selectorReceiptBytes(),
        base.originalAccountBindingBytes(),
        base.appliedResultBytes());
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
