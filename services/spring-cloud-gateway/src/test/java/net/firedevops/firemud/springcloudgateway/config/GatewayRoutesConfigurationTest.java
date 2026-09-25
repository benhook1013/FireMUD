package net.firedevops.firemud.springcloudgateway.config;

import static net.firedevops.firemud.springcloudgateway.config.GatewayRouteTestSupport.assertHasMethod;
import static net.firedevops.firemud.springcloudgateway.config.GatewayRouteTestSupport.assertHasPath;
import static net.firedevops.firemud.springcloudgateway.config.GatewayRouteTestSupport.assertHasStripPrefixTwo;
import static net.firedevops.firemud.springcloudgateway.config.GatewayRouteTestSupport.assertNoConfiguredPath;
import static net.firedevops.firemud.springcloudgateway.config.GatewayRouteTestSupport.assertNoConfiguredPathStartsWith;
import static net.firedevops.firemud.springcloudgateway.config.GatewayRouteTestSupport.assertNoRouteWithPathAndMethod;
import static net.firedevops.firemud.springcloudgateway.config.GatewayRouteTestSupport.assertSocialChatAndFriendsAreEdgeGated;
import static net.firedevops.firemud.springcloudgateway.config.GatewayRouteTestSupport.route;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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
          "admin-logs",
          "admin-remote-followups",
          "admin-sagas",
          "admin-tick-remediation",
          "design-ping",
          "design-templates-read",
          "account-auth-login",
          "account-auth-player-bootstrap",
          "account-auth-bootstrap-worlds",
          "account-auth-bootstrap-realms",
          "account-auth-bootstrap-characters",
          "account-auth-bootstrap-join",
          "account-auth-connect-token",
          "account-auth-request-password-reset",
          "account-auth-complete-password-reset",
          "account-auth-request-email-verification",
          "account-auth-verify-email",
          "account-auth-recover-username",
          "account-accounts",
          "account-profiles",
          "account-ping",
          "account-jwks",
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
  void genericGatewayRetryNeverReplaysOneUseAccountPosts() throws IOException {
    // The test classpath has its own application.yml, so inspect the shipped
    // configuration rather than asserting the test profile's default filters.
    Path source = Path.of("src/main/resources/application.yml");
    String config = Files.readString(source);
    assertThat(config).containsPattern("(?s)- name: Retry\\s+args:.*?methods: GET(?:\\s|$)");
    assertThat(config).doesNotContain("methods: GET,POST");
    assertHasMethod(gatewayProperties, "account-auth-verify-email", "POST");
    assertHasMethod(gatewayProperties, "account-auth-complete-password-reset", "POST");
  }

  @Test
  void socialChatAndFriendsAreEdgeGated() {
    assertSocialChatAndFriendsAreEdgeGated(gatewayProperties);
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
  void publicReportsRouteIsNotConfigured() {
    assertNoConfiguredPathStartsWith(gatewayProperties, "/api/admin/reports");
  }

  @Test
  void publishedAssetDeliveryPathIsNotConfigured() {
    assertNoConfiguredPathStartsWith(gatewayProperties, "/assets/");
  }

  @Test
  void publishedAssetDeliveryRouteIdIsNotConfigured() {
    assertThat(gatewayProperties.getRoutes().stream().map(RouteDefinition::getId))
        .doesNotContain("asset-store-public");
  }

  @Test
  void restEdgeRoutesStripExternalServicePrefixBeforeForwarding() {
    assertHasPath(gatewayProperties, "session-ping", "/api/session/ping");
    assertHasPath(gatewayProperties, "admin-ping", "/api/admin/ping");
    assertHasPath(
        gatewayProperties, "admin-admission-pointers", "/api/admin/admission-pointers/**");
    assertHasMethod(gatewayProperties, "admin-admission-pointers", "GET");
    assertHasPath(gatewayProperties, "admin-logs", "/api/admin/logs/**");
    assertHasPath(gatewayProperties, "admin-remote-followups", "/api/admin/remote-followups/**");
    assertHasPath(gatewayProperties, "admin-sagas", "/api/admin/sagas/**");
    assertHasPath(gatewayProperties, "admin-tick-remediation", "/api/admin/tick-remediation/**");
    assertHasMethod(gatewayProperties, "admin-tick-remediation", "GET");

    assertHasStripPrefixTwo(gatewayProperties, "admin-ping");
    assertHasStripPrefixTwo(gatewayProperties, "admin-admission-pointers");
    assertHasStripPrefixTwo(gatewayProperties, "admin-logs");
    assertHasStripPrefixTwo(gatewayProperties, "admin-remote-followups");
    assertHasStripPrefixTwo(gatewayProperties, "admin-sagas");
    assertHasStripPrefixTwo(gatewayProperties, "admin-tick-remediation");

    assertHasPath(gatewayProperties, "design-ping", "/api/design/ping");
    assertHasMethod(gatewayProperties, "design-ping", "GET");
    assertHasStripPrefixTwo(gatewayProperties, "design-ping");
    assertHasPath(gatewayProperties, "design-templates-read", "/api/design/templates");
    assertHasMethod(gatewayProperties, "design-templates-read", "GET");
    assertHasStripPrefixTwo(gatewayProperties, "design-templates-read");
    assertNoConfiguredPath(gatewayProperties, "/api/design/**");
    assertNoConfiguredPath(gatewayProperties, "/api/design/assets");
    assertNoRouteWithPathAndMethod(gatewayProperties, "/api/design/templates", "POST");
    assertNoRouteWithPathAndMethod(gatewayProperties, "/api/design/assets", "POST");

    assertHasPath(gatewayProperties, "account-auth-verify-email", "/api/account/auth/verify-email");
    assertHasMethod(gatewayProperties, "account-auth-verify-email", "POST");
    assertHasPath(
        gatewayProperties,
        "account-auth-complete-password-reset",
        "/api/account/auth/complete-password-reset");
    assertHasMethod(gatewayProperties, "account-auth-complete-password-reset", "POST");
    assertHasMethod(gatewayProperties, "account-auth-bootstrap-worlds", "GET");
    assertHasMethod(gatewayProperties, "account-auth-bootstrap-realms", "GET");
    assertHasMethod(gatewayProperties, "account-auth-bootstrap-characters", "GET");
    assertHasMethod(gatewayProperties, "account-auth-request-email-verification", "POST");
    assertHasMethod(gatewayProperties, "account-auth-request-password-reset", "POST");
    assertNoConfiguredPath(gatewayProperties, "/api/account/auth/**");
    assertNoRouteWithPathAndMethod(gatewayProperties, "/api/account/auth/verify-email", "GET");
    assertNoRouteWithPathAndMethod(
        gatewayProperties, "/api/account/auth/complete-password-reset", "GET");
    assertNoRouteWithPathAndMethod(
        gatewayProperties, "/api/account/auth/verify-email/extra", "POST");
    assertNoRouteWithPathAndMethod(gatewayProperties, "/api/account/auth/internal/runtime", "POST");
    assertNoConfiguredPathStartsWith(gatewayProperties, "/api/account/internal/");
    assertNoConfiguredPathStartsWith(gatewayProperties, "/verify-email");
    assertNoConfiguredPathStartsWith(gatewayProperties, "/reset-password");
    assertHasPath(gatewayProperties, "account-accounts", "/api/account/accounts/**");
    assertHasPath(gatewayProperties, "account-profiles", "/api/account/profiles/**");
    assertHasPath(gatewayProperties, "account-ping", "/api/account/ping");
    assertHasPath(gatewayProperties, "account-jwks", "/api/account/.well-known/jwks.json");

    assertHasStripPrefixTwo(gatewayProperties, "account-auth-verify-email");
    assertHasStripPrefixTwo(gatewayProperties, "account-accounts");
    assertHasStripPrefixTwo(gatewayProperties, "account-profiles");
    assertHasStripPrefixTwo(gatewayProperties, "account-ping");
    assertHasStripPrefixTwo(gatewayProperties, "account-jwks");

    assertHasPath(gatewayProperties, "social-guilds", "/api/social/guilds/**");
    assertHasPath(gatewayProperties, "social-mail", "/api/social/mail/**");
    assertHasPath(gatewayProperties, "social-ping", "/api/social/ping");
    assertHasPath(gatewayProperties, "social-voice-token", "/api/social/voice/token/**");

    assertHasStripPrefixTwo(gatewayProperties, "social-guilds");
    assertHasStripPrefixTwo(gatewayProperties, "social-mail");
    assertHasStripPrefixTwo(gatewayProperties, "social-ping");
    assertHasStripPrefixTwo(gatewayProperties, "social-voice-token");
  }
}
