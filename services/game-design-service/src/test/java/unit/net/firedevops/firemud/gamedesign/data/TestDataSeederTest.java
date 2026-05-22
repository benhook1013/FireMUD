package net.firedevops.firemud.gamedesign.data;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
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
    when(versionRepository.findTopByTenantIdOrderByVersionNumberDesc("1"))
        .thenReturn(java.util.Optional.empty());
    when(versionRepository.save(any(Version.class))).thenReturn(version);
    when(templateRepository.findAll()).thenReturn(List.of());
    GameTemplate template = new GameTemplate();
    template.setId(9L);
    template.setTenantId("1");
    when(templateRepository.save(any(GameTemplate.class))).thenReturn(template);
    when(revisionRepository.findAll()).thenReturn(List.of());
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
    verify(publishedReleaseBundleRepository).save(any());
    verify(versionAssetArtifactRepository).save(any());
  }
}
