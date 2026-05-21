package net.firedevops.firemud.gamedesign;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GameDesignServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(GameDesignServiceApplication.class, args);
  }
}
