package net.firedevops.firemud.loggingadmin.controller;

import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import java.util.List;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.dto.QueryLogsRequest;
import net.firedevops.firemud.loggingadmin.service.LogQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/logs")
public class LogQueryController {
  private final LogQueryService service;

  public LogQueryController(LogQueryService service) {
    this.service = service;
  }

  @PostMapping("/query")
  @Timed(value = "queryLogs", description = "Query log events")
  public ResponseEntity<ApiResponse<List<String>>> query(
      @Valid @RequestBody QueryLogsRequest request) {
    SessionContext.requireTenantAccess(request.tenantId());
    return ResponseEntity.ok(ApiResponse.success(service.queryLogs(request)));
  }
}
