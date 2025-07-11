package net.firedevops.firemud.service;

import java.util.List;
import net.firedevops.firemud.dto.QueryLogsRequest;

public interface LogQueryService {
  List<String> queryLogs(QueryLogsRequest request);
}
