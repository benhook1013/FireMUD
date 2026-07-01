package net.firedevops.firemud.loggingadmin.service;

import net.firedevops.firemud.loggingadmin.dto.RemoteCommandCoordinatorDto;

public interface RemoteCommandCoordinatorService {
  RemoteCommandCoordinatorDto getRemoteCommandCoordinator(long tenantId, String coordinatorId);
}
