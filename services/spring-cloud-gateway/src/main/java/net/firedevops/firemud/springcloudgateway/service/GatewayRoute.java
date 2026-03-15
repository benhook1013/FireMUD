package net.firedevops.firemud.springcloudgateway.service;

import java.util.List;

/** Simple DTO representing a dynamic gateway route. */
public record GatewayRoute(
    String routeId, String uri, List<String> predicates, List<String> filters) {
  /**
   * Canonical constructor that defensively copies predicate and filter lists to avoid exposing
   * internal mutable state.
   */
  public GatewayRoute {
    predicates = predicates != null ? List.copyOf(predicates) : null;
    filters = filters != null ? List.copyOf(filters) : null;
  }
}
