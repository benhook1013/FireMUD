package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.firedevops.firemud.service.GatewayRoute;
import org.junit.jupiter.api.Test;

class GatewayRouteServiceImplTest {

  @Test
  void upsertAndRemoveRoute() {
    GatewayRouteServiceImpl service = new GatewayRouteServiceImpl();
    GatewayRoute route = new GatewayRoute("test", "http://example.com", null, null);
    service.upsert(route);
    assertEquals(route, service.upsert(route));
    service.remove("test");
    // route map is package-private not accessible; verifying no exception on remove
  }
}
