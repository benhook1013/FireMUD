package net.firedevops.firemud.controller;

import java.util.List;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.dto.SagaInstanceDto;
import net.firedevops.firemud.dto.SagaStepDto;
import net.firedevops.firemud.service.SagaDashboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sagas")
public class SagaDashboardController {
  private final SagaDashboardService sagaDashboardService;

  public SagaDashboardController(SagaDashboardService sagaDashboardService) {
    this.sagaDashboardService = sagaDashboardService;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<List<SagaInstanceDto>>> listInstances() {
    return ResponseEntity.ok(ApiResponse.success(sagaDashboardService.listInstances()));
  }

  @GetMapping("/{id}/steps")
  public ResponseEntity<ApiResponse<List<SagaStepDto>>> listSteps(@PathVariable Long id) {
    return ResponseEntity.ok(ApiResponse.success(sagaDashboardService.listSteps(id)));
  }
}
