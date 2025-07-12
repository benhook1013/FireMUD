package net.firedevops.firemud.service;

import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.dto.ScriptDefinitionDto;

public interface ScriptDefinitionService {
  ScriptDefinitionDto updateScript(ScriptDefinitionDto dto) throws SagaException;
}
