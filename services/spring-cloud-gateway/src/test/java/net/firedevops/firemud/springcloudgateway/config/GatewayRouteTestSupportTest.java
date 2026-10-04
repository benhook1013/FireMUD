package net.firedevops.firemud.springcloudgateway.config;

import static net.firedevops.firemud.springcloudgateway.config.GatewayRouteTestSupport.assertNoConfiguredPath;
import static net.firedevops.firemud.springcloudgateway.config.GatewayRouteTestSupport.assertNoRouteWithPathAndMethod;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;

class GatewayRouteTestSupportTest {

  @Test
  void assertNoConfiguredPathRejectsWildcardAndPathVariableMatches() {
    assertThatThrownBy(
            () ->
                assertNoConfiguredPath(
                    gatewayProperties(route(List.of("/api/design/**"), null)),
                    "/api/design/assets"))
        .isInstanceOf(AssertionError.class);

    assertThatThrownBy(
            () ->
                assertNoConfiguredPath(
                    gatewayProperties(route(List.of("/api/design/{resource}"), null)),
                    "/api/design/assets"))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void assertNoConfiguredPathPreservesExactWildcardPatternAssertion() {
    assertThatThrownBy(
            () ->
                assertNoConfiguredPath(
                    gatewayProperties(route(List.of("/api/social/**"), null)), "/api/social/**"))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void assertNoRouteWithPathAndMethodRejectsMatchingWildcardAndPathVariableRoutes() {
    assertThatThrownBy(
            () ->
                assertNoRouteWithPathAndMethod(
                    gatewayProperties(route(List.of("/api/design/**"), "POST")),
                    "/api/design/assets",
                    "POST"))
        .isInstanceOf(AssertionError.class);

    assertThatThrownBy(
            () ->
                assertNoRouteWithPathAndMethod(
                    gatewayProperties(route(List.of("/api/design/{resource}"), "POST")),
                    "/api/design/assets",
                    "POST"))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void assertNoRouteWithPathAndMethodTreatsMissingMethodAsMatchingAndOtherMethodAsNonmatching() {
    assertThatThrownBy(
            () ->
                assertNoRouteWithPathAndMethod(
                    gatewayProperties(route(List.of("/api/design/**"), null)),
                    "/api/design/assets",
                    "POST"))
        .isInstanceOf(AssertionError.class);

    assertThatCode(
            () ->
                assertNoRouteWithPathAndMethod(
                    gatewayProperties(route(List.of("/api/design/**"), "GET")),
                    "/api/design/assets",
                    "POST"))
        .doesNotThrowAnyException();

    assertThatCode(
            () ->
                assertNoRouteWithPathAndMethod(
                    gatewayProperties(route(List.of("/api/other/**"), "POST")),
                    "/api/design/assets",
                    "POST"))
        .doesNotThrowAnyException();
  }

  @Test
  void assertNoRouteWithPathAndMethodChecksEveryConfiguredPathPattern() {
    RouteDefinition route = route(List.of("/api/unrelated/**", "/api/design/{resource}"), "POST");

    assertThatThrownBy(
            () ->
                assertNoRouteWithPathAndMethod(
                    gatewayProperties(route), "/api/design/assets", "POST"))
        .isInstanceOf(AssertionError.class);
  }

  private static GatewayProperties gatewayProperties(RouteDefinition... routes) {
    GatewayProperties properties = new GatewayProperties();
    properties.setRoutes(List.of(routes));
    return properties;
  }

  private static RouteDefinition route(List<String> paths, String method) {
    RouteDefinition route = new RouteDefinition();
    List<PredicateDefinition> predicates = new java.util.ArrayList<>();
    PredicateDefinition pathPredicate = new PredicateDefinition();
    pathPredicate.setName("Path");
    LinkedHashMap<String, String> pathPatterns = new LinkedHashMap<>();
    for (int index = 0; index < paths.size(); index++) {
      pathPatterns.put("_genkey_" + index, paths.get(index));
    }
    pathPredicate.setArgs(pathPatterns);
    predicates.add(pathPredicate);
    if (method != null) {
      predicates.add(new PredicateDefinition("Method=" + method));
    }
    route.setPredicates(predicates);
    return route;
  }
}
