package net.firedevops.firemud.loggingadmin.service;

import net.firedevops.firemud.loggingadmin.dto.CreateReportRequest;
import net.firedevops.firemud.loggingadmin.dto.ReportDto;

public interface ReportService {
  ReportDto createReport(CreateReportRequest request);
}
