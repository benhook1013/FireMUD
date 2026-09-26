package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Consumer;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import net.firedevops.firemud.automationscripting.service.ScriptEventIngressService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchReadinessProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleDefinitionService;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleInstanceService;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;

class ScriptPatchVersionCommandServiceTest {
  private ScriptDefinitionRepository repository;
  private ScriptScheduleDefinitionService scheduleDefinitionService;
  private ScriptScheduleInstanceService scheduleInstanceService;
  private ScriptEventIngressService scriptEventIngressService;
  private ScriptPatchReadinessProjectionService readinessProjectionService;
  private ScriptPatchVersionCommandService service;

  @BeforeEach
  void setup() {
    repository = mock(ScriptDefinitionRepository.class);
    scheduleDefinitionService = mock(ScriptScheduleDefinitionService.class);
    scheduleInstanceService = mock(ScriptScheduleInstanceService.class);
    scriptEventIngressService = mock(ScriptEventIngressService.class);
    readinessProjectionService = mock(ScriptPatchReadinessProjectionService.class);
    when(readinessProjectionService.beginPatchReadiness(any(), any(), any())).thenReturn(true);
    when(readinessProjectionService.applyIfCurrent(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              runDownstream(invocation, true);
              return true;
            });
    when(readinessProjectionService.rebuildRegistryIfCurrent(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              runRegistryRebuild(invocation);
              return true;
            });
    service =
        new ScriptPatchVersionCommandService(
            repository,
            scheduleDefinitionService,
            scheduleInstanceService,
            scriptEventIngressService,
            readinessProjectionService);
  }

  @Test
  void notifyUpdateRejectsZeroTenantIdBeforeLookups() {
    assertThatThrownBy(() -> service.notifyUpdate("0", "v1-script.1", List.of("npc-barkeep")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenantId must be positive");

    verifyNoInteractions(
        repository,
        scheduleDefinitionService,
        scheduleInstanceService,
        scriptEventIngressService,
        readinessProjectionService);
  }

  @Test
  void notifyUpdateUsesCanonicalScriptSetAndRefreshesSchedules() {
    ScriptDefinition barkeep = definition("npc-barkeep");
    ScriptDefinition guard = definition("npc-guard");
    when(repository.findByTenantIdAndScriptVersionAndNameIn(
            1L, "v1-script.1", List.of("npc-barkeep", "npc-guard")))
        .thenReturn(List.of(guard, barkeep));

    assertThat(service.notifyUpdate("1", "v1-script.1", List.of("npc-guard", "npc-barkeep")))
        .isTrue();

    verify(readinessProjectionService)
        .beginPatchReadiness("1", "v1-script.1", List.of("npc-barkeep", "npc-guard"));
    verify(scheduleDefinitionService)
        .refreshPatchSchedules(
            "1", "v1-script.1", List.of(barkeep, guard), List.of("npc-barkeep", "npc-guard"));
    verify(scheduleInstanceService).reconcilePinnedPatchInstances("1", "v1-script.1");
    verify(readinessProjectionService)
        .rebuildRegistryIfCurrent(
            eq("1"), eq("v1-script.1"), eq(List.of("npc-barkeep", "npc-guard")), any());

    ArgumentCaptor<TriggerScriptEventRequest> requestCaptor =
        ArgumentCaptor.forClass(TriggerScriptEventRequest.class);
    verify(scriptEventIngressService, times(2))
        .admit(requestCaptor.capture(), eq("automation-scripting-service"));
    assertThat(requestCaptor.getAllValues())
        .extracting(TriggerScriptEventRequest::getScriptEventId)
        .containsExactly("onload:1:v1-script.1:npc-barkeep", "onload:1:v1-script.1:npc-guard");
  }

  @Test
  void notifyUpdateRejectsDuplicateScriptNamesBeforeReadinessAdmission() {
    assertThatThrownBy(
            () -> service.notifyUpdate("1", "v1-script.1", List.of("npc-barkeep", "npc-barkeep")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exactly one definition per unique requested name");

    verifyNoInteractions(
        repository,
        scheduleDefinitionService,
        scheduleInstanceService,
        scriptEventIngressService,
        readinessProjectionService);
  }

  @Test
  void notifyUpdateRejectsMissingDefinitionBeforeReadinessAdmission() {
    when(repository.findByTenantIdAndScriptVersionAndNameIn(
            1L, "v1-script.1", List.of("npc-barkeep")))
        .thenReturn(List.of());

    assertThatThrownBy(() -> service.notifyUpdate("1", "v1-script.1", List.of("npc-barkeep")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exactly one definition per unique requested name");

    verifyNoInteractions(
        scheduleDefinitionService,
        scheduleInstanceService,
        scriptEventIngressService,
        readinessProjectionService);
  }

  @Test
  void activeRetryReplaysIdempotentOnLoadAndDatabaseDownstreamWork() {
    ScriptDefinition definition = definition("npc-barkeep");
    stubDefinitions(definition);
    when(readinessProjectionService.beginPatchReadiness("1", "v1-script.1", List.of("npc-barkeep")))
        .thenReturn(false);

    assertThat(service.notifyUpdate("1", "v1-script.1", List.of("npc-barkeep"))).isTrue();

    verify(readinessProjectionService)
        .applyIfCurrent(eq("1"), eq("v1-script.1"), eq(List.of("npc-barkeep")), any());
    verify(scriptEventIngressService)
        .admit(any(TriggerScriptEventRequest.class), eq("automation-scripting-service"));
    verify(scheduleDefinitionService)
        .refreshPatchSchedules("1", "v1-script.1", List.of(definition), List.of("npc-barkeep"));
  }

  @Test
  void readyRetryRepairsSchedulesAndRegistryWithoutReadmittingOnLoad() {
    ScriptDefinition definition = definition("npc-barkeep");
    stubDefinitions(definition);
    when(readinessProjectionService.beginPatchReadiness("1", "v1-script.1", List.of("npc-barkeep")))
        .thenReturn(false);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              runDownstream(invocation, false);
              return true;
            })
        .when(readinessProjectionService)
        .applyIfCurrent(eq("1"), eq("v1-script.1"), eq(List.of("npc-barkeep")), any());

    assertThat(service.notifyUpdate("1", "v1-script.1", List.of("npc-barkeep"))).isTrue();

    verify(scheduleDefinitionService)
        .refreshPatchSchedules("1", "v1-script.1", List.of(definition), List.of("npc-barkeep"));
    verify(scheduleInstanceService).reconcilePinnedPatchInstances("1", "v1-script.1");
    verify(readinessProjectionService)
        .rebuildRegistryIfCurrent(eq("1"), eq("v1-script.1"), eq(List.of("npc-barkeep")), any());
    verifyNoInteractions(scriptEventIngressService);
  }

  @Test
  void skipsAllDownstreamWorkWhenANewerGenerationIsCurrent() {
    stubDefinitions(definition("npc-barkeep"));
    when(readinessProjectionService.beginPatchReadiness("1", "v1-script.1", List.of("npc-barkeep")))
        .thenReturn(false);
    org.mockito.Mockito.doReturn(false)
        .when(readinessProjectionService)
        .applyIfCurrent(eq("1"), eq("v1-script.1"), eq(List.of("npc-barkeep")), any());

    assertThat(service.notifyUpdate("1", "v1-script.1", List.of("npc-barkeep"))).isFalse();

    verifyNoInteractions(
        scheduleDefinitionService, scheduleInstanceService, scriptEventIngressService);
    verify(readinessProjectionService, org.mockito.Mockito.never())
        .rebuildRegistryIfCurrent(any(), any(), any(), any());
  }

  @Test
  void reportsSkippedRegistryRebuildWhenANewerGenerationWinsAfterApply() {
    ScriptDefinition definition = definition("npc-barkeep");
    stubDefinitions(definition);
    org.mockito.Mockito.doReturn(false)
        .when(readinessProjectionService)
        .rebuildRegistryIfCurrent(eq("1"), eq("v1-script.1"), eq(List.of("npc-barkeep")), any());

    assertThat(service.notifyUpdate("1", "v1-script.1", List.of("npc-barkeep"))).isFalse();

    verify(readinessProjectionService)
        .applyIfCurrent(eq("1"), eq("v1-script.1"), eq(List.of("npc-barkeep")), any());
    verify(scheduleDefinitionService)
        .refreshPatchSchedules("1", "v1-script.1", List.of(definition), List.of("npc-barkeep"));
    verify(readinessProjectionService)
        .rebuildRegistryIfCurrent(eq("1"), eq("v1-script.1"), eq(List.of("npc-barkeep")), any());
  }

  @Test
  void downstreamFailureDoesNotRebuildProcessLocalRegistry() {
    ScriptDefinition definition = definition("npc-barkeep");
    stubDefinitions(definition);
    org.mockito.Mockito.doThrow(new IllegalStateException("schedule_reconcile_failed"))
        .when(scheduleInstanceService)
        .reconcilePinnedPatchInstances("1", "v1-script.1");

    assertThatThrownBy(() -> service.notifyUpdate("1", "v1-script.1", List.of("npc-barkeep")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("schedule_reconcile_failed");

    verify(scriptEventIngressService)
        .admit(any(TriggerScriptEventRequest.class), eq("automation-scripting-service"));
    verify(readinessProjectionService, org.mockito.Mockito.never())
        .rebuildRegistryIfCurrent(any(), any(), any(), any());
  }

  private void stubDefinitions(ScriptDefinition definition) {
    when(repository.findByTenantIdAndScriptVersionAndNameIn(
            1L, "v1-script.1", List.of(definition.getName())))
        .thenReturn(List.of(definition));
  }

  private static ScriptDefinition definition(String name) {
    ScriptDefinition definition = new ScriptDefinition();
    definition.setTenantId(1L);
    definition.setName(name);
    definition.setDefinition("{}");
    return definition;
  }

  private static void runDownstream(InvocationOnMock invocation, boolean admitOnLoad) {
    Consumer<Boolean> work = invocation.getArgument(3);
    work.accept(admitOnLoad);
  }

  private static void runRegistryRebuild(InvocationOnMock invocation) {
    Runnable rebuild = invocation.getArgument(3);
    rebuild.run();
  }
}
