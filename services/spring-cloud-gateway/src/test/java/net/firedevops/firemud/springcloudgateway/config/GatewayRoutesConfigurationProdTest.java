package net.firedevops.firemud.springcloudgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;
import net.firedevops.firemud.springcloudgateway.SpringCloudGatewayApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    classes = SpringCloudGatewayApplication.class,
    properties = {
      "spring.flyway.enabled=false",
      "firemud.database.enabled=false",
      "grpc.server.security.enabled=false",
      "grpc.server.port=0",
      "firemud.auth.jwt-secret=test-secret-for-prod-profile-tests",
      "spring.main.web-application-type=reactive",
      "spring.autoconfigure.exclude=org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration,org.lognet.springboot.grpc.autoconfigure.GRpcAutoConfiguration,org.lognet.springboot.grpc.autoconfigure.actuate.GRpcActuateAutoConfiguration"
    })
@ActiveProfiles("prod")
@Import(TestGatewayRateLimiterConfig.class)
class GatewayRoutesConfigurationProdTest {

  @MockitoBean private org.lognet.springboot.grpc.GRpcServerRunner grpcServerRunner;
  @MockitoBean private org.lognet.springboot.grpc.GRpcServicesRegistry grpcServicesRegistry;

  @MockitoBean
  private org.lognet.springboot.grpc.health.ManagedHealthStatusService managedHealthStatusService;

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

    assertThat(pathArgs.values()).containsExactlyInAnyOrder("/api/session/**", "/ws/game/**");
  }
}
