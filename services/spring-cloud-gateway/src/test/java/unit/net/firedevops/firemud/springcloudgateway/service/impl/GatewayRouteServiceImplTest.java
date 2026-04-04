package net.firedevops.firemud.springcloudgateway.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.firedevops.firemud.springcloudgateway.service.GatewayRoute;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.InMemoryRouteDefinitionRepository;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class GatewayRouteServiceImplTest {

  @Test
  void upsertAndRemoveRoute() {
    InMemoryRouteDefinitionRepository repo = new InMemoryRouteDefinitionRepository();
    GatewayRouteServiceImpl service = new GatewayRouteServiceImpl(repo, e -> {});
    GatewayRoute route =
        new GatewayRoute("test", "http://example.com", List.of("Path=/foo"), List.of());

    StepVerifier.create(service.upsert(route)).expectNext(route).verifyComplete();
    assertEquals(1, Flux.from(repo.getRouteDefinitions()).collectList().block().size());

    StepVerifier.create(service.remove("test")).expectNext(true).verifyComplete();
    assertEquals(0, Flux.from(repo.getRouteDefinitions()).collectList().block().size());
  }
}
