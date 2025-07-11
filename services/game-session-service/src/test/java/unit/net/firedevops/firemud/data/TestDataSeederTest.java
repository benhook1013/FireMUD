package net.firedevops.firemud.data;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.repository.FeatureFlagRepository;
import net.firedevops.firemud.repository.GameInstanceRepository;
import net.firedevops.firemud.repository.GameManifestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.DefaultApplicationArguments;

class TestDataSeederTest {
  @Mock GameManifestRepository gameManifestRepository;
  @Mock FeatureFlagRepository featureFlagRepository;
  @Mock GameInstanceRepository gameInstanceRepository;

  private TestDataSeeder seeder;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    seeder =
        new TestDataSeeder(gameManifestRepository, featureFlagRepository, gameInstanceRepository);
  }

  @Test
  void runSeedsDataWhenRepositoriesEmpty() throws Exception {
    when(gameManifestRepository.count()).thenReturn(0L);
    when(featureFlagRepository.count()).thenReturn(0L);
    when(gameInstanceRepository.count()).thenReturn(0L);

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(gameManifestRepository).save(any());
    verify(featureFlagRepository).save(any());
    verify(gameInstanceRepository).save(any());
  }
}
