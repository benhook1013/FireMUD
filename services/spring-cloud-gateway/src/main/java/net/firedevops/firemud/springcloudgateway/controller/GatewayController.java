package net.firedevops.firemud.springcloudgateway.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.springcloudgateway.service.GatewayRoute;
import net.firedevops.firemud.springcloudgateway.service.GatewayRouteService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** REST API for dynamic route management. */
@RestController
@RequestMapping("/routes")
@ConditionalOnProperty(
    prefix = "firemud.gateway.dynamic-routes",
    name = "enabled",
    havingValue = "true")
public class GatewayController {
  private final GatewayRouteService routeService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "RouteService is injected and not exposed externally")
  public GatewayController(GatewayRouteService routeService) {
    this.routeService = routeService;
  }

  /** Add or update a gateway route. */
  @PostMapping
  public Mono<ResponseEntity<ApiResponse<GatewayRoute>>> upsert(@RequestBody GatewayRoute route) {
    return routeService.upsert(route).map(saved -> ResponseEntity.ok(ApiResponse.success(saved)));
  }

  /** Remove a gateway route by ID. */
  @DeleteMapping("/{routeId}")
  public Mono<ResponseEntity<ApiResponse<String>>> remove(@PathVariable String routeId) {
    return routeService
        .remove(routeId)
        .map(
            removed ->
                removed
                    ? ResponseEntity.ok(ApiResponse.success("removed"))
                    : ResponseEntity.ok(ApiResponse.success("notFound")));
  }
}
