package net.firedevops.firemud.springcloudgateway.config;

import static net.firedevops.firemud.springcloudgateway.config.GatewayRouteTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.handler.predicate.MethodRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.http.HttpMethod;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class GatewayAuthRoutesTest {
  private record Endpoint(String id, String path, HttpMethod method) {}

  private static final List<Endpoint> ENDPOINTS =
      List.of(
          new Endpoint("login", "login", HttpMethod.POST),
          new Endpoint("player-bootstrap", "player-bootstrap", HttpMethod.POST),
          new Endpoint("bootstrap-worlds", "bootstrap/worlds", HttpMethod.GET),
          new Endpoint("bootstrap-realms", "bootstrap/worlds/{worldSlug}/realms", HttpMethod.GET),
          new Endpoint(
              "bootstrap-characters",
              "bootstrap/worlds/{worldSlug}/realms/{realmSlug}/characters",
              HttpMethod.GET),
          new Endpoint("bootstrap-join", "bootstrap/join", HttpMethod.POST),
          new Endpoint("connect-token", "connect-token", HttpMethod.POST),
          new Endpoint("request-password-reset", "request-password-reset", HttpMethod.POST),
          new Endpoint("complete-password-reset", "complete-password-reset", HttpMethod.POST),
          new Endpoint("request-email-verification", "request-email-verification", HttpMethod.POST),
          new Endpoint("verify-email", "verify-email", HttpMethod.POST),
          new Endpoint("recover-username", "recover-username", HttpMethod.POST));

  static void assertCatalog(GatewayProperties properties) {
    properties.getRoutes().forEach(route -> assertThat(route.getId()).isNotNull());
    assertThat(
            properties.getRoutes().stream()
                .filter(
                    r -> java.util.Objects.requireNonNull(r.getId()).startsWith("account-auth")))
        .hasSize(ENDPOINTS.size());
    assertNoConfiguredPath(properties, "/api/account/auth/**");
    assertNoConfiguredPathStartsWith(properties, "/verify-email");
    assertNoConfiguredPathStartsWith(properties, "/reset-password");
    for (Endpoint endpoint : ENDPOINTS) {
      String id = "account-auth-" + endpoint.id();
      assertHasPath(properties, id, "/api/account/auth/" + endpoint.path());
      assertHasMethod(properties, id, endpoint.method().name());
      assertHasStripPrefixTwo(properties, id);
      assertThat(route(properties, id).getUri().toString())
          .isEqualTo("http://account-service:8080");
    }
  }

  @Test
  void actualPredicatesAdmitOnlyDeclaredAccountAuthMethodsAndPaths() {
    GatewayProperties properties =
        new CanonicalGatewayRoutesConfiguration().canonicalGatewayProperties(new MockEnvironment());
    assertCatalog(properties);
    for (Endpoint endpoint : ENDPOINTS) {
      String path =
          "/api/account/auth/"
              + endpoint.path().replace("{worldSlug}", "world").replace("{realmSlug}", "realm");
      for (HttpMethod method : HttpMethod.values()) {
        assertThat(matches(properties, path, method))
            .as("%s %s", method, path)
            .isEqualTo(method.equals(endpoint.method()));
      }
      assertThat(matches(properties, path + "/extra", endpoint.method())).isFalse();
    }
    for (String path :
        List.of(
            "/api/account/auth/internal/runtime",
            "/api/account/auth/unknown",
            "/api/account/auth",
            "/verify-email",
            "/reset-password")) {
      for (HttpMethod method : HttpMethod.values()) {
        assertThat(matches(properties, path, method)).as("%s %s", method, path).isFalse();
      }
    }
  }

  private boolean matches(GatewayProperties properties, String path, HttpMethod method) {
    for (RouteDefinition route : properties.getRoutes()) {
      var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, path).build());
      boolean matches =
          route.getPredicates().stream()
              .allMatch(
                  predicate -> {
                    if ("Path".equals(predicate.getName())) {
                      var config = new PathRoutePredicateFactory.Config();
                      config.setPatterns(List.copyOf(predicate.getArgs().values()));
                      return new PathRoutePredicateFactory(
                              new org.springframework.boot.webflux.autoconfigure
                                  .WebFluxProperties())
                          .apply(config)
                          .test(exchange);
                    }
                    if ("Method".equals(predicate.getName())) {
                      var config = new MethodRoutePredicateFactory.Config();
                      config.setMethods(
                          predicate.getArgs().values().stream()
                              .map(HttpMethod::valueOf)
                              .toArray(HttpMethod[]::new));
                      return new MethodRoutePredicateFactory().apply(config).test(exchange);
                    }
                    throw new AssertionError("Unexpected predicate " + predicate.getName());
                  });
      if (matches) return true;
    }
    return false;
  }
}
