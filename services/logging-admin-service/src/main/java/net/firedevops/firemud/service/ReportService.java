package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.CreateReportRequest;
import net.firedevops.firemud.dto.ReportDto;

public interface ReportService {
  ReportDto createReport(CreateReportRequest request);
}
