package net.firedevops.firemud.loggingadmin.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.loggingadmin.dto.CreateReportRequest;
import net.firedevops.firemud.loggingadmin.dto.ReportDto;
import net.firedevops.firemud.loggingadmin.entity.PlayerReport;
import net.firedevops.firemud.loggingadmin.mapper.ReportMapper;
import net.firedevops.firemud.loggingadmin.repository.PlayerReportRepository;
import net.firedevops.firemud.loggingadmin.service.ReportService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring injects the shared repository singleton for this service seam.")
public class ReportServiceImpl implements ReportService {
  private static final Logger logger = LoggingUtil.getLogger(ReportServiceImpl.class);

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
    logger.info("Creating player report by {}", request.reporterAccountId());
    PlayerReport entity = new PlayerReport();
    entity.setTenantId(request.tenantId());
    entity.setReporterAccountId(request.reporterAccountId());
    entity.setTargetAccountId(request.targetAccountId());
    entity.setType(request.type());
    entity.setDescription(request.description());
    entity.setCreatedAt(Instant.now());
    entity = reportRepository.save(entity);
    return reportMapper.toDto(entity);
  }
}
