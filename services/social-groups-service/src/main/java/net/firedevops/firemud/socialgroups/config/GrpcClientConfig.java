package net.firedevops.firemud.socialgroups.config;

import javax.net.ssl.SSLException;
import net.firedevops.firemud.socialgroups.client.LoggingAdminClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bean configuration for outbound gRPC clients. */
@Configuration
public class GrpcClientConfig {

  @Bean
  public LoggingAdminClient loggingAdminClient(
      net.firedevops.firemud.common.config.ServiceEndpointsProperties endpoints,
      GrpcClientProperties properties)
      throws SSLException {
    return new LoggingAdminClient(endpoints, properties);
  }
}
