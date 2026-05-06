package net.firedevops.firemud.gamedesign.data;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.GameTemplate;
import net.firedevops.firemud.gamedesign.entity.Revision;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(
    prefix = "firemud.smoke.seed-demo-runtime",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
@RequiredArgsConstructor
public class TestDataSeeder implements ApplicationRunner {
  private final GameRepository gameRepository;
  private final GameTemplateRepository templateRepository;
  private final RevisionRepository revisionRepository;
  private final VersionRepository versionRepository;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    Game game =
        gameRepository.findAll().stream()
            .findFirst()
            .orElseGet(
                () -> {
                  Game g = new Game();
                  g.setTenantId("1");
                  g.setName("Demo Game");
                  g.setDescription("Seed game");
                  return gameRepository.save(g);
                });

    if (templateRepository.count() == 0) {
      GameTemplate template = new GameTemplate();
      template.setTenantId(String.valueOf(game.getTenantId()));
      template.setName("Default Template");
      template.setDescription("Demo template");
      template.setConfig("{}");
      templateRepository.save(template);
    }

    if (versionRepository.count() == 0) {
      Version v = new Version();
      v.setTenantId(game.getTenantId());
      v.setVersionNumber(1);
      v.setNotes("Initial version");
      versionRepository.save(v);
    }

    if (revisionRepository.count() == 0) {
      Version version = versionRepository.findAll().stream().findFirst().orElseThrow();
      Revision rev = new Revision();
      rev.setTenantId(game.getTenantId());
      rev.setVersionId(version.getId());
      rev.setAuthorAccountId(1L);
      rev.setData("{}");
      rev.setRevisionKind("GENERIC");
      revisionRepository.save(rev);
    }
  }
}
