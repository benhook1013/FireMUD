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
                "session",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_SESSION_URI", "http://game-session-service:8080"),
                "/api/session/**",
                2),
            route(
                "admin",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ADMIN_URI", "http://logging-admin-service:8080"),
                "/api/admin/**",
                2),
            route(
                "design",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_DESIGN_URI", "http://game-design-service:8080"),
                "/api/design/**",
                2),
            route(
                "account",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_ACCOUNT_URI", "http://account-service:8080"),
                "/api/account/**",
                2),
            route(
                "social",
                environment.getProperty(
                    "FIREMUD_GATEWAY_ROUTE_SOCIAL_URI", "http://social-groups-service:8080"),
                "/api/social/**",
                2),
            route(
                "asset-store",
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
