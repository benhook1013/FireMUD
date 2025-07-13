package net.firedevops.firemud.config;

import javax.net.ssl.SSLException;
import net.firedevops.firemud.client.GameLogicClient;
import net.firedevops.firemud.client.WorldManagementClient;
import net.firedevops.firemud.client.EntityManagementClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bean configuration for outbound gRPC clients. */
@Configuration
public class GrpcClientConfig {

  @Bean
  public GameLogicClient gameLogicClient(
      net.firedevops.firemud.common.config.ServiceEndpointsProperties endpoints,
      GrpcClientProperties properties)
      throws SSLException {
    return new GameLogicClient(endpoints, properties);
  }

  @Bean
  public WorldManagementClient worldManagementClient(
      net.firedevops.firemud.common.config.ServiceEndpointsProperties endpoints,
      GrpcClientProperties properties)
      throws SSLException {
    return new WorldManagementClient(endpoints, properties);
  }

  @Bean
  public EntityManagementClient entityManagementClient(
      net.firedevops.firemud.common.config.ServiceEndpointsProperties endpoints,
      GrpcClientProperties properties)
      throws SSLException {
    return new EntityManagementClient(endpoints, properties);
  }
}
