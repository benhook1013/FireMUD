package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptScheduleDefinition;
import net.firedevops.firemud.automationscripting.repository.ScriptScheduleDefinitionRepository;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleDefinitionService;
import net.firedevops.firemud.common.security.RequestIdValidation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected Spring collaborators are retained internally by the service.")
public class ScriptScheduleDefinitionServiceImpl implements ScriptScheduleDefinitionService {
  private static final String EVENT_ON_INTERVAL = "onInterval";
  private static final String EVENT_ON_TIMER_EXPIRE = "onTimerExpire";
  private static final String KIND_INTERVAL = "INTERVAL";
  private static final String KIND_TIMER = "TIMER";
  private static final String UNIT_TICKS = "TICKS";
  private static final String UNIT_MILLISECONDS = "MILLISECONDS";
  private static final String PRIORITY_HIGH = "high";
  private static final String PRIORITY_NORMAL = "normal";
  private static final String PRIORITY_BACKGROUND = "background";

  private final ScriptScheduleDefinitionRepository repository;
  private final ObjectMapper objectMapper;

  public ScriptScheduleDefinitionServiceImpl(
      ScriptScheduleDefinitionRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional
  public void refreshPatchSchedules(
      String tenantId,
      String scriptPatchVersion,
      List<ScriptDefinition> definitions,
      List<String> affectedScripts) {
    long tenantKey = RequestIdValidation.requirePositiveLong(tenantId, "tenantId");
    List<ScriptScheduleDefinition> existing =
        repository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                tenantKey, scriptPatchVersion);
    Set<String> affectedScriptSet = new HashSet<>(affectedScripts);
    Set<String> occupiedKeys = new HashSet<>();
    for (ScriptScheduleDefinition definition : existing) {
      if (!affectedScriptSet.contains(definition.getScriptId())) {
        occupiedKeys.add(scheduleKey(definition));
      }
    }

    List<ScriptScheduleDefinition> extracted = new ArrayList<>();
    Set<String> batchKeys = new HashSet<>();
    for (ScriptDefinition definition : definitions) {
      for (ScriptScheduleDefinition schedule :
          extractSchedules(tenantKey, scriptPatchVersion, definition)) {
        String key = scheduleKey(schedule);
        if (!batchKeys.add(key) || occupiedKeys.contains(key)) {
          throw new IllegalArgumentException(
              "duplicate_schedule_definition_id: " + schedule.getScheduleDefinitionId());
        }
        extracted.add(schedule);
      }
    }

    repository.deleteByTenantIdAndScriptPatchVersionAndScriptIdIn(
        tenantKey, scriptPatchVersion, affectedScripts);
    if (!extracted.isEmpty()) {
      repository.saveAll(extracted);
    }
  }

  private List<ScriptScheduleDefinition> extractSchedules(
      long tenantId, String scriptPatchVersion, ScriptDefinition definition) {
    Map<?, ?> root;
    try {
      root = objectMapper.readValue(definition.getDefinition(), Map.class);
    } catch (Exception ex) {
      throw new IllegalArgumentException("script_definition_json_invalid");
    }
    Map<String, Object> eventHandlers = asObjectMap(root.get("eventHandlers"));
    if (eventHandlers.isEmpty()) {
      return List.of();
    }

    List<ScriptScheduleDefinition> schedules = new ArrayList<>();
    eventHandlers.forEach(
        (eventType, handlerNode) -> {
          ScriptScheduleDefinition extracted =
              extractSchedule(
                  tenantId,
                  scriptPatchVersion,
                  definition,
                  asObjectMap(root),
                  eventType,
                  asObjectMap(handlerNode));
          if (extracted != null) {
            schedules.add(extracted);
          }
        });
    return List.copyOf(schedules);
  }

  private ScriptScheduleDefinition extractSchedule(
      long tenantId,
      String scriptPatchVersion,
      ScriptDefinition scriptDefinition,
      Map<String, Object> rootNode,
      String eventType,
      Map<String, Object> handlerNode) {
    String scheduleDefinitionId = normalizedText(handlerNode.get("scheduleDefinitionId"));
    if (scheduleDefinitionId.isBlank()) {
      return null;
    }

    Cadence cadence = resolveCadence(eventType, handlerNode);
    String priorityTag = normalizePriorityTag(handlerNode.get("priorityTag"));
    PluginOwner pluginOwner = resolvePluginOwner(rootNode, handlerNode);
    String metadataJson =
        scheduleMetadataJson(
            scheduleDefinitionId,
            eventType,
            cadence,
            priorityTag,
            pluginOwner,
            scriptDefinition.getName(),
            scriptPatchVersion);
    Instant now = Instant.now();
    ScriptScheduleDefinition schedule = new ScriptScheduleDefinition();
    schedule.setTenantId(tenantId);
    schedule.setScriptPatchVersion(scriptPatchVersion);
    schedule.setScriptId(scriptDefinition.getName());
    schedule.setPluginId(pluginOwner.pluginId());
    schedule.setPluginVersionId(pluginOwner.pluginVersionId());
    schedule.setEventType(eventType);
    schedule.setScheduleDefinitionId(scheduleDefinitionId);
    schedule.setScheduleKind(cadence.kind());
    schedule.setCadenceUnit(cadence.unit());
    schedule.setCadenceValue(cadence.value());
    schedule.setPriorityTag(priorityTag);
    schedule.setScheduleMetadataJson(metadataJson);
    schedule.setScheduleSemanticsHash(sha256(metadataJson));
    schedule.setCreatedAt(now);
    schedule.setUpdatedAt(now);
    return schedule;
  }

  private Cadence resolveCadence(String eventType, Map<String, Object> handlerNode) {
    if (EVENT_ON_INTERVAL.equals(eventType)) {
      long intervalTicks = positiveLong(handlerNode, "intervalTicks");
      if (intervalTicks <= 0) {
        throw new IllegalArgumentException("schedule_interval_ticks_required");
      }
      return new Cadence(KIND_INTERVAL, UNIT_TICKS, intervalTicks);
    }
    if (EVENT_ON_TIMER_EXPIRE.equals(eventType)) {
      long delayTicks = positiveLong(handlerNode, "delayTicks");
      if (delayTicks > 0) {
        return new Cadence(KIND_TIMER, UNIT_TICKS, delayTicks);
      }
      long delayMs = positiveLong(handlerNode, "delayMs");
      if (delayMs > 0) {
        return new Cadence(KIND_TIMER, UNIT_MILLISECONDS, delayMs);
      }
      throw new IllegalArgumentException("schedule_timer_delay_required");
    }
    throw new IllegalArgumentException("unsupported_scheduled_event_type: " + eventType);
  }

  private long positiveLong(Map<String, Object> node, String field) {
    Object value = node.get(field);
    if (value == null) {
      return 0L;
    }
    if (value instanceof Number number) {
      return Math.max(number.longValue(), 0L);
    }
    if (value instanceof String text) {
      try {
        return Math.max(Long.parseLong(text.trim()), 0L);
      } catch (NumberFormatException ex) {
        return 0L;
      }
    }
    return 0L;
  }

  private String scheduleMetadataJson(
      String scheduleDefinitionId,
      String eventType,
      Cadence cadence,
      String priorityTag,
      PluginOwner pluginOwner,
      String scriptId,
      String scriptPatchVersion) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("scheduleDefinitionId", scheduleDefinitionId);
    metadata.put("eventType", eventType);
    metadata.put("scheduleKind", cadence.kind());
    metadata.put("cadenceUnit", cadence.unit());
    metadata.put("cadenceValue", cadence.value());
    metadata.put("priorityTag", priorityTag);
    metadata.put("scriptId", scriptId);
    metadata.put("scriptPatchVersion", scriptPatchVersion);
    if (!pluginOwner.pluginId().isBlank()) {
      metadata.put("pluginId", pluginOwner.pluginId());
      metadata.put("pluginVersionId", pluginOwner.pluginVersionId());
    }
    try {
      return objectMapper.writeValueAsString(metadata);
    } catch (Exception ex) {
      throw new IllegalArgumentException("schedule_metadata_json_invalid");
    }
  }

  private String scheduleKey(ScriptScheduleDefinition definition) {
    return definition.getPluginId()
        + "\u0000"
        + definition.getPluginVersionId()
        + "\u0000"
        + definition.getScheduleDefinitionId();
  }

  private static PluginOwner resolvePluginOwner(
      Map<String, Object> rootNode, Map<String, Object> handlerNode) {
    String pluginId =
        firstPresent(
            normalizedText(handlerNode.get("pluginId")),
            normalizedText(asObjectMap(handlerNode.get("plugin")).get("pluginId")),
            normalizedText(asObjectMap(handlerNode.get("owner")).get("pluginId")),
            normalizedText(rootNode.get("pluginId")),
            normalizedText(asObjectMap(rootNode.get("plugin")).get("pluginId")),
            normalizedText(asObjectMap(rootNode.get("owner")).get("pluginId")));
    String pluginVersionId =
        firstPresent(
            normalizedText(handlerNode.get("pluginVersionId")),
            normalizedText(asObjectMap(handlerNode.get("plugin")).get("pluginVersionId")),
            normalizedText(asObjectMap(handlerNode.get("owner")).get("pluginVersionId")),
            normalizedText(rootNode.get("pluginVersionId")),
            normalizedText(asObjectMap(rootNode.get("plugin")).get("pluginVersionId")),
            normalizedText(asObjectMap(rootNode.get("owner")).get("pluginVersionId")));
    if (pluginId.isBlank()) {
      return new PluginOwner("", "");
    }
    if (pluginVersionId.isBlank()) {
      throw new IllegalArgumentException("plugin_schedule_owner_incomplete");
    }
    return new PluginOwner(pluginId, pluginVersionId);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asObjectMap(Object value) {
    if (value instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    return Map.of();
  }

  private static String normalizePriorityTag(Object priorityTag) {
    String normalized = normalizedText(priorityTag).toLowerCase();
    return switch (normalized) {
      case PRIORITY_HIGH -> PRIORITY_HIGH;
      case PRIORITY_BACKGROUND -> PRIORITY_BACKGROUND;
      default -> PRIORITY_NORMAL;
    };
  }

  private static String normalizedText(Object value) {
    return value == null ? "" : value.toString().trim();
  }

  private static String firstPresent(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalArgumentException("schedule_hash_unavailable");
    }
  }

  private record Cadence(String kind, String unit, long value) {}

  private record PluginOwner(String pluginId, String pluginVersionId) {}
}
