package net.firedevops.firemud.loggingadmin;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import net.firedevops.firemud.common.config.DatabaseAutoConfiguration;
import net.firedevops.firemud.loggingadmin.config.AuthConfig;
import net.firedevops.firemud.loggingadmin.config.GrpcClientProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableConfigurationProperties(GrpcClientProperties.class)
@OpenAPIDefinition(info = @Info(title = "Logging Admin Service", version = "v1"))
@Import({DatabaseAutoConfiguration.class, CommonAutoConfiguration.class, AuthConfig.class})
public class LoggingAdminServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(LoggingAdminServiceApplication.class, args);
  }
}
