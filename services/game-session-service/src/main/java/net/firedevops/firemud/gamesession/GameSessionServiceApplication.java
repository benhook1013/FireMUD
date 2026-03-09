package net.firedevops.firemud.gamesession;

import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import net.firedevops.firemud.common.config.DatabaseAutoConfiguration;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import net.firedevops.firemud.gamesession.config.GrpcClientProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
  GrpcClientProperties.class,
  GameSessionProperties.class,
  GameLogicProperties.class
})
@Import({DatabaseAutoConfiguration.class, CommonAutoConfiguration.class})
public class GameSessionServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(GameSessionServiceApplication.class, args);
  }
}
