package net.firedevops.firemud.gamesession.data;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.gamesession.entity.FeatureFlag;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.GameManifest;
import net.firedevops.firemud.gamesession.repository.FeatureFlagRepository;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameManifestRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Seeds minimal records for local development when running with the {@code dev} Spring profile. */
@Component
@Profile("dev")
@ConditionalOnProperty(
    prefix = "firemud.database",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
@RequiredArgsConstructor
public class TestDataSeeder implements ApplicationRunner {
  private final GameManifestRepository gameManifestRepository;
  private final FeatureFlagRepository featureFlagRepository;
  private final GameInstanceRepository gameInstanceRepository;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (gameManifestRepository.count() == 0) {
      GameManifest manifest = new GameManifest();
      manifest.setVersionId("v1.0.0");
      manifest.setDescription("Demo version");
      gameManifestRepository.save(manifest);
    }

    if (featureFlagRepository.count() == 0) {
      FeatureFlag flag = new FeatureFlag();
      flag.setTenantId(1L);
      flag.setName("double_xp");
      flag.setEnabled(true);
      featureFlagRepository.save(flag);
    }

    if (gameInstanceRepository.count() == 0) {
      GameInstance instance = new GameInstance();
      instance.setTenantId(1L);
      instance.setRuntimeVersion("v1.0.0");
      instance.setOwnerAccountId(1L);
      instance.setStatus("RUNNING");
      gameInstanceRepository.save(instance);
    }
  }
}
