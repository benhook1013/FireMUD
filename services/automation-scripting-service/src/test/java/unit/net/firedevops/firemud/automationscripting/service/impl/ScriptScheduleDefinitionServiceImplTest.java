package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptScheduleDefinition;
import net.firedevops.firemud.automationscripting.repository.ScriptScheduleDefinitionRepository;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleDefinitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ScriptScheduleDefinitionServiceImplTest {
  private ScriptScheduleDefinitionRepository repository;
  private ScriptScheduleDefinitionService service;

  @BeforeEach
  void setup() {
    repository = mock(ScriptScheduleDefinitionRepository.class);
    service = new ScriptScheduleDefinitionServiceImpl(repository, new ObjectMapper());
  }

  @Test
  void refreshPatchSchedulesPersistsIntervalAndTimerMetadata() {
    ScriptDefinition script = new ScriptDefinition();
    script.setTenantId(1L);
    script.setName("npc-guard");
    script.setDefinition(
        """
        {
          "eventHandlers": {
            "onInterval": {
              "scheduleDefinitionId": "guard.patrol.v1",
              "intervalTicks": 30,
              "priorityTag": "high"
            },
            "onTimerExpire": {
              "scheduleDefinitionId": "guard.alert.expire.v1",
              "delayTicks": 5
            }
          }
        }
        """);
    when(repository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of());

    service.refreshPatchSchedules("1", "patch-1", List.of(script), List.of("npc-guard"));

    @SuppressWarnings("unchecked")
    var captor = org.mockito.ArgumentCaptor.forClass(List.class);
    verify(repository).saveAll(captor.capture());
    @SuppressWarnings("unchecked")
    List<ScriptScheduleDefinition> saved = captor.getValue();
    assertThat(saved).hasSize(2);
    assertThat(saved)
        .extracting(ScriptScheduleDefinition::getScheduleDefinitionId)
        .containsExactlyInAnyOrder("guard.patrol.v1", "guard.alert.expire.v1");
    assertThat(saved)
        .filteredOn(schedule -> schedule.getScheduleDefinitionId().equals("guard.patrol.v1"))
        .singleElement()
        .satisfies(
            schedule -> {
              assertThat(schedule.getScheduleKind()).isEqualTo("INTERVAL");
              assertThat(schedule.getCadenceUnit()).isEqualTo("TICKS");
              assertThat(schedule.getCadenceValue()).isEqualTo(30L);
              assertThat(schedule.getPriorityTag()).isEqualTo("high");
            });
    assertThat(saved)
        .filteredOn(schedule -> schedule.getScheduleDefinitionId().equals("guard.alert.expire.v1"))
        .singleElement()
        .satisfies(
            schedule -> {
              assertThat(schedule.getScheduleKind()).isEqualTo("TIMER");
              assertThat(schedule.getCadenceUnit()).isEqualTo("TICKS");
              assertThat(schedule.getCadenceValue()).isEqualTo(5L);
              assertThat(schedule.getPriorityTag()).isEqualTo("normal");
            });
  }

  @Test
  void refreshPatchSchedulesRejectsDuplicateScheduleDefinitionIdsAcrossPatch() {
    ScriptScheduleDefinition existing = new ScriptScheduleDefinition();
    existing.setTenantId(1L);
    existing.setScriptPatchVersion("patch-1");
    existing.setScriptId("npc-existing");
    existing.setScheduleDefinitionId("shared.schedule.v1");
    existing.setPluginId("");
    existing.setPluginVersionId("");
    ScriptDefinition script = new ScriptDefinition();
    script.setTenantId(1L);
    script.setName("npc-guard");
    script.setDefinition(
        """
        {
          "eventHandlers": {
            "onInterval": {
              "scheduleDefinitionId": "shared.schedule.v1",
              "intervalTicks": 30
            }
          }
        }
        """);
    when(repository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(existing));

    assertThatThrownBy(
            () ->
                service.refreshPatchSchedules(
                    "1", "patch-1", List.of(script), List.of("npc-guard")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate_schedule_definition_id");
  }

  @Test
  void refreshPatchSchedulesRejectsUnsupportedScheduledEventType() {
    ScriptDefinition script = new ScriptDefinition();
    script.setTenantId(1L);
    script.setName("npc-guard");
    script.setDefinition(
        """
        {
          "eventHandlers": {
            "onSpawn": {
              "scheduleDefinitionId": "bad.schedule.v1",
              "intervalTicks": 30
            }
          }
        }
        """);
    when(repository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of());

    assertThatThrownBy(
            () ->
                service.refreshPatchSchedules(
                    "1", "patch-1", List.of(script), List.of("npc-guard")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported_scheduled_event_type");
  }
}
