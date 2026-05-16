package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.config.ScriptOutputProperties;
import net.firedevops.firemud.automationscripting.config.ScriptRuntimeProperties;
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
import net.firedevops.firemud.automationscripting.service.quota.ScriptDryRunQuotaService;
import net.firedevops.firemud.automationscripting.service.quota.ScriptQuotaService;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.automationscripting.v1.TriggerAdmissionOutcome;
import net.firedevops.firemud.automationscripting.v1.TriggerMode;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
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
  private static final String OUTCOME_QUOTA_DENIED =
      TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_QUOTA_DENIED.name();

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
  private final ScriptQuotaService quotaService;
  private final ScriptDryRunQuotaService dryRunQuotaService;
  private final ScriptRuntimeProperties runtimeProperties;

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
      PluginRuntimeStateService pluginRuntimeStateService,
      ScriptQuotaService quotaService,
      ScriptDryRunQuotaService dryRunQuotaService) {
    this(
        repository,
        bindingRepository,
        workItemRepository,
        eventAuditRepository,
        eventRegistryService,
        automationQueueService,
        outputProperties,
        gameSessionControlPlaneClient,
        automationAdmissionStateService,
        scriptPatchPinProjectionService,
        rolloutProjectionService,
        pluginRuntimeStateService,
        quotaService,
        dryRunQuotaService,
        new ScriptRuntimeProperties());
  }

  @org.springframework.beans.factory.annotation.Autowired
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
      PluginRuntimeStateService pluginRuntimeStateService,
      ScriptQuotaService quotaService,
      ScriptDryRunQuotaService dryRunQuotaService,
      ScriptRuntimeProperties runtimeProperties) {
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
    this.quotaService = quotaService;
    this.dryRunQuotaService = dryRunQuotaService;
    this.runtimeProperties = runtimeProperties;
  }

  @Override
  @Transactional
  public TriggerAdmission admit(TriggerScriptEventRequest request) {
    return admit(request, resolveSourceService());
  }

  @Override
  @Transactional
  public TriggerAdmission admit(TriggerScriptEventRequest request, String sourceService) {
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
      admission = admissionWithHandlers(request, schemaVersion, sourceService);
    }
    HandlerScopeValues requestScopeValues = requestScopeValues(request);
    ScriptEventIngressAudit audit = new ScriptEventIngressAudit();
    audit.setTenantId(requiredText(request.getTenantId(), "tenant_id"));
    audit.setGameInstanceId(normalize(request.getGameInstanceId()));
    audit.setRegionId(normalize(request.getRegionId()));
    audit.setRegionEpoch(request.getRegionEpoch() > 0 ? request.getRegionEpoch() : 0L);
    audit.setEntityId(requestScopeValues.entityId());
    audit.setPlayableStateScope(requestScopeValues.playableStateScope());
    audit.setWorldSlug(requestScopeValues.worldSlug());
    audit.setRealmSlug(requestScopeValues.realmSlug());
    audit.setPointerVersion(requestScopeValues.pointerVersion());
    audit.setScriptId(normalize(request.getScriptId()));
    audit.setPluginId(normalize(request.getPluginId()));
    audit.setPluginVersionId(normalize(request.getPluginVersionId()));
    audit.setEventType(requiredText(request.getEventType(), "event_type"));
    audit.setEventSchemaVersion(schemaVersion);
    audit.setScriptPatchVersion(
        requiredText(request.getScriptPatchVersion(), "script_patch_version"));
    audit.setScriptEventId(requiredText(request.getScriptEventId(), "script_event_id"));
    audit.setSourceService(sourceService);
    audit.setTriggerMode(request.getTriggerMode().name());
    audit.setSourceKind(sourceKind(request));
    audit.setSourceState(admission.admitted() ? "TRIGGER_ADMITTED" : "TRIGGER_REJECTED");
    audit.setSourceOrdinal(sourceOrdinal(request));
    audit.setSourceDueTickId(sourceDueTickId(request));
    audit.setSourceDueAtMs(sourceDueAtMs(request));
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
    if (isOnLoadRequest(request) && request.getScriptId().isBlank()) {
      return rejected("missing_script_identity");
    }
    if (definition.requiredTriggerIdentityFields().contains("regionEpoch")) {
      if (request.getGameInstanceId().isBlank()
          || request.getRegionId().isBlank()
          || request.getRegionEpoch() <= 0
          || request.getEntityId().isBlank()) {
        return rejected("missing_trigger_identity");
      }
    }
    if (definition.requiredTriggerIdentityFields().contains("playableStateScope")
        && (request.getPlayableStateScopeValue() == 0
            || request.getPlayableStateScope().name().equals("UNRECOGNIZED"))) {
      return rejected("missing_trigger_identity");
    }
    TriggerAdmission routingAdmission = validateGameplayRoutingBundle(request, sourceService);
    if (routingAdmission != null) {
      return routingAdmission;
    }
    TriggerAdmission pinAdmission = validatePinnedPatch(request);
    if (pinAdmission != null) {
      return pinAdmission;
    }
    TriggerAdmission pluginAdmission = validatePluginRuntimeState(request);
    if (pluginAdmission != null) {
      return pluginAdmission;
    }
    TriggerAdmission dryRunAdmission = validateDryRunBudget(request);
    if (dryRunAdmission != null) {
      return dryRunAdmission;
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

  private TriggerAdmission validateGameplayRoutingBundle(
      TriggerScriptEventRequest request, String sourceService) {
    if (!"game-session-service".equals(sourceService)
        || request.getGameInstanceId().isBlank()
        || request.getPlayableStateScopeValue() == 0) {
      return null;
    }
    if (request.getWorldSlug().isBlank()
        || request.getRealmSlug().isBlank()
        || request.getPointerVersion().isBlank()) {
      return rejected("missing_gameplay_routing_bundle");
    }
    return null;
  }

  private TriggerAdmission validatePinnedPatch(TriggerScriptEventRequest request) {
    if (request.getGameInstanceId().isBlank()) {
      return null;
    }
    var runtime =
        gameSessionControlPlaneClient.getGameInstanceRuntimeState(
            request.getTenantId(), request.getGameInstanceId(), request.getRegionId());
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
    if (request.getPlayableStateScopeValue() != 0
        && runtime.getRuntimeState().getPlayableStateScopeValue() != 0
        && request.getPlayableStateScope() != runtime.getRuntimeState().getPlayableStateScope()) {
      return new TriggerAdmission(
          false, OUTCOME_VERSION_UNAVAILABLE, "playable_state_scope_mismatch", 0);
    }
    if (!request.getRegionId().isBlank()
        && !runtime.getRuntimeState().getRegionId().isBlank()
        && !request.getRegionId().equals(runtime.getRuntimeState().getRegionId())) {
      return new TriggerAdmission(
          false, OUTCOME_VERSION_UNAVAILABLE, "runtime_region_scope_advanced", 0);
    }
    if (request.getRegionEpoch() > 0
        && runtime.getRuntimeState().getRegionEpoch() > 0
        && request.getRegionEpoch() != runtime.getRuntimeState().getRegionEpoch()) {
      return new TriggerAdmission(
          false, OUTCOME_VERSION_UNAVAILABLE, "runtime_region_scope_advanced", 0);
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
    if (pluginPolicyStale(status.get())) {
      return new TriggerAdmission(
          false, OUTCOME_VERSION_UNAVAILABLE, "signer_policy_unavailable", 0);
    }
    return null;
  }

  private boolean pluginPolicyStale(PluginRuntimeStateService.PluginRuntimeStatus status) {
    long ageMs = Instant.now().toEpochMilli() - status.lastPolicyCheckedAtMs();
    return ageMs > runtimeProperties.getPluginPolicyStaleThresholdSeconds() * 1_000L;
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

  private TriggerAdmission validateDryRunBudget(TriggerScriptEventRequest request) {
    if (!request.getIsDryRun()) {
      return null;
    }
    String principalKey = dryRunPrincipalKey();
    if (principalKey.isBlank()) {
      return new TriggerAdmission(false, OUTCOME_QUOTA_DENIED, "dry_run_principal_missing", 0);
    }
    if (!dryRunQuotaService.tryAcquire(
        request.getTenantId(), request.getScriptId(), principalKey)) {
      return new TriggerAdmission(false, OUTCOME_QUOTA_DENIED, "dry_run_budget_exceeded", 0);
    }
    return null;
  }

  private TriggerAdmission admissionWithHandlers(
      TriggerScriptEventRequest request, String schemaVersion, String sourceService) {
    if (isOnLoadRequest(request)) {
      return admissionWithOnLoadHandler(request, schemaVersion, sourceService);
    }
    long tenantKey = Long.parseLong(request.getTenantId());
    List<ScriptEventBinding> handlers =
        bindingRepository
            .findByTenantIdAndScriptPatchVersionAndEventTypeAndEventSchemaVersionAndEnabledTrueOrderByPriorityAscScriptIdAsc(
                tenantKey, request.getScriptPatchVersion(), request.getEventType(), schemaVersion)
            .stream()
            .filter(
                binding ->
                    request.getScriptId().isBlank()
                        || binding.getScriptId().equals(request.getScriptId()))
            .filter(binding -> matchesScope(binding, request))
            .toList();
    handlers.forEach(binding -> admitHandler(request, schemaVersion, binding, sourceService));
    String reason = handlers.isEmpty() ? "admitted_no_handlers" : "admitted_handlers_resolved";
    return new TriggerAdmission(true, OUTCOME_ADMITTED, reason, handlers.size());
  }

  private TriggerAdmission admissionWithOnLoadHandler(
      TriggerScriptEventRequest request, String schemaVersion, String sourceService) {
    String scriptId = requiredText(request.getScriptId(), "script_id");
    if (handlerAuditExistsForScript(request, schemaVersion, scriptId)
        || workItemExistsForScript(request, schemaVersion, scriptId)) {
      return new TriggerAdmission(true, OUTCOME_ADMITTED, "admitted_handlers_resolved", 1);
    }
    persistWorkItemForScript(request, schemaVersion, scriptId, "", sourceService);
    return new TriggerAdmission(true, OUTCOME_ADMITTED, "admitted_handlers_resolved", 1);
  }

  private void admitHandler(
      TriggerScriptEventRequest request,
      String schemaVersion,
      ScriptEventBinding binding,
      String sourceService) {
    if (handlerAuditExists(request, schemaVersion, binding)) {
      return;
    }
    if (!request.getIsDryRun()
        && !isOnLoadRequest(request)
        && !quotaService.tryAcquire(request.getTenantId(), binding.getScriptId())) {
      persistHandlerAudit(
          request,
          schemaVersion,
          binding,
          sourceService,
          null,
          "ADMISSION",
          "quota_denied",
          "script_quota_denied");
      return;
    }
    persistWorkItem(
        request,
        schemaVersion,
        binding.getScriptId(),
        binding.getPriorityTag(),
        sourceService,
        requestScopeValues(request));
  }

  private void persistWorkItem(
      TriggerScriptEventRequest request,
      String schemaVersion,
      String scriptId,
      String priorityTag,
      String sourceService,
      HandlerScopeValues scopeValues) {
    Long admissionEpoch =
        request.getGameInstanceId().isBlank()
            ? 0L
            : automationAdmissionStateService
                .getState(request.getTenantId(), request.getGameInstanceId(), request.getRegionId())
                .admissionEpoch();
    ScriptWorkItem item = new ScriptWorkItem();
    item.setTenantId(request.getTenantId());
    item.setGameInstanceId(normalize(request.getGameInstanceId()));
    item.setRegionId(normalize(request.getRegionId()));
    item.setRegionEpoch(request.getRegionEpoch() > 0 ? request.getRegionEpoch() : 0L);
    item.setEntityId(scopeValues.entityId());
    item.setPlayableStateScope(scopeValues.playableStateScope());
    item.setWorldSlug(scopeValues.worldSlug());
    item.setRealmSlug(scopeValues.realmSlug());
    item.setPointerVersion(scopeValues.pointerVersion());
    item.setScriptId(scriptId);
    item.setPluginId(normalize(request.getPluginId()));
    item.setPluginVersionId(normalize(request.getPluginVersionId()));
    item.setEventType(request.getEventType());
    item.setEventSchemaVersion(schemaVersion);
    item.setScriptPatchVersion(request.getScriptPatchVersion());
    item.setScriptEventId(request.getScriptEventId());
    item.setDryRun(request.getIsDryRun());
    item.setSourceService(sourceService);
    item.setTriggerMode(request.getTriggerMode().name());
    item.setSourceKind(sourceKind(request));
    item.setSourceState("WORK_ITEM_PERSISTED");
    item.setSourceOrdinal(sourceOrdinal(request));
    item.setSourceDueTickId(sourceDueTickId(request));
    item.setSourceDueAtMs(sourceDueAtMs(request));
    item.setPriorityTag(priorityTag);
    item.setReadSnapshotToken(normalize(request.getReadSnapshotToken()));
    item.setPayloadJson(normalize(request.getPayloadJson()));
    item.setAdmissionEpoch(admissionEpoch);
    ScriptWorkItem saved = workItemRepository.save(item);
    rolloutProjectionService.refreshForWorkItem(saved);
    automationQueueService.enqueueWorkItem(saved);
    persistHandlerAudit(
        request,
        schemaVersion,
        scriptId,
        sourceService,
        saved,
        "ADMISSION",
        "work_item_persisted",
        "handler_resolved");
  }

  private void persistWorkItemForScript(
      TriggerScriptEventRequest request,
      String schemaVersion,
      String scriptId,
      String priorityTag,
      String sourceService) {
    persistWorkItem(
        request, schemaVersion, scriptId, priorityTag, sourceService, requestScopeValues(request));
  }

  private void persistHandlerAudit(
      TriggerScriptEventRequest request,
      String schemaVersion,
      ScriptEventBinding binding,
      String sourceService,
      ScriptWorkItem workItem,
      String finalStage,
      String finalOutcome,
      String finalReason) {
    persistHandlerAudit(
        request,
        schemaVersion,
        binding.getScriptId(),
        sourceService,
        workItem,
        finalStage,
        finalOutcome,
        finalReason);
  }

  private void persistHandlerAudit(
      TriggerScriptEventRequest request,
      String schemaVersion,
      String scriptId,
      String sourceService,
      ScriptWorkItem workItem,
      String finalStage,
      String finalOutcome,
      String finalReason) {
    HandlerScopeValues scopeValues =
        workItem == null ? requestScopeValues(request) : workItemScopeValues(workItem);
    ScriptEventAudit audit = new ScriptEventAudit();
    audit.setTenantId(request.getTenantId());
    audit.setGameInstanceId(normalize(request.getGameInstanceId()));
    audit.setRegionId(normalize(request.getRegionId()));
    audit.setRegionEpoch(request.getRegionEpoch() > 0 ? request.getRegionEpoch() : 0L);
    audit.setEntityId(scopeValues.entityId());
    audit.setPlayableStateScope(scopeValues.playableStateScope());
    audit.setWorldSlug(scopeValues.worldSlug());
    audit.setRealmSlug(scopeValues.realmSlug());
    audit.setPointerVersion(scopeValues.pointerVersion());
    audit.setScriptId(scriptId);
    audit.setPluginId(normalize(request.getPluginId()));
    audit.setPluginVersionId(normalize(request.getPluginVersionId()));
    audit.setEventType(request.getEventType());
    audit.setEventSchemaVersion(schemaVersion);
    audit.setScriptPatchVersion(request.getScriptPatchVersion());
    audit.setScriptEventId(request.getScriptEventId());
    audit.setDryRun(request.getIsDryRun());
    audit.setSourceService(sourceService);
    audit.setTriggerMode(request.getTriggerMode().name());
    audit.setSourceKind(sourceKind(request));
    audit.setSourceState(
        workItem == null ? finalOutcome.toUpperCase(Locale.ROOT) : "WORK_ITEM_PERSISTED");
    audit.setSourceOrdinal(sourceOrdinal(request));
    audit.setSourceDueTickId(sourceDueTickId(request));
    audit.setSourceDueAtMs(sourceDueAtMs(request));
    audit.setWorkItemId(workItem == null ? null : workItem.getId());
    audit.setFinalStage(finalStage);
    audit.setFinalOutcome(finalOutcome);
    audit.setFinalReason(finalReason);
    eventAuditRepository.save(audit);
  }

  private static String sourceKind(TriggerScriptEventRequest request) {
    if (isOnLoadRequest(request)) {
      return "PATCH_READINESS_EVENT";
    }
    if (request.getTriggerMode() == TriggerMode.TRIGGER_MODE_CATCH_UP) {
      return "TRIGGER_CATCH_UP_EVENT";
    }
    return "GAMEPLAY_EVENT";
  }

  private static Long sourceOrdinal(TriggerScriptEventRequest request) {
    Long dueAtMs = sourceDueAtMs(request);
    if (dueAtMs != null) {
      return dueAtMs;
    }
    return sourceDueTickId(request);
  }

  private static Long sourceDueTickId(TriggerScriptEventRequest request) {
    return request.getDueTickId() > 0 ? request.getDueTickId() : null;
  }

  private static Long sourceDueAtMs(TriggerScriptEventRequest request) {
    return request.getDueAtMs() > 0 ? request.getDueAtMs() : null;
  }

  private boolean handlerAuditExists(
      TriggerScriptEventRequest request, String schemaVersion, ScriptEventBinding binding) {
    return handlerAuditExistsForScope(
        request, schemaVersion, binding.getScriptId(), requestScopeValues(request));
  }

  private boolean handlerAuditExistsForScript(
      TriggerScriptEventRequest request, String schemaVersion, String scriptId) {
    return handlerAuditExistsForScope(
        request, schemaVersion, scriptId, requestScopeValues(request));
  }

  private boolean handlerAuditExistsForScope(
      TriggerScriptEventRequest request,
      String schemaVersion,
      String scriptId,
      HandlerScopeValues scopeValues) {
    return eventAuditRepository
        .existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndScriptIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
            request.getTenantId(),
            normalize(request.getGameInstanceId()),
            normalize(request.getRegionId()),
            request.getRegionEpoch() > 0 ? request.getRegionEpoch() : 0L,
            scopeValues.entityId(),
            scopeValues.playableStateScope(),
            scopeValues.worldSlug(),
            scopeValues.realmSlug(),
            scopeValues.pointerVersion(),
            scriptId,
            request.getEventType(),
            schemaVersion,
            request.getScriptPatchVersion(),
            request.getScriptEventId(),
            request.getIsDryRun());
  }

  private boolean workItemExists(
      TriggerScriptEventRequest request, String schemaVersion, ScriptEventBinding binding) {
    return workItemExistsForScope(
        request, schemaVersion, binding.getScriptId(), requestScopeValues(request));
  }

  private boolean workItemExistsForScript(
      TriggerScriptEventRequest request, String schemaVersion, String scriptId) {
    return workItemExistsForScope(request, schemaVersion, scriptId, requestScopeValues(request));
  }

  private boolean workItemExistsForScope(
      TriggerScriptEventRequest request,
      String schemaVersion,
      String scriptId,
      HandlerScopeValues scopeValues) {
    return workItemRepository
        .existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndScriptIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
            request.getTenantId(),
            normalize(request.getGameInstanceId()),
            normalize(request.getRegionId()),
            request.getRegionEpoch() > 0 ? request.getRegionEpoch() : 0L,
            scopeValues.entityId(),
            scopeValues.playableStateScope(),
            scopeValues.worldSlug(),
            scopeValues.realmSlug(),
            scopeValues.pointerVersion(),
            scriptId,
            request.getEventType(),
            schemaVersion,
            request.getScriptPatchVersion(),
            request.getScriptEventId(),
            request.getIsDryRun());
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
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(
            request.getWorldSlug(), request.getRealmSlug(), request.getPointerVersion());
    var existing =
        repository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
                request.getTenantId(),
                normalize(request.getGameInstanceId()),
                normalize(request.getRegionId()),
                request.getRegionEpoch() > 0 ? request.getRegionEpoch() : 0L,
                normalize(request.getEntityId()),
                normalizePlayableStateScope(request.getPlayableStateScope()),
                routingBundle.worldSlug(),
                routingBundle.realmSlug(),
                routingBundle.pointerVersion(),
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

  private static String dryRunPrincipalKey() {
    String accountId = SessionContext.getAccountId();
    if (accountId != null && !accountId.isBlank()) {
      return "account:" + accountId;
    }
    String serviceName = SessionContext.getServiceName();
    if (serviceName != null && !serviceName.isBlank()) {
      String serviceInstanceId = normalize(SessionContext.getServiceInstanceId());
      return serviceInstanceId.isBlank()
          ? "service:" + serviceName
          : "service:" + serviceName + ":" + serviceInstanceId;
    }
    return SessionContext.hasGlobalPrivilegedRole() ? "operator" : "";
  }

  private static String normalizePlayableStateScope(PlayableStateScope playableStateScope) {
    if (playableStateScope == null) {
      return "";
    }
    return switch (playableStateScope) {
      case PLAYABLE_STATE_SCOPE_SHARED -> "SHARED";
      case PLAYABLE_STATE_SCOPE_ISOLATED -> "ISOLATED";
      case PLAYABLE_STATE_SCOPE_UNSPECIFIED, UNRECOGNIZED -> "";
    };
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

  private static boolean isOnLoadRequest(TriggerScriptEventRequest request) {
    return "onLoad".equals(request.getEventType());
  }

  private static HandlerScopeValues requestScopeValues(TriggerScriptEventRequest request) {
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(
            request.getWorldSlug(), request.getRealmSlug(), request.getPointerVersion());
    return new HandlerScopeValues(
        normalize(request.getEntityId()),
        normalizePlayableStateScope(request.getPlayableStateScope()),
        routingBundle.worldSlug(),
        routingBundle.realmSlug(),
        routingBundle.pointerVersion());
  }

  private static HandlerScopeValues workItemScopeValues(ScriptWorkItem workItem) {
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(
            workItem.getWorldSlug(), workItem.getRealmSlug(), workItem.getPointerVersion());
    return new HandlerScopeValues(
        normalize(workItem.getEntityId()),
        normalize(workItem.getPlayableStateScope()),
        routingBundle.worldSlug(),
        routingBundle.realmSlug(),
        routingBundle.pointerVersion());
  }

  private record HandlerScopeValues(
      String entityId,
      String playableStateScope,
      String worldSlug,
      String realmSlug,
      String pointerVersion) {}
}
