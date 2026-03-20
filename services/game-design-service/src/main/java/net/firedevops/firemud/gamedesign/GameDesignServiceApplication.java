package net.firedevops.firemud.gamedesign;

import net.firedevops.firemud.common.saga.persistence.EnableSagaEntityScan;
import net.firedevops.firemud.gamedesign.config.AssetStoreProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableSagaEntityScan(basePackageClasses = GameDesignServiceApplication.class)
@EnableConfigurationProperties(AssetStoreProperties.class)
public class GameDesignServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(GameDesignServiceApplication.class, args);
  }
}
