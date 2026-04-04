package net.firedevops.firemud.springcloudgateway.service;

import reactor.core.publisher.Mono;

/** Service for managing dynamic gateway routes. */
public interface GatewayRouteService {
  Mono<GatewayRoute> upsert(GatewayRoute route);

  /**
   * Remove a route by ID.
   *
   * @param routeId identifier of the route to remove
   * @return true if the route existed and was removed
   */
  Mono<Boolean> remove(String routeId);
}
