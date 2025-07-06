package net.firedevops.firemud.controller;

import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.dto.CreateReportRequest;
import net.firedevops.firemud.dto.ReportDto;
import net.firedevops.firemud.service.ReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reports")
public class ReportController {
  private final ReportService reportService;

  public ReportController(ReportService reportService) {
    this.reportService = reportService;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<ReportDto>> createReport(
      @Valid @RequestBody CreateReportRequest request) {
    ReportDto dto = reportService.createReport(request);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }
}
