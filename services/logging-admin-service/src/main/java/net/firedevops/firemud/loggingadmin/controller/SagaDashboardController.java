package net.firedevops.firemud.loggingadmin.controller;

import io.micrometer.core.annotation.Timed;
import java.util.List;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.loggingadmin.dto.SagaInstanceDto;
import net.firedevops.firemud.loggingadmin.dto.SagaStepDto;
import net.firedevops.firemud.loggingadmin.service.SagaDashboardService;
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
  @Timed(value = "listSagas", description = "List saga instances")
  public ResponseEntity<ApiResponse<List<SagaInstanceDto>>> listInstances() {
    return ResponseEntity.ok(ApiResponse.success(sagaDashboardService.listInstances()));
  }

  @GetMapping("/{id}/steps")
  @Timed(value = "listSagaSteps", description = "List saga steps for instance")
  public ResponseEntity<ApiResponse<List<SagaStepDto>>> listSteps(@PathVariable Long id) {
    return ResponseEntity.ok(ApiResponse.success(sagaDashboardService.listSteps(id)));
  }
}
