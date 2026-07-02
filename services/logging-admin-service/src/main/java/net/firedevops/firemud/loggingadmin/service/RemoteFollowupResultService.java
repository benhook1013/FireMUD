package net.firedevops.firemud.loggingadmin.service;

import java.util.List;
import net.firedevops.firemud.loggingadmin.dto.RemoteFollowupResultDto;
import net.firedevops.firemud.loggingadmin.dto.RemoteFollowupResultListRequest;

public interface RemoteFollowupResultService {
  RemoteFollowupResultDto getRemoteFollowupResult(long tenantId, String resultId);

  List<RemoteFollowupResultDto> listRemoteFollowupResults(
      long tenantId, RemoteFollowupResultListRequest request);
}
