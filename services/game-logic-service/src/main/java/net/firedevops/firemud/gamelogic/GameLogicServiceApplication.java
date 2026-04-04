package net.firedevops.firemud.gamelogic;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import net.firedevops.firemud.gamelogic.config.CommunicationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(CommunicationProperties.class)
@OpenAPIDefinition(info = @Info(title = "Game Logic Service", version = "v1"))
public class GameLogicServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(GameLogicServiceApplication.class, args);
  }
}
