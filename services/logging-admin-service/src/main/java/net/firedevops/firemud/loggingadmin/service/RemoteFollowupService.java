package net.firedevops.firemud.loggingadmin.service;

import java.util.List;
import net.firedevops.firemud.loggingadmin.dto.RemoteFollowupDto;
import net.firedevops.firemud.loggingadmin.dto.RemoteFollowupListRequest;

public interface RemoteFollowupService {
  RemoteFollowupDto getRemoteFollowup(long tenantId, String followupId);

  List<RemoteFollowupDto> listRemoteFollowups(long tenantId, RemoteFollowupListRequest request);
}
