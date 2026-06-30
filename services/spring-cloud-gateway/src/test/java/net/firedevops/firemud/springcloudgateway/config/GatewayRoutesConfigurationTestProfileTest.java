package net.firedevops.firemud.springcloudgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
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
          "session-sessions",
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
  void testProfileUsesCanonicalCuratedPublicRouteIds() {
    assertThat(gatewayProperties.getRoutes().stream().map(RouteDefinition::getId))
        .containsExactlyInAnyOrderElementsOf(ROUTE_IDS);
  }

  @Test
  void testProfileRestRoutesStripExternalServicePrefix() {
    assertThat(pathArgs("admin-ping").values()).containsExactly("/api/admin/ping");
    assertThat(pathArgs("admin-admission-pointers").values())
        .containsExactly("/api/admin/admission-pointers/**");
    assertThat(pathArgs("admin-feature-flags").values())
        .containsExactly("/api/admin/feature-flags/**");
    assertThat(pathArgs("admin-logs").values()).containsExactly("/api/admin/logs/**");
    assertThat(pathArgs("admin-moderation").values()).containsExactly("/api/admin/moderation/**");
    assertThat(pathArgs("admin-reports").values()).containsExactly("/api/admin/reports/**");
    assertThat(pathArgs("admin-sagas").values()).containsExactly("/api/admin/sagas/**");
    assertThat(pathArgs("admin-tick-remediation").values())
        .containsExactly("/api/admin/tick-remediation/**");

    assertThat(pathArgs("design").values()).containsExactly("/api/design/**");

    assertThat(pathArgs("session-ping").values()).containsExactly("/api/session/ping");
    assertThat(pathArgs("session-sessions").values()).containsExactly("/api/session/sessions/**");

    assertThat(pathArgs("account-auth").values()).containsExactly("/api/account/auth/**");
    assertThat(pathArgs("account-accounts").values()).containsExactly("/api/account/accounts/**");
    assertThat(pathArgs("account-profiles").values()).containsExactly("/api/account/profiles/**");
    assertThat(pathArgs("account-ping").values()).containsExactly("/api/account/ping");
    assertThat(pathArgs("account-jwks").values())
        .containsExactly("/api/account/.well-known/jwks.json");

    assertThat(pathArgs("social-chat").values()).containsExactly("/api/social/chat/**");
    assertThat(pathArgs("social-friends").values()).containsExactly("/api/social/friends/**");
    assertThat(pathArgs("social-guilds").values()).containsExactly("/api/social/guilds/**");
    assertThat(pathArgs("social-mail").values()).containsExactly("/api/social/mail/**");
    assertThat(pathArgs("social-ping").values()).containsExactly("/api/social/ping");
    assertThat(pathArgs("social-voice-token").values())
        .containsExactly("/api/social/voice/token/**");

    assertThat(pathArgs("asset-store-public").values()).containsExactly("/assets/**");

    assertHasStripPrefix("session-ping", "2");
    assertHasStripPrefix("session-sessions", "2");
    assertHasStripPrefix("admin-ping", "2");
    assertHasStripPrefix("admin-admission-pointers", "2");
    assertHasStripPrefix("admin-feature-flags", "2");
    assertHasStripPrefix("admin-logs", "2");
    assertHasStripPrefix("admin-moderation", "2");
    assertHasStripPrefix("admin-reports", "2");
    assertHasStripPrefix("admin-sagas", "2");
    assertHasStripPrefix("admin-tick-remediation", "2");
    assertHasStripPrefix("design", "2");
    assertHasStripPrefix("account-auth", "2");
    assertHasStripPrefix("account-accounts", "2");
    assertHasStripPrefix("account-profiles", "2");
    assertHasStripPrefix("account-ping", "2");
    assertHasStripPrefix("account-jwks", "2");
    assertHasStripPrefix("social-chat", "2");
    assertHasStripPrefix("social-friends", "2");
    assertHasStripPrefix("social-guilds", "2");
    assertHasStripPrefix("social-mail", "2");
    assertHasStripPrefix("social-ping", "2");
    assertHasStripPrefix("social-voice-token", "2");
    assertHasStripPrefix("asset-store-public", "1");
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
