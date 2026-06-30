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
                2),
            route(
                "session-sessions",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_SESSION_URI", "http://game-session-service:8080"),
                "/api/session/sessions/**",
                2),
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
                2),
            route(
                "admin-feature-flags",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ADMIN_URI", "http://logging-admin-service:8080"),
                "/api/admin/feature-flags/**",
                2),
            route(
                "admin-logs",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ADMIN_URI", "http://logging-admin-service:8080"),
                "/api/admin/logs/**",
                2),
            route(
                "admin-moderation",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ADMIN_URI", "http://logging-admin-service:8080"),
                "/api/admin/moderation/**",
                2),
            route(
                "admin-reports",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ADMIN_URI", "http://logging-admin-service:8080"),
                "/api/admin/reports/**",
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
                2),
            route(
                "design",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_DESIGN_URI", "http://game-design-service:8080"),
                "/api/design/**",
                2),
            route(
                "account-auth",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ACCOUNT_URI", "http://account-service:8080"),
                "/api/account/auth/**",
                2),
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
                "social-chat",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_SOCIAL_URI", "http://social-groups-service:8080"),
                "/api/social/chat/**",
                2),
            route(
                "social-friends",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_SOCIAL_URI", "http://social-groups-service:8080"),
                "/api/social/friends/**",
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
                2),
            route(
                "asset-store-public",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ASSET_STORE_URI", "http://minio:9000"),
                "/assets/**",
                1)));
    return properties;
  }

  private static RouteDefinition route(String id, String uri, String path, int stripPrefix) {
    RouteDefinition definition = new RouteDefinition();
    definition.setId(id);
    definition.setUri(URI.create(uri));
    definition.setPredicates(
        List.of(
            new org.springframework.cloud.gateway.handler.predicate.PredicateDefinition(
                "Path=" + path)));
    definition.setFilters(
        List.of(
            new org.springframework.cloud.gateway.filter.FilterDefinition(
                "StripPrefix=" + stripPrefix)));
    return definition;
  }
}
