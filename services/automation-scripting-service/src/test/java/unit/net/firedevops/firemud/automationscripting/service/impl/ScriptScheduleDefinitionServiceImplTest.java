package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Stream;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptScheduleDefinition;
import net.firedevops.firemud.automationscripting.repository.ScriptScheduleDefinitionRepository;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleDefinitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
  void refreshPatchSchedulesRejectsZeroTenantIdBeforeRepositoryReads() {
    assertThatThrownBy(
            () ->
                service.refreshPatchSchedules(
                    "0", "patch-1", List.of(new ScriptDefinition()), List.of("npc-guard")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenantId must be positive");

    verifyNoInteractions(repository);
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
              assertThat(schedule.getPluginId()).isEmpty();
              assertThat(schedule.getPluginVersionId()).isEmpty();
            });
  }

  @Test
  void refreshPatchSchedulesIgnoresOrdinaryHandlersAlongsideSchedules() {
    ScriptDefinition script = new ScriptDefinition();
    script.setTenantId(1L);
    script.setName("npc-guard");
    script.setDefinition(
        "{\"eventHandlers\":{"
            + "\"onCommand\":{\"commandText\":\"LOOK\"},"
            + "\"onInterval\":{\"scheduleDefinitionId\":\"guard.patrol.v1\",\"intervalTicks\":30}}}");
    when(repository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of());

    service.refreshPatchSchedules("1", "patch-1", List.of(script), List.of("npc-guard"));

    var captor = org.mockito.ArgumentCaptor.forClass(List.class);
    verify(repository).saveAll(captor.capture());
    @SuppressWarnings("unchecked")
    List<ScriptScheduleDefinition> saved = captor.getValue();
    assertThat(saved)
        .singleElement()
        .satisfies(schedule -> assertThat(schedule.getEventType()).isEqualTo("onInterval"));
  }

  @Test
  void refreshPatchSchedulesRetainsOrdinaryOnlyScriptsAsValidEmptyScheduleSet() {
    when(repository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of());
    ScriptDefinition script = new ScriptDefinition();
    script.setTenantId(1L);
    script.setName("npc-guard");
    script.setDefinition("{\"eventHandlers\":{\"onCommand\":{\"commandText\":\"LOOK\"}}}");

    service.refreshPatchSchedules("1", "patch-1", List.of(script), List.of("npc-guard"));

    verify(repository)
        .deleteByTenantIdAndScriptPatchVersionAndScriptIdIn(1L, "patch-1", List.of("npc-guard"));
    verify(repository, never()).saveAll(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void refreshPatchSchedulesPersistsPluginOwnerMetadata() {
    ScriptDefinition script = new ScriptDefinition();
    script.setTenantId(1L);
    script.setName("plugin-town-crier");
    script.setDefinition(
        """
        {
          "plugin": {
            "pluginId": "town-crier",
            "pluginVersionId": "town-crier-v3"
          },
          "eventHandlers": {
            "onInterval": {
              "scheduleDefinitionId": "town-crier.market.pulse.v1",
              "intervalTicks": 12
            }
          }
        }
        """);
    when(repository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of());

    service.refreshPatchSchedules("1", "patch-1", List.of(script), List.of("plugin-town-crier"));

    var captor = org.mockito.ArgumentCaptor.forClass(List.class);
    verify(repository).saveAll(captor.capture());
    @SuppressWarnings("unchecked")
    List<ScriptScheduleDefinition> saved = captor.getValue();
    assertThat(saved)
        .singleElement()
        .satisfies(
            schedule -> {
              assertThat(schedule.getPluginId()).isEqualTo("town-crier");
              assertThat(schedule.getPluginVersionId()).isEqualTo("town-crier-v3");
              assertThat(schedule.getScheduleMetadataJson())
                  .contains("\"pluginId\":\"town-crier\"");
              assertThat(schedule.getScheduleMetadataJson())
                  .contains("\"pluginVersionId\":\"town-crier-v3\"");
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
  void refreshPatchSchedulesIgnoresOrdinaryHandlerKinds() {
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

    service.refreshPatchSchedules("1", "patch-1", List.of(script), List.of("npc-guard"));

    verify(repository)
        .deleteByTenantIdAndScriptPatchVersionAndScriptIdIn(1L, "patch-1", List.of("npc-guard"));
    verify(repository, never()).saveAll(org.mockito.ArgumentMatchers.any());
  }

  @ParameterizedTest(name = "invalid authored cadence {0}")
  @MethodSource("invalidCadenceValues")
  void refreshPatchSchedulesRejectsInvalidCadence(String authoredCadence, boolean quoted) {
    when(repository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of());

    String jsonValue = quoted ? "\"" + authoredCadence + "\"" : authoredCadence;
    ScriptDefinition script = new ScriptDefinition();
    script.setTenantId(1L);
    script.setName("npc-guard");
    script.setDefinition(
        "{\"eventHandlers\":{\"onInterval\":{\"scheduleDefinitionId\":\"guard.patrol.v1\",\"intervalTicks\":"
            + jsonValue
            + "}}}");

    assertThatThrownBy(
            () ->
                service.refreshPatchSchedules(
                    "1", "patch-1", List.of(script), List.of("npc-guard")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid_schedule_cadence");
    verify(repository, never()).saveAll(org.mockito.ArgumentMatchers.any());
  }

  private static Stream<Arguments> invalidCadenceValues() {
    return Stream.of(
        Arguments.of("1.5", true),
        Arguments.of("-1", true),
        Arguments.of("9223372036854775808", true),
        Arguments.of("9223372036854775807", true),
        Arguments.of("1.5", false),
        Arguments.of("-1", false),
        Arguments.of("9223372036854775808", false),
        Arguments.of("9223372036854775807", false));
  }

  @Test
  void refreshPatchSchedulesRejectsInvalidDelayTicksInsteadOfUsingDelayMs() {
    when(repository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of());
    ScriptDefinition script = new ScriptDefinition();
    script.setTenantId(1L);
    script.setName("npc-guard");
    script.setDefinition(
        "{\"eventHandlers\":{\"onTimerExpire\":{\"scheduleDefinitionId\":\"guard.alert.expire.v1\",\"delayTicks\":1.5,\"delayMs\":5000}}}");

    assertThatThrownBy(
            () ->
                service.refreshPatchSchedules(
                    "1", "patch-1", List.of(script), List.of("npc-guard")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid_schedule_cadence");
    verify(repository, never()).saveAll(org.mockito.ArgumentMatchers.any());
    verify(repository, never())
        .deleteByTenantIdAndScriptPatchVersionAndScriptIdIn(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
  }

  @ParameterizedTest(name = "rejects invalid timer cadence {0}={1}")
  @MethodSource("invalidTimerCadenceValues")
  void refreshPatchSchedulesRejectsInvalidTimerCadence(
      String cadenceField, String authoredCadence, boolean quoted) {
    when(repository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of());
    String jsonValue = quoted ? "\"" + authoredCadence + "\"" : authoredCadence;
    String fallback = "delayTicks".equals(cadenceField) ? ",\"delayMs\":5000" : "";
    ScriptDefinition script = new ScriptDefinition();
    script.setTenantId(1L);
    script.setName("npc-guard");
    script.setDefinition(
        "{\"eventHandlers\":{\"onTimerExpire\":{\"scheduleDefinitionId\":\"timer.v1\",\""
            + cadenceField
            + "\":"
            + jsonValue
            + fallback
            + "}}}");

    assertThatThrownBy(
            () ->
                service.refreshPatchSchedules(
                    "1", "patch-1", List.of(script), List.of("npc-guard")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid_schedule_cadence");
    verify(repository, never()).saveAll(org.mockito.ArgumentMatchers.any());
  }

  private static Stream<Arguments> invalidTimerCadenceValues() {
    return Stream.of(
        Arguments.of("delayTicks", "1.5", true),
        Arguments.of("delayTicks", "-1", true),
        Arguments.of("delayTicks", "9223372036854775808", true),
        Arguments.of("delayTicks", "9223372036854775807", true),
        Arguments.of("delayTicks", "1.5", false),
        Arguments.of("delayTicks", "-1", false),
        Arguments.of("delayTicks", "9223372036854775808", false),
        Arguments.of("delayTicks", "9223372036854775807", false),
        Arguments.of("delayMs", "1.5", true),
        Arguments.of("delayMs", "-1", true),
        Arguments.of("delayMs", "9223372036854775808", true),
        Arguments.of("delayMs", "9223372036854775807", true),
        Arguments.of("delayMs", "1.5", false),
        Arguments.of("delayMs", "-1", false),
        Arguments.of("delayMs", "9223372036854775808", false),
        Arguments.of("delayMs", "9223372036854775807", false));
  }

  @ParameterizedTest(name = "rejects malformed plugin owner {0}")
  @MethodSource("malformedPluginOwners")
  void refreshPatchSchedulesRejectsOneSidedOrContradictoryPluginOwner(
      String ownerJson, String expectedMessage) {
    when(repository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of());
    ScriptDefinition script = new ScriptDefinition();
    script.setTenantId(1L);
    script.setName("plugin-town-crier");
    script.setDefinition(
        "{"
            + ownerJson
            + ",\"eventHandlers\":{\"onInterval\":{\"scheduleDefinitionId\":\"pulse.v1\",\"intervalTicks\":1}}}");

    assertThatThrownBy(
            () ->
                service.refreshPatchSchedules(
                    "1", "patch-1", List.of(script), List.of(script.getName())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(expectedMessage);
    verify(repository, never()).saveAll(org.mockito.ArgumentMatchers.any());
    verify(repository, never())
        .deleteByTenantIdAndScriptPatchVersionAndScriptIdIn(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
  }

  private static Stream<Arguments> malformedPluginOwners() {
    return Stream.of(
        Arguments.of("\"pluginId\":\"town-crier\"", "plugin_schedule_owner_incomplete"),
        Arguments.of("\"pluginVersionId\":\"town-crier-v1\"", "plugin_schedule_owner_incomplete"),
        Arguments.of(
            "\"pluginId\":123,\"pluginVersionId\":\"town-crier-v1\"",
            "plugin_schedule_owner_invalid"),
        Arguments.of("\"plugin\":null", "plugin_schedule_owner_invalid"),
        Arguments.of(
            "\"plugin\":{\"pluginId\":\"town-crier\"}", "plugin_schedule_owner_incomplete"),
        Arguments.of(
            "\"plugin\":{\"pluginId\":\"town-crier\",\"pluginVersionId\":\"v1\"},\"pluginId\":\"other\",\"pluginVersionId\":\"v1\"",
            "plugin_schedule_owner_contradictory"));
  }

  @Test
  void refreshPatchSchedulesRejectsContradictoryOwnersAcrossHandlers() {
    when(repository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of());
    ScriptDefinition script = new ScriptDefinition();
    script.setTenantId(1L);
    script.setName("plugin-town-crier");
    script.setDefinition(
        "{\"eventHandlers\":{"
            + "\"onInterval\":{\"scheduleDefinitionId\":\"pulse.v1\",\"intervalTicks\":1,"
            + "\"pluginId\":\"town-crier\",\"pluginVersionId\":\"v1\"},"
            + "\"onTimerExpire\":{\"scheduleDefinitionId\":\"expire.v1\",\"delayMs\":1,"
            + "\"pluginId\":\"other-plugin\",\"pluginVersionId\":\"v1\"}}}");

    assertThatThrownBy(
            () ->
                service.refreshPatchSchedules(
                    "1", "patch-1", List.of(script), List.of(script.getName())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("plugin_schedule_owner_contradictory");
    verify(repository, never()).saveAll(org.mockito.ArgumentMatchers.any());
    verify(repository, never())
        .deleteByTenantIdAndScriptPatchVersionAndScriptIdIn(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
  }
}
