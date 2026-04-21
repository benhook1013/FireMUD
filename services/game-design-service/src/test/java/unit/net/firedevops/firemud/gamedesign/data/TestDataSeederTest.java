package net.firedevops.firemud.gamedesign.data;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
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

  private TestDataSeeder seeder;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    seeder =
        new TestDataSeeder(
            gameRepository, templateRepository, revisionRepository, versionRepository);
  }

  @Test
  void runSeedsDataWhenRepositoriesEmpty() throws Exception {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("1");
    when(gameRepository.findAll()).thenReturn(java.util.List.of());
    when(gameRepository.save(any(Game.class))).thenReturn(game);
    when(templateRepository.count()).thenReturn(0L);
    when(revisionRepository.count()).thenReturn(0L);
    when(versionRepository.count()).thenReturn(0L);
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("1");
    when(versionRepository.findAll()).thenReturn(List.of(version));
    when(versionRepository.save(any(Version.class))).thenReturn(version);

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(gameRepository).save(any());
    verify(templateRepository).save(any());
    verify(revisionRepository).save(any());
    verify(versionRepository).save(any());
  }
}
