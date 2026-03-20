package net.firedevops.firemud.gamesession;

import net.firedevops.firemud.common.saga.persistence.EnableSagaEntityScan;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableSagaEntityScan(basePackageClasses = GameSessionServiceApplication.class)
@EnableScheduling
@EnableConfigurationProperties({GameSessionProperties.class, GameLogicProperties.class})
public class GameSessionServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(GameSessionServiceApplication.class, args);
  }
}
