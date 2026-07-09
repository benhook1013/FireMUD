package net.firedevops.firemud.gamedesign;

import net.firedevops.firemud.common.GlobalExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ConfigurationPropertiesScan
@Import(GlobalExceptionHandler.class)
public class GameDesignServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(GameDesignServiceApplication.class, args);
  }
}
