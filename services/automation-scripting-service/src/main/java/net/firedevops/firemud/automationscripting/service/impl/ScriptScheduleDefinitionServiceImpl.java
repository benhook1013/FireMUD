package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
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
    if (root == null) {
      throw new IllegalArgumentException("script_definition_json_invalid");
    }
    if (!root.containsKey("eventHandlers")) {
      return List.of();
    }
    if (!(root.get("eventHandlers") instanceof Map<?, ?>)) {
      throw new IllegalArgumentException("schedule_event_handlers_invalid");
    }
    Map<String, Object> eventHandlers = asObjectMap(root.get("eventHandlers"));

    List<ScriptScheduleDefinition> schedules = new ArrayList<>();
    ScriptCommandMetadataSupport.PluginOwner scriptOwner = null;
    for (Map.Entry<String, Object> entry : eventHandlers.entrySet()) {
      String eventType = entry.getKey();
      if (!isScheduledEventType(eventType)) {
        continue;
      }
      Map<String, Object> handler =
          ScriptCommandMetadataSupport.extractHandlerNode(asObjectMap(root), eventType);
      ScriptCommandMetadataSupport.PluginOwner owner =
          ScriptCommandMetadataSupport.resolvePluginOwner(asObjectMap(root), handler);
      if (scriptOwner != null && !scriptOwner.equals(owner)) {
        throw new IllegalArgumentException("plugin_schedule_owner_contradictory");
      }
      scriptOwner = owner;
      schedules.add(
          extractSchedule(tenantId, scriptPatchVersion, definition, eventType, handler, owner));
    }
    return List.copyOf(schedules);
  }

  private ScriptScheduleDefinition extractSchedule(
      long tenantId,
      String scriptPatchVersion,
      ScriptDefinition scriptDefinition,
      String eventType,
      Map<String, Object> handlerNode,
      ScriptCommandMetadataSupport.PluginOwner pluginOwner) {
    String scheduleDefinitionId = normalizedText(handlerNode.get("scheduleDefinitionId"));
    if (scheduleDefinitionId.isBlank()) {
      throw new IllegalArgumentException("schedule_definition_id_required");
    }

    Cadence cadence = resolveCadence(eventType, handlerNode);
    String priorityTag = normalizePriorityTag(handlerNode.get("priorityTag"));
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
      Long intervalTicks = positiveLong(handlerNode, "intervalTicks");
      if (intervalTicks == null) {
        throw new IllegalArgumentException("schedule_interval_ticks_required");
      }
      return new Cadence(KIND_INTERVAL, UNIT_TICKS, intervalTicks);
    }
    if (EVENT_ON_TIMER_EXPIRE.equals(eventType)) {
      if (handlerNode.containsKey("delayTicks")) {
        Long delayTicks = positiveLong(handlerNode, "delayTicks");
        return new Cadence(KIND_TIMER, UNIT_TICKS, delayTicks);
      }
      Long delayMs = positiveLong(handlerNode, "delayMs");
      if (delayMs != null) {
        return new Cadence(KIND_TIMER, UNIT_MILLISECONDS, delayMs);
      }
      throw new IllegalArgumentException("schedule_timer_delay_required");
    }
    throw new IllegalArgumentException("unsupported_scheduled_event_type: " + eventType);
  }

  private static boolean isScheduledEventType(String eventType) {
    return EVENT_ON_INTERVAL.equals(eventType) || EVENT_ON_TIMER_EXPIRE.equals(eventType);
  }

  private Long positiveLong(Map<String, Object> node, String field) {
    if (!node.containsKey(field)) {
      return null;
    }
    Object value = node.get(field);
    if (value == null) {
      throw invalidCadence(field);
    }
    BigDecimal decimal;
    try {
      if (value instanceof Double doubleValue) {
        if (!Double.isFinite(doubleValue)) {
          throw invalidCadence(field);
        }
        decimal = BigDecimal.valueOf(doubleValue);
      } else if (value instanceof Float floatValue) {
        if (!Float.isFinite(floatValue)) {
          throw invalidCadence(field);
        }
        decimal = BigDecimal.valueOf(floatValue.doubleValue());
      } else if (value instanceof Number number) {
        decimal = new BigDecimal(number.toString());
      } else if (value instanceof String text && !text.trim().isBlank()) {
        decimal = new BigDecimal(text.trim());
      } else {
        throw invalidCadence(field);
      }
      long parsed = decimal.longValueExact();
      if (parsed <= 0 || parsed == Long.MAX_VALUE) {
        throw invalidCadence(field);
      }
      return parsed;
    } catch (NumberFormatException | ArithmeticException ex) {
      throw invalidCadence(field);
    }
  }

  private IllegalArgumentException invalidCadence(String field) {
    return new IllegalArgumentException("invalid_schedule_cadence: " + field);
  }

  private String scheduleMetadataJson(
      String scheduleDefinitionId,
      String eventType,
      Cadence cadence,
      String priorityTag,
      ScriptCommandMetadataSupport.PluginOwner pluginOwner,
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

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalArgumentException("schedule_hash_unavailable");
    }
  }

  private record Cadence(String kind, String unit, long value) {}
}
