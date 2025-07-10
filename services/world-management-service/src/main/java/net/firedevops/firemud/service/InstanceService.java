package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.InstanceDto;

public interface InstanceService {
  InstanceDto createInstance(InstanceDto request);

  void cleanupExpiredInstances();
}
