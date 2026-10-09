package net.firedevops.firemud.gamedesign.service.impl;

import static net.firedevops.firemud.gamedesign.service.impl.CommandDefinitionFixtures.commandDefinition;
import static net.firedevops.firemud.gamedesign.service.impl.CommandDefinitionFixtures.validCommandDefinition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.Revision;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import tools.jackson.databind.ObjectMapper;

class PublishedReleaseBundleServiceImplTest {
  private static final String MANIFEST_HASH = "sha256:" + "a".repeat(64);
  private static final String LOGO_DIGEST = "sha256:" + "b".repeat(64);

  @Mock private PublishedReleaseBundleRepository repository;
  @Mock private RevisionRepository revisionRepository;
  @Mock private VersionRepository versionRepository;

  private PublishedReleaseBundleServiceImpl service;

  @Test
  void selectorV2PersistsActualOriginalBytesAndRetryReturnsStoredEvidenceWithoutResolvingDefaults()
      throws Exception {
    var evidence = selectorEvidence();
    var participants = PublishedWorldSelectorFixtures.participants(7L, evidence);
    // ISOLATED source-repository response; this test covers service assembly only.
    when(repository.requireSelectedCommandDefinitions(any(), any(), any(), any()))
        .thenReturn(List.of(validCommandDefinition()));
    when(repository.save(any(PublishedReleaseBundle.class)))
        .thenAnswer(
            invocation -> {
              PublishedReleaseBundle saved = invocation.getArgument(0);
              saved.setId(11L);
              saved.setPublishedReleaseBundleRef("owner-release");
              when(repository.findByTenantIdAndVersionId("tenant-1", 7L))
                  .thenReturn(Optional.of(saved));
              return saved;
            });
    var first =
        service.createFullVersionBundle(
            selectorVersion(),
            "publish-workflow",
            emptyManifest(),
            "genrev-1",
            participants,
            evidence);
    assertEquals("v2", first.attestationSchemaVersion());
    assertThat(first.worldPublishedStartLocationEvidence().canonicalBytes())
        .containsExactly(evidence.canonicalBytes());
    assertThat(first.commandDefinitions()).containsExactly(validCommandDefinition());
    org.mockito.Mockito.verifyNoInteractions(revisionRepository);
    org.mockito.Mockito.clearInvocations(versionRepository, revisionRepository, repository);
    when(versionRepository.findByTenantIdAndId("tenant-1", 7L)).thenReturn(Optional.empty());
    var retry =
        service.createFullVersionBundle(
            selectorVersion(),
            "publish-workflow",
            emptyManifest(),
            "genrev-1",
            participants,
            WorldPublishedStartLocationEvidence.fromStored(evidence.canonicalBytes()));
    assertEquals(first.id(), retry.id());
    assertThat(retry.worldPublishedStartLocationEvidence().canonicalBytes())
        .containsExactly(first.worldPublishedStartLocationEvidence().canonicalBytes());
    org.mockito.Mockito.verifyNoInteractions(versionRepository, revisionRepository);
    org.mockito.Mockito.verify(repository)
        .requireSelectedCommandDefinitions(any(), any(), any(), any());
    org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
    assertThatThrownBy(
            () ->
                service.createFullVersionBundle(
                    selectorVersion(),
                    "publish-workflow",
                    emptyManifest(),
                    "changed",
                    participants,
                    evidence))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("IDEMPOTENCY_CONFLICT");
    assertThatThrownBy(() -> PublishedReleaseBundleContract.requireSupportedSchemaForLaunch(first))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SCHEMA_VERSION_UNSUPPORTED");
    assertThatThrownBy(
            () -> PublishedReleaseBundleContract.requireExactRepairMatch(first, emptyManifest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SCHEMA_VERSION_UNSUPPORTED");
  }

  @Test
  void selectorV2RejectsMissingEvidenceWrongWorkflowIncompleteOwnersAndChangedWorldDigest()
      throws Exception {
    var evidence = selectorEvidence();
    var participants = PublishedWorldSelectorFixtures.participants(7L, evidence);
    assertThatThrownBy(
            () ->
                service.createFullVersionBundle(
                    selectorVersion(),
                    "publish-workflow",
                    emptyManifest(),
                    "genrev-1",
                    participants,
                    null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                service.createFullVersionBundle(
                    selectorVersion(),
                    "wrong-workflow",
                    emptyManifest(),
                    "genrev-1",
                    participants,
                    evidence))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                service.createFullVersionBundle(
                    selectorVersion(),
                    "publish-workflow",
                    emptyManifest(),
                    "genrev-1",
                    participants.subList(0, 4),
                    evidence))
        .isInstanceOf(IllegalArgumentException.class);
    var world = participants.getFirst();
    var changed = new java.util.ArrayList<>(participants);
    changed.set(
        0,
        new PublishParticipantDigestDto(
            world.participantKey(),
            world.scopeValue(),
            null,
            world.appliedCommitId(),
            "e".repeat(64),
            3,
            null,
            null,
            null));
    assertThatThrownBy(
            () ->
                service.createFullVersionBundle(
                    selectorVersion(),
                    "publish-workflow",
                    emptyManifest(),
                    "genrev-1",
                    changed,
                    evidence))
        .isInstanceOf(IllegalArgumentException.class);
    org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
  }

  @Test
  void selectorV2RequiresSelectedSourceCaptureBeforeBundleWrite() throws Exception {
    var evidence = selectorEvidence();
    when(repository.requireSelectedCommandDefinitions(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("SELECTED_SOURCE_CAPTURE_UNAVAILABLE"));

    assertThatThrownBy(
            () ->
                service.createFullVersionBundle(
                    selectorVersion(),
                    "publish-workflow",
                    emptyManifest(),
                    "genrev-1",
                    PublishedWorldSelectorFixtures.participants(7L, evidence),
                    evidence))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SELECTED_SOURCE_CAPTURE_UNAVAILABLE");
    org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(revisionRepository);
  }

  @Test
  void historicalReleaseNeverGainsSelectorAndCorruptedStoredCarrierFailsReadback()
      throws Exception {
    PublishedReleaseBundle retained = new PublishedReleaseBundle();
    retained.setAttestationSchemaVersion("v1");
    retained.setTenantId("tenant-1");
    retained.setVersionId(7L);
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(retained));
    assertNull(
        service.getPublishedReleaseBundle("tenant-1", 7L).worldPublishedStartLocationEvidence());
    var evidence = selectorEvidence();
    assertThatThrownBy(
            () ->
                service.createFullVersionBundle(
                    selectorVersion(),
                    "publish-workflow",
                    emptyManifest(),
                    "genrev-1",
                    PublishedWorldSelectorFixtures.participants(7L, evidence),
                    evidence))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("selector-ineligible");
    retained.setWorldPublishedStartLocationEvidenceJson(
        new String(evidence.canonicalBytes(), StandardCharsets.UTF_8));
    assertThatThrownBy(() -> service.getPublishedReleaseBundle("tenant-1", 7L))
        .isInstanceOf(IllegalArgumentException.class);
    retained.setAttestationSchemaVersion("v2");
    retained.setWorldPublishedStartLocationEvidenceJson("{}");
    assertThatThrownBy(() -> service.getPublishedReleaseBundle("tenant-1", 7L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private WorldPublishedStartLocationEvidence selectorEvidence() throws Exception {
    return PublishedWorldSelectorFixtures.evidence(
        new TargetProof(
            sourceIdentity().getCanonicalTenantId(),
            sourceIdentity().getCanonicalVersionId(),
            7L,
            "tenant-1",
            42L,
            "tenant-1",
            "NEW_GAME_ROW"));
  }

  private VersionDto selectorVersion() {
    return new VersionDto(
        7L,
        "tenant-1",
        8,
        VersionLifecycleState.PUBLISHED,
        2L,
        null,
        null,
        false,
        "notes",
        LocalDateTime.now(),
        LocalDateTime.now());
  }

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service =
        new PublishedReleaseBundleServiceImpl(repository, versionRepository, new ObjectMapper());
    Version source = new Version();
    source.setId(7L);
    source.setTenantId("tenant-1");
    source.setCanonicalTenantId(UUID.fromString("67d7b75b-42d1-4ac6-9572-684c5e633cda"));
    source.setCanonicalVersionId(UUID.fromString("c472ebd1-56d8-49df-b8fa-85963dd940f8"));
    when(versionRepository.findByTenantIdAndId("tenant-1", 7L)).thenReturn(Optional.of(source));
  }

  @Test
  void legacyFullVersionCannotCreateV1BundleFromMutableRevisions() {
    assertThatThrownBy(
            () ->
                service.createFullVersionBundle(
                    version(), "workflow-1", logoManifest(), "genrev-1", List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("PUBLISH_SELECTION_REQUIRED");

    org.mockito.Mockito.verifyNoInteractions(repository, revisionRepository, versionRepository);
  }

  @Test
  void retainedBundleWithoutCanonicalIdentityStaysAbsent() {
    PublishedReleaseBundle retained = new PublishedReleaseBundle();
    retained.setId(11L);
    retained.setTenantId("tenant-1");
    retained.setVersionId(7L);
    retained.setParticipantDigestsJson(
        "[{\"participantKey\":\"GAME_LOGIC\",\"scopeValue\":\"7\","
            + "\"baseVersionId\":null,\"appliedCommitId\":\"version:7\","
            + "\"contentDigest\":\"legacy-aggregate\",\"digestSchemaVersion\":1,"
            + "\"errorCode\":null,\"errorMessage\":null}]");
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(retained));

    var dto = service.findPublishedReleaseBundle("tenant-1", 7L).orElseThrow();

    assertNull(dto.canonicalTenantId());
    assertNull(dto.canonicalVersionId());
    assertNull(dto.publishedReleaseBundleRef());
    assertNull(dto.manifestSchemaVersion());
    assertNull(dto.artifactDigests());
    assertNull(dto.participantDigests().getFirst().abilitySchemaDigest());
  }

  private Version sourceIdentity() {
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("tenant-1");
    version.setCanonicalTenantId(UUID.fromString("67d7b75b-42d1-4ac6-9572-684c5e633cda"));
    version.setCanonicalVersionId(UUID.fromString("c472ebd1-56d8-49df-b8fa-85963dd940f8"));
    return version;
  }

  @Test
  void legacyBundleEntryPointRejectsWithoutSelectedPublicationEvidence() {
    VersionDto version =
        new VersionDto(
            7L,
            "tenant-1",
            8,
            VersionLifecycleState.PUBLISHED,
            2L,
            null,
            null,
            false,
            "notes",
            LocalDateTime.now(),
            LocalDateTime.now());
    assertThatThrownBy(
            () ->
                service.createFullVersionBundle(
                    version, "workflow-1", emptyManifest(), "genrev-1", List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("PUBLISH_SELECTION_REQUIRED");
    org.mockito.Mockito.verifyNoInteractions(repository, revisionRepository, versionRepository);
  }

  @Test
  void optionalReadDistinguishesMissingBundleWhileRequiredReadStillFails() {
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.empty());

    assertEquals(Optional.empty(), service.findPublishedReleaseBundle("tenant-1", 7L));
    assertThrows(
        PublishedReleaseBundleNotFoundException.class,
        () -> service.getPublishedReleaseBundle("tenant-1", 7L));
  }

  @Test
  void legacyBundleEntryPointDoesNotReadMutableCommandRevisionRows() {
    VersionDto version =
        new VersionDto(
            7L,
            "tenant-1",
            8,
            VersionLifecycleState.PUBLISHED,
            2L,
            null,
            null,
            false,
            "notes",
            LocalDateTime.now(),
            LocalDateTime.now());
    Revision first = new Revision();
    first.setData(commandDefinition("salute", "hail"));
    when(revisionRepository.findByTenantIdAndVersionIdAndRevisionKindOrderByIdAsc(
            "tenant-1", 7L, "COMMAND_DEFINITION"))
        .thenReturn(List.of(first));

    assertThatThrownBy(
            () ->
                service.createFullVersionBundle(
                    version, "workflow-1", emptyManifest(), "genrev-1", List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("PUBLISH_SELECTION_REQUIRED");
    org.mockito.Mockito.verifyNoInteractions(repository, versionRepository);
    org.mockito.Mockito.verify(revisionRepository, org.mockito.Mockito.never())
        .findByTenantIdAndVersionIdAndRevisionKindOrderByIdAsc(
            "tenant-1", 7L, "COMMAND_DEFINITION");
  }

  @Test
  void selectedBundleEntryPointRequiresCapturedCommandAndPolicySources() throws Exception {
    var evidence = selectorEvidence();
    var participants = PublishedWorldSelectorFixtures.participants(7L, evidence);
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.empty());
    when(versionRepository.findByTenantIdAndId("tenant-1", 7L))
        .thenReturn(Optional.of(sourceIdentity()));
    when(repository.requireSelectedCommandDefinitions(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("SELECTED_SOURCE_CAPTURE_UNAVAILABLE"));

    assertThatThrownBy(
            () ->
                service.createFullVersionBundle(
                    selectorVersion(),
                    "publish-workflow",
                    emptyManifest(),
                    "genrev-1",
                    participants,
                    evidence))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("SELECTED_SOURCE_CAPTURE_UNAVAILABLE");
    org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(revisionRepository);
  }

  private VersionDto version() {
    return new VersionDto(
        7L,
        "tenant-1",
        8,
        VersionLifecycleState.PUBLISHED,
        2L,
        null,
        null,
        false,
        "notes",
        LocalDateTime.now(),
        LocalDateTime.now());
  }

  @Test
  void selectedFullVersionBundlePersistsImmutableAttestation() throws Exception {
    var evidence = selectorEvidence();
    var participants = PublishedWorldSelectorFixtures.participants(7L, evidence);
    when(repository.requireSelectedCommandDefinitions(any(), any(), any(), any()))
        .thenReturn(List.of(validCommandDefinition()));
    when(repository.save(any(PublishedReleaseBundle.class)))
        .thenAnswer(
            invocation -> {
              PublishedReleaseBundle saved = invocation.getArgument(0);
              saved.setId(11L);
              saved.setPublishedReleaseBundleRef("owner-issued-reference-11");
              return saved;
            });

    var dto =
        service.createFullVersionBundle(
            selectorVersion(),
            "publish-workflow",
            logoManifest(),
            "genrev-1",
            participants,
            evidence);

    assertEquals(11L, dto.id());
    assertEquals("tenant-1", dto.tenantId());
    assertEquals(7L, dto.versionId());
    assertEquals(8, dto.versionNumber());
    assertEquals(MANIFEST_HASH, dto.manifestHash());
    assertEquals(1, dto.manifestSchemaVersion());
    assertEquals(List.of(logoProof()), dto.artifactDigests());
    assertEquals("genrev-1", dto.generationConfigRevision());
    assertEquals(List.of("logo.png"), dto.requiredManifestAssetKeys());
    assertEquals(participants, dto.participantDigests());
    assertEquals(List.of(validCommandDefinition()), dto.commandDefinitions());
    assertEquals("v2", dto.attestationSchemaVersion());
    assertEquals("owner-issued-reference-11", dto.publishedReleaseBundleRef());
    assertEquals(sourceIdentity().getCanonicalTenantId(), dto.canonicalTenantId());
    assertEquals(sourceIdentity().getCanonicalVersionId(), dto.canonicalVersionId());
    assertThat(dto.worldPublishedStartLocationEvidence().canonicalBytes())
        .containsExactly(evidence.canonicalBytes());
    org.mockito.Mockito.verifyNoInteractions(revisionRepository);
  }

  @Test
  void selectedFullVersionBundleRejectsDuplicateCommandDefinitionAlias() throws Exception {
    assertSelectedCommandsRejected(
        List.of(commandDefinition("salute", "hail"), commandDefinition("greet", "HAIL")),
        IllegalStateException.class);
  }

  @Test
  void selectedFullVersionBundleRejectsDuplicateCanonicalCommandIds() throws Exception {
    assertSelectedCommandsRejected(
        List.of(commandDefinition("salute", "hail"), commandDefinition("SALUTE", "greet")),
        IllegalStateException.class);
  }

  @Test
  void selectedFullVersionBundleRejectsCanonicalIdAndAliasCollisions() throws Exception {
    assertSelectedCommandsRejected(
        List.of(commandDefinition("salute", "greet"), commandDefinition("hail", "SALUTE")),
        IllegalStateException.class);
  }

  @Test
  void selectedFullVersionBundleRejectsMalformedCommandEffectDeclaration() throws Exception {
    assertSelectedCommandsRejected(
        List.of(validCommandDefinition().replace("\"value\":1", "\"value\":\"one\"")),
        IllegalArgumentException.class);
  }

  private void assertSelectedCommandsRejected(
      List<String> commandDefinitions, Class<? extends RuntimeException> exceptionType)
      throws Exception {
    var evidence = selectorEvidence();
    when(repository.requireSelectedCommandDefinitions(any(), any(), any(), any()))
        .thenReturn(commandDefinitions);

    assertThrows(
        exceptionType,
        () ->
            service.createFullVersionBundle(
                selectorVersion(),
                "publish-workflow",
                emptyManifest(),
                "genrev-1",
                PublishedWorldSelectorFixtures.participants(7L, evidence),
                evidence));
    org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(revisionRepository);
  }

  private static ExportedAssetManifest emptyManifest() {
    return new ExportedAssetManifest(MANIFEST_HASH, 1, List.of(), List.of());
  }

  private static ExportedAssetManifest logoManifest() {
    return new ExportedAssetManifest(MANIFEST_HASH, 1, List.of("logo.png"), List.of(logoProof()));
  }

  private static PublishedArtifactDigest logoProof() {
    return new PublishedArtifactDigest(
        "logo.png",
        "BINARY",
        "artifacts/sha256/" + LOGO_DIGEST.substring("sha256:".length()),
        LOGO_DIGEST,
        "image/png",
        1);
  }
}
