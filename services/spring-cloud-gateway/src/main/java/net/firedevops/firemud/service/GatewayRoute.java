package net.firedevops.firemud.service;

import java.util.List;

/** Simple DTO representing a dynamic gateway route. */
public record GatewayRoute(
    String routeId, String uri, List<String> predicates, List<String> filters) {}
