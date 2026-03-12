package net.firedevops.firemud.automationscripting.service.impl;

import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.automationscripting.dto.ScriptDefinitionDto;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.mapper.ScriptDefinitionMapper;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import net.firedevops.firemud.automationscripting.service.ScriptDefinitionService;
import net.firedevops.firemud.common.saga.SagaBuilder;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.common.saga.SagaRunner;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ScriptDefinitionServiceImpl implements ScriptDefinitionService {
  private final ScriptDefinitionRepository repository;
  private final ScriptDefinitionMapper mapper;
  @Nullable private final SagaRunner sagaRunner;

  private void runSaga(net.firedevops.firemud.common.saga.Saga saga) throws SagaException {
    if (sagaRunner == null) {
      saga.run();
      return;
    }
    sagaRunner.run(saga);
  }

  @Override
  @Transactional
  @Timed(value = "script.update")
  public ScriptDefinitionDto updateScript(ScriptDefinitionDto dto) throws SagaException {
    ScriptDefinition entity = mapper.toEntity(dto);
    var saga =
        new SagaBuilder("updateScript")
            .step("persistScript", () -> repository.save(entity), () -> repository.delete(entity))
            .build();
    runSaga(saga);
    return mapper.toDto(entity);
  }
}
