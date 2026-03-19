package net.firedevops.firemud.gamedesign;

import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import net.firedevops.firemud.common.config.DatabaseAutoConfiguration;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.saga.persistence.SagaInstance;
import net.firedevops.firemud.common.saga.persistence.SagaStep;
import net.firedevops.firemud.gamedesign.config.AssetStoreProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EntityScan(
    basePackageClasses = {GameDesignServiceApplication.class, SagaInstance.class, SagaStep.class})
@EnableConfigurationProperties({CommonGrpcClientProperties.class, AssetStoreProperties.class})
@Import({DatabaseAutoConfiguration.class, CommonAutoConfiguration.class})
public class GameDesignServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(GameDesignServiceApplication.class, args);
  }
}
