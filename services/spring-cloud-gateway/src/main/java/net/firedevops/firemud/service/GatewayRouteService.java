package net.firedevops.firemud.service;

/** Service for managing dynamic gateway routes. */
public interface GatewayRouteService {
  GatewayRoute upsert(GatewayRoute route);

  void remove(String routeId);
}
