package net.firedevops.firemud.gamesession.data;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.gamesession.entity.FeatureFlag;
import net.firedevops.firemud.gamesession.entity.GameManifest;
import net.firedevops.firemud.gamesession.repository.FeatureFlagRepository;
import net.firedevops.firemud.gamesession.repository.GameManifestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

@ExtendWith(MockitoExtension.class)
class TestDataSeederTest {
  @Mock GameManifestRepository gameManifestRepository;
  @Mock FeatureFlagRepository featureFlagRepository;

  private TestDataSeeder seeder;

  @BeforeEach
  void setup() {
    seeder = new TestDataSeeder(gameManifestRepository, featureFlagRepository);
  }

  @Test
  void runSeedsAndReassertsCanonicalRuntimeData() throws Exception {
    when(gameManifestRepository.findAll()).thenReturn(java.util.List.of());
    when(featureFlagRepository.findByTenantIdAndName(1L, "double_xp"))
        .thenReturn(java.util.Optional.empty());
    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(gameManifestRepository).save(any(GameManifest.class));
    verify(featureFlagRepository).save(any(FeatureFlag.class));
  }
}
