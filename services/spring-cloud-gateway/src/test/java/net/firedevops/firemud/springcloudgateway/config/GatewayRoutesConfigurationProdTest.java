package net.firedevops.firemud.springcloudgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
@Import({NoGrpcServerTestConfiguration.class, TestGatewayRateLimiterConfig.class})
class GatewayRoutesConfigurationProdTest {

  private static final Set<String> ROUTE_IDS =
      Set.of(
          "session-ping",
          "admin-ping",
          "admin-admission-pointers",
          "admin-feature-flags",
          "admin-logs",
          "admin-moderation",
          "admin-reports",
          "admin-remote-followups",
          "admin-sagas",
          "admin-tick-remediation",
          "design",
          "account-auth",
          "account-accounts",
          "account-profiles",
          "account-ping",
          "account-jwks",
          "social-chat",
          "social-friends",
          "social-guilds",
          "social-mail",
          "social-ping",
          "social-voice-token",
          "asset-store-public");

  @Autowired private GatewayProperties gatewayProperties;

  @Test
  void sessionRouteUsesHttpSchemeForControlPlaneTraffic() {
    RouteDefinition sessionRoute =
        gatewayProperties.getRoutes().stream()
            .filter(route -> "session-ping".equals(route.getId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected session-ping route to be configured"));

    URI targetUri = sessionRoute.getUri();
    assertThat(targetUri.getScheme()).isEqualTo("http");

    Map<String, String> pathArgs =
        sessionRoute.getPredicates().stream()
            .filter(predicate -> predicate.getName().equalsIgnoreCase("path"))
            .findFirst()
            .map(predicate -> predicate.getArgs())
            .orElseThrow(() -> new AssertionError("Session route should have a Path predicate"));

    assertThat(pathArgs.values()).containsExactly("/api/session/ping");
    assertHasStripPrefixTwo(sessionRoute);
  }

  @Test
  void publicRouteAllowlistExposesOnlyCuratedEdgeRoutes() {
    assertThat(gatewayProperties.getRoutes().stream().map(RouteDefinition::getId))
        .containsExactlyInAnyOrderElementsOf(ROUTE_IDS);
  }

  @Test
  void prodProfileHasNoCoarsePublicCatchallRouteFallbacks() {
    Set<String> coarsePaths =
        Set.of("/api/session/**", "/api/admin/**", "/api/account/**", "/api/social/**");
    assertThat(
            gatewayProperties.getRoutes().stream()
                .flatMap(route -> route.getPredicates().stream())
                .filter(predicate -> "Path".equalsIgnoreCase(predicate.getName()))
                .flatMap(predicate -> predicate.getArgs().values().stream())
                .collect(Collectors.toSet()))
        .noneMatch(coarsePaths::contains);
  }

  @Test
  void restEdgeRoutesStripExternalServicePrefixBeforeForwarding() {
    assertThat(route("admin-ping").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/admin/ping");
    assertThat(route("admin-remote-followups").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/admin/remote-followups/**");
    assertThat(route("design").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/design/**");
    assertThat(route("account-auth").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/account/auth/**");
    assertThat(route("social-chat").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/social/chat/**");
    assertThat(route("asset-store-public").getPredicates().get(0).getArgs().values())
        .containsExactly("/assets/**");

    assertHasStripPrefixTwo("admin-ping");
    assertHasStripPrefixTwo("admin-remote-followups");
    assertHasStripPrefixTwo("design");
    assertHasStripPrefixTwo("account-auth");
    assertHasStripPrefixTwo("social-chat");
    assertHasStripPrefix("asset-store-public", "1");
  }

  private RouteDefinition route(String routeId) {
    return gatewayProperties.getRoutes().stream()
        .filter(route -> routeId.equals(route.getId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected route to be configured: " + routeId));
  }

  private void assertHasStripPrefixTwo(RouteDefinition route) {
    assertHasStripPrefix(route, "2");
  }

  private void assertHasStripPrefixTwo(String routeId) {
    assertHasStripPrefix(route(routeId), "2");
  }

  private void assertHasStripPrefix(String route, String value) {
    assertHasStripPrefix(route(route), value);
  }

  private void assertHasStripPrefix(RouteDefinition route, String value) {
    FilterDefinition filter =
        route.getFilters().stream()
            .filter(candidate -> "StripPrefix".equals(candidate.getName()))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("Expected StripPrefix filter for " + route.getId()));
    assertThat(filter.getArgs().values()).containsExactly(value);
  }
}
