package net.firedevops.firemud.springcloudgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

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
      GatewayTestProperties.REACTIVE_WEB_APPLICATION,
      GatewayTestProperties.DISABLE_GATEWAY_WARNING_AND_GRPC_SERVER
    })
@ActiveProfiles("test")
@Import({NoGrpcServerTestConfiguration.class, TestGatewayRateLimiterConfig.class})
class GatewayRoutesConfigurationTestProfileTest {

  @Autowired private GatewayProperties gatewayProperties;

  @Test
  void testProfileUsesCanonicalCuratedPublicRouteFamilies() {
    assertThat(gatewayProperties.getRoutes().stream().map(RouteDefinition::getId))
        .containsExactlyInAnyOrder(
            "session", "admin", "design", "account", "social", "asset-store");
  }

  @Test
  void testProfileRestRoutesStripExternalServicePrefix() {
    assertThat(pathArgs("admin").values()).containsExactly("/api/admin/**");
    assertThat(pathArgs("design").values()).containsExactly("/api/design/**");
    assertThat(pathArgs("account").values()).containsExactly("/api/account/**");
    assertThat(pathArgs("social").values()).containsExactly("/api/social/**");
    assertHasStripPrefix("session", "2");
    assertHasStripPrefix("admin", "2");
    assertHasStripPrefix("design", "2");
    assertHasStripPrefix("account", "2");
    assertHasStripPrefix("social", "2");
    assertHasStripPrefix("asset-store", "1");
  }

  private Map<String, String> pathArgs(String routeId) {
    return route(routeId).getPredicates().stream()
        .filter(predicate -> predicate.getName().equalsIgnoreCase("path"))
        .findFirst()
        .map(org.springframework.cloud.gateway.handler.predicate.PredicateDefinition::getArgs)
        .orElseThrow(() -> new AssertionError("Expected Path predicate for route " + routeId));
  }

  private void assertHasStripPrefix(String routeId, String value) {
    FilterDefinition filter =
        route(routeId).getFilters().stream()
            .filter(candidate -> "StripPrefix".equals(candidate.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected StripPrefix filter for " + routeId));
    assertThat(filter.getArgs().values()).containsExactly(value);
  }

  private RouteDefinition route(String routeId) {
    return gatewayProperties.getRoutes().stream()
        .filter(route -> routeId.equals(route.getId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected route to be configured: " + routeId));
  }
}
