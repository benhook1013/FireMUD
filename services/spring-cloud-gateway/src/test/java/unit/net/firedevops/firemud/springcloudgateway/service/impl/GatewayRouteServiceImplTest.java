package net.firedevops.firemud.springcloudgateway.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.firedevops.firemud.springcloudgateway.service.GatewayRoute;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.InMemoryRouteDefinitionRepository;
import reactor.core.publisher.Flux;

class GatewayRouteServiceImplTest {

  @Test
  void upsertAndRemoveRoute() {
    InMemoryRouteDefinitionRepository repo = new InMemoryRouteDefinitionRepository();
    GatewayRouteServiceImpl service = new GatewayRouteServiceImpl(repo, e -> {});
    GatewayRoute route =
        new GatewayRoute("test", "http://example.com", List.of("Path=/foo"), List.of());

    service.upsert(route);
    assertEquals(1, Flux.from(repo.getRouteDefinitions()).collectList().block().size());

    boolean removed = service.remove("test");
    assertEquals(true, removed);
    assertEquals(0, Flux.from(repo.getRouteDefinitions()).collectList().block().size());
  }
}
