package net.firedevops.firemud.springcloudgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
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
      GatewayTestProperties.REACTIVE_WEB_APPLICATION,
      GatewayTestProperties.DISABLE_GATEWAY_WARNING_AND_GRPC_SERVER
    })
@Import({NoGrpcServerTestConfiguration.class, TestGatewayRateLimiterConfig.class})
class GatewayRoutesConfigurationTest {

  private static final Set<String> ROUTE_IDS =
      Set.of(
          "session-ping",
          "admin-ping",
          "admin-admission-pointers",
          "admin-feature-flags",
          "admin-logs",
          "admin-moderation",
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
    assertHasPath(sessionRoute, "/api/session/ping");
    assertHasMethod(sessionRoute, "GET");
    assertHasStripPrefixTwo(sessionRoute);
  }

  @Test
  void publicRouteAllowlistExposesOnlyCuratedEdgeRoutes() {
    assertThat(gatewayProperties.getRoutes().stream().map(RouteDefinition::getId))
        .containsExactlyInAnyOrderElementsOf(ROUTE_IDS);
  }

  @Test
  void publicSessionAndSocialRouteFamiliesHaveNoCoarseCatchallFallback() {
    Set<String> coarsePaths =
        Set.of("/api/session/**", "/api/admin/**", "/api/account/**", "/api/social/**");
    assertThat(
            gatewayProperties.getRoutes().stream()
                .flatMap(route -> route.getPredicates().stream())
                .filter(route -> "Path".equalsIgnoreCase(route.getName()))
                .flatMap(predicate -> predicate.getArgs().values().stream())
                .collect(Collectors.toSet()))
        .noneMatch(coarsePaths::contains);
  }

  @Test
  void restEdgeRoutesStripExternalServicePrefixBeforeForwarding() {
    assertHasPath(route("session-ping"), "/api/session/ping");
    assertHasPath(route("admin-ping"), "/api/admin/ping");
    assertHasPath(route("admin-admission-pointers"), "/api/admin/admission-pointers/**");
    assertHasPath(route("admin-feature-flags"), "/api/admin/feature-flags/**");
    assertHasPath(route("admin-logs"), "/api/admin/logs/**");
    assertHasPath(route("admin-moderation"), "/api/admin/moderation/**");
    assertHasPath(route("admin-remote-followups"), "/api/admin/remote-followups/**");
    assertHasPath(route("admin-sagas"), "/api/admin/sagas/**");
    assertHasPath(route("admin-tick-remediation"), "/api/admin/tick-remediation/**");

    assertHasStripPrefixTwo(route("admin-ping"));
    assertHasStripPrefixTwo(route("admin-admission-pointers"));
    assertHasStripPrefixTwo(route("admin-feature-flags"));
    assertHasStripPrefixTwo(route("admin-logs"));
    assertHasStripPrefixTwo(route("admin-moderation"));
    assertHasStripPrefixTwo(route("admin-remote-followups"));
    assertHasStripPrefixTwo(route("admin-sagas"));
    assertHasStripPrefixTwo(route("admin-tick-remediation"));

    assertHasPath(route("design"), "/api/design/**");
    assertHasStripPrefixTwo(route("design"));

    assertHasPath(route("account-auth"), "/api/account/auth/**");
    assertHasPath(route("account-accounts"), "/api/account/accounts/**");
    assertHasPath(route("account-profiles"), "/api/account/profiles/**");
    assertHasPath(route("account-ping"), "/api/account/ping");
    assertHasPath(route("account-jwks"), "/api/account/.well-known/jwks.json");

    assertHasStripPrefixTwo(route("account-auth"));
    assertHasStripPrefixTwo(route("account-accounts"));
    assertHasStripPrefixTwo(route("account-profiles"));
    assertHasStripPrefixTwo(route("account-ping"));
    assertHasStripPrefixTwo(route("account-jwks"));

    assertHasPath(route("social-chat"), "/api/social/chat/**");
    assertHasPath(route("social-friends"), "/api/social/friends/**");
    assertHasPath(route("social-guilds"), "/api/social/guilds/**");
    assertHasPath(route("social-mail"), "/api/social/mail/**");
    assertHasPath(route("social-ping"), "/api/social/ping");
    assertHasPath(route("social-voice-token"), "/api/social/voice/token/**");

    assertHasStripPrefixTwo(route("social-chat"));
    assertHasStripPrefixTwo(route("social-friends"));
    assertHasStripPrefixTwo(route("social-guilds"));
    assertHasStripPrefixTwo(route("social-mail"));
    assertHasStripPrefixTwo(route("social-ping"));
    assertHasStripPrefixTwo(route("social-voice-token"));

    assertHasPath(route("asset-store-public"), "/assets/**");
    assertHasStripPrefix(route("asset-store-public"), "1");
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

  private void assertHasPath(RouteDefinition route, String path) {
    assertThat(predicate(route, "Path").getArgs().values()).containsExactly(path);
  }

  private void assertHasMethod(RouteDefinition route, String method) {
    assertThat(predicate(route, "Method").getArgs().values()).containsExactly(method);
  }

  private org.springframework.cloud.gateway.handler.predicate.PredicateDefinition predicate(
      RouteDefinition route, String name) {
    return route.getPredicates().stream()
        .filter(candidate -> name.equalsIgnoreCase(candidate.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected predicate " + name));
  }

  private void assertHasStripPrefix(RouteDefinition route, String value) {
    FilterDefinition filter =
        route.getFilters().stream()
            .filter(candidate -> "StripPrefix".equals(candidate.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected StripPrefix filter"));
    assertThat(filter.getArgs().values()).containsExactly(value);
  }
}
