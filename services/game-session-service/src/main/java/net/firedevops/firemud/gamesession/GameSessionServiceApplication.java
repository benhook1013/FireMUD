package net.firedevops.firemud.gamesession;

import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.common.saga.persistence.EnableSagaEntityScan;
import net.firedevops.firemud.gamesession.config.FirstPartyConnectContextProperties;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import net.firedevops.firemud.gamesession.config.MovementProperties;
import net.firedevops.firemud.gamesession.config.PresenceProperties;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.config.WorldTopologyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableSagaEntityScan
@EnableScheduling
@EnableConfigurationProperties({
  GameSessionProperties.class,
  GameplayCatalogProperties.class,
  GameLogicProperties.class,
  FirstPartyConnectContextProperties.class,
  PresentationProperties.class,
  PresenceProperties.class,
  MovementProperties.class,
  WorldTopologyProperties.class
})
public class GameSessionServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(GameSessionServiceApplication.class, args);
  }
}
