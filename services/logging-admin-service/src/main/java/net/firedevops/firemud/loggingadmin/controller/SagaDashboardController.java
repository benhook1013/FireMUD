package net.firedevops.firemud.loggingadmin.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.util.List;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.ErrorDetail;
import net.firedevops.firemud.loggingadmin.dto.SagaInstanceDto;
import net.firedevops.firemud.loggingadmin.dto.SagaStepDto;
import net.firedevops.firemud.loggingadmin.service.SagaDashboardService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sagas")
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification =
        "Spring supplies ObjectProvider and controller methods fail closed when the optional saga dashboard service is absent.")
public class SagaDashboardController {
  private final SagaDashboardService sagaDashboardService;

  public SagaDashboardController(ObjectProvider<SagaDashboardService> sagaDashboardService) {
    this.sagaDashboardService = sagaDashboardService.getIfAvailable();
  }

  @GetMapping
  @Timed(value = "listSagas", description = "List saga instances")
  public ResponseEntity<ApiResponse<List<SagaInstanceDto>>> listInstances() {
    if (sagaDashboardService == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(
              ApiResponse.error(
                  new ErrorDetail("SAGA_DASHBOARD_UNAVAILABLE", "Saga dashboard is unavailable")));
    }
    return ResponseEntity.ok(ApiResponse.success(sagaDashboardService.listInstances()));
  }

  @GetMapping("/{id}/steps")
  @Timed(value = "listSagaSteps", description = "List saga steps for instance")
  public ResponseEntity<ApiResponse<List<SagaStepDto>>> listSteps(@PathVariable Long id) {
    if (sagaDashboardService == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(
              ApiResponse.error(
                  new ErrorDetail("SAGA_DASHBOARD_UNAVAILABLE", "Saga dashboard is unavailable")));
    }
    return ResponseEntity.ok(ApiResponse.success(sagaDashboardService.listSteps(id)));
  }
}
