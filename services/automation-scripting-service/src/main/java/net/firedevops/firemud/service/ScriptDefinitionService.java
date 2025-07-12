package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.ScriptDefinitionDto;
import net.firedevops.firemud.common.saga.SagaException;

public interface ScriptDefinitionService {
  ScriptDefinitionDto updateScript(ScriptDefinitionDto dto) throws SagaException;
}
