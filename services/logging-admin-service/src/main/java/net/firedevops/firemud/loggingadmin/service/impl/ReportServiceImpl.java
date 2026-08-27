package net.firedevops.firemud.loggingadmin.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.loggingadmin.dto.CreateReportRequest;
import net.firedevops.firemud.loggingadmin.dto.ReportDto;
import net.firedevops.firemud.loggingadmin.mapper.ReportMapper;
import net.firedevops.firemud.loggingadmin.repository.PlayerReportRepository;
import net.firedevops.firemud.loggingadmin.service.ReportService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring injects the shared repository singleton for this service seam.")
public class ReportServiceImpl implements ReportService {
  private final PlayerReportRepository reportRepository;
  private final ReportMapper reportMapper;

  public ReportServiceImpl(PlayerReportRepository reportRepository, ReportMapper reportMapper) {
    this.reportRepository = reportRepository;
    this.reportMapper = reportMapper;
  }

  @Override
  @Transactional
  @Timed(value = "report.create")
  public ReportDto createReport(CreateReportRequest request) {
    throw new UnsupportedOperationException(
        "Report creation is unavailable until the shared mutation gate is implemented");
  }
}
