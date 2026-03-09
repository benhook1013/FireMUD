package net.firedevops.firemud.springcloudgateway.service;

/** Service for managing dynamic gateway routes. */
public interface GatewayRouteService {
  GatewayRoute upsert(GatewayRoute route);

  /**
   * Remove a route by ID.
   *
   * @param routeId identifier of the route to remove
   * @return true if the route existed and was removed
   */
  boolean remove(String routeId);
}
