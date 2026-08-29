package net.firedevops.firemud.springcloudgateway.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import net.firedevops.firemud.springcloudgateway.service.GatewayRoute;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.InMemoryRouteDefinitionRepository;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GatewayRouteServiceImplTest {

  @Test
  void upsertAndRemoveRoute() {
    InMemoryRouteDefinitionRepository repo = new InMemoryRouteDefinitionRepository();
    GatewayRouteServiceImpl service = enabledService(repo);
    GatewayRoute route =
        new GatewayRoute("test", "http://example.com", List.of("Path=/foo"), List.of());

    StepVerifier.create(service.upsert(route)).expectNext(route).verifyComplete();
    assertEquals(1, Flux.from(repo.getRouteDefinitions()).collectList().block().size());

    StepVerifier.create(service.remove("test")).expectNext(true).verifyComplete();
    assertEquals(0, Flux.from(repo.getRouteDefinitions()).collectList().block().size());
  }

  @Test
  void removeMissingRouteReturnsFalse() {
    InMemoryRouteDefinitionRepository repo = new InMemoryRouteDefinitionRepository();
    GatewayRouteServiceImpl service = enabledService(repo);

    StepVerifier.create(service.remove("missing")).expectNext(false).verifyComplete();
  }

  @Test
  void removePropagatesWriterFailures() {
    RouteDefinitionWriter writer =
        new RouteDefinitionWriter() {
          @Override
          public Mono<Void> save(
              Mono<org.springframework.cloud.gateway.route.RouteDefinition> route) {
            return Mono.empty();
          }

          @Override
          public Mono<Void> delete(Mono<String> routeId) {
            return Mono.error(new IllegalStateException("boom"));
          }
        };
    GatewayRouteServiceImpl service = enabledService(writer);

    StepVerifier.create(service.remove("test"))
        .expectErrorSatisfies(error -> assertEquals("boom", error.getMessage()))
        .verify();
  }

  @Test
  void disabledMutationDoesNotWriteRoute() {
    InMemoryRouteDefinitionRepository repo = new InMemoryRouteDefinitionRepository();
    GatewayRouteServiceImpl service =
        new GatewayRouteServiceImpl(
            repo, e -> {}, new DynamicRouteMutationPolicy(false, new MockEnvironment()));
    GatewayRoute route =
        new GatewayRoute("test", "http://example.com", List.of("Path=/foo"), List.of());

    StepVerifier.create(service.upsert(route))
        .expectErrorMatches(
            error ->
                error instanceof IllegalStateException && error.getMessage().contains("disabled"))
        .verify();
    assertEquals(0, Flux.from(repo.getRouteDefinitions()).collectList().block().size());

    StepVerifier.create(service.remove("test"))
        .expectErrorMatches(
            error ->
                error instanceof IllegalStateException
                    && error.getMessage().contains("disabled"))
        .verify();
  }

  @Test
  void prodProfileRejectsEnabledMutationAtStartup() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> new DynamicRouteMutationPolicy(true, environment));

    assertEquals(
        "Dynamic gateway route mutation must not be enabled with the prod profile",
        error.getMessage());
  }

  private static GatewayRouteServiceImpl enabledService(RouteDefinitionWriter writer) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("test");
    return new GatewayRouteServiceImpl(
        writer, e -> {}, new DynamicRouteMutationPolicy(true, environment));
  }
}
