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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Seeds deterministic smoke/runtime records when local compose explicitly enables them. */
@Component
@ConditionalOnProperty(
    prefix = "firemud.smoke.seed-demo-runtime",
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

    GameInstance instance =
        gameInstanceRepository
            .findFirstByTenantIdAndOwnerAccountIdAndStatus(1L, 1L, "RUNNING")
            .orElseGet(GameInstance::new);
    instance.setTenantId(1L);
    instance.setRuntimeVersion("v1.0.0");
    instance.setOwnerAccountId(1L);
    instance.setStatus("RUNNING");
    gameInstanceRepository.save(instance);
  }
}
