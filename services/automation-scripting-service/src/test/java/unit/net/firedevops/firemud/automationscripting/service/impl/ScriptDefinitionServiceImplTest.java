package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.automationscripting.dto.ScriptDefinitionDto;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;
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
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
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
    assertEquals(5L, result.id());
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
                    "onCommand", "v1", "ACTION_TAG", "COMMUNICATION", 0, "normal", false)));

    ScriptDefinitionDto result = service.updateScript(dto);

    assertNotNull(result);
    verify(bindingRepository).saveAll(any());
  }

  @Test
  void failedBindingReplacementRestoresExistingDefinitionAndDoesNotDeleteIt() {
    ScriptDefinition previous = new ScriptDefinition();
    previous.setId(5L);
    previous.setTenantId(1L);
    previous.setName("test");
    previous.setScriptVersion("v1");
    previous.setDefinition("{\"original\":true}");
    previous.setRowVersion(3);
    when(repository.findByTenantIdAndScriptVersionAndName(1L, "v1", "test"))
        .thenReturn(java.util.Optional.of(previous));
    ScriptEventBinding previousBinding = new ScriptEventBinding();
    previousBinding.setId(9L);
    previousBinding.setTenantId(1L);
    previousBinding.setScriptPatchVersion("v1");
    previousBinding.setScriptId("test");
    previousBinding.setEventType("onCommand");
    previousBinding.setEventSchemaVersion("v1");
    previousBinding.setTargetScopeType("ACTION_TAG");
    previousBinding.setTargetScopeId("COMMUNICATION");
    previousBinding.setRowVersion(13);
    when(bindingRepository.findByTenantIdAndScriptPatchVersionAndScriptId(1L, "v1", "test"))
        .thenReturn(List.of(previousBinding));

    ScriptDefinition persisted = new ScriptDefinition();
    persisted.setId(5L);
    persisted.setRowVersion(4);
    persisted.setTenantId(1L);
    persisted.setName("test");
    persisted.setScriptVersion("v1");
    persisted.setDefinition("{\"replacement\":true}");
    when(repository.save(any(ScriptDefinition.class)))
        .thenReturn(persisted)
        .thenAnswer(invocation -> invocation.getArgument(0));
    Mockito.doThrow(new IllegalStateException("binding write failed"))
        .doAnswer(invocation -> invocation.getArgument(0))
        .when(bindingRepository)
        .saveAll(any());

    ScriptDefinitionDto dto =
        new ScriptDefinitionDto(
            null,
            1L,
            "test",
            "v1",
            "{\"replacement\":true}",
            List.of(
                new ScriptDefinitionDto.EventBindingDto(
                    "onCommand", "v1", "ACTION_TAG", "COMMUNICATION", 0, "normal", false)));

    assertThatThrownBy(() -> service.updateScript(dto)).isInstanceOf(SagaException.class);

    verify(repository, never()).delete(any(ScriptDefinition.class));
    ArgumentCaptor<ScriptDefinition> definitionCaptor =
        ArgumentCaptor.forClass(ScriptDefinition.class);
    verify(repository, times(2)).save(definitionCaptor.capture());
    ScriptDefinition restored = definitionCaptor.getAllValues().get(1);
    assertEquals("{\"original\":true}", restored.getDefinition());
    assertEquals(5L, restored.getId());
    assertEquals(4, restored.getRowVersion());
    ArgumentCaptor<ScriptEventBinding> bindingCaptor =
        ArgumentCaptor.forClass(ScriptEventBinding.class);
    verify(bindingRepository).restoreWithId(bindingCaptor.capture());
    assertEquals(9L, bindingCaptor.getValue().getId());
    assertEquals(13, bindingCaptor.getValue().getRowVersion());
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
                    "onUnknown", "v1", "GLOBAL", "", 0, "normal", false)));

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
                    "onSpawn", "v1", "COMMAND_ALIAS", "look", 0, "normal", false)));

    assertThatThrownBy(() -> service.updateScript(dto))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unsupported binding scope COMMAND_ALIAS for onSpawn@v1");
  }
}
