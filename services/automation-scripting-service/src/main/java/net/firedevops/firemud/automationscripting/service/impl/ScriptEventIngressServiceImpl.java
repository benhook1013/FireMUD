package net.firedevops.firemud.automationscripting.service.impl;

import java.util.Map;
import java.util.Set;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
import net.firedevops.firemud.automationscripting.repository.ScriptEventAuditRepository;
import net.firedevops.firemud.automationscripting.service.ScriptEventIngressService;
import net.firedevops.firemud.automationscripting.v1.TriggerAdmissionOutcome;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScriptEventIngressServiceImpl implements ScriptEventIngressService {
  private static final String DEFAULT_SCHEMA_VERSION = "v1";
  private static final String OUTCOME_ADMITTED =
      TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_ADMITTED.name();
  private static final String OUTCOME_REGISTRY_REJECTED =
      TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_EVENT_REGISTRY_REJECTED.name();

  private final ScriptEventAuditRepository repository;
  private final Map<String, EventDefinition> eventDefinitions;

  public ScriptEventIngressServiceImpl(ScriptEventAuditRepository repository) {
    this.repository = repository;
    this.eventDefinitions =
        Map.of(
            key("onLoad", DEFAULT_SCHEMA_VERSION),
            new EventDefinition(Set.of("automation-scripting-service"), false, false),
            key("onCommand", DEFAULT_SCHEMA_VERSION),
            new EventDefinition(Set.of("game-session-service", "game-logic-service"), true, true),
            key("onSpawn", DEFAULT_SCHEMA_VERSION),
            new EventDefinition(
                Set.of("game-session-service", "world-management-service"), true, true),
            key("onEnterRegion", DEFAULT_SCHEMA_VERSION),
            new EventDefinition(
                Set.of("game-session-service", "world-management-service"), true, true),
            key("onLeaveRegion", DEFAULT_SCHEMA_VERSION),
            new EventDefinition(
                Set.of("game-session-service", "world-management-service"), true, true),
            key("onTimerExpire", DEFAULT_SCHEMA_VERSION),
            new EventDefinition(Set.of("automation-scripting-service"), true, true),
            key("onInterval", DEFAULT_SCHEMA_VERSION),
            new EventDefinition(Set.of("automation-scripting-service"), true, true));
  }

  @Override
  @Transactional
  public TriggerAdmission admit(TriggerScriptEventRequest request) {
    String schemaVersion = schemaVersion(request);
    ScriptEventAudit existing = findExisting(request, schemaVersion);
    if (existing != null) {
      return new TriggerAdmission(
          existing.isAdmitted(), existing.getAdmissionOutcome(), existing.getAdmissionReason());
    }

    TriggerAdmission admission = validate(request, schemaVersion);
    ScriptEventAudit audit = new ScriptEventAudit();
    audit.setTenantId(requiredText(request.getTenantId(), "tenant_id"));
    audit.setGameInstanceId(normalize(request.getGameInstanceId()));
    audit.setRegionId(normalize(request.getRegionId()));
    audit.setRegionEpoch(request.getRegionEpoch() > 0 ? request.getRegionEpoch() : 0L);
    audit.setEntityId(normalize(request.getEntityId()));
    audit.setScriptId(normalize(request.getScriptId()));
    audit.setPluginId(normalize(request.getPluginId()));
    audit.setPluginVersionId(normalize(request.getPluginVersionId()));
    audit.setEventType(requiredText(request.getEventType(), "event_type"));
    audit.setEventSchemaVersion(schemaVersion);
    audit.setScriptPatchVersion(
        requiredText(request.getScriptPatchVersion(), "script_patch_version"));
    audit.setScriptEventId(requiredText(request.getScriptEventId(), "script_event_id"));
    audit.setSourceService(resolveSourceService());
    audit.setTriggerMode(request.getTriggerMode().name());
    audit.setDryRun(request.getIsDryRun());
    audit.setReadSnapshotToken(normalize(request.getReadSnapshotToken()));
    audit.setPayloadJson(normalize(request.getPayloadJson()));
    audit.setAdmitted(admission.admitted());
    audit.setAdmissionOutcome(admission.outcome());
    audit.setAdmissionReason(admission.reason());
    repository.save(audit);
    return admission;
  }

  private TriggerAdmission validate(TriggerScriptEventRequest request, String schemaVersion) {
    requiredText(request.getTenantId(), "tenant_id");
    requiredText(request.getEventType(), "event_type");
    requiredText(request.getScriptPatchVersion(), "script_patch_version");
    requiredText(request.getScriptEventId(), "script_event_id");

    EventDefinition definition = eventDefinitions.get(key(request.getEventType(), schemaVersion));
    if (definition == null) {
      return rejected("unknown_event_type");
    }
    String sourceService = resolveSourceService();
    if (!definition.allowedProducers().contains(sourceService)) {
      return rejected("unauthorized_producer");
    }
    if (definition.requiresSnapshotToken() && request.getReadSnapshotToken().isBlank()) {
      return rejected("missing_snapshot_token");
    }
    if (definition.requiresRegionIdentity()) {
      if (request.getGameInstanceId().isBlank()
          || request.getRegionId().isBlank()
          || request.getRegionEpoch() <= 0
          || request.getEntityId().isBlank()) {
        return rejected("missing_trigger_identity");
      }
    }
    return new TriggerAdmission(true, OUTCOME_ADMITTED, "admitted_for_handler_resolution");
  }

  private TriggerAdmission rejected(String reason) {
    return new TriggerAdmission(false, OUTCOME_REGISTRY_REJECTED, reason);
  }

  private ScriptEventAudit findExisting(TriggerScriptEventRequest request, String schemaVersion) {
    if (request.getTenantId().isBlank()
        || request.getEventType().isBlank()
        || request.getScriptPatchVersion().isBlank()
        || request.getScriptEventId().isBlank()) {
      return null;
    }
    var existing =
        repository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndScriptIdAndPluginIdAndPluginVersionIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
                request.getTenantId(),
                normalize(request.getGameInstanceId()),
                normalize(request.getRegionId()),
                request.getRegionEpoch() > 0 ? request.getRegionEpoch() : 0L,
                normalize(request.getEntityId()),
                normalize(request.getScriptId()),
                normalize(request.getPluginId()),
                normalize(request.getPluginVersionId()),
                request.getEventType(),
                schemaVersion,
                request.getScriptPatchVersion(),
                request.getScriptEventId(),
                request.getIsDryRun());
    return existing == null ? null : existing.orElse(null);
  }

  private String schemaVersion(TriggerScriptEventRequest request) {
    return request.getEventSchemaVersion().isBlank()
        ? DEFAULT_SCHEMA_VERSION
        : request.getEventSchemaVersion();
  }

  private static String key(String eventType, String schemaVersion) {
    return eventType + ":" + schemaVersion;
  }

  private static String resolveSourceService() {
    String source = SessionContext.getServiceName();
    if (source == null || source.isBlank()) {
      return SessionContext.hasGlobalPrivilegedRole() ? "operator" : "unknown";
    }
    return source;
  }

  private static String requiredText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value;
  }

  private static String normalize(String value) {
    return value == null ? "" : value;
  }

  private record EventDefinition(
      Set<String> allowedProducers,
      boolean requiresRegionIdentity,
      boolean requiresSnapshotToken) {}
}
