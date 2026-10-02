package net.firedevops.firemud.loggingadmin.service;

import net.firedevops.firemud.loggingadmin.dto.CreateLogEventRequest;
import net.firedevops.firemud.loggingadmin.dto.LogEventDto;

public interface LogEventService {
  LogEventDto createLogEvent(CreateLogEventRequest request);
}
