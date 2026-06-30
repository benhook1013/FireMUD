package net.firedevops.firemud.springcloudgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Set;
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
          "admin-reports",
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

    var pathArgs =
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
  void restEdgeRoutesStripExternalServicePrefixBeforeForwarding() {
    assertThat(route("session-ping").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/session/ping");
    assertThat(route("admin-ping").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/admin/ping");
    assertThat(route("admin-admission-pointers").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/admin/admission-pointers/**");
    assertThat(route("admin-feature-flags").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/admin/feature-flags/**");
    assertThat(route("admin-logs").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/admin/logs/**");
    assertThat(route("admin-moderation").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/admin/moderation/**");
    assertThat(route("admin-reports").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/admin/reports/**");
    assertThat(route("admin-sagas").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/admin/sagas/**");
    assertThat(route("admin-tick-remediation").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/admin/tick-remediation/**");

    assertHasStripPrefixTwo(route("admin-ping"));
    assertHasStripPrefixTwo(route("admin-admission-pointers"));
    assertHasStripPrefixTwo(route("admin-feature-flags"));
    assertHasStripPrefixTwo(route("admin-logs"));
    assertHasStripPrefixTwo(route("admin-moderation"));
    assertHasStripPrefixTwo(route("admin-reports"));
    assertHasStripPrefixTwo(route("admin-sagas"));
    assertHasStripPrefixTwo(route("admin-tick-remediation"));

    assertThat(route("design").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/design/**");
    assertHasStripPrefixTwo(route("design"));

    assertThat(route("account-auth").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/account/auth/**");
    assertThat(route("account-accounts").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/account/accounts/**");
    assertThat(route("account-profiles").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/account/profiles/**");
    assertThat(route("account-ping").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/account/ping");
    assertThat(route("account-jwks").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/account/.well-known/jwks.json");

    assertHasStripPrefixTwo(route("account-auth"));
    assertHasStripPrefixTwo(route("account-accounts"));
    assertHasStripPrefixTwo(route("account-profiles"));
    assertHasStripPrefixTwo(route("account-ping"));
    assertHasStripPrefixTwo(route("account-jwks"));

    assertThat(route("social-chat").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/social/chat/**");
    assertThat(route("social-friends").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/social/friends/**");
    assertThat(route("social-guilds").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/social/guilds/**");
    assertThat(route("social-mail").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/social/mail/**");
    assertThat(route("social-ping").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/social/ping");
    assertThat(route("social-voice-token").getPredicates().get(0).getArgs().values())
        .containsExactly("/api/social/voice/token/**");

    assertHasStripPrefixTwo(route("social-chat"));
    assertHasStripPrefixTwo(route("social-friends"));
    assertHasStripPrefixTwo(route("social-guilds"));
    assertHasStripPrefixTwo(route("social-mail"));
    assertHasStripPrefixTwo(route("social-ping"));
    assertHasStripPrefixTwo(route("social-voice-token"));

    assertThat(route("asset-store-public").getPredicates().get(0).getArgs().values())
        .containsExactly("/assets/**");
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

  private void assertHasStripPrefix(RouteDefinition route, String value) {
    FilterDefinition filter =
        route.getFilters().stream()
            .filter(candidate -> "StripPrefix".equals(candidate.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected StripPrefix filter"));
    assertThat(filter.getArgs().values()).containsExactly(value);
  }
}
