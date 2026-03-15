package net.firedevops.firemud.loggingadmin.service;

import java.util.List;
import net.firedevops.firemud.loggingadmin.dto.QueryLogsRequest;

public interface LogQueryService {
  List<String> queryLogs(QueryLogsRequest request);
}
