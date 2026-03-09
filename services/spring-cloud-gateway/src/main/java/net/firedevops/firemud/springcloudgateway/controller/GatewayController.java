package net.firedevops.firemud.springcloudgateway.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.springcloudgateway.service.GatewayRoute;
import net.firedevops.firemud.springcloudgateway.service.GatewayRouteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST API for dynamic route management. */
@RestController
@RequestMapping("/routes")
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
  public ResponseEntity<ApiResponse<GatewayRoute>> upsert(@RequestBody GatewayRoute route) {
    GatewayRoute saved = routeService.upsert(route);
    return ResponseEntity.ok(ApiResponse.success(saved));
  }

  /** Remove a gateway route by ID. */
  @DeleteMapping("/{routeId}")
  public ResponseEntity<ApiResponse<String>> remove(@PathVariable String routeId) {
    boolean removed = routeService.remove(routeId);
    if (removed) {
      return ResponseEntity.ok(ApiResponse.success("removed"));
    }
    return ResponseEntity.ok(ApiResponse.success("notFound"));
  }
}
