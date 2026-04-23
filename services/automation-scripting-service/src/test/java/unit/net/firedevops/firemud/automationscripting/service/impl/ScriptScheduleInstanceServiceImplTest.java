package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchPinProjection;
import net.firedevops.firemud.automationscripting.entity.ScriptScheduleDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptScheduleInstance;
import net.firedevops.firemud.automationscripting.repository.ScriptPatchPinProjectionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptScheduleDefinitionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptScheduleInstanceRepository;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleInstanceService;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScriptScheduleInstanceServiceImplTest {
  private ScriptScheduleDefinitionRepository scheduleDefinitionRepository;
  private ScriptScheduleInstanceRepository scheduleInstanceRepository;
  private ScriptPatchPinProjectionRepository pinProjectionRepository;
  private ScriptScheduleInstanceService service;

  @BeforeEach
  void setup() {
    scheduleDefinitionRepository = mock(ScriptScheduleDefinitionRepository.class);
    scheduleInstanceRepository = mock(ScriptScheduleInstanceRepository.class);
    pinProjectionRepository = mock(ScriptPatchPinProjectionRepository.class);
    service =
        new ScriptScheduleInstanceServiceImpl(
            scheduleDefinitionRepository, scheduleInstanceRepository, pinProjectionRepository);
  }

  @Test
  void reconcileObservedRuntimeStateMaterializesMillisecondAndTickSchedules() {
    when(scheduleDefinitionRepository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(millisecondsDefinition(), tickDefinition()));
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1"))
        .thenReturn(List.of());

    service.reconcileObservedRuntimeState(
        "1",
        "game-1",
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPinnedScriptPatchVersion("patch-1")
            .setRuntimeVersionId("runtime-v2")
            .setScriptPatchPinnedControlPlaneRequestId("req-1")
            .setScriptPatchPinnedAtMs(1_000L)
            .build());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ScriptScheduleInstance>> captor = ArgumentCaptor.forClass(List.class);
    verify(scheduleInstanceRepository).saveAll(captor.capture());
    List<ScriptScheduleInstance> saved = captor.getValue();
    assertThat(saved).hasSize(2);
    assertThat(saved)
        .filteredOn(instance -> instance.getScheduleDefinitionId().equals("guard.alert.expire.v1"))
        .singleElement()
        .satisfies(
            instance -> {
              assertThat(instance.getMaterializationStatus()).isEqualTo("READY");
              assertThat(instance.getNextDueAt()).isEqualTo(Instant.ofEpochMilli(6_000L));
              assertThat(instance.getObservedRuntimeVersionId()).isEqualTo("runtime-v2");
              assertThat(instance.getLastObservedControlPlaneRequestId()).isEqualTo("req-1");
            });
    assertThat(saved)
        .filteredOn(instance -> instance.getScheduleDefinitionId().equals("guard.patrol.v1"))
        .singleElement()
        .satisfies(
            instance -> {
              assertThat(instance.getMaterializationStatus()).isEqualTo("PENDING_RUNTIME_PROGRESS");
              assertThat(instance.getNextDueAt()).isNull();
              assertThat(instance.getNextDueTickId()).isNull();
            });
  }

  @Test
  void reconcilePinnedPatchInstancesReusesObservedPins() {
    ScriptPatchPinProjection projection = new ScriptPatchPinProjection();
    projection.setTenantId("1");
    projection.setGameInstanceId("game-1");
    projection.setObservedPinnedScriptPatchVersion("patch-1");
    projection.setLastObservedControlPlaneRequestId("req-3");
    projection.setObservedAt(Instant.ofEpochMilli(3_000L));
    when(pinProjectionRepository.findByTenantIdAndObservedPinnedScriptPatchVersion("1", "patch-1"))
        .thenReturn(List.of(projection));
    when(scheduleDefinitionRepository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(millisecondsDefinition()));
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1"))
        .thenReturn(List.of());

    service.reconcilePinnedPatchInstances("1", "patch-1");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ScriptScheduleInstance>> captor = ArgumentCaptor.forClass(List.class);
    verify(scheduleInstanceRepository).saveAll(captor.capture());
    assertThat(captor.getValue())
        .singleElement()
        .satisfies(
            instance ->
                assertThat(instance.getPinObservedAt()).isEqualTo(Instant.ofEpochMilli(3_000L)));
  }

  private static ScriptScheduleDefinition millisecondsDefinition() {
    ScriptScheduleDefinition definition = new ScriptScheduleDefinition();
    definition.setTenantId(1L);
    definition.setScriptPatchVersion("patch-1");
    definition.setScriptId("npc-guard");
    definition.setEventType("onTimerExpire");
    definition.setScheduleDefinitionId("guard.alert.expire.v1");
    definition.setScheduleKind("TIMER");
    definition.setCadenceUnit("MILLISECONDS");
    definition.setCadenceValue(5_000L);
    definition.setPriorityTag("normal");
    definition.setScheduleMetadataJson("{\"scheduleDefinitionId\":\"guard.alert.expire.v1\"}");
    definition.setScheduleSemanticsHash("hash-ms");
    return definition;
  }

  private static ScriptScheduleDefinition tickDefinition() {
    ScriptScheduleDefinition definition = new ScriptScheduleDefinition();
    definition.setTenantId(1L);
    definition.setScriptPatchVersion("patch-1");
    definition.setScriptId("npc-guard");
    definition.setEventType("onInterval");
    definition.setScheduleDefinitionId("guard.patrol.v1");
    definition.setScheduleKind("INTERVAL");
    definition.setCadenceUnit("TICKS");
    definition.setCadenceValue(30L);
    definition.setPriorityTag("high");
    definition.setScheduleMetadataJson("{\"scheduleDefinitionId\":\"guard.patrol.v1\"}");
    definition.setScheduleSemanticsHash("hash-ticks");
    return definition;
  }
}
