package net.firedevops.firemud.gamesession.data;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.gamesession.entity.FeatureFlag;
import net.firedevops.firemud.gamesession.entity.GameManifest;
import net.firedevops.firemud.gamesession.repository.FeatureFlagRepository;
import net.firedevops.firemud.gamesession.repository.GameManifestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds demo metadata only; runtime instance ownership remains unavailable without Account UUID
 * authority.
 */
@Component
@ConditionalOnProperty(
    prefix = "firemud.smoke.seed-demo-runtime",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
@RequiredArgsConstructor
public class TestDataSeeder implements ApplicationRunner {
  private static final Logger LOGGER = LoggerFactory.getLogger(TestDataSeeder.class);

  private final GameManifestRepository gameManifestRepository;
  private final FeatureFlagRepository featureFlagRepository;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    GameManifest manifest =
        gameManifestRepository.findAll().stream()
            .filter(candidate -> "v1.0.0".equals(candidate.getVersionId()))
            .findFirst()
            .orElseGet(GameManifest::new);
    manifest.setVersionId("v1.0.0");
    manifest.setDescription("Demo version");
    gameManifestRepository.save(manifest);

    FeatureFlag flag =
        featureFlagRepository.findByTenantIdAndName(1L, "double_xp").orElseGet(FeatureFlag::new);
    flag.setTenantId(1L);
    flag.setName("double_xp");
    flag.setEnabled(true);
    featureFlagRepository.save(flag);

    LOGGER.warn(
        "Skipping demo game instance seed: TestDataSeeder has no authoritative Account UUID"
            + " source");
  }
}
