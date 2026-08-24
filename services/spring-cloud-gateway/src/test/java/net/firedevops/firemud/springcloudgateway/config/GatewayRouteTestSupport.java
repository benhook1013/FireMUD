package net.firedevops.firemud.springcloudgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;

final class GatewayRouteTestSupport {

  private GatewayRouteTestSupport() {}

  static RouteDefinition route(GatewayProperties gatewayProperties, String routeId) {
    return gatewayProperties.getRoutes().stream()
        .filter(route -> routeId.equals(route.getId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected route to be configured: " + routeId));
  }

  static Map<String, String> pathArgs(GatewayProperties gatewayProperties, String routeId) {
    return predicateArgs(gatewayProperties, routeId, "Path");
  }

  static Map<String, String> predicateArgs(
      GatewayProperties gatewayProperties, String routeId, String predicateName) {
    return predicate(gatewayProperties, routeId, predicateName).getArgs();
  }

  static void assertHasPath(
      GatewayProperties gatewayProperties, String routeId, String expectedPath) {
    assertThat(pathArgs(gatewayProperties, routeId).values()).containsExactly(expectedPath);
  }

  static void assertHasMethod(
      GatewayProperties gatewayProperties, String routeId, String expectedMethod) {
    assertThat(predicateArgs(gatewayProperties, routeId, "Method").values())
        .containsExactly(expectedMethod);
  }

  static void assertNoConfiguredPathStartsWith(
      GatewayProperties gatewayProperties, String pathPrefix) {
    Set<String> configuredPaths =
        gatewayProperties.getRoutes().stream()
            .flatMap(route -> route.getPredicates().stream())
            .filter(predicate -> "Path".equalsIgnoreCase(predicate.getName()))
            .flatMap(predicate -> predicate.getArgs().values().stream())
            .collect(Collectors.toSet());

    assertThat(configuredPaths).isNotEmpty();
    assertThat(configuredPaths).noneMatch(path -> path.startsWith(pathPrefix));
  }

  static void assertNoConfiguredPath(GatewayProperties gatewayProperties, String path) {
    Set<String> configuredPaths =
        gatewayProperties.getRoutes().stream()
            .flatMap(route -> route.getPredicates().stream())
            .filter(predicate -> "Path".equalsIgnoreCase(predicate.getName()))
            .flatMap(predicate -> predicate.getArgs().values().stream())
            .collect(Collectors.toSet());

    assertThat(configuredPaths).doesNotContain(path);
  }

  static void assertSocialChatAndFriendsAreEdgeGated(GatewayProperties gatewayProperties) {
    assertThat(gatewayProperties.getRoutes().stream().map(RouteDefinition::getId))
        .doesNotContain("social-chat", "social-friends");
    assertNoConfiguredPath(gatewayProperties, "/api/social/**");
    assertNoConfiguredPathStartsWith(gatewayProperties, "/api/social/chat");
    assertNoConfiguredPathStartsWith(gatewayProperties, "/api/social/friends");
  }

  static void assertHasStripPrefixTwo(GatewayProperties gatewayProperties, String routeId) {
    assertHasStripPrefix(gatewayProperties, routeId, "2");
  }

  static void assertHasStripPrefix(
      GatewayProperties gatewayProperties, String routeId, String expectedValue) {
    FilterDefinition filter =
        route(gatewayProperties, routeId).getFilters().stream()
            .filter(candidate -> "StripPrefix".equals(candidate.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected StripPrefix filter for " + routeId));
    assertThat(filter.getArgs().values()).containsExactly(expectedValue);
  }

  private static PredicateDefinition predicate(
      GatewayProperties gatewayProperties, String routeId, String predicateName) {
    return route(gatewayProperties, routeId).getPredicates().stream()
        .filter(predicate -> predicateName.equalsIgnoreCase(predicate.getName()))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "Expected " + predicateName + " predicate for route " + routeId));
  }
}
