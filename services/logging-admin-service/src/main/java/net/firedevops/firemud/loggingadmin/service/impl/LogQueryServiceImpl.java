package net.firedevops.firemud.loggingadmin.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.List;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.loggingadmin.dto.QueryLogsRequest;
import net.firedevops.firemud.loggingadmin.entity.LogEvent;
import net.firedevops.firemud.loggingadmin.repository.LogEventRepository;
import net.firedevops.firemud.loggingadmin.service.LogQueryService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LogQueryServiceImpl implements LogQueryService {
  private static final Logger logger = LoggingUtil.getLogger(LogQueryServiceImpl.class);

  private final LogEventRepository repository;

  public LogQueryServiceImpl(LogEventRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "logging.queryLogs")
  public List<String> queryLogs(QueryLogsRequest request) {
    logger.info("Querying logs for tenant {}", request.tenantId());
    List<LogEvent> events =
        repository.findByTenantIdAndMessageContainingIgnoreCase(
            request.tenantId(), request.filter() == null ? "" : request.filter());
    return events.stream().map(LogEvent::getMessage).toList();
  }
}
