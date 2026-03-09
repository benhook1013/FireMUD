package net.firedevops.firemud.springcloudgateway.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.springcloudgateway.service.GatewayRoute;
import net.firedevops.firemud.springcloudgateway.service.GatewayRouteService;
import org.slf4j.Logger;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Implementation of {@link GatewayRouteService} that updates Spring Cloud Gateway at runtime. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected writer and publisher references are not exposed")
@Service
public class GatewayRouteServiceImpl implements GatewayRouteService {
  private static final Logger logger = LoggingUtil.getLogger(GatewayRouteServiceImpl.class);

  private final RouteDefinitionWriter writer;
  private final ApplicationEventPublisher publisher;
  private final ConcurrentMap<String, GatewayRoute> routes = new ConcurrentHashMap<>();

  public GatewayRouteServiceImpl(
      RouteDefinitionWriter writer, ApplicationEventPublisher publisher) {
    this.writer = writer;
    this.publisher = publisher;
  }

  @Override
  @Timed(value = "gateway.route.upsert")
  public GatewayRoute upsert(GatewayRoute route) {
    routes.put(route.routeId(), route);

    RouteDefinition definition = new RouteDefinition();
    definition.setId(route.routeId());
    definition.setUri(URI.create(route.uri()));
    if (route.predicates() != null) {
      definition.setPredicates(route.predicates().stream().map(PredicateDefinition::new).toList());
    }
    if (route.filters() != null) {
      definition.setFilters(route.filters().stream().map(FilterDefinition::new).toList());
    }

    writer.save(Mono.just(definition)).block();
    publisher.publishEvent(new RefreshRoutesEvent(this));

    logger.info("Upserted route {} -> {}", route.routeId(), route.uri());
    return route;
  }

  @Override
  @Timed(value = "gateway.route.remove")
  public boolean remove(String routeId) {
    boolean existed = routes.remove(routeId) != null;
    writer.delete(Mono.just(routeId)).onErrorResume(e -> Mono.empty()).block();
    publisher.publishEvent(new RefreshRoutesEvent(this));

    if (existed) {
      logger.info("Removed route {}", routeId);
    } else {
      logger.info("Route {} not found", routeId);
    }
    return existed;
  }
}
