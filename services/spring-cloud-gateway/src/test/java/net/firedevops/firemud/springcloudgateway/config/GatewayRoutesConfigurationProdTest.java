package net.firedevops.firemud.springcloudgateway.config;

import static net.firedevops.firemud.springcloudgateway.config.GatewayRouteTestSupport.assertHasMethod;
import static net.firedevops.firemud.springcloudgateway.config.GatewayRouteTestSupport.assertHasPath;
import static net.firedevops.firemud.springcloudgateway.config.GatewayRouteTestSupport.assertHasStripPrefixTwo;
import static net.firedevops.firemud.springcloudgateway.config.GatewayRouteTestSupport.assertNoConfiguredPathStartsWith;
import static net.firedevops.firemud.springcloudgateway.config.GatewayRouteTestSupport.route;
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
          "admin-logs",
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
          "social-voice-token");

  @Autowired private GatewayProperties gatewayProperties;

  @Test
  void sessionRouteUsesHttpSchemeForControlPlaneTraffic() {
    RouteDefinition sessionRoute = route(gatewayProperties, "session-ping");

    URI targetUri = sessionRoute.getUri();
    assertThat(targetUri.getScheme()).isEqualTo("http");
    assertHasPath(gatewayProperties, "session-ping", "/api/session/ping");
    assertHasMethod(gatewayProperties, "session-ping", "GET");
    assertHasStripPrefixTwo(gatewayProperties, "session-ping");
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
  void publicReportsRouteIsNotConfigured() {
    assertNoConfiguredPathStartsWith(gatewayProperties, "/api/admin/reports");
  }

  @Test
  void publishedAssetDeliveryRouteIsNotConfigured() {
    assertNoConfiguredPathStartsWith(gatewayProperties, "/assets/");
    assertThat(gatewayProperties.getRoutes().stream().map(RouteDefinition::getId))
        .doesNotContain("asset-store-public");
  }

  @Test
  void restEdgeRoutesStripExternalServicePrefixBeforeForwarding() {
    assertHasPath(gatewayProperties, "admin-ping", "/api/admin/ping");
    assertHasPath(
        gatewayProperties, "admin-admission-pointers", "/api/admin/admission-pointers/**");
    assertHasMethod(gatewayProperties, "admin-admission-pointers", "GET");
    assertHasPath(gatewayProperties, "admin-remote-followups", "/api/admin/remote-followups/**");
    assertHasPath(gatewayProperties, "admin-tick-remediation", "/api/admin/tick-remediation/**");
    assertHasMethod(gatewayProperties, "admin-tick-remediation", "GET");
    assertHasPath(gatewayProperties, "design", "/api/design/**");
    assertHasPath(gatewayProperties, "account-auth", "/api/account/auth/**");
    assertHasPath(gatewayProperties, "social-chat", "/api/social/chat/**");
    assertHasStripPrefixTwo(gatewayProperties, "admin-ping");
    assertHasStripPrefixTwo(gatewayProperties, "admin-admission-pointers");
    assertHasStripPrefixTwo(gatewayProperties, "admin-remote-followups");
    assertHasStripPrefixTwo(gatewayProperties, "admin-tick-remediation");
    assertHasStripPrefixTwo(gatewayProperties, "design");
    assertHasStripPrefixTwo(gatewayProperties, "account-auth");
    assertHasStripPrefixTwo(gatewayProperties, "social-chat");
  }
}
