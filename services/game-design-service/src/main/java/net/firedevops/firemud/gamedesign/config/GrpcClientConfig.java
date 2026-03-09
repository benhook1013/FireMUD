package net.firedevops.firemud.gamedesign.config;

import javax.net.ssl.SSLException;
import net.firedevops.firemud.gamedesign.client.AutomationScriptingClient;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bean configuration for outbound gRPC clients. */
@Configuration
public class GrpcClientConfig {

  @Bean
  public AutomationScriptingClient automationScriptingClient(
      ServiceEndpointsProperties endpoints, GrpcClientProperties properties) throws SSLException {
    return new AutomationScriptingClient(endpoints, properties);
  }
}
