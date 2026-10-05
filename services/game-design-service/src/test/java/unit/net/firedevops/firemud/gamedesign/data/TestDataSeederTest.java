package net.firedevops.firemud.gamedesign.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.GameTemplate;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.Revision;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.DefaultApplicationArguments;

class TestDataSeederTest {
  @Mock GameRepository gameRepository;
  @Mock GameTemplateRepository templateRepository;
  @Mock RevisionRepository revisionRepository;
  @Mock VersionRepository versionRepository;
  @Mock PublishedReleaseBundleRepository publishedReleaseBundleRepository;
  @Mock VersionAssetArtifactRepository versionAssetArtifactRepository;

  private TestDataSeeder seeder;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    seeder =
        new TestDataSeeder(
            gameRepository,
            templateRepository,
            revisionRepository,
            versionRepository,
            publishedReleaseBundleRepository,
            versionAssetArtifactRepository);
  }

  @Test
  void runSeedsAndReassertsCanonicalPublishedRuntimeData() throws Exception {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("1");
    when(gameRepository.findByTenantId("1")).thenReturn(null);
    when(gameRepository.save(any(Game.class))).thenReturn(game);
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("1");
    version.setVersionNumber(1);
    when(versionRepository.findByTenantIdAndVersionNumber("1", 1))
        .thenReturn(java.util.Optional.empty());
    when(versionRepository.save(any(Version.class))).thenReturn(version);
    when(templateRepository.findByTenantIdAndName("1", "Default Template"))
        .thenReturn(java.util.Optional.empty());
    GameTemplate template = new GameTemplate();
    template.setId(9L);
    template.setTenantId("1");
    when(templateRepository.save(any(GameTemplate.class))).thenReturn(template);
    when(revisionRepository.findByTenantIdAndVersionIdAndRevisionKind("1", 7L, "GENERIC"))
        .thenReturn(java.util.Optional.empty());
    when(revisionRepository.save(any(Revision.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(publishedReleaseBundleRepository.findByTenantIdAndVersionId("1", 7L))
        .thenReturn(java.util.Optional.empty());
    when(publishedReleaseBundleRepository.save(any(PublishedReleaseBundle.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(versionAssetArtifactRepository.findByTenantIdAndVersionId("1", 7L))
        .thenReturn(java.util.Optional.empty());
    when(versionAssetArtifactRepository.save(any(VersionAssetArtifact.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(gameRepository).save(any());
    verify(templateRepository).save(any());
    verify(revisionRepository).save(any());
    verify(versionRepository).save(any());
    ArgumentCaptor<PublishedReleaseBundle> bundleCaptor =
        ArgumentCaptor.forClass(PublishedReleaseBundle.class);
    verify(publishedReleaseBundleRepository).save(bundleCaptor.capture());
    assertThat(bundleCaptor.getValue().getTenantId()).isEqualTo("1");
    assertThat(bundleCaptor.getValue().getVersionId()).isEqualTo(7L);
    assertThat(bundleCaptor.getValue().getManifestHash()).isEqualTo("demo-manifest-hash");
    assertThat(bundleCaptor.getValue().getPublishWorkflowId()).isEqualTo("demo-seed-publish");

    ArgumentCaptor<VersionAssetArtifact> artifactCaptor =
        ArgumentCaptor.forClass(VersionAssetArtifact.class);
    verify(versionAssetArtifactRepository).save(artifactCaptor.capture());
    assertThat(artifactCaptor.getValue().getTenantId()).isEqualTo("1");
    assertThat(artifactCaptor.getValue().getVersionId()).isEqualTo(7L);
    assertThat(artifactCaptor.getValue().getLastWorkflowId()).isEqualTo("demo-seed-publish");
    assertThat(artifactCaptor.getValue().getManifestHash()).isEqualTo("demo-manifest-hash");
  }

  @Test
  void runReusesExistingPublishedReleaseBundleWithoutSavingOrChangingItsMetadata() {
    when(gameRepository.findByTenantId("1")).thenReturn(null);
    when(versionRepository.findByTenantIdAndVersionNumber("1", 1))
        .thenReturn(java.util.Optional.empty());
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("1");
    version.setVersionNumber(1);
    when(versionRepository.save(any(Version.class))).thenReturn(version);
    when(templateRepository.findByTenantIdAndName("1", "Default Template"))
        .thenReturn(java.util.Optional.empty());
    when(revisionRepository.findByTenantIdAndVersionIdAndRevisionKind("1", 7L, "GENERIC"))
        .thenReturn(java.util.Optional.empty());

    LocalDateTime publishedAt = LocalDateTime.of(2025, 3, 4, 5, 6, 7);
    PublishedReleaseBundle existingBundle = new PublishedReleaseBundle();
    existingBundle.setId(19L);
    existingBundle.setTenantId("1");
    existingBundle.setVersionId(7L);
    existingBundle.setCanonicalTenantId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    existingBundle.setCanonicalVersionId(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    existingBundle.setVersionNumber(42);
    existingBundle.setAttestationSchemaVersion("stored-schema");
    existingBundle.setPublishWorkflowId("stored-workflow");
    existingBundle.setManifestHash("stored-manifest");
    existingBundle.setGenerationConfigRevision("stored-generation-revision");
    existingBundle.setRequiredManifestAssetKeysJson("[\"stored-asset\"]");
    existingBundle.setParticipantDigestsJson("[\"stored-participant\"]");
    existingBundle.setCommandDefinitionsJson("[\"stored-command\"]");
    existingBundle.setScriptOnly(true);
    existingBundle.setScriptPatchVersion("stored-patch");
    existingBundle.setPublishedAt(publishedAt);
    when(publishedReleaseBundleRepository.findByTenantIdAndVersionId("1", 7L))
        .thenReturn(java.util.Optional.of(existingBundle));
    when(versionAssetArtifactRepository.findByTenantIdAndVersionId("1", 7L))
        .thenReturn(java.util.Optional.empty());
    when(versionAssetArtifactRepository.save(any(VersionAssetArtifact.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(publishedReleaseBundleRepository, never()).save(any(PublishedReleaseBundle.class));
    assertThat(existingBundle.getId()).isEqualTo(19L);
    assertThat(existingBundle.getCanonicalTenantId())
        .isEqualTo(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    assertThat(existingBundle.getCanonicalVersionId())
        .isEqualTo(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    assertThat(existingBundle.getVersionNumber()).isEqualTo(42);
    assertThat(existingBundle.getAttestationSchemaVersion()).isEqualTo("stored-schema");
    assertThat(existingBundle.getPublishWorkflowId()).isEqualTo("stored-workflow");
    assertThat(existingBundle.getManifestHash()).isEqualTo("stored-manifest");
    assertThat(existingBundle.getGenerationConfigRevision())
        .isEqualTo("stored-generation-revision");
    assertThat(existingBundle.getRequiredManifestAssetKeysJson()).isEqualTo("[\"stored-asset\"]");
    assertThat(existingBundle.getParticipantDigestsJson()).isEqualTo("[\"stored-participant\"]");
    assertThat(existingBundle.getCommandDefinitionsJson()).isEqualTo("[\"stored-command\"]");
    assertThat(existingBundle.isScriptOnly()).isTrue();
    assertThat(existingBundle.getScriptPatchVersion()).isEqualTo("stored-patch");
    assertThat(existingBundle.getPublishedAt()).isEqualTo(publishedAt);

    ArgumentCaptor<VersionAssetArtifact> artifactCaptor =
        ArgumentCaptor.forClass(VersionAssetArtifact.class);
    verify(versionAssetArtifactRepository).save(artifactCaptor.capture());
    assertThat(artifactCaptor.getValue().getManifestHash()).isEqualTo("stored-manifest");
  }
}
