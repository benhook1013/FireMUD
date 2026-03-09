package net.firedevops.firemud.automationscripting.service;

import net.firedevops.firemud.automationscripting.dto.ScriptDefinitionDto;
import net.firedevops.firemud.common.saga.SagaException;

public interface ScriptDefinitionService {
  ScriptDefinitionDto updateScript(ScriptDefinitionDto dto) throws SagaException;
}
