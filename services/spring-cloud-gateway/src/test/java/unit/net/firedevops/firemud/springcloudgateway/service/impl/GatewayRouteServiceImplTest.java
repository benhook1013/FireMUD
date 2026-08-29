package net.firedevops.firemud.springcloudgateway.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
    AtomicBoolean saveInvoked = new AtomicBoolean();
    AtomicBoolean deleteInvoked = new AtomicBoolean();
    RouteDefinitionWriter writer =
        new RouteDefinitionWriter() {
          @Override
          public Mono<Void> save(
              Mono<org.springframework.cloud.gateway.route.RouteDefinition> route) {
            saveInvoked.set(true);
            return Mono.empty();
          }

          @Override
          public Mono<Void> delete(Mono<String> routeId) {
            deleteInvoked.set(true);
            return Mono.empty();
          }
        };
    GatewayRouteServiceImpl service =
        new GatewayRouteServiceImpl(
            writer, e -> {}, new DynamicRouteMutationPolicy(false, new MockEnvironment()));
    GatewayRoute route =
        new GatewayRoute("test", "http://example.com", List.of("Path=/foo"), List.of());

    StepVerifier.create(service.upsert(route))
        .expectErrorMatches(
            error ->
                error instanceof IllegalStateException && error.getMessage().contains("disabled"))
        .verify();
    assertFalse(saveInvoked.get());

    StepVerifier.create(service.remove("test"))
        .expectErrorMatches(
            error ->
                error instanceof IllegalStateException
                    && error.getMessage().contains("disabled"))
        .verify();
    assertFalse(saveInvoked.get());
    assertFalse(deleteInvoked.get());
  }

  @Test
  void prodProfileRejectsEnabledMutationAtStartup() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");

    IllegalStateException error = assertEnabledPolicyRejected(environment);

    assertEquals(
        "Dynamic gateway route mutation requires an explicitly active dev or test profile",
        error.getMessage());
  }

  @Test
  void stagingProfileRejectsEnabledMutationAtStartup() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("staging");

    assertEnabledPolicyRejected(environment);
  }

  @Test
  void customProfileRejectsEnabledMutationAtStartup() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("custom");

    assertEnabledPolicyRejected(environment);
  }

  @Test
  void mixedSupportedAndUnsupportedProfilesRejectEnabledMutationAtStartup() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("dev", "staging");

    assertEnabledPolicyRejected(environment);
  }

  @Test
  void noActiveProfileRejectsEnabledMutationAtStartup() {
    assertEnabledPolicyRejected(new MockEnvironment());
  }

  @Test
  void testProfileAllowsEnabledMutation() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("test");

    assertDoesNotThrow(() -> new DynamicRouteMutationPolicy(true, environment));
  }

  @Test
  void devProfileAllowsEnabledMutation() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("dev");

    assertDoesNotThrow(() -> new DynamicRouteMutationPolicy(true, environment));
  }

  private static GatewayRouteServiceImpl enabledService(RouteDefinitionWriter writer) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("test");
    return new GatewayRouteServiceImpl(
        writer, e -> {}, new DynamicRouteMutationPolicy(true, environment));
  }

  private static IllegalStateException assertEnabledPolicyRejected(MockEnvironment environment) {
    return assertThrows(
        IllegalStateException.class, () -> new DynamicRouteMutationPolicy(true, environment));
  }
}
