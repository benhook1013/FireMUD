package net.firedevops.firemud.springcloudgateway.config;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/** Publishes the canonical static public-edge route catalog from explicit env-backed values. */
@Configuration
public class CanonicalGatewayRoutesConfiguration {
  private static final String GATEWAY_PREFIX = GatewayProperties.PREFIX;

  @Bean
  @Primary
  public GatewayProperties canonicalGatewayProperties(Environment environment) {
    GatewayProperties properties =
        Binder.get(environment)
            .bind(GATEWAY_PREFIX, GatewayProperties.class)
            .orElseGet(GatewayProperties::new);
    properties.setRoutes(
        List.of(
            route(
                "session-ping",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_SESSION_URI", "http://game-session-service:8080"),
                "/api/session/ping",
                2,
                "GET"),
            route(
                "admin-ping",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ADMIN_URI", "http://logging-admin-service:8080"),
                "/api/admin/ping",
                2),
            route(
                "admin-admission-pointers",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ADMIN_URI", "http://logging-admin-service:8080"),
                "/api/admin/admission-pointers/**",
                2,
                "GET"),
            route(
                "admin-logs",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ADMIN_URI", "http://logging-admin-service:8080"),
                "/api/admin/logs/**",
                2),
            route(
                "admin-remote-followups",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ADMIN_URI", "http://logging-admin-service:8080"),
                "/api/admin/remote-followups/**",
                2),
            route(
                "admin-sagas",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ADMIN_URI", "http://logging-admin-service:8080"),
                "/api/admin/sagas/**",
                2),
            route(
                "admin-tick-remediation",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ADMIN_URI", "http://logging-admin-service:8080"),
                "/api/admin/tick-remediation/**",
                2,
                "GET"),
            route(
                "design-ping",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_DESIGN_URI", "http://game-design-service:8080"),
                "/api/design/ping",
                2,
                "GET"),
            route(
                "design-templates-read",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_DESIGN_URI", "http://game-design-service:8080"),
                "/api/design/templates",
                2,
                "GET"),
            route(
                "account-auth-login",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ACCOUNT_URI", "http://account-service:8080"),
                "/api/account/auth/login",
                2,
                "POST"),
            route(
                "account-auth-player-bootstrap",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ACCOUNT_URI", "http://account-service:8080"),
                "/api/account/auth/player-bootstrap",
                2,
                "POST"),
            route(
                "account-auth-bootstrap-worlds",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ACCOUNT_URI", "http://account-service:8080"),
                "/api/account/auth/bootstrap/worlds",
                2,
                "GET"),
            route(
                "account-auth-bootstrap-realms",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ACCOUNT_URI", "http://account-service:8080"),
                "/api/account/auth/bootstrap/worlds/{worldSlug}/realms",
                2,
                "GET"),
            route(
                "account-auth-bootstrap-characters",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ACCOUNT_URI", "http://account-service:8080"),
                "/api/account/auth/bootstrap/worlds/{worldSlug}/realms/{realmSlug}/characters",
                2,
                "GET"),
            route(
                "account-auth-bootstrap-join",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ACCOUNT_URI", "http://account-service:8080"),
                "/api/account/auth/bootstrap/join",
                2,
                "POST"),
            route(
                "account-auth-connect-token",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ACCOUNT_URI", "http://account-service:8080"),
                "/api/account/auth/connect-token",
                2,
                "POST"),
            route(
                "account-auth-request-password-reset",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ACCOUNT_URI", "http://account-service:8080"),
                "/api/account/auth/request-password-reset",
                2,
                "POST"),
            route(
                "account-auth-complete-password-reset",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ACCOUNT_URI", "http://account-service:8080"),
                "/api/account/auth/complete-password-reset",
                2,
                "POST"),
            route(
                "account-auth-request-email-verification",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ACCOUNT_URI", "http://account-service:8080"),
                "/api/account/auth/request-email-verification",
                2,
                "POST"),
            route(
                "account-auth-verify-email",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ACCOUNT_URI", "http://account-service:8080"),
                "/api/account/auth/verify-email",
                2,
                "POST"),
            route(
                "account-auth-recover-username",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ACCOUNT_URI", "http://account-service:8080"),
                "/api/account/auth/recover-username",
                2,
                "POST"),
            route(
                "account-accounts",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ACCOUNT_URI", "http://account-service:8080"),
                "/api/account/accounts/**",
                2),
            route(
                "account-profiles",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ACCOUNT_URI", "http://account-service:8080"),
                "/api/account/profiles/**",
                2),
            route(
                "account-ping",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ACCOUNT_URI", "http://account-service:8080"),
                "/api/account/ping",
                2),
            route(
                "account-jwks",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ACCOUNT_URI", "http://account-service:8080"),
                "/api/account/.well-known/jwks.json",
                2),
            route(
                "social-guilds",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_SOCIAL_URI", "http://social-groups-service:8080"),
                "/api/social/guilds/**",
                2),
            route(
                "social-mail",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_SOCIAL_URI", "http://social-groups-service:8080"),
                "/api/social/mail/**",
                2),
            route(
                "social-ping",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_SOCIAL_URI", "http://social-groups-service:8080"),
                "/api/social/ping",
                2),
            route(
                "social-voice-token",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_SOCIAL_URI", "http://social-groups-service:8080"),
                "/api/social/voice/token/**",
                2)));
    return properties;
  }

  private static RouteDefinition route(String id, String uri, String path, int stripPrefix) {
    return route(id, uri, path, stripPrefix, null);
  }

  private static RouteDefinition route(
      String id, String uri, String path, int stripPrefix, String method) {
    RouteDefinition definition = new RouteDefinition();
    definition.setId(id);
    definition.setUri(URI.create(uri));
    List<org.springframework.cloud.gateway.handler.predicate.PredicateDefinition> predicates =
        new java.util.ArrayList<>();
    predicates.add(
        new org.springframework.cloud.gateway.handler.predicate.PredicateDefinition(
            "Path=" + path));
    if (method != null && !method.isBlank()) {
      predicates.add(
          new org.springframework.cloud.gateway.handler.predicate.PredicateDefinition(
              "Method=" + method));
    }
    definition.setPredicates(predicates);
    definition.setFilters(
        List.of(
            new org.springframework.cloud.gateway.filter.FilterDefinition(
                "StripPrefix=" + stripPrefix)));
    return definition;
  }
}
