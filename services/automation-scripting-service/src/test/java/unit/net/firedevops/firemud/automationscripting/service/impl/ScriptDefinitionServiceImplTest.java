package net.firedevops.firemud.automationscripting.service.impl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.automationscripting.dto.ScriptDefinitionDto;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.mapper.ScriptDefinitionMapper;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventBindingRepository;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.common.saga.SagaRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;

class ScriptDefinitionServiceImplTest {
  private ScriptDefinitionRepository repository;
  private ScriptEventBindingRepository bindingRepository;
  private SagaRunner sagaRunner;
  private ScriptDefinitionServiceImpl service;

  @BeforeEach
  void setup() {
    repository = Mockito.mock(ScriptDefinitionRepository.class);
    bindingRepository = Mockito.mock(ScriptEventBindingRepository.class);
    sagaRunner = Mockito.mock(SagaRunner.class);
    ScriptDefinitionMapper mapper = Mappers.getMapper(ScriptDefinitionMapper.class);
    service = new ScriptDefinitionServiceImpl(repository, bindingRepository, mapper, sagaRunner);
  }

  @Test
  void updateScriptPersistsEntity() throws SagaException {
    ScriptDefinition saved = new ScriptDefinition();
    saved.setId(5L);
    when(repository.save(any(ScriptDefinition.class))).thenReturn(saved);
    ScriptDefinitionDto dto = new ScriptDefinitionDto(null, 1L, "test", "v1", "{}", List.of());

    ScriptDefinitionDto result = service.updateScript(dto);

    assertNotNull(result);
    verify(sagaRunner).run(any(net.firedevops.firemud.common.saga.Saga.class));
  }
}
