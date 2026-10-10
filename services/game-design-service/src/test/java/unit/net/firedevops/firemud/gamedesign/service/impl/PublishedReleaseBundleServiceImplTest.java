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
  void selectedFullV4PersistsOriginalBytesAndRetryReturnsStoredEvidenceWithoutResolvingDefaults()
      throws Exception {
    var evidence = closureSelectorEvidence();
    var participants = PublishedWorldSelectorFixtures.selectedFullParticipants(7L, evidence);
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
    assertEquals("v4", first.attestationSchemaVersion());
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
  void selectedFullV4RejectsMissingEvidenceWrongWorkflowIncompleteOwnersAndChangedWorldDigest()
      throws Exception {
    var evidence = closureSelectorEvidence();
    var participants = PublishedWorldSelectorFixtures.selectedFullParticipants(7L, evidence);
    var obsolete = new java.util.ArrayList<>(participants);
    var gd = obsolete.getLast();
    obsolete.set(
        obsolete.size() - 1,
        new PublishParticipantDigestDto(
            gd.participantKey(),
            gd.scopeValue(),
            gd.appliedCommitId(),
            gd.contentDigest(),
            1,
            null,
            null));
    assertThatThrownBy(
            () ->
                service.createFullVersionBundle(
                    selectorVersion(),
                    "publish-workflow",
                    emptyManifest(),
                    "genrev-1",
                    obsolete,
                    evidence))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact successful release participants");
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
            world.digestSchemaVersion(),
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
  void selectedFullV4RejectsEveryMixedParticipantSchemaBeforeWrite() throws Exception {
    var evidence = closureSelectorEvidence();
    var participants = PublishedWorldSelectorFixtures.selectedFullParticipants(7L, evidence);
    for (int index = 0; index < participants.size(); index++) {
      var mixed = new java.util.ArrayList<>(participants);
      var participant = mixed.get(index);
      mixed.set(
          index,
          new PublishParticipantDigestDto(
              participant.participantKey(),
              participant.scopeValue(),
              participant.baseVersionId(),
              participant.appliedCommitId(),
              participant.contentDigest(),
              participant.digestSchemaVersion() == 1 ? 2 : participant.digestSchemaVersion() - 1,
              participant.abilitySchemaDigest(),
              participant.errorCode(),
              participant.errorMessage()));
      assertThatThrownBy(
              () ->
                  service.createFullVersionBundle(
                      selectorVersion(),
                      "publish-workflow",
                      emptyManifest(),
                      "genrev-1",
                      mixed,
                      evidence))
          .as("mixed profile for %s", participant.participantKey())
          .isInstanceOf(IllegalArgumentException.class);
    }
    org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
  }

  @Test
  void retainedV3ReadsAndRetriesOriginalBytesButConflictsWithSelectedFullV4() throws Exception {
    var evidence = closureSelectorEvidence();
    var retained = new PublishedReleaseBundle();
    retained.setId(11L);
    retained.setTenantId("tenant-1");
    retained.setVersionId(7L);
    retained.setVersionNumber(selectorVersion().versionNumber());
    retained.setCanonicalTenantId(sourceIdentity().getCanonicalTenantId());
    retained.setCanonicalVersionId(sourceIdentity().getCanonicalVersionId());
    retained.setAttestationSchemaVersion("v3");
    retained.setPublishWorkflowId("publish-workflow");
    retained.setManifestHash(emptyManifest().manifestHash());
    retained.setManifestSchemaVersion(emptyManifest().manifestSchemaVersion());
    retained.setArtifactDigestsJson("[]");
    retained.setRequiredManifestAssetKeysJson("[]");
    retained.setGenerationConfigRevision("genrev-1");
    retained.setCommandDefinitionsJson(
        new ObjectMapper().writeValueAsString(List.of(validCommandDefinition())));
    retained.setParticipantDigestsJson(
        new ObjectMapper()
            .writeValueAsString(PublishedWorldSelectorFixtures.participants(7L, evidence)));
    retained.setWorldPublishedStartLocationEvidenceJson(
        new String(evidence.canonicalBytes(), StandardCharsets.UTF_8));
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(retained));
    when(repository.requireSelectedCommandDefinitions(any(), any(), any(), any()))
        .thenReturn(List.of(validCommandDefinition()));
    var read = service.getPublishedReleaseBundle("tenant-1", 7L);
    assertEquals("v3", read.attestationSchemaVersion());
    assertThat(read.worldPublishedStartLocationEvidence().canonicalBytes())
        .containsExactly(evidence.canonicalBytes());
    var retry =
        service.createFullVersionBundle(
            selectorVersion(),
            "publish-workflow",
            emptyManifest(),
            "genrev-1",
            PublishedWorldSelectorFixtures.participants(7L, evidence),
            evidence);
    assertThat(retry).isEqualTo(read);
    assertThatThrownBy(
            () ->
                service.createFullVersionBundle(
                    selectorVersion(),
                    "publish-workflow",
                    emptyManifest(),
                    "genrev-1",
                    PublishedWorldSelectorFixtures.selectedFullParticipants(7L, evidence),
                    evidence))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("IDEMPOTENCY_CONFLICT");
    assertEquals("v3", retained.getAttestationSchemaVersion());
    org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
  }

  @Test
  void selectedFullV4RequiresSelectedSourceCaptureBeforeBundleWrite() throws Exception {
    var evidence = closureSelectorEvidence();
    when(repository.requireSelectedCommandDefinitions(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("SELECTED_SOURCE_CAPTURE_UNAVAILABLE"));

    assertThatThrownBy(
            () ->
                service.createFullVersionBundle(
                    selectorVersion(),
                    "publish-workflow",
                    emptyManifest(),
                    "genrev-1",
                    PublishedWorldSelectorFixtures.selectedFullParticipants(7L, evidence),
                    evidence))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SELECTED_SOURCE_CAPTURE_UNAVAILABLE");
    org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(revisionRepository);
  }

  @Test
  void newSelectedReleaseCannotBeCreatedFromRetainedWorldDigestSchema3() throws Exception {
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
        .hasMessageContaining("World digest schema 4");
    org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
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

  private WorldPublishedStartLocationEvidence closureSelectorEvidence() throws Exception {
    return PublishedWorldSelectorFixtures.closureEvidence(
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
        new PublishedReleaseBundleServiceImpl(
            repository, revisionRepository, versionRepository, new ObjectMapper());
    Version source = new Version();
    source.setId(7L);
    source.setTenantId("tenant-1");
    source.setCanonicalTenantId(UUID.fromString("67d7b75b-42d1-4ac6-9572-684c5e633cda"));
    source.setCanonicalVersionId(UUID.fromString("c472ebd1-56d8-49df-b8fa-85963dd940f8"));
    when(versionRepository.findByTenantIdAndId("tenant-1", 7L)).thenReturn(Optional.of(source));
  }

  @Test
  void createFullVersionBundlePersistsImmutableAttestation() {
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
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.empty());
    Revision commandDefinition = new Revision();
    commandDefinition.setData(validCommandDefinition());
    when(revisionRepository.findByTenantIdAndVersionIdAndRevisionKindOrderByIdAsc(
            "tenant-1", 7L, "COMMAND_DEFINITION"))
        .thenReturn(List.of(commandDefinition));
    when(repository.save(any(PublishedReleaseBundle.class)))
        .thenAnswer(
            invocation -> {
              PublishedReleaseBundle entity = invocation.getArgument(0);
              entity.setId(11L);
              entity.setPublishedReleaseBundleRef("owner-issued-reference-11");
              return entity;
            });

    var dto =
        service.createFullVersionBundle(
            version,
            "workflow-1",
            logoManifest(),
            "genrev-1",
            List.of(
                new PublishParticipantDigestDto(
                    "GAME_LOGIC",
                    "7",
                    null,
                    "version:7",
                    "digest-logic",
                    1,
                    "ability-schema-v1",
                    null,
                    null),
                new PublishParticipantDigestDto(
                    "GAME_DESIGN_CONTROL_PLANE", "7", "version:7", "digest-1", 1, null, null)));

    assertEquals(11L, dto.id());
    assertEquals("tenant-1", dto.tenantId());
    assertEquals(7L, dto.versionId());
    assertEquals(MANIFEST_HASH, dto.manifestHash());
    assertEquals(1, dto.manifestSchemaVersion());
    assertEquals(List.of(logoProof()), dto.artifactDigests());
    assertEquals("genrev-1", dto.generationConfigRevision());
    assertEquals(List.of("logo.png"), dto.requiredManifestAssetKeys());
    assertEquals(2, dto.participantDigests().size());
    assertEquals("GAME_LOGIC", dto.participantDigests().getFirst().participantKey());
    assertEquals("version:7", dto.participantDigests().getFirst().appliedCommitId());
    assertEquals("digest-logic", dto.participantDigests().getFirst().contentDigest());
    assertEquals(1, dto.participantDigests().getFirst().digestSchemaVersion());
    assertEquals("ability-schema-v1", dto.participantDigests().getFirst().abilitySchemaDigest());
    assertEquals(List.of(validCommandDefinition()), dto.commandDefinitions());
    assertEquals("v1", dto.attestationSchemaVersion());
    assertEquals("owner-issued-reference-11", dto.publishedReleaseBundleRef());
    assertEquals(sourceIdentity().getCanonicalTenantId(), dto.canonicalTenantId());
    assertEquals(sourceIdentity().getCanonicalVersionId(), dto.canonicalVersionId());
    org.mockito.Mockito.verify(repository)
        .save(
            org.mockito.ArgumentMatchers.argThat(
                saved ->
                    sourceIdentity().getCanonicalTenantId().equals(saved.getCanonicalTenantId())
                        && sourceIdentity()
                            .getCanonicalVersionId()
                            .equals(saved.getCanonicalVersionId())));
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
    version.setCanonicalTenantId(UUID.fromString("67d7b75b-42d1-4ac6-9572-684c5e633cda"));
    version.setCanonicalVersionId(UUID.fromString("c472ebd1-56d8-49df-b8fa-85963dd940f8"));
    return version;
  }

  @Test
  void createFullVersionBundleRejectsDuplicateAttestation() {
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
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L))
        .thenReturn(Optional.of(new PublishedReleaseBundle()));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.createFullVersionBundle(
                version, "workflow-1", emptyManifest(), "genrev-1", List.of()));
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
  void createFullVersionBundleRejectsDuplicateCommandDefinitionAlias() {
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
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.empty());
    Revision first = new Revision();
    first.setData(commandDefinition("salute", "hail"));
    Revision second = new Revision();
    second.setData(commandDefinition("greet", "HAIL"));
    when(revisionRepository.findByTenantIdAndVersionIdAndRevisionKindOrderByIdAsc(
            "tenant-1", 7L, "COMMAND_DEFINITION"))
        .thenReturn(List.of(first, second));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.createFullVersionBundle(
                version, "workflow-1", emptyManifest(), "genrev-1", List.of()));
  }

  @Test
  void createFullVersionBundleRejectsDuplicateCanonicalCommandIds() {
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.empty());
    Revision first = new Revision();
    first.setData(commandDefinition("salute", "hail"));
    Revision second = new Revision();
    second.setData(commandDefinition("SALUTE", "greet"));
    when(revisionRepository.findByTenantIdAndVersionIdAndRevisionKindOrderByIdAsc(
            "tenant-1", 7L, "COMMAND_DEFINITION"))
        .thenReturn(List.of(first, second));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.createFullVersionBundle(
                version(), "workflow-1", emptyManifest(), "genrev-1", List.of()));
  }

  @Test
  void createFullVersionBundleRejectsCanonicalIdAndAliasCollisions() {
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.empty());
    Revision first = new Revision();
    first.setData(commandDefinition("salute", "greet"));
    Revision second = new Revision();
    second.setData(commandDefinition("hail", "SALUTE"));
    when(revisionRepository.findByTenantIdAndVersionIdAndRevisionKindOrderByIdAsc(
            "tenant-1", 7L, "COMMAND_DEFINITION"))
        .thenReturn(List.of(first, second));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.createFullVersionBundle(
                version(), "workflow-1", emptyManifest(), "genrev-1", List.of()));
  }

  @Test
  void createFullVersionBundleRejectsMalformedCommandEffectDeclaration() {
    VersionDto version = version();
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.empty());
    Revision commandDefinition = new Revision();
    commandDefinition.setData(validCommandDefinition().replace("\"value\":1", "\"value\":\"one\""));
    when(revisionRepository.findByTenantIdAndVersionIdAndRevisionKindOrderByIdAsc(
            "tenant-1", 7L, "COMMAND_DEFINITION"))
        .thenReturn(List.of(commandDefinition));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.createFullVersionBundle(
                version, "workflow-1", emptyManifest(), "genrev-1", List.of()));
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
