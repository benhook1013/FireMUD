package net.firedevops.firemud.gamelogic;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import net.firedevops.firemud.common.config.DatabaseAutoConfiguration;
import net.firedevops.firemud.gamelogic.config.GrpcClientProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@OpenAPIDefinition(info = @Info(title = "Game Logic Service", version = "v1"))
@EnableConfigurationProperties(GrpcClientProperties.class)
@Import({DatabaseAutoConfiguration.class, CommonAutoConfiguration.class})
public class GameLogicServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(GameLogicServiceApplication.class, args);
  }
}
