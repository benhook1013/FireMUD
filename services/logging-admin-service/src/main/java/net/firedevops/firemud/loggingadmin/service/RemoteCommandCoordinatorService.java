package net.firedevops.firemud.loggingadmin.service;

import java.util.List;
import net.firedevops.firemud.loggingadmin.dto.RemoteCommandCoordinatorDto;
import net.firedevops.firemud.loggingadmin.dto.RemoteCommandCoordinatorListRequest;

public interface RemoteCommandCoordinatorService {
  RemoteCommandCoordinatorDto getRemoteCommandCoordinator(long tenantId, String coordinatorId);

  List<RemoteCommandCoordinatorDto> listRemoteCommandCoordinators(
      long tenantId, RemoteCommandCoordinatorListRequest request);
}
