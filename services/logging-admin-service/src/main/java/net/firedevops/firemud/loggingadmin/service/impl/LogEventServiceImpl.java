package net.firedevops.firemud.loggingadmin.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.loggingadmin.dto.CreateLogEventRequest;
import net.firedevops.firemud.loggingadmin.dto.LogEventDto;
import net.firedevops.firemud.loggingadmin.entity.LogEvent;
import net.firedevops.firemud.loggingadmin.repository.LogEventRepository;
import net.firedevops.firemud.loggingadmin.service.LogEventService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring injects the shared repository singleton for this service seam.")
public class LogEventServiceImpl implements LogEventService {
  private static final Logger logger = LoggingUtil.getLogger(LogEventServiceImpl.class);

  private final LogEventRepository repository;

  public LogEventServiceImpl(LogEventRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional
  @Timed(value = "logging.createLogEvent")
  public LogEventDto createLogEvent(CreateLogEventRequest request) {
    logger.info("Creating log event {} for tenant {}", request.type(), request.tenantId());
    LogEvent entity = new LogEvent();
    entity.setTenantId(request.tenantId());
    entity.setAccountId(request.accountId());
    entity.setType(request.type());
    entity.setMessage(request.message());
    entity.setTimestamp(Instant.now());
    entity = repository.save(entity);
    return new LogEventDto(
        entity.getId(),
        entity.getTenantId(),
        entity.getAccountId(),
        entity.getType(),
        entity.getMessage(),
        entity.getTimestamp());
  }
}
