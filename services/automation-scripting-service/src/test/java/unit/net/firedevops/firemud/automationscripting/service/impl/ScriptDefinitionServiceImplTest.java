package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
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

  @Test
  void updateScriptReplacesChangedDefinitionForExistingStableIdentity() throws SagaException {
    ScriptDefinition existing = script(5L, "test", "v1", "{\"original\":true}");
    existing.setRowVersion(9);
    when(repository.findByTenantIdAndScriptVersionAndName(1L, "v1", "test"))
        .thenReturn(java.util.Optional.of(existing));
    when(repository.findById(5L)).thenReturn(java.util.Optional.of(existing));
    ScriptDefinition saved = script(5L, "test", "v1", "{\"changed\":true}");
    saved.setRowVersion(10);
    when(repository.save(any(ScriptDefinition.class))).thenReturn(saved);
    ScriptDefinitionDto dto =
        new ScriptDefinitionDto(5L, 1L, "test", "v1", "{\"changed\":true}", List.of());

    ScriptDefinitionDto result = service.updateScript(dto);

    assertEquals(5L, result.id());
    ArgumentCaptor<ScriptDefinition> savedEntity = ArgumentCaptor.forClass(ScriptDefinition.class);
    verify(repository).save(savedEntity.capture());
    assertEquals(9, savedEntity.getValue().getRowVersion());
  }

  @Test
  void updateScriptExplicitIdIdenticalRetryReturnsCurrentDurableVersion() throws SagaException {
    ScriptDefinition existing = script(5L, "test", "v1", "{\"original\":true}");
    existing.setRowVersion(9);
    when(repository.findByTenantIdAndScriptVersionAndName(1L, "v1", "test"))
        .thenReturn(java.util.Optional.of(existing));
    when(repository.findById(5L)).thenReturn(java.util.Optional.of(existing));
    when(repository.save(any(ScriptDefinition.class))).thenReturn(existing);
    ScriptDefinitionDto dto =
        new ScriptDefinitionDto(5L, 1L, "test", "v1", "{\"original\":true}", List.of());

    ScriptDefinitionDto result = service.updateScript(dto);

    assertEquals(5L, result.id());
    ArgumentCaptor<ScriptDefinition> savedEntity = ArgumentCaptor.forClass(ScriptDefinition.class);
    verify(repository).save(savedEntity.capture());
    assertEquals(9, savedEntity.getValue().getRowVersion());
  }

  @Test
  void updateScriptRejectsExistingIdIdentityMutationBeforePersistence() {
    ScriptDefinition existing = script(5L, "test", "v1", "{\"original\":true}");
    when(repository.findById(5L)).thenReturn(java.util.Optional.of(existing));
    ScriptDefinitionDto dto =
        new ScriptDefinitionDto(5L, 1L, "renamed", "v1", "{\"replacement\":true}", List.of());

    assertThatThrownBy(() -> service.updateScript(dto))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("SCRIPT_DEFINITION_CONFLICT: ");

    verify(repository, never()).save(any(ScriptDefinition.class));
    verifyNoBindingWrites();
  }

  @Test
  void bindingFailureDeletesOnlyTheNewlySavedDefinition() {
    ScriptDefinition saved = script(5L, "test", "v1", "{}");
    when(repository.save(any(ScriptDefinition.class))).thenReturn(saved);
    doThrow(new IllegalStateException("binding write failed"))
        .when(bindingRepository)
        .saveAll(any());
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

    assertThatThrownBy(() -> service.updateScript(dto)).isInstanceOf(SagaException.class);

    verify(repository).delete(saved);
    verify(repository, times(1)).save(any(ScriptDefinition.class));
  }

  @Test
  void bindingFailureDoesNotDeleteDurableWinnerOnSameDefinitionRetry() {
    ScriptDefinition existing = script(5L, "test", "v1", "{}");
    when(repository.findByTenantIdAndScriptVersionAndName(1L, "v1", "test"))
        .thenReturn(java.util.Optional.of(existing));
    when(repository.save(any(ScriptDefinition.class))).thenReturn(existing);
    doThrow(new IllegalStateException("binding write failed"))
        .when(bindingRepository)
        .saveAll(any());
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

    assertThatThrownBy(() -> service.updateScript(dto)).isInstanceOf(SagaException.class);

    verify(repository, never()).delete(any(ScriptDefinition.class));
    verify(repository, times(1)).save(any(ScriptDefinition.class));
  }

  @Test
  void bindingFailureRestoresBindingIdAlongsideTheBinding() {
    ScriptDefinition existing = script(5L, "test", "v1", "{}");
    when(repository.findByTenantIdAndScriptVersionAndName(1L, "v1", "test"))
        .thenReturn(java.util.Optional.of(existing));
    when(repository.findById(5L)).thenReturn(java.util.Optional.of(existing));
    when(repository.save(any(ScriptDefinition.class))).thenReturn(existing);

    ScriptEventBinding previousBinding = new ScriptEventBinding();
    previousBinding.setTenantId(1L);
    previousBinding.setScriptPatchVersion("v1");
    previousBinding.setEventType("onCommand");
    previousBinding.setEventSchemaVersion("v1");
    previousBinding.setScriptId("test");
    previousBinding.setBindingId("binding-original");
    previousBinding.setTargetScopeType("ACTION_TAG");
    previousBinding.setTargetScopeId("COMMUNICATION");
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L, "v1"))
        .thenReturn(List.of(previousBinding));

    AtomicInteger saveAllCalls = new AtomicInteger();
    AtomicReference<Collection<ScriptEventBinding>> restoredBindings = new AtomicReference<>();
    doAnswer(
            invocation -> {
              Collection<ScriptEventBinding> bindings = invocation.getArgument(0);
              if (saveAllCalls.getAndIncrement() == 0) {
                throw new IllegalStateException("binding write failed");
              }
              restoredBindings.set(bindings);
              return bindings.stream().toList();
            })
        .when(bindingRepository)
        .saveAll(any());
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
                    "binding-replacement")));

    assertThatThrownBy(() -> service.updateScript(dto)).isInstanceOf(SagaException.class);

    assertEquals(2, saveAllCalls.get());
    assertNotNull(restoredBindings.get());
    assertEquals("binding-original", restoredBindings.get().iterator().next().getBindingId());
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

  private static ScriptDefinition script(Long id, String name, String version, String definition) {
    ScriptDefinition script = new ScriptDefinition();
    script.setId(id);
    script.setTenantId(1L);
    script.setName(name);
    script.setScriptVersion(version);
    script.setDefinition(definition);
    return script;
  }

  private void verifyNoBindingWrites() {
    verify(bindingRepository, never())
        .deleteByTenantIdAndScriptPatchVersionAndScriptId(any(), any(), any());
    verify(bindingRepository, never()).saveAll(any());
  }
}
