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
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    classes = SpringCloudGatewayApplication.class,
    properties = {
      "spring.flyway.enabled=false",
      "firemud.database.enabled=false",
      GatewayTestProperties.SPRING_GRPC_SERVER_RANDOM_PORT,
      GatewayTestProperties.SPRING_GRPC_SERVER_SSL_DISABLED,
      GatewayTestProperties.FIREMUD_GRPC_CERT_CHAIN_PATH,
      GatewayTestProperties.FIREMUD_GRPC_PRIVATE_KEY_PATH,
      GatewayTestProperties.FIREMUD_GRPC_CA_CERT_PATH,
      "firemud.auth.jwt-secret=test-secret-for-prod-profile-tests",
      GatewayTestProperties.REACTIVE_WEB_APPLICATION,
      GatewayTestProperties.DISABLE_GATEWAY_WARNING_AND_GRPC_SERVER
    })
@ActiveProfiles("prod")
@Import({NoGrpcServerTestConfiguration.class, TestGatewayRateLimiterConfig.class})
class GatewayRoutesConfigurationProdTest {

  @Autowired private GatewayProperties gatewayProperties;

  @Test
  void sessionRouteUsesHttpSchemeForControlPlaneTraffic() {
    RouteDefinition sessionRoute =
        gatewayProperties.getRoutes().stream()
            .filter(route -> "session".equals(route.getId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected session route to be configured"));

    URI targetUri = sessionRoute.getUri();
    assertThat(targetUri.getScheme()).isEqualTo("http");

    Map<String, String> pathArgs =
        sessionRoute.getPredicates().stream()
            .filter(predicate -> predicate.getName().equalsIgnoreCase("path"))
            .findFirst()
            .map(predicate -> predicate.getArgs())
            .orElseThrow(() -> new AssertionError("Session route should have a Path predicate"));

    assertThat(pathArgs.values()).containsExactly("/api/session/**");
    assertHasStripPrefixTwo(sessionRoute);
  }

  @Test
  void restEdgeRoutesStripExternalServicePrefixBeforeForwarding() {
    assertThat(route("admin").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/admin/**");
    assertHasStripPrefixTwo(route("admin"));
    assertHasStripPrefixTwo(route("design"));
    assertHasStripPrefixTwo(route("account"));
    assertHasStripPrefixTwo(route("social"));
  }

  private RouteDefinition route(String routeId) {
    return gatewayProperties.getRoutes().stream()
        .filter(route -> routeId.equals(route.getId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected route to be configured: " + routeId));
  }

  private void assertHasStripPrefixTwo(RouteDefinition route) {
    FilterDefinition filter =
        route.getFilters().stream()
            .filter(candidate -> "StripPrefix".equals(candidate.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected StripPrefix filter"));
    assertThat(filter.getArgs().values()).containsExactly("2");
  }
}
