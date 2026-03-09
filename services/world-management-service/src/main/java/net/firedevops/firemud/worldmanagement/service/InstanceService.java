package net.firedevops.firemud.worldmanagement.service;

import net.firedevops.firemud.worldmanagement.dto.InstanceDto;

public interface InstanceService {
  InstanceDto createInstance(InstanceDto request);

  void cleanupExpiredInstances();
}
