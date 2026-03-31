package net.firedevops.firemud.springcloudgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;
import net.firedevops.firemud.springcloudgateway.SpringCloudGatewayApplication;
import net.firedevops.firemud.test.GatewayTestProperties;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    classes = SpringCloudGatewayApplication.class,
    properties = {
      "spring.flyway.enabled=false",
      "firemud.database.enabled=false",
      GatewayTestProperties.SPRING_GRPC_SERVER_RANDOM_PORT,
      GatewayTestProperties.REACTIVE_WEB_APPLICATION,
      GatewayTestProperties.DISABLE_GATEWAY_WARNING_AND_GRPC_SERVER
    })
@ActiveProfiles("dev")
@Import({NoGrpcServerTestConfiguration.class, TestGatewayRateLimiterConfig.class})
class GatewayRoutesConfigurationTest {

  @Autowired private GatewayProperties gatewayProperties;

  @Test
  void sessionRouteUsesWebSocketSchemeAndAlias() {
    RouteDefinition sessionRoute =
        gatewayProperties.getRoutes().stream()
            .filter(route -> "session".equals(route.getId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected session route to be configured"));

    URI targetUri = sessionRoute.getUri();
    assertThat(targetUri.getScheme()).isEqualTo("ws");

    Map<String, String> pathArgs =
        sessionRoute.getPredicates().stream()
            .filter(predicate -> predicate.getName().equalsIgnoreCase("path"))
            .findFirst()
            .map(predicate -> predicate.getArgs())
            .orElseThrow(() -> new AssertionError("Session route should have a Path predicate"));

    assertThat(pathArgs.values()).containsExactly("/api/session/**");
  }
}
