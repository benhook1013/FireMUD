package net.firedevops.firemud.gamedesign.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
  void retainedVersionWithoutBundlePropagatesCanonicalRefusalBeforeArtifactWrites() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("1");
    game.setCanonicalTenantId(java.util.UUID.randomUUID());
    when(gameRepository.findByTenantId("1")).thenReturn(game);
    when(gameRepository.save(any(Game.class))).thenReturn(game);
    Version retainedVersion = new Version();
    retainedVersion.setId(7L);
    retainedVersion.setTenantId("1");
    retainedVersion.setVersionNumber(1);
    when(versionRepository.findByTenantIdAndVersionNumber("1", 1))
        .thenReturn(java.util.Optional.of(retainedVersion));
    when(versionRepository.save(any(Version.class))).thenReturn(retainedVersion);
    when(templateRepository.findByTenantIdAndName("1", "Default Template"))
        .thenReturn(java.util.Optional.empty());
    when(templateRepository.save(any(GameTemplate.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(revisionRepository.findByTenantIdAndVersionIdAndRevisionKind("1", 7L, "GENERIC"))
        .thenReturn(java.util.Optional.empty());
    when(revisionRepository.save(any(Revision.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(publishedReleaseBundleRepository.findByTenantIdAndVersionId("1", 7L))
        .thenReturn(java.util.Optional.empty());
    when(publishedReleaseBundleRepository.save(any(PublishedReleaseBundle.class)))
        .thenThrow(
            new IllegalStateException(
                "No exact canonical Game Design source association exists for the requested Version"));

    assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments(new String[] {})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No exact canonical Game Design source association");
    assertThat(retainedVersion.getCanonicalTenantId()).isNull();
    assertThat(retainedVersion.getCanonicalVersionId()).isNull();
    verifyNoInteractions(versionAssetArtifactRepository);
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

    PublishedReleaseBundle stored = bundleCaptor.getValue();
    stored.setId(11L);
    stored.setPublishedReleaseBundleRef("persisted-opaque-reference");
    when(publishedReleaseBundleRepository.findByTenantIdAndVersionId("1", 7L))
        .thenReturn(java.util.Optional.of(stored));
    seeder.run(new DefaultApplicationArguments(new String[] {}));
    verify(publishedReleaseBundleRepository, times(1)).save(any(PublishedReleaseBundle.class));
    assertThat(stored.getPublishedReleaseBundleRef()).isEqualTo("persisted-opaque-reference");

    ArgumentCaptor<VersionAssetArtifact> artifactCaptor =
        ArgumentCaptor.forClass(VersionAssetArtifact.class);
    verify(versionAssetArtifactRepository, times(2)).save(artifactCaptor.capture());
    assertThat(artifactCaptor.getValue().getTenantId()).isEqualTo("1");
    assertThat(artifactCaptor.getValue().getVersionId()).isEqualTo(7L);
    assertThat(artifactCaptor.getValue().getLastWorkflowId()).isEqualTo("demo-seed-publish");
    assertThat(artifactCaptor.getValue().getManifestHash()).isEqualTo("demo-manifest-hash");
  }
}
