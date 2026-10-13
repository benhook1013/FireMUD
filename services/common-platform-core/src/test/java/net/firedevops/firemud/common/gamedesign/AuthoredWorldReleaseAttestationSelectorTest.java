package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadGrpcCodecTest;
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
  void v3BindsClosureQualifiedWorldSchema4InItsOwnDomainAndSurvivesStoredReadback()
      throws Exception {
    var selector = selectorEvidence(4);
    var descriptor = descriptor(selector);
    var release = release(descriptor, selector, 3);
    var stored = JSON.writeValueAsString(release);
    var readback = JSON.readValue(stored, AuthoredWorldReleaseAttestationEvidence.class);

    assertThat(release.schemaVersion())
        .isEqualTo(AuthoredWorldReleaseAttestationEvidence.CLOSURE_SELECTOR_SCHEMA_VERSION);
    assertThat(release.participantDigests().getFirst().digestSchemaVersion()).isEqualTo(4);
    assertThat(release.participantDigests().getLast().digestSchemaVersion()).isEqualTo(2);
    assertThat(readback).isEqualTo(release);
    readback.requireValid(descriptor);
    assertThat(
            new String(
                AuthoredWorldReleaseAttestationEvidence.evidencePreimage(release),
                StandardCharsets.UTF_8))
        .startsWith("49:game-design-authored-world-release-attestation/v3")
        .contains("worldStartLocationEvidence.canonicalBytesBase64")
        .endsWith(Base64.getEncoder().encodeToString(selector.canonicalBytes()));

    var wrongWorldSchema =
        release.participantDigests().stream()
            .map(
                participant ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        participant.participantKey(),
                        participant.scopeValue(),
                        participant.baseVersionIdPresent(),
                        participant.baseVersionId(),
                        participant.appliedCommitId(),
                        participant.contentDigest(),
                        "WORLD_MANAGEMENT".equals(participant.participantKey())
                            ? 3
                            : participant.digestSchemaVersion(),
                        participant.abilitySchemaDigestPresent(),
                        participant.abilitySchemaDigest()))
            .toList();
    assertThatThrownBy(
            () ->
                AuthoredWorldReleaseAttestationEvidence.createClosureSelector(
                    release.targetNamespace(),
                    release.descriptorResultDigest(),
                    release.canonicalTenantId(),
                    release.canonicalVersionId(),
                    release.worldSlug(),
                    release.authoredWorldSourceOperationId(),
                    release.authoredWorldSourceEvidenceDigest(),
                    release.launchDescriptorId(),
                    release.publishedReleaseBundleRef(),
                    release.versionStateEpoch(),
                    release.publishWorkflowId(),
                    release.commitId(),
                    wrongWorldSchema,
                    release.manifestHash(),
                    release.manifestSchemaVersion(),
                    release.requiredManifestAssetKeys(),
                    release.artifactDigests(),
                    release.commandDefinitions(),
                    release.generationConfigRevision(),
                    selector))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Participant digest schema is unsupported");
  }

  @Test
  void v4BindsTheCompleteSelectedFullMatrixInItsOwnDomainAndSurvivesStoredReadback()
      throws Exception {
    var selector = selectorEvidence(4);
    var descriptor = descriptor(selector);
    var release = release(descriptor, selector, 4);
    var stored = JSON.writeValueAsString(release);
    var readback = JSON.readValue(stored, AuthoredWorldReleaseAttestationEvidence.class);

    assertThat(release.schemaVersion())
        .isEqualTo(AuthoredWorldReleaseAttestationEvidence.SELECTED_FULL_SCHEMA_VERSION);
    assertThat(release.participantDigests())
        .extracting(AuthoredWorldReleaseAttestationEvidence.Participant::digestSchemaVersion)
        .containsExactly(4, 3, 1, 6, 2);
    assertThat(readback).isEqualTo(release);
    readback.requireValid(descriptor);
    assertThat(readback.worldStartLocationEvidence().canonicalBytes())
        .containsExactly(selector.canonicalBytes());
    assertThat(
            new String(
                AuthoredWorldReleaseAttestationEvidence.evidencePreimage(release),
                StandardCharsets.UTF_8))
        .startsWith("49:game-design-authored-world-release-attestation/v4")
        .contains("worldStartLocationEvidence.canonicalBytesBase64")
        .endsWith(Base64.getEncoder().encodeToString(selector.canonicalBytes()));

    var retainedAutomationSchema = participantDigestsWithSchema(release, "AUTOMATION_SCRIPTING", 5);
    assertThatThrownBy(() -> selectedFull(release, retainedAutomationSchema, selector))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Participant digest schema is unsupported");
    var retainedEntitySchema = participantDigestsWithSchema(release, "ENTITY_MANAGEMENT", 2);
    assertThatThrownBy(() -> selectedFull(release, retainedEntitySchema, selector))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Participant digest schema is unsupported");
    var retainedWorldSchema = participantDigestsWithSchema(release, "WORLD_MANAGEMENT", 3);
    assertThatThrownBy(() -> selectedFull(release, retainedWorldSchema, selector))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Participant digest schema is unsupported");
    var retainedControlPlaneSchema =
        participantDigestsWithSchema(release, "GAME_DESIGN_CONTROL_PLANE", 1);
    assertThatThrownBy(() -> selectedFull(release, retainedControlPlaneSchema, selector))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Participant digest schema is unsupported");
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
            retainedV1Participants(current.participantDigests()),
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
  void retainedV1CanonicalByteVectorKeepsItsOriginalDigest() {
    var evidence = retainedV1EvidenceVector();

    assertThat(evidence.evidenceDigest())
        .isEqualTo("sha256:54d38e2af416b25c0c3d05495853124eedd885ed725046052a71e3cc0a6d87e5");
    assertThat(
            new String(
                AuthoredWorldReleaseAttestationEvidence.evidencePreimage(evidence),
                StandardCharsets.UTF_8))
        .startsWith("49:game-design-authored-world-release-attestation/v1");
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

  @Test
  void selectedAttestationRejectsRetainedSchemaOneControlPlaneEvidence() throws Exception {
    var selector = selectorEvidence();
    var selected = release(descriptor(selector), selector);
    var retained = retainedV1Participants(selected.participantDigests());
    assertThat(retained.getLast().digestSchemaVersion()).isEqualTo(1);
    assertThat(selected.participantDigests().getLast().digestSchemaVersion()).isEqualTo(2);
    assertThatThrownBy(
            () ->
                new AuthoredWorldReleaseAttestationEvidence(
                    selected.schemaVersion(),
                    selected.targetNamespace(),
                    selected.descriptorResultDigest(),
                    selected.canonicalTenantId(),
                    selected.canonicalVersionId(),
                    selected.worldSlug(),
                    selected.authoredWorldSourceOperationId(),
                    selected.authoredWorldSourceEvidenceDigest(),
                    selected.launchDescriptorId(),
                    selected.publishedReleaseBundleRef(),
                    selected.versionStateEpoch(),
                    selected.publishWorkflowId(),
                    selected.commitId(),
                    retained,
                    selected.manifestHash(),
                    selected.manifestSchemaVersion(),
                    selected.requiredManifestAssetKeys(),
                    selected.artifactDigests(),
                    selected.commandDefinitions(),
                    selected.generationConfigRevision(),
                    selected.evidenceDigest(),
                    selector))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Participant digest schema is unsupported");
  }

  private static List<AuthoredWorldReleaseAttestationEvidence.Participant> retainedV1Participants(
      List<AuthoredWorldReleaseAttestationEvidence.Participant> selected) {
    // Stipulated historical evidence, independently scoped to the retained-v1 contract.
    return selected.stream()
        .map(
            p ->
                new AuthoredWorldReleaseAttestationEvidence.Participant(
                    p.participantKey(),
                    p.scopeValue(),
                    p.baseVersionIdPresent(),
                    p.baseVersionId(),
                    p.appliedCommitId(),
                    "GAME_DESIGN_CONTROL_PLANE".equals(p.participantKey())
                        ? "e".repeat(64)
                        : p.contentDigest(),
                    AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                        p.participantKey(), AuthoredWorldReleaseAttestationEvidence.SCHEMA_VERSION),
                    p.abilitySchemaDigestPresent(),
                    p.abilitySchemaDigest()))
        .toList();
  }

  private static AuthoredWorldReleaseAttestationEvidence retainedV1EvidenceVector() {
    var participants =
        List.of(
            new AuthoredWorldReleaseAttestationEvidence.Participant(
                "WORLD_MANAGEMENT", "7", false, null, "commit", "1".repeat(64), 3, false, null),
            new AuthoredWorldReleaseAttestationEvidence.Participant(
                "ENTITY_MANAGEMENT", "7", false, null, "commit", "2".repeat(64), 2, false, null),
            new AuthoredWorldReleaseAttestationEvidence.Participant(
                "GAME_LOGIC",
                "7",
                false,
                null,
                "commit",
                "3".repeat(64),
                1,
                true,
                "sha256:" + "c".repeat(64)),
            new AuthoredWorldReleaseAttestationEvidence.Participant(
                "AUTOMATION_SCRIPTING", "7", false, null, "commit", "4".repeat(64), 5, false, null),
            new AuthoredWorldReleaseAttestationEvidence.Participant(
                "GAME_DESIGN_CONTROL_PLANE",
                "7",
                false,
                null,
                "commit",
                "5".repeat(64),
                1,
                false,
                null));
    return AuthoredWorldReleaseAttestationEvidence.create(
        "test",
        "sha256:" + "a".repeat(64),
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        UUID.fromString("22222222-2222-4222-8222-222222222222"),
        "synthetic-world",
        UUID.fromString("33333333-3333-4333-8333-333333333333"),
        "sha256:" + "b".repeat(64),
        "descriptor",
        "release",
        42L,
        "workflow",
        "commit",
        participants,
        "sha256:" + "d".repeat(64),
        1,
        List.of(),
        List.of(),
        List.of(),
        "generation");
  }

  private static List<AuthoredWorldReleaseAttestationEvidence.Participant>
      participantDigestsWithSchema(
          AuthoredWorldReleaseAttestationEvidence release, String owner, int schemaVersion) {
    return release.participantDigests().stream()
        .map(
            participant ->
                new AuthoredWorldReleaseAttestationEvidence.Participant(
                    participant.participantKey(),
                    participant.scopeValue(),
                    participant.baseVersionIdPresent(),
                    participant.baseVersionId(),
                    participant.appliedCommitId(),
                    participant.contentDigest(),
                    owner.equals(participant.participantKey())
                        ? schemaVersion
                        : participant.digestSchemaVersion(),
                    participant.abilitySchemaDigestPresent(),
                    participant.abilitySchemaDigest()))
        .toList();
  }

  private static AuthoredWorldReleaseAttestationEvidence selectedFull(
      AuthoredWorldReleaseAttestationEvidence basis,
      List<AuthoredWorldReleaseAttestationEvidence.Participant> participants,
      WorldPublishedStartLocationEvidence selector) {
    return AuthoredWorldReleaseAttestationEvidence.createSelectedFull(
        basis.targetNamespace(),
        basis.descriptorResultDigest(),
        basis.canonicalTenantId(),
        basis.canonicalVersionId(),
        basis.worldSlug(),
        basis.authoredWorldSourceOperationId(),
        basis.authoredWorldSourceEvidenceDigest(),
        basis.launchDescriptorId(),
        basis.publishedReleaseBundleRef(),
        basis.versionStateEpoch(),
        basis.publishWorkflowId(),
        basis.commitId(),
        participants,
        basis.manifestHash(),
        basis.manifestSchemaVersion(),
        basis.requiredManifestAssetKeys(),
        basis.artifactDigests(),
        basis.commandDefinitions(),
        basis.generationConfigRevision(),
        selector);
  }

  static WorldPublishedStartLocationEvidence selectorEvidence() throws Exception {
    return selectorEvidence(3);
  }

  private static WorldPublishedStartLocationEvidence selectorEvidence(int digestSchemaVersion)
      throws Exception {
    var terminal =
        WorldDraftTerminalReadGrpcCodecTest.freshGraphRequestForStartLocationEvidenceTest();
    var applied =
        WorldDraftTerminalReadGrpcCodecTest.committedFreshGraphReadbackForStartLocationEvidenceTest(
            terminal);
    var account = terminal.accountBinding();
    var draft =
        DraftCommitBinding.fromStored(
            new String(account.gameDesignBinding(), StandardCharsets.UTF_8), account.inputDigest());
    var tuples =
        draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                        unit.owner().name(),
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch()))
            .toList();
    var request =
        new WorldPublishedStartLocationEvidence.Request(
            "test",
            account.tenantId(),
            account.versionId(),
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            "publication-request",
            "a".repeat(64),
            5L,
            "publish-workflow",
            account.commitId().toString(),
            "b".repeat(64),
            digestSchemaVersion,
            tuples);
    var result = JSON.readTree(applied.result());
    return new WorldPublishedStartLocationEvidence(
        request,
        Base64.getDecoder().decode(result.get("startLocationReceiptBase64").textValue()),
        applied.fullBinding(),
        applied.result());
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
    return release(
        descriptor, selector, AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION);
  }

  private static AuthoredWorldReleaseAttestationEvidence release(
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      WorldPublishedStartLocationEvidence selector,
      int attestationSchemaVersion) {
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
                            owner, attestationSchemaVersion),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "c".repeat(64) : null))
            .toList();
    if (attestationSchemaVersion
        == AuthoredWorldReleaseAttestationEvidence.CLOSURE_SELECTOR_SCHEMA_VERSION) {
      return AuthoredWorldReleaseAttestationEvidence.createClosureSelector(
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
    if (attestationSchemaVersion
        == AuthoredWorldReleaseAttestationEvidence.SELECTED_FULL_SCHEMA_VERSION) {
      return AuthoredWorldReleaseAttestationEvidence.createSelectedFull(
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
