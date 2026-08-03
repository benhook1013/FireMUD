package net.firedevops.firemud.springcloudgateway.config;

import static net.firedevops.firemud.springcloudgateway.config.GatewayRouteTestSupport.assertHasMethod;
import static net.firedevops.firemud.springcloudgateway.config.GatewayRouteTestSupport.assertHasPath;
import static net.firedevops.firemud.springcloudgateway.config.GatewayRouteTestSupport.assertHasStripPrefix;
import static org.assertj.core.api.Assertions.assertThat;

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
          "social-voice-token",
          "asset-store-public");

  @Autowired private GatewayProperties gatewayProperties;

  @Test
  void testProfileUsesCanonicalCuratedPublicRouteIds() {
    assertThat(gatewayProperties.getRoutes().stream().map(RouteDefinition::getId))
        .containsExactlyInAnyOrderElementsOf(ROUTE_IDS);
  }

  @Test
  void testProfileHasNoCoarsePublicCatchallRouteFallbacks() {
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
  void testProfileRestRoutesStripExternalServicePrefix() {
    assertHasPath(gatewayProperties, "admin-ping", "/api/admin/ping");
    assertHasPath(
        gatewayProperties, "admin-admission-pointers", "/api/admin/admission-pointers/**");
    assertHasMethod(gatewayProperties, "admin-admission-pointers", "GET");
    assertHasPath(gatewayProperties, "admin-logs", "/api/admin/logs/**");
    assertHasPath(gatewayProperties, "admin-remote-followups", "/api/admin/remote-followups/**");
    assertHasPath(gatewayProperties, "admin-sagas", "/api/admin/sagas/**");
    assertHasPath(gatewayProperties, "admin-tick-remediation", "/api/admin/tick-remediation/**");
    assertHasMethod(gatewayProperties, "admin-tick-remediation", "GET");

    assertHasPath(gatewayProperties, "design", "/api/design/**");

    assertHasPath(gatewayProperties, "session-ping", "/api/session/ping");

    assertHasPath(gatewayProperties, "account-auth", "/api/account/auth/**");
    assertHasPath(gatewayProperties, "account-accounts", "/api/account/accounts/**");
    assertHasPath(gatewayProperties, "account-profiles", "/api/account/profiles/**");
    assertHasPath(gatewayProperties, "account-ping", "/api/account/ping");
    assertHasPath(gatewayProperties, "account-jwks", "/api/account/.well-known/jwks.json");

    assertHasPath(gatewayProperties, "social-chat", "/api/social/chat/**");
    assertHasPath(gatewayProperties, "social-friends", "/api/social/friends/**");
    assertHasPath(gatewayProperties, "social-guilds", "/api/social/guilds/**");
    assertHasPath(gatewayProperties, "social-mail", "/api/social/mail/**");
    assertHasPath(gatewayProperties, "social-ping", "/api/social/ping");
    assertHasPath(gatewayProperties, "social-voice-token", "/api/social/voice/token/**");

    assertHasPath(gatewayProperties, "asset-store-public", "/assets/**");

    assertHasStripPrefix(gatewayProperties, "session-ping", "2");
    assertHasStripPrefix(gatewayProperties, "admin-ping", "2");
    assertHasStripPrefix(gatewayProperties, "admin-admission-pointers", "2");
    assertHasStripPrefix(gatewayProperties, "admin-logs", "2");
    assertHasStripPrefix(gatewayProperties, "admin-remote-followups", "2");
    assertHasStripPrefix(gatewayProperties, "admin-sagas", "2");
    assertHasStripPrefix(gatewayProperties, "admin-tick-remediation", "2");
    assertHasStripPrefix(gatewayProperties, "design", "2");
    assertHasStripPrefix(gatewayProperties, "account-auth", "2");
    assertHasStripPrefix(gatewayProperties, "account-accounts", "2");
    assertHasStripPrefix(gatewayProperties, "account-profiles", "2");
    assertHasStripPrefix(gatewayProperties, "account-ping", "2");
    assertHasStripPrefix(gatewayProperties, "account-jwks", "2");
    assertHasStripPrefix(gatewayProperties, "social-chat", "2");
    assertHasStripPrefix(gatewayProperties, "social-friends", "2");
    assertHasStripPrefix(gatewayProperties, "social-guilds", "2");
    assertHasStripPrefix(gatewayProperties, "social-mail", "2");
    assertHasStripPrefix(gatewayProperties, "social-ping", "2");
    assertHasStripPrefix(gatewayProperties, "social-voice-token", "2");
    assertHasStripPrefix(gatewayProperties, "asset-store-public", "1");
  }
}
