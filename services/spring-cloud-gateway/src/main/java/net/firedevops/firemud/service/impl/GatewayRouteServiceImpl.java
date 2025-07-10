package net.firedevops.firemud.service.impl;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.service.GatewayRoute;
import net.firedevops.firemud.service.GatewayRouteService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

/**
 * In-memory implementation of {@link GatewayRouteService}.
 *
 * <p>This placeholder stores routes locally until full integration with Spring Cloud Gateway's
 * route APIs is implemented.
 */
@Service
public class GatewayRouteServiceImpl implements GatewayRouteService {
  private static final Logger logger = LoggingUtil.getLogger(GatewayRouteServiceImpl.class);
  private final ConcurrentMap<String, GatewayRoute> routes = new ConcurrentHashMap<>();

  @Override
  public GatewayRoute upsert(GatewayRoute route) {
    routes.put(route.routeId(), route);
    logger.info("Upserted route {} -> {}", route.routeId(), route.uri());
    return route;
  }

  @Override
  public boolean remove(String routeId) {
    boolean existed = routes.remove(routeId) != null;
    if (existed) {
      logger.info("Removed route {}", routeId);
    } else {
      logger.info("Route {} not found", routeId);
    }
    return existed;
  }
}
