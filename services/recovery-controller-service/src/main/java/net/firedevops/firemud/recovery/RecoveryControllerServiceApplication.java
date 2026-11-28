package net.firedevops.firemud.recovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration;

@SpringBootApplication(
    exclude = DataSourceInitializationAutoConfiguration.class,
    excludeName = "net.firedevops.firemud.common.config.DatabaseAutoConfiguration")
public class RecoveryControllerServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(RecoveryControllerServiceApplication.class, args);
  }
}
