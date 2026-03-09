package net.firedevops.firemud.automationscripting.service;

import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.automationscripting.dto.ScriptDefinitionDto;

public interface ScriptDefinitionService {
  ScriptDefinitionDto updateScript(ScriptDefinitionDto dto) throws SagaException;
}
