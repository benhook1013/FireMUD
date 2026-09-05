package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import net.firedevops.firemud.automationscripting.service.ScriptEventRegistryService;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.common.saga.persistence.SagaInstance;
import net.firedevops.firemud.common.saga.persistence.SagaInstanceRepository;
import net.firedevops.firemud.common.saga.persistence.SagaStep;
import net.firedevops.firemud.common.saga.persistence.SagaStepRepository;
import net.firedevops.firemud.metrics.SagaMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;

class ScriptDefinitionServiceImplTest {
  private ScriptDefinitionRepository repository;
  private ScriptEventBindingRepository bindingRepository;
  private SagaRunner sagaRunner;
  private ScriptEventRegistryService eventRegistryService;
  private ScriptDefinitionServiceImpl service;

  @BeforeEach
  void setup() {
    repository = Mockito.mock(ScriptDefinitionRepository.class);
    bindingRepository = Mockito.mock(ScriptEventBindingRepository.class);
    eventRegistryService = new BuiltInScriptEventRegistryService();
    SagaMetrics sagaMetrics = Mockito.mock(SagaMetrics.class);
    SagaInstanceRepository instanceRepository = Mockito.mock(SagaInstanceRepository.class);
    SagaStepRepository stepRepository = Mockito.mock(SagaStepRepository.class);
    when(instanceRepository.save(any(SagaInstance.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(stepRepository.save(any(SagaStep.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    sagaRunner = new SagaRunner(sagaMetrics, instanceRepository, stepRepository);
    ScriptDefinitionMapper mapper = Mappers.getMapper(ScriptDefinitionMapper.class);
    service =
        new ScriptDefinitionServiceImpl(
            repository, bindingRepository, mapper, sagaRunner, eventRegistryService);
  }

  @Test
  void updateScriptPersistsEntity() throws SagaException {
    ScriptDefinition saved = new ScriptDefinition();
    saved.setId(5L);
    when(repository.save(any(ScriptDefinition.class))).thenReturn(saved);
    ScriptDefinitionDto dto = new ScriptDefinitionDto(null, 1L, "test", "v1", "{}", List.of());

    ScriptDefinitionDto result = service.updateScript(dto);

    assertNotNull(result);
    verify(repository).save(any(ScriptDefinition.class));
  }

  @Test
  void updateScriptAllowsOnCommandActionTagBinding() throws SagaException {
    ScriptDefinition saved = new ScriptDefinition();
    saved.setId(6L);
    when(repository.save(any(ScriptDefinition.class))).thenReturn(saved);
    ScriptDefinitionDto dto =
        new ScriptDefinitionDto(
            null,
            1L,
            "test",
            "v1",
            "{}",
            List.of(
                new ScriptDefinitionDto.EventBindingDto(
                    "onCommand",
                    "v1",
                    "ACTION_TAG",
                    "COMMUNICATION",
                    0,
                    "normal",
                    false,
                    "binding-communication")));

    ScriptDefinitionDto result = service.updateScript(dto);

    assertNotNull(result);
    verify(bindingRepository).saveAll(any());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "  ", "\t"})
  void updateScriptRejectsBlankBindingId(String bindingId) {
    ScriptDefinitionDto dto =
        new ScriptDefinitionDto(
            null,
            1L,
            "test",
            "v1",
            "{}",
            List.of(
                new ScriptDefinitionDto.EventBindingDto(
                    "onCommand",
                    "v1",
                    "ACTION_TAG",
                    "COMMUNICATION",
                    0,
                    "normal",
                    false,
                    bindingId)));

    assertThatThrownBy(() -> service.updateScript(dto))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("binding id is required");
  }

  @Test
  void updateScriptRejectsUnknownBuiltInEventBinding() {
    ScriptDefinitionDto dto =
        new ScriptDefinitionDto(
            null,
            1L,
            "test",
            "v1",
            "{}",
            List.of(
                new ScriptDefinitionDto.EventBindingDto(
                    "onUnknown", "v1", "GLOBAL", "", 0, "normal", false, "binding-unknown")));

    assertThatThrownBy(() -> service.updateScript(dto))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unknown built-in event binding: onUnknown@v1");
  }

  @Test
  void updateScriptRejectsBindingScopeNotAllowedByRegistry() {
    ScriptDefinitionDto dto =
        new ScriptDefinitionDto(
            null,
            1L,
            "test",
            "v1",
            "{}",
            List.of(
                new ScriptDefinitionDto.EventBindingDto(
                    "onSpawn", "v1", "COMMAND_ALIAS", "look", 0, "normal", false, "binding-look")));

    assertThatThrownBy(() -> service.updateScript(dto))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unsupported binding scope COMMAND_ALIAS for onSpawn@v1");
  }

  @Test
  void updateScriptRejectsDuplicateNormalizedBindingIdsAcrossScopes() {
    ScriptDefinitionDto dto =
        new ScriptDefinitionDto(
            null,
            1L,
            "test",
            "v1",
            "{}",
            List.of(
                new ScriptDefinitionDto.EventBindingDto(
                    "onCommand",
                    "v1",
                    "ACTION_TAG",
                    "COMMUNICATION",
                    0,
                    "normal",
                    false,
                    "binding-command"),
                new ScriptDefinitionDto.EventBindingDto(
                    "onCommand", "v1", "GLOBAL", "", 0, "normal", false, " binding-command ")));

    assertThatThrownBy(() -> service.updateScript(dto))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("duplicate binding id: binding-command");
  }
}
