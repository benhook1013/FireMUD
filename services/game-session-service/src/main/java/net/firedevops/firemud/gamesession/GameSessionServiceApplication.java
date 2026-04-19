package net.firedevops.firemud.gamesession;

import net.firedevops.firemud.common.saga.persistence.EnableSagaEntityScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableSagaEntityScan
@EnableScheduling
@ConfigurationPropertiesScan
public class GameSessionServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(GameSessionServiceApplication.class, args);
  }
}
