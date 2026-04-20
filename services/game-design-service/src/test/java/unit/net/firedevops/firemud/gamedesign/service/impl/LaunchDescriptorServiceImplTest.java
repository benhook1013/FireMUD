package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.entity.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.TemplateReferencePhase;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameTemplateLaunchConfigView;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.LaunchDescriptorRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import tools.jackson.databind.ObjectMapper;

class LaunchDescriptorServiceImplTest {
  @Mock private GameTemplateRepository gameTemplateRepository;
  @Mock private LaunchDescriptorRepository launchDescriptorRepository;
  @Mock private VersionRepository versionRepository;
  @Mock private PublishedReleaseBundleService publishedReleaseBundleService;

  private LaunchDescriptorServiceImpl service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service =
        new LaunchDescriptorServiceImpl(
            gameTemplateRepository,
            launchDescriptorRepository,
            versionRepository,
            publishedReleaseBundleService,
            new ObjectMapper());
  }

  @Test
  void resolveLaunchDescriptorPersistsDeterministicDescriptor() {
    GameTemplateLaunchConfigView template =
        org.mockito.Mockito.mock(GameTemplateLaunchConfigView.class);
    when(template.getId()).thenReturn(9L);
    when(template.getTenantId()).thenReturn("tenant-1");
    when(template.getDefaultVersionId()).thenReturn(7L);
    when(template.getDefaultScriptPatchVersion()).thenReturn("patch-1");
    when(template.getDefaultRuntimeFlagsJson()).thenReturn("{}");
    when(template.getTemplateReferencePhase()).thenReturn(TemplateReferencePhase.ENFORCED);
    when(gameTemplateRepository.findLaunchConfigByTenantIdAndId("tenant-1", 9L))
        .thenReturn(Optional.of(template));
    when(launchDescriptorRepository.findByTenantIdAndGameTemplateIdAndControlPlaneRequestId(
            "tenant-1", 9L, "cp-1"))
        .thenReturn(Optional.empty());
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("tenant-1");
    version.setVersionNumber(8);
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    version.setVersionStateEpoch(17L);
    version.setUpdatedAt(LocalDateTime.now());
    when(versionRepository.findById(7L)).thenReturn(Optional.of(version));
    when(publishedReleaseBundleService.getPublishedReleaseBundle("tenant-1", 7L))
        .thenReturn(
            new PublishedReleaseBundleDto(
                11L,
                "tenant-1",
                7L,
                8,
                "v1",
                "workflow-1",
                "hash-1",
                List.of("manifest.json"),
                List.of(),
                "genrev-1",
                false,
                null,
                LocalDateTime.now()));
    when(launchDescriptorRepository.save(any(LaunchDescriptor.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var descriptor =
        service.resolveLaunchDescriptor("tenant-1", 9L, "cp-1", null, null, null, null);

    assertEquals("tenant-1", descriptor.tenantId());
    assertEquals(7L, descriptor.versionId());
    assertEquals("genrev-1", descriptor.generationConfigRevision());
    assertEquals(17L, descriptor.versionStateEpoch());
    assertEquals(11L, descriptor.releaseBundleId());
  }

  @Test
  void resolveLaunchDescriptorRejectsConflictingRequestReuse() {
    LaunchDescriptor existing = new LaunchDescriptor();
    existing.setRequestHash("other-hash");
    GameTemplateLaunchConfigView template =
        org.mockito.Mockito.mock(GameTemplateLaunchConfigView.class);
    when(gameTemplateRepository.findLaunchConfigByTenantIdAndId("tenant-1", 9L))
        .thenReturn(Optional.of(template));
    when(launchDescriptorRepository.findByTenantIdAndGameTemplateIdAndControlPlaneRequestId(
            "tenant-1", 9L, "cp-1"))
        .thenReturn(Optional.of(existing));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.resolveLaunchDescriptor("tenant-1", 9L, "cp-1", null, null, null, null));
  }

  @Test
  void resolveLaunchDescriptorFailsClosedOnUnsupportedAttestationSchema() {
    GameTemplateLaunchConfigView template =
        org.mockito.Mockito.mock(GameTemplateLaunchConfigView.class);
    when(template.getId()).thenReturn(9L);
    when(template.getTenantId()).thenReturn("tenant-1");
    when(template.getDefaultVersionId()).thenReturn(7L);
    when(template.getDefaultScriptPatchVersion()).thenReturn("patch-1");
    when(template.getDefaultRuntimeFlagsJson()).thenReturn("{}");
    when(template.getTemplateReferencePhase()).thenReturn(TemplateReferencePhase.ENFORCED);
    when(gameTemplateRepository.findLaunchConfigByTenantIdAndId("tenant-1", 9L))
        .thenReturn(Optional.of(template));
    when(launchDescriptorRepository.findByTenantIdAndGameTemplateIdAndControlPlaneRequestId(
            "tenant-1", 9L, "cp-1"))
        .thenReturn(Optional.empty());
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("tenant-1");
    version.setVersionNumber(8);
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    version.setVersionStateEpoch(17L);
    version.setUpdatedAt(LocalDateTime.now());
    when(versionRepository.findById(7L)).thenReturn(Optional.of(version));
    when(publishedReleaseBundleService.getPublishedReleaseBundle("tenant-1", 7L))
        .thenReturn(
            new PublishedReleaseBundleDto(
                11L,
                "tenant-1",
                7L,
                8,
                "v2",
                "workflow-1",
                "hash-1",
                List.of("manifest.json"),
                List.of(),
                "genrev-1",
                false,
                null,
                LocalDateTime.now()));

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.resolveLaunchDescriptor("tenant-1", 9L, "cp-1", null, null, null, null));

    assertEquals(
        true,
        thrown
            .getMessage()
            .startsWith(
                "SCHEMA_VERSION_UNSUPPORTED: unsupported published release bundle attestation schema"));
  }
}
