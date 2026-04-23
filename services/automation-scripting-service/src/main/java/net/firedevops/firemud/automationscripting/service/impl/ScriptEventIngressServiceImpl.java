package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.config.ScriptOutputProperties;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;
import net.firedevops.firemud.automationscripting.entity.ScriptEventIngressAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptEventAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventBindingRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventIngressAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.AutomationAdmissionStateService;
import net.firedevops.firemud.automationscripting.service.AutomationQueueService;
import net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService;
import net.firedevops.firemud.automationscripting.service.ScriptEventIngressService;
import net.firedevops.firemud.automationscripting.service.ScriptEventRegistryService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchInstanceRolloutProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchPinProjectionService;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.automationscripting.v1.TriggerAdmissionOutcome;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected Spring dependencies are not exposed externally")
public class ScriptEventIngressServiceImpl implements ScriptEventIngressService {
  private static final String DEFAULT_SCHEMA_VERSION = "v1";
  private static final String OUTCOME_ADMITTED =
      TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_ADMITTED.name();
  private static final String OUTCOME_REGISTRY_REJECTED =
      TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_EVENT_REGISTRY_REJECTED.name();
  private static final String OUTCOME_OUTPUT_BUDGET_EXCEEDED =
      TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_OUTPUT_BUDGET_EXCEEDED.name();
  private static final String OUTCOME_BACKPRESSURE_ROLLBACK =
      TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_BACKPRESSURE_ROLLBACK.name();
  private static final String OUTCOME_VERSION_UNAVAILABLE =
      TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE.name();
  private static final String OUTCOME_PIN_STATE_UNAVAILABLE =
      TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_PIN_STATE_UNAVAILABLE.name();

  private final ScriptEventIngressAuditRepository repository;
  private final ScriptEventBindingRepository bindingRepository;
  private final ScriptWorkItemRepository workItemRepository;
  private final ScriptEventAuditRepository eventAuditRepository;
  private final ScriptEventRegistryService eventRegistryService;
  private final AutomationQueueService automationQueueService;
  private final ScriptOutputProperties outputProperties;
  private final GameSessionControlPlaneClient gameSessionControlPlaneClient;
  private final AutomationAdmissionStateService automationAdmissionStateService;
  private final ScriptPatchPinProjectionService scriptPatchPinProjectionService;
  private final ScriptPatchInstanceRolloutProjectionService rolloutProjectionService;
  private final PluginRuntimeStateService pluginRuntimeStateService;

  public ScriptEventIngressServiceImpl(
      ScriptEventIngressAuditRepository repository,
      ScriptEventBindingRepository bindingRepository,
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository eventAuditRepository,
      ScriptEventRegistryService eventRegistryService,
      AutomationQueueService automationQueueService,
      ScriptOutputProperties outputProperties,
      GameSessionControlPlaneClient gameSessionControlPlaneClient,
      AutomationAdmissionStateService automationAdmissionStateService,
      ScriptPatchPinProjectionService scriptPatchPinProjectionService,
      ScriptPatchInstanceRolloutProjectionService rolloutProjectionService,
      PluginRuntimeStateService pluginRuntimeStateService) {
    this.repository = repository;
    this.bindingRepository = bindingRepository;
    this.workItemRepository = workItemRepository;
    this.eventAuditRepository = eventAuditRepository;
    this.eventRegistryService = eventRegistryService;
    this.automationQueueService = automationQueueService;
    this.outputProperties = outputProperties;
    this.gameSessionControlPlaneClient = gameSessionControlPlaneClient;
    this.automationAdmissionStateService = automationAdmissionStateService;
    this.scriptPatchPinProjectionService = scriptPatchPinProjectionService;
    this.rolloutProjectionService = rolloutProjectionService;
    this.pluginRuntimeStateService = pluginRuntimeStateService;
  }

  @Override
  @Transactional
  public TriggerAdmission admit(TriggerScriptEventRequest request) {
    String schemaVersion = schemaVersion(request);
    ScriptEventIngressAudit existing = findExisting(request, schemaVersion);
    if (existing != null) {
      return new TriggerAdmission(
          existing.isAdmitted(),
          existing.getAdmissionOutcome(),
          existing.getAdmissionReason(),
          existing.getResolvedHandlerCount());
    }

    TriggerAdmission admission = validate(request, schemaVersion);
    if (admission.admitted()) {
      admission = admissionWithHandlers(request, schemaVersion);
    }
    ScriptEventIngressAudit audit = new ScriptEventIngressAudit();
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
    audit.setResolvedHandlerCount(admission.resolvedHandlerCount());
    repository.save(audit);
    return admission;
  }

  private TriggerAdmission validate(TriggerScriptEventRequest request, String schemaVersion) {
    requiredText(request.getTenantId(), "tenant_id");
    requiredText(request.getEventType(), "event_type");
    requiredText(request.getScriptPatchVersion(), "script_patch_version");
    requiredText(request.getScriptEventId(), "script_event_id");
    if (request.getPayloadJson().getBytes(StandardCharsets.UTF_8).length
        > outputProperties.getMaxSerializedWorkItemBytes()) {
      return new TriggerAdmission(
          false, OUTCOME_OUTPUT_BUDGET_EXCEEDED, "work_item_size_exceeded", 0);
    }

    ScriptEventRegistryService.EventDefinition definition =
        eventRegistryService.getDefinition(request.getEventType(), schemaVersion).orElse(null);
    if (definition == null) {
      return rejected("unknown_event_type");
    }
    String sourceService = resolveSourceService();
    if (!definition.allowedProducerPrincipals().contains(sourceService)) {
      return rejected("unauthorized_producer");
    }
    if (definition.snapshotAuthority().equals("PRODUCER_SUPPLIED_TOKEN")
        && request.getReadSnapshotToken().isBlank()) {
      return rejected("missing_snapshot_token");
    }
    if (definition.requiredTriggerIdentityFields().contains("regionEpoch")) {
      if (request.getGameInstanceId().isBlank()
          || request.getRegionId().isBlank()
          || request.getRegionEpoch() <= 0
          || request.getEntityId().isBlank()) {
        return rejected("missing_trigger_identity");
      }
    }
    TriggerAdmission pinAdmission = validatePinnedPatch(request);
    if (pinAdmission != null) {
      return pinAdmission;
    }
    TriggerAdmission pluginAdmission = validatePluginRuntimeState(request);
    if (pluginAdmission != null) {
      return pluginAdmission;
    }
    TriggerAdmission stateAdmission = validateAdmissionState(request);
    if (stateAdmission != null) {
      return stateAdmission;
    }
    return new TriggerAdmission(true, OUTCOME_ADMITTED, "admitted_for_handler_resolution", 0);
  }

  private TriggerAdmission rejected(String reason) {
    return new TriggerAdmission(false, OUTCOME_REGISTRY_REJECTED, reason, 0);
  }

  private TriggerAdmission validatePinnedPatch(TriggerScriptEventRequest request) {
    if (request.getGameInstanceId().isBlank()) {
      return null;
    }
    var runtime =
        gameSessionControlPlaneClient.getGameInstanceRuntimeState(
            request.getTenantId(), request.getGameInstanceId());
    if (runtime.hasError() && !runtime.getError().getCode().isBlank()) {
      return new TriggerAdmission(false, OUTCOME_PIN_STATE_UNAVAILABLE, "pin_state_unavailable", 0);
    }
    if (!runtime.hasRuntimeState()) {
      return new TriggerAdmission(false, OUTCOME_PIN_STATE_UNAVAILABLE, "pin_state_unavailable", 0);
    }
    scriptPatchPinProjectionService.observeRuntimeState(
        request.getTenantId(), request.getGameInstanceId(), runtime.getRuntimeState());
    if (!request
        .getScriptPatchVersion()
        .equals(runtime.getRuntimeState().getPinnedScriptPatchVersion())) {
      return new TriggerAdmission(false, OUTCOME_VERSION_UNAVAILABLE, "version_unavailable", 0);
    }
    return null;
  }

  private TriggerAdmission validatePluginRuntimeState(TriggerScriptEventRequest request) {
    boolean hasPluginId = !request.getPluginId().isBlank();
    boolean hasPluginVersion = !request.getPluginVersionId().isBlank();
    if (!hasPluginId && !hasPluginVersion) {
      return null;
    }
    if (!hasPluginId || !hasPluginVersion || request.getGameInstanceId().isBlank()) {
      return rejected("missing_plugin_identity");
    }
    var status =
        pluginRuntimeStateService.getStatus(
            request.getTenantId(), request.getGameInstanceId(), request.getPluginId());
    if (status.isEmpty()) {
      return new TriggerAdmission(false, OUTCOME_VERSION_UNAVAILABLE, "plugin_not_active", 0);
    }
    if (status.get().pluginState() != PluginState.PLUGIN_STATE_ENABLED) {
      return new TriggerAdmission(false, OUTCOME_VERSION_UNAVAILABLE, "plugin_disabled", 0);
    }
    if (!status.get().activePluginVersionId().equals(request.getPluginVersionId())) {
      return new TriggerAdmission(
          false, OUTCOME_VERSION_UNAVAILABLE, "plugin_version_unavailable", 0);
    }
    return null;
  }

  private TriggerAdmission validateAdmissionState(TriggerScriptEventRequest request) {
    if (request.getGameInstanceId().isBlank()) {
      return null;
    }
    AutomationAdmissionStateService.AdmissionStateSummary state =
        automationAdmissionStateService.getState(
            request.getTenantId(), request.getGameInstanceId(), request.getRegionId());
    if ("PAUSED_FOR_ROLLBACK".equals(state.mode())) {
      return new TriggerAdmission(false, OUTCOME_BACKPRESSURE_ROLLBACK, "rollback_paused", 0);
    }
    return null;
  }

  private TriggerAdmission admissionWithHandlers(
      TriggerScriptEventRequest request, String schemaVersion) {
    long tenantKey = Long.parseLong(request.getTenantId());
    List<ScriptEventBinding> handlers =
        bindingRepository
            .findByTenantIdAndScriptPatchVersionAndEventTypeAndEventSchemaVersionAndEnabledTrueOrderByPriorityAscScriptIdAsc(
                tenantKey, request.getScriptPatchVersion(), request.getEventType(), schemaVersion)
            .stream()
            .filter(binding -> matchesScope(binding, request))
            .toList();
    handlers.forEach(binding -> persistWorkItem(request, schemaVersion, binding));
    String reason = handlers.isEmpty() ? "admitted_no_handlers" : "admitted_handlers_resolved";
    return new TriggerAdmission(true, OUTCOME_ADMITTED, reason, handlers.size());
  }

  private void persistWorkItem(
      TriggerScriptEventRequest request, String schemaVersion, ScriptEventBinding binding) {
    if (workItemRepository
        .existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndScriptIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
            request.getTenantId(),
            normalize(request.getGameInstanceId()),
            normalize(request.getRegionId()),
            request.getRegionEpoch() > 0 ? request.getRegionEpoch() : 0L,
            normalize(request.getEntityId()),
            binding.getScriptId(),
            request.getEventType(),
            schemaVersion,
            request.getScriptPatchVersion(),
            request.getScriptEventId(),
            request.getIsDryRun())) {
      return;
    }
    AutomationAdmissionStateService.AdmissionStateSummary admissionState =
        automationAdmissionStateService.getState(
            request.getTenantId(), request.getGameInstanceId(), request.getRegionId());
    ScriptWorkItem item = new ScriptWorkItem();
    item.setTenantId(request.getTenantId());
    item.setGameInstanceId(normalize(request.getGameInstanceId()));
    item.setRegionId(normalize(request.getRegionId()));
    item.setRegionEpoch(request.getRegionEpoch() > 0 ? request.getRegionEpoch() : 0L);
    item.setEntityId(normalize(request.getEntityId()));
    item.setScriptId(binding.getScriptId());
    item.setPluginId(normalize(request.getPluginId()));
    item.setPluginVersionId(normalize(request.getPluginVersionId()));
    item.setEventType(request.getEventType());
    item.setEventSchemaVersion(schemaVersion);
    item.setScriptPatchVersion(request.getScriptPatchVersion());
    item.setScriptEventId(request.getScriptEventId());
    item.setDryRun(request.getIsDryRun());
    item.setSourceService(resolveSourceService());
    item.setTriggerMode(request.getTriggerMode().name());
    item.setReadSnapshotToken(normalize(request.getReadSnapshotToken()));
    item.setPayloadJson(normalize(request.getPayloadJson()));
    item.setAdmissionEpoch(admissionState.admissionEpoch());
    ScriptWorkItem saved = workItemRepository.save(item);
    rolloutProjectionService.refreshForWorkItem(saved);
    automationQueueService.enqueueWorkItem(saved);
    persistHandlerAudit(request, schemaVersion, binding, saved);
  }

  private void persistHandlerAudit(
      TriggerScriptEventRequest request,
      String schemaVersion,
      ScriptEventBinding binding,
      ScriptWorkItem workItem) {
    if (eventAuditRepository
        .existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndScriptIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
            request.getTenantId(),
            normalize(request.getGameInstanceId()),
            normalize(request.getRegionId()),
            request.getRegionEpoch() > 0 ? request.getRegionEpoch() : 0L,
            normalize(request.getEntityId()),
            binding.getScriptId(),
            request.getEventType(),
            schemaVersion,
            request.getScriptPatchVersion(),
            request.getScriptEventId(),
            request.getIsDryRun())) {
      return;
    }
    ScriptEventAudit audit = new ScriptEventAudit();
    audit.setTenantId(request.getTenantId());
    audit.setGameInstanceId(normalize(request.getGameInstanceId()));
    audit.setRegionId(normalize(request.getRegionId()));
    audit.setRegionEpoch(request.getRegionEpoch() > 0 ? request.getRegionEpoch() : 0L);
    audit.setEntityId(normalize(request.getEntityId()));
    audit.setScriptId(binding.getScriptId());
    audit.setEventType(request.getEventType());
    audit.setEventSchemaVersion(schemaVersion);
    audit.setScriptPatchVersion(request.getScriptPatchVersion());
    audit.setScriptEventId(request.getScriptEventId());
    audit.setDryRun(request.getIsDryRun());
    audit.setSourceService(resolveSourceService());
    audit.setTriggerMode(request.getTriggerMode().name());
    audit.setWorkItemId(workItem.getId());
    audit.setFinalStage("ADMISSION");
    audit.setFinalOutcome("work_item_persisted");
    audit.setFinalReason("handler_resolved");
    eventAuditRepository.save(audit);
  }

  private boolean matchesScope(ScriptEventBinding binding, TriggerScriptEventRequest request) {
    return switch (binding.getTargetScopeType()) {
      case "GLOBAL" -> binding.getTargetScopeId().isBlank();
      case "ENTITY" -> binding.getTargetScopeId().equals(request.getEntityId());
      case "REGION" -> binding.getTargetScopeId().equals(request.getRegionId());
      default -> false;
    };
  }

  private ScriptEventIngressAudit findExisting(
      TriggerScriptEventRequest request, String schemaVersion) {
    if (request.getTenantId().isBlank()
        || request.getEventType().isBlank()
        || request.getScriptPatchVersion().isBlank()
        || request.getScriptEventId().isBlank()) {
      return null;
    }
    var existing =
        repository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
                request.getTenantId(),
                normalize(request.getGameInstanceId()),
                normalize(request.getRegionId()),
                request.getRegionEpoch() > 0 ? request.getRegionEpoch() : 0L,
                normalize(request.getEntityId()),
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
}
