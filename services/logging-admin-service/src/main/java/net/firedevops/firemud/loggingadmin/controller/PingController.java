package net.firedevops.firemud.loggingadmin.controller;

import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.loggingadmin.service.PingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ping")
public class PingController {
  private final PingService pingService;

  public PingController(PingService pingService) {
    this.pingService = pingService;
  }

  @GetMapping
  @Timed(value = "ping", description = "Time spent responding to ping requests")
  public ResponseEntity<ApiResponse<String>> ping() {
    String result = pingService.ping();
    return ResponseEntity.ok(ApiResponse.success(result));
  }
}
