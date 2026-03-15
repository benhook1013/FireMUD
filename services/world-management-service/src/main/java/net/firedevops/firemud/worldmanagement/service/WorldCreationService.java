package net.firedevops.firemud.worldmanagement.service;

import net.firedevops.firemud.common.saga.SagaException;

/** Creates a new world from published design data using a Saga workflow. */
public interface WorldCreationService {
  void createWorld(Long tenantId, Long versionId) throws SagaException;
}
