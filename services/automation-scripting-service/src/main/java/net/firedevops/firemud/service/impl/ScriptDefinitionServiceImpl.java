package net.firedevops.firemud.service.impl;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.saga.SagaBuilder;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.dto.ScriptDefinitionDto;
import net.firedevops.firemud.entity.ScriptDefinition;
import net.firedevops.firemud.mapper.ScriptDefinitionMapper;
import net.firedevops.firemud.repository.ScriptDefinitionRepository;
import net.firedevops.firemud.service.ScriptDefinitionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ScriptDefinitionServiceImpl implements ScriptDefinitionService {
  private final ScriptDefinitionRepository repository;
  private final ScriptDefinitionMapper mapper;
  private final SagaRunner sagaRunner;

  @Override
  @Transactional
  public ScriptDefinitionDto updateScript(ScriptDefinitionDto dto) throws SagaException {
    ScriptDefinition entity = mapper.toEntity(dto);
    var saga =
        new SagaBuilder("updateScript")
            .step("persistScript", () -> repository.save(entity), () -> repository.delete(entity))
            .build();
    sagaRunner.run(saga);
    return mapper.toDto(entity);
  }
}
