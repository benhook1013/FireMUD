package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.Revision;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import tools.jackson.databind.ObjectMapper;

class PublishedReleaseBundleServiceImplTest {
  @Mock private PublishedReleaseBundleRepository repository;
  @Mock private RevisionRepository revisionRepository;

  private PublishedReleaseBundleServiceImpl service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service =
        new PublishedReleaseBundleServiceImpl(repository, revisionRepository, new ObjectMapper());
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
              return entity;
            });

    var dto =
        service.createFullVersionBundle(
            version,
            "workflow-1",
            new ExportedAssetManifest("abc123", List.of("logo.png", "manifest.json")),
            "genrev-1",
            List.of(
                new PublishParticipantDigestDto(
                    "GAME_DESIGN_CONTROL_PLANE", "7", "version:7", "digest-1", 1, null, null)));

    assertEquals(11L, dto.id());
    assertEquals("tenant-1", dto.tenantId());
    assertEquals(7L, dto.versionId());
    assertEquals("abc123", dto.manifestHash());
    assertEquals("genrev-1", dto.generationConfigRevision());
    assertEquals(List.of("logo.png", "manifest.json"), dto.requiredManifestAssetKeys());
    assertEquals(1, dto.participantDigests().size());
    assertEquals(List.of(validCommandDefinition()), dto.commandDefinitions());
    assertEquals("v1", dto.attestationSchemaVersion());
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
                version,
                "workflow-1",
                new ExportedAssetManifest("abc123", List.of("manifest.json")),
                "genrev-1",
                List.of()));
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
                version,
                "workflow-1",
                new ExportedAssetManifest("abc123", List.of("manifest.json")),
                "genrev-1",
                List.of()));
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
                version(),
                "workflow-1",
                new ExportedAssetManifest("abc123", List.of("manifest.json")),
                "genrev-1",
                List.of()));
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
                version,
                "workflow-1",
                new ExportedAssetManifest("abc123", List.of("manifest.json")),
                "genrev-1",
                List.of()));
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

  private String validCommandDefinition() {
    return commandDefinition("block", "block");
  }

  private String commandDefinition(String commandId, String alias) {
    String template =
        """
        {"schemaVersion":1,"commandId":"%s","semanticOwner":"GAME_LOGIC","executionDiscipline":"DURABLE_GAMEPLAY","stageRequirement":"GAMEPLAY","promptPolicy":"WHEN_GAMEPLAY","actionCategory":"GAMEPLAY","aliases":["%s"],"actionTags":["COMBAT"],"effects":[{"effectKind":"APPLY_ACTION_STATE","schemaVersion":1,"targeting":"SELF","replayPolicy":"EFFECT_IDEMPOTENT","payload":{"conditionKey":"blocking","durationSeconds":5,"effectPayload":{"modifiers":[{"operation":"ADD","target_key":"block_mitigation","value":1}]}}}]}%n
        """
            .stripTrailing();
    return String.format(template, commandId, alias);
  }
}
