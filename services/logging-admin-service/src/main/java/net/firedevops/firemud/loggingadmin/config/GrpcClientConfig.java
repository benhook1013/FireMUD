package net.firedevops.firemud.loggingadmin.config;

import javax.net.ssl.SSLException;
import net.firedevops.firemud.loggingadmin.client.AccountClient;
import net.firedevops.firemud.loggingadmin.client.GameSessionClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bean configuration for outbound gRPC clients. */
@Configuration
public class GrpcClientConfig {

  @Bean
  public AccountClient accountClient(
      net.firedevops.firemud.common.config.ServiceEndpointsProperties endpoints,
      GrpcClientProperties properties)
      throws SSLException {
    return new AccountClient(endpoints, properties);
  }

  @Bean
  public GameSessionClient gameSessionClient(
      net.firedevops.firemud.common.config.ServiceEndpointsProperties endpoints,
      GrpcClientProperties properties)
      throws SSLException {
    return new GameSessionClient(endpoints, properties);
  }
}
