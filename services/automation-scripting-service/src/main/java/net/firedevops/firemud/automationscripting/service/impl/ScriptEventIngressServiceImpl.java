package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.config.ScriptOutputProperties;
import net.firedevops.firemud.automationscripting.config.ScriptRuntimeProperties;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;
import net.firedevops.firemud.automationscripting.entity.ScriptEventIngressAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
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
import net.firedevops.firemud.automationscripting.service.ScriptQuotaClasses;
import net.firedevops.firemud.automationscripting.service.quota.ScriptDryRunQuotaService;
import net.firedevops.firemud.automationscripting.service.quota.ScriptQuotaService;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.automationscripting.v1.TriggerAdmissionOutcome;
import net.firedevops.firemud.automationscripting.v1.TriggerMode;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected Spring dependencies are not exposed externally")
public class ScriptEventIngressServiceImpl implements ScriptEventIngressService {
  private static final Logger LOGGER = LoggerFactory.getLogger(ScriptEventIngressServiceImpl.class);
  private static final String SCOPE_ACTION_CATEGORY = "ACTION_CATEGORY";
  private static final String SCOPE_ACTION_TAG = "ACTION_TAG";
  private static final String DEFAULT_SCHEMA_VERSION = "v1";
  private static final String SCOPE_COMMAND_ALIAS = "COMMAND_ALIAS";
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

  /**
   * The ingress row is claimed before the admission decision exists. Keep the placeholder a valid
   * wire outcome so a concurrent caller can receive a retryable response without attempting any
   * handler work. The durable state is carried by sourceState=IN_PROGRESS.
   */
  private static final String IN_PROGRESS_REASON = "ingress_in_progress";
  private static final String RECLAIMED_STALE_REASON = "ingress_reclaimed_stale";

  private static final String IN_PROGRESS_STATE = "IN_PROGRESS";

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
  private final ScriptDefinitionRepository scriptDefinitionRepository;
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
        null,
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
      ScriptDefinitionRepository scriptDefinitionRepository,
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
    this.scriptDefinitionRepository = scriptDefinitionRepository;
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
    long tenantKey = RequestIdValidation.requirePositiveLong(request.getTenantId(), "tenantId");
    validateGameplayRoutingBundleShape(request, sourceService);
    TriggerAdmission pinTupleAdmission = validatePinTupleShape(request);
    if (pinTupleAdmission != null) {
      return pinTupleAdmission;
    }
    String schemaVersion = schemaVersion(request);
    ScriptEventRegistryService.EventDefinition definition =
        eventRegistryService.getDefinition(request.getEventType(), schemaVersion).orElse(null);

    // Claim the complete event identity before validation that can consult mutable runtime state,
    // before quota acquisition, and before resolving or materializing any handler. PostgreSQL's
    // unique-index-backed insert waits for a concurrent winner and returns that exact row, so the
    // loser cannot race a lookup with a second fan-out.
    ScriptEventIngressAudit claimRequest =
        buildIngressAudit(
            request,
            schemaVersion,
            definition,
            sourceService,
            new TriggerAdmission(false, OUTCOME_REGISTRY_REJECTED, IN_PROGRESS_REASON, 0),
            IN_PROGRESS_STATE);
    ScriptEventIngressAuditRepository.IdempotentInsertResult claim =
        repository.insertIfAbsentByIdentity(claimRequest);
    if (claim != null) {
      ScriptEventIngressAudit claimedAudit = claim.audit();
      if (claimedAudit == null) {
        throw new IllegalStateException("Event identity claim returned no audit row");
      }
      if (claim.inserted()) {
        claimRequest = claimedAudit;
      } else {
        if (isStaleInProgress(claimedAudit)) {
          Instant now = Instant.now();
          ScriptEventIngressAudit reclaimed =
              repository
                  .reclaimStaleInProgress(
                      claimedAudit,
                      now.minusMillis(runtimeProperties.getIngressClaimStaleThresholdMs()),
                      now)
                  .orElse(null);
          if (reclaimed != null) {
            LOGGER.warn(
                "Reclaimed stale script ingress claim id={} rowVersion={} reason={}",
                claimedAudit.getId(),
                claimedAudit.getRowVersion(),
                RECLAIMED_STALE_REASON);
            claimRequest = reclaimed;
          } else {
            return admissionFromStoredIngress(claimedAudit);
          }
        } else {
          return admissionFromStoredIngress(claimedAudit);
        }
      }
    } else {
      // Existing unit tests use a Mockito repository that predates the claim API. Keep this
      // fallback inert for the real repository (which always returns a result), while retaining
      // the old read path for those tests and for a misconfigured test double.
      ScriptEventIngressAudit existing = findExisting(request, schemaVersion, sourceService);
      if (existing != null) {
        return admissionFromStoredIngress(existing);
      }
    }

    PinEpochHolder pinEpoch = new PinEpochHolder();
    TriggerAdmission admission =
        validate(request, schemaVersion, sourceService, definition, pinEpoch);
    if (admission.admitted()) {
      AdmissionStateValidation stateValidation = validateAdmissionState(request);
      admission = stateValidation.admission();
      if (admission.admitted()) {
        TriggerAdmission dryRunAdmission = validateDryRunBudget(request);
        admission =
            dryRunAdmission != null
                ? dryRunAdmission
                : admissionWithHandlers(
                    request,
                    schemaVersion,
                    definition,
                    sourceService,
                    tenantKey,
                    stateValidation.state(),
                    pinEpoch.value);
      }
    }
    ScriptEventIngressAudit audit = claimRequest;
    audit.setAdmitted(admission.admitted());
    audit.setAdmissionOutcome(admission.outcome());
    audit.setAdmissionReason(admission.reason());
    audit.setResolvedHandlerCount(admission.resolvedHandlerCount());
    audit.setSourceState(admission.admitted() ? "TRIGGER_ADMITTED" : "TRIGGER_REJECTED");
    ScriptEventIngressAudit finalized = repository.save(audit);
    // save returns the fenced winner. Returning its fields makes retries converge on the durable
    // result even if the repository implementation normalizes or repairs the stored response.
    return finalized == null ? admission : admissionFromStoredIngress(finalized);
  }

  private ScriptEventIngressAudit buildIngressAudit(
      TriggerScriptEventRequest request,
      String schemaVersion,
      ScriptEventRegistryService.EventDefinition definition,
      String sourceService,
      TriggerAdmission admission,
      String sourceState) {
    HandlerScopeValues requestScopeValues = requestScopeValues(request);
    ScriptEventIngressAudit audit = new ScriptEventIngressAudit();
    audit.setTenantId(requiredText(request.getTenantId(), "tenant_id"));
    boolean instanceScoped = !request.getGameInstanceId().isBlank();
    audit.setGameInstanceId(nullableText(request.getGameInstanceId()));
    audit.setRegionId(instanceScoped ? nullableText(request.getRegionId()) : null);
    audit.setRegionEpoch(
        instanceScoped && request.getRegionEpoch() > 0 ? request.getRegionEpoch() : null);
    audit.setEntityId(instanceScoped ? requestScopeValues.entityId() : null);
    audit.setPlayableStateScope(instanceScoped ? requestScopeValues.playableStateScope() : null);
    audit.setWorldSlug(instanceScoped ? requestScopeValues.worldSlug() : null);
    audit.setRealmSlug(instanceScoped ? requestScopeValues.realmSlug() : null);
    audit.setPointerVersion(instanceScoped ? requestScopeValues.pointerVersion() : null);
    audit.setScriptId(normalize(request.getScriptId()));
    audit.setPluginId(normalize(request.getPluginId()));
    audit.setPluginVersionId(normalize(request.getPluginVersionId()));
    audit.setEventType(requiredText(request.getEventType(), "event_type"));
    audit.setEventSchemaVersion(schemaVersion);
    audit.setQuotaClass(
        ScriptQuotaClasses.normalize(definition == null ? null : definition.quotaClass()));
    audit.setScriptPatchVersion(
        requiredText(request.getScriptPatchVersion(), "script_patch_version"));
    audit.setScriptPinEpoch(
        instanceScoped && request.getScriptPinEpoch() > 0L ? request.getScriptPinEpoch() : null);
    audit.setScriptPinControlPlaneRequestId(
        instanceScoped ? normalize(request.getScriptPinControlPlaneRequestId()) : null);
    audit.setClaimStartedAt(Instant.now());
    audit.setScriptEventId(requiredText(request.getScriptEventId(), "script_event_id"));
    audit.setSourceService(sourceService);
    audit.setTriggerMode(request.getTriggerMode().name());
    audit.setSourceKind(sourceKind(request));
    audit.setSourceState(sourceState);
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
    return audit;
  }

  private TriggerAdmission admissionFromStoredIngress(ScriptEventIngressAudit audit) {
    if (IN_PROGRESS_STATE.equals(audit.getSourceState())) {
      return new TriggerAdmission(false, OUTCOME_REGISTRY_REJECTED, IN_PROGRESS_REASON, 0);
    }
    return new TriggerAdmission(
        audit.isAdmitted(),
        audit.getAdmissionOutcome(),
        audit.getAdmissionReason(),
        audit.getResolvedHandlerCount());
  }

  private boolean isStaleInProgress(ScriptEventIngressAudit audit) {
    if (!IN_PROGRESS_STATE.equals(audit.getSourceState())) {
      return false;
    }
    Instant startedAt =
        audit.getClaimStartedAt() == null ? audit.getCreatedAt() : audit.getClaimStartedAt();
    return startedAt != null
        && startedAt.plusMillis(runtimeProperties.getIngressClaimStaleThresholdMs()).isBefore(Instant.now());
  }

  private TriggerAdmission validate(
      TriggerScriptEventRequest request,
      String schemaVersion,
      String sourceService,
      ScriptEventRegistryService.EventDefinition definition,
      PinEpochHolder pinEpoch) {
    requiredText(request.getTenantId(), "tenant_id");
    requiredText(request.getEventType(), "event_type");
    requiredText(request.getScriptPatchVersion(), "script_patch_version");
    requiredText(request.getScriptEventId(), "script_event_id");
    if (!request.getGameInstanceId().isBlank() && request.getScriptPinEpoch() <= 0L) {
      return rejected("missing_script_pin_epoch");
    }
    if (!request.getGameInstanceId().isBlank()
        && request.getScriptPinEpoch() > 0L
        && request.getScriptPinControlPlaneRequestId().isBlank()) {
      return rejected("missing_script_pin_control_plane_request_id");
    }
    if (request.getPayloadJson().getBytes(StandardCharsets.UTF_8).length
        > outputProperties.getMaxSerializedWorkItemBytes()) {
      return new TriggerAdmission(
          false, OUTCOME_OUTPUT_BUDGET_EXCEEDED, "work_item_size_exceeded", 0);
    }
    if (definition == null) {
      return rejected("unknown_event_type");
    }
    if (!definition.allowedProducerPrincipals().contains(sourceService)) {
      return rejected("unauthorized_producer");
    }
    if (definition.snapshotAuthority().equals("PRODUCER_SUPPLIED_TOKEN")
        && request.getReadSnapshotToken().isBlank()) {
      return rejected("missing_snapshot_token");
    }
    TriggerAdmission payloadAdmission = validateBuiltInPayload(request);
    if (payloadAdmission != null) {
      return payloadAdmission;
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
    PinValidation pinValidation = validatePinnedPatch(request);
    TriggerAdmission pinAdmission = pinValidation.admission();
    pinEpoch.value = pinValidation.scriptPinEpoch();
    if (pinAdmission != null) {
      return pinAdmission;
    }
    TriggerAdmission pluginAdmission = validatePluginRuntimeState(request);
    if (pluginAdmission != null) {
      return pluginAdmission;
    }
    return new TriggerAdmission(true, OUTCOME_ADMITTED, "admitted_for_handler_resolution", 0);
  }

  private TriggerAdmission rejected(String reason) {
    return new TriggerAdmission(false, OUTCOME_REGISTRY_REJECTED, reason, 0);
  }

  /** Validates the nullable/unpinned branches before they can reach the durable claim. */
  private TriggerAdmission validatePinTupleShape(TriggerScriptEventRequest request) {
    boolean instanceScoped = !request.getGameInstanceId().isBlank();
    boolean hasEpoch = request.getScriptPinEpoch() != 0L;
    boolean hasRequestId = !request.getScriptPinControlPlaneRequestId().isBlank();
    if (isOnLoadRequest(request) && instanceScoped) {
      return rejected("on_load_must_be_pre_instance");
    }
    if (!instanceScoped || isOnLoadRequest(request)) {
      if (hasEpoch || hasRequestId) {
        return rejected("unexpected_script_pin_tuple");
      }
      return null;
    }
    if (request.getScriptPinEpoch() <= 0L) {
      return rejected(
          hasRequestId ? "script_pin_request_id_without_epoch" : "missing_script_pin_epoch");
    }
    return hasRequestId ? null : rejected("missing_script_pin_control_plane_request_id");
  }

  private TriggerAdmission validateBuiltInPayload(TriggerScriptEventRequest request) {
    String eventType = request.getEventType();
    if ("onLoad".equals(eventType)) {
      return null;
    }
    Map<String, Object> payload = parsePayloadObject(request.getPayloadJson());
    if (payload == null) {
      return rejected("invalid_built_in_payload");
    }
    return switch (eventType) {
      case "onCommand" ->
          requirePayloadFields(payload, "commandId", "commandName")
              ? null
              : rejected("invalid_built_in_payload");
      case "onSpawn" ->
          requirePayloadFields(payload, "spawnReason")
              ? null
              : rejected("invalid_built_in_payload");
      case "onEnterRegion" ->
          requirePayloadFields(payload, "toRegionId") ? null : rejected("invalid_built_in_payload");
      case "onLeaveRegion" ->
          requirePayloadFields(payload, "fromRegionId")
              ? null
              : rejected("invalid_built_in_payload");
      case "onTimerExpire", "onInterval" ->
          requirePayloadFields(payload, "scheduleId") && hasDuePointIdentity(payload)
              ? null
              : rejected("invalid_built_in_payload");
      default -> null;
    };
  }

  private Map<String, Object> parsePayloadObject(String payloadJson) {
    if (payloadJson == null || payloadJson.isBlank()) {
      return null;
    }
    try {
      return JsonParserFactory.getJsonParser().parseMap(payloadJson);
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private boolean requirePayloadFields(Map<String, Object> payload, String... fields) {
    for (String field : fields) {
      if (!hasTextValue(payload.get(field))) {
        return false;
      }
    }
    return true;
  }

  private boolean hasDuePointIdentity(Map<String, Object> payload) {
    boolean hasDueTickId = payload.containsKey("dueTickId");
    boolean hasDueAt = payload.containsKey("dueAt");
    // A scheduler payload is tagged by exactly one due-point field. Treat a present
    // null/invalid value as invalid rather than allowing the alternate field to mask it.
    if (hasDueTickId == hasDueAt) {
      return false;
    }
    return hasDueTickId
        ? hasPositiveLongValue(payload.get("dueTickId"))
        : hasPositiveLongValue(payload.get("dueAt"));
  }

  private boolean hasTextValue(Object value) {
    return value instanceof String text && !text.isBlank();
  }

  private boolean hasPositiveLongValue(Object value) {
    return ScriptCommandMetadataSupport.hasPositiveDueTick(value);
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
    validateGameplayRoutingBundleShape(request, sourceService);
    return null;
  }

  private void validateGameplayRoutingBundleShape(
      TriggerScriptEventRequest request, String sourceService) {
    if (!"game-session-service".equals(sourceService)
        || request.getGameInstanceId().isBlank()
        || request.getPlayableStateScopeValue() == 0
        || request.getWorldSlug().isBlank()
        || request.getRealmSlug().isBlank()
        || request.getPointerVersion().isBlank()) {
      return;
    }
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(
            request.getWorldSlug(), request.getRealmSlug(), request.getPointerVersion());
    if (!routingBundle.isPresent()) {
      RequestIdValidation.requirePositiveLong(request.getPointerVersion(), "pointerVersion");
      throw new IllegalArgumentException("routing bundle is invalid");
    }
  }

  private PinValidation validatePinnedPatch(TriggerScriptEventRequest request) {
    if (request.getGameInstanceId().isBlank()) {
      return new PinValidation(null, 0L);
    }
    var runtime =
        gameSessionControlPlaneClient.getGameInstanceRuntimeState(
            request.getTenantId(), request.getGameInstanceId(), request.getRegionId());
    if (runtime == null || (runtime.hasError() && !runtime.getError().getCode().isBlank())) {
      return new PinValidation(
          new TriggerAdmission(false, OUTCOME_PIN_STATE_UNAVAILABLE, "pin_state_unavailable", 0),
          0L);
    }
    if (!runtime.hasRuntimeState()) {
      return new PinValidation(
          new TriggerAdmission(false, OUTCOME_PIN_STATE_UNAVAILABLE, "pin_state_unavailable", 0),
          0L);
    }
    var runtimeState = runtime.getRuntimeState();
    if (!request.getTenantId().equals(runtimeState.getTenantId())
        || !request.getGameInstanceId().equals(runtimeState.getGameInstanceId())) {
      // A successful owner RPC with a different scope is not authority for this request. Do not
      // project it before rejecting the trigger.
      return new PinValidation(
          new TriggerAdmission(false, OUTCOME_PIN_STATE_UNAVAILABLE, "pin_state_unavailable", 0),
          0L);
    }
    if (runtimeState.getScriptPinEpoch() <= 0) {
      return new PinValidation(
          new TriggerAdmission(false, OUTCOME_PIN_STATE_UNAVAILABLE, "pin_state_unavailable", 0),
          0L);
    }
    String observedPinRequestId = runtimeState.getScriptPatchPinnedControlPlaneRequestId();
    if (observedPinRequestId.isBlank()) {
      return new PinValidation(
          new TriggerAdmission(false, OUTCOME_PIN_STATE_UNAVAILABLE, "pin_state_unavailable", 0),
          0L);
    }
    if (!request.getScriptPatchVersion().equals(runtimeState.getPinnedScriptPatchVersion())) {
      return new PinValidation(
          new TriggerAdmission(false, OUTCOME_VERSION_UNAVAILABLE, "version_unavailable", 0),
          request.getScriptPinEpoch());
    }
    if (request.getScriptPinEpoch() != runtimeState.getScriptPinEpoch()) {
      return new PinValidation(
          new TriggerAdmission(false, OUTCOME_VERSION_UNAVAILABLE, "script_pin_epoch_mismatch", 0),
          request.getScriptPinEpoch());
    }
    if (!request.getScriptPinControlPlaneRequestId().equals(observedPinRequestId)) {
      return new PinValidation(
          new TriggerAdmission(
              false,
              OUTCOME_VERSION_UNAVAILABLE,
              "script_pin_control_plane_request_id_mismatch",
              0),
          request.getScriptPinEpoch());
    }
    scriptPatchPinProjectionService.observeRuntimeState(
        request.getTenantId(), request.getGameInstanceId(), runtimeState);
    if (request.getPlayableStateScopeValue() != 0
        && runtimeState.getPlayableStateScopeValue() != 0
        && request.getPlayableStateScope() != runtimeState.getPlayableStateScope()) {
      return new PinValidation(
          new TriggerAdmission(
              false, OUTCOME_VERSION_UNAVAILABLE, "playable_state_scope_mismatch", 0),
          runtimeState.getScriptPinEpoch());
    }
    if (!request.getRegionId().isBlank()
        && !runtimeState.getRegionId().isBlank()
        && !request.getRegionId().equals(runtimeState.getRegionId())) {
      return new PinValidation(
          new TriggerAdmission(
              false, OUTCOME_VERSION_UNAVAILABLE, "runtime_region_scope_advanced", 0),
          runtimeState.getScriptPinEpoch());
    }
    if (request.getRegionEpoch() > 0
        && runtimeState.getRegionEpoch() > 0
        && request.getRegionEpoch() != runtimeState.getRegionEpoch()) {
      return new PinValidation(
          new TriggerAdmission(
              false, OUTCOME_VERSION_UNAVAILABLE, "runtime_region_scope_advanced", 0),
          runtimeState.getScriptPinEpoch());
    }
    return new PinValidation(null, runtimeState.getScriptPinEpoch());
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

  private AdmissionStateValidation validateAdmissionState(TriggerScriptEventRequest request) {
    if (request.getGameInstanceId().isBlank()) {
      return AdmissionStateValidation.admitted(null);
    }
    AutomationAdmissionStateService.AdmissionStateSummary state =
        automationAdmissionStateService.getState(
            request.getTenantId(), request.getGameInstanceId(), request.getRegionId());
    if (state == null || !isUsableAdmissionMode(state.mode())) {
      return AdmissionStateValidation.rejected(
          new TriggerAdmission(
              false, OUTCOME_VERSION_UNAVAILABLE, "admission_state_unavailable", 0));
    }
    if ("PAUSED_FOR_ROLLBACK".equals(state.mode())) {
      return AdmissionStateValidation.rejected(
          new TriggerAdmission(false, OUTCOME_BACKPRESSURE_ROLLBACK, "rollback_paused", 0));
    }
    return AdmissionStateValidation.admitted(state);
  }

  private static boolean isUsableAdmissionMode(String mode) {
    return "NORMAL".equals(mode) || "PAUSED_FOR_ROLLBACK".equals(mode);
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
      TriggerScriptEventRequest request,
      String schemaVersion,
      ScriptEventRegistryService.EventDefinition definition,
      String sourceService,
      long tenantKey,
      AutomationAdmissionStateService.AdmissionStateSummary admissionState,
      long scriptPinEpoch) {
    if (isOnLoadRequest(request)) {
      return admissionWithOnLoadHandler(
          request, schemaVersion, definition, sourceService, admissionState, scriptPinEpoch);
    }
    Map<String, Object> payload = parsePayloadObject(request.getPayloadJson());
    List<ScriptEventBinding> scopedBindings =
        bindingRepository
            .findByTenantIdAndScriptPatchVersionAndEventTypeAndEventSchemaVersionAndEnabledTrueOrderByPriorityAscScriptIdAsc(
                tenantKey, request.getScriptPatchVersion(), request.getEventType(), schemaVersion)
            .stream()
            .filter(
                binding ->
                    request.getScriptId().isBlank()
                        || binding.getScriptId().equals(request.getScriptId()))
            .filter(binding -> matchesScope(binding, request, payload))
            .toList();
    PluginOwnerResolution ownerResolution =
        scriptDefinitionRepository == null
            ? PluginOwnerResolution.EMPTY
            : resolvePluginOwners(
                tenantKey, request.getScriptPatchVersion(), request.getEventType(), scopedBindings);
    Map<String, PluginOwner> ownersByScriptId = ownerResolution.ownersByScriptId();
    if (ownerResolution.hasUnresolvedPluginOwner()) {
      return new TriggerAdmission(
          false, OUTCOME_VERSION_UNAVAILABLE, "plugin_binding_unresolved", 0);
    }
    boolean filterByRequestPluginOwnership =
        scriptDefinitionRepository != null
            && !request.getPluginId().isBlank()
            && !request.getPluginVersionId().isBlank();
    Map<String, String> activePluginVersions =
        scriptDefinitionRepository == null
                || request.getGameInstanceId().isBlank()
                || ownersByScriptId.isEmpty()
            ? Map.of()
            : pluginRuntimeStateService.getActivePluginVersions(
                request.getTenantId(),
                request.getGameInstanceId(),
                normalize(request.getRegionId()),
                request.getRegionEpoch());
    List<ResolvedHandler> handlers =
        scopedBindings.stream()
            .map(
                binding ->
                    new ResolvedHandler(binding, ownersByScriptId.get(binding.getScriptId())))
            .filter(
                handler ->
                    participatesInResolvedHandlerSet(handler.pluginOwner(), activePluginVersions))
            .filter(
                handler ->
                    !filterByRequestPluginOwnership
                        || hasMatchingPluginOwner(handler.pluginOwner(), request))
            .toList();
    if ((filterByRequestPluginOwnership && handlers.isEmpty())
        || (handlers.isEmpty()
            && scopedBindings.stream()
                .anyMatch(binding -> isPluginOwned(ownersByScriptId, binding)))) {
      return new TriggerAdmission(
          false, OUTCOME_VERSION_UNAVAILABLE, "plugin_binding_unresolved", 0);
    }
    handlers.forEach(
        handler ->
            admitHandler(
                request,
                schemaVersion,
                definition,
                handler,
                sourceService,
                admissionState,
                scriptPinEpoch));
    String reason = handlers.isEmpty() ? "admitted_no_handlers" : "admitted_handlers_resolved";
    return new TriggerAdmission(true, OUTCOME_ADMITTED, reason, handlers.size());
  }

  private boolean hasMatchingPluginOwner(PluginOwner owner, TriggerScriptEventRequest request) {
    return owner != null
        && owner.pluginId().equals(request.getPluginId())
        && owner.pluginVersionId().equals(request.getPluginVersionId());
  }

  private PluginOwnerResolution resolvePluginOwners(
      Long tenantId,
      String scriptPatchVersion,
      String owningEventType,
      List<ScriptEventBinding> scopedBindings) {
    List<String> scriptIds =
        scopedBindings.stream().map(ScriptEventBinding::getScriptId).distinct().toList();
    if (scriptIds.isEmpty()) {
      return PluginOwnerResolution.EMPTY;
    }
    List<ScriptDefinition> definitions =
        scriptDefinitionRepository.findByTenantIdAndScriptVersionAndNameIn(
            tenantId, scriptPatchVersion, scriptIds);
    Map<String, PluginOwner> resolved = new HashMap<>();
    Set<String> returnedScriptIds = new HashSet<>();
    boolean hasUnresolvedPluginOwner = definitions == null;
    if (definitions == null) {
      definitions = List.of();
    }
    for (ScriptDefinition definition : definitions) {
      if (definition == null) {
        hasUnresolvedPluginOwner = true;
        continue;
      }
      if (!returnedScriptIds.add(definition.getName())) {
        hasUnresolvedPluginOwner = true;
      }
      PluginOwnerResolutionResult result = resolvePluginOwner(definition, owningEventType);
      if (result.pluginOwnerUnresolved()) {
        hasUnresolvedPluginOwner = true;
        continue;
      }
      if (result.owner() != null) {
        resolved.put(definition.getName(), result.owner());
      }
    }
    hasUnresolvedPluginOwner |= !returnedScriptIds.containsAll(scriptIds);
    return new PluginOwnerResolution(Map.copyOf(resolved), hasUnresolvedPluginOwner);
  }

  private static boolean participatesInResolvedHandlerSet(
      PluginOwner owner, Map<String, String> activePluginVersions) {
    if (owner == null) {
      return true;
    }
    return owner.pluginVersionId().equals(activePluginVersions.get(owner.pluginId()));
  }

  private static boolean isPluginOwned(
      Map<String, PluginOwner> ownersByScriptId, ScriptEventBinding binding) {
    return ownersByScriptId.get(binding.getScriptId()) != null;
  }

  private PluginOwnerResolutionResult resolvePluginOwner(
      ScriptDefinition definition, String owningEventType) {
    if (definition == null
        || definition.getDefinition() == null
        || definition.getDefinition().isBlank()) {
      return PluginOwnerResolutionResult.unresolvedPluginOwner();
    }
    Map<String, Object> root;
    try {
      root = JsonParserFactory.getJsonParser().parseMap(definition.getDefinition());
    } catch (RuntimeException ex) {
      return PluginOwnerResolutionResult.unresolvedPluginOwner();
    }
    if (root == null) {
      return PluginOwnerResolutionResult.unresolvedPluginOwner();
    }
    try {
      ScriptCommandMetadataSupport.PluginOwner owner =
          ScriptCommandMetadataSupport.resolvePluginOwner(
              root, ScriptCommandMetadataSupport.extractHandlerNode(root, owningEventType));
      if (owner.pluginId().isBlank()) {
        return PluginOwnerResolutionResult.firstParty();
      }
      return PluginOwnerResolutionResult.resolved(
          new PluginOwner(owner.pluginId(), owner.pluginVersionId()));
    } catch (IllegalArgumentException ex) {
      return PluginOwnerResolutionResult.unresolvedPluginOwner();
    }
  }

  private TriggerAdmission admissionWithOnLoadHandler(
      TriggerScriptEventRequest request,
      String schemaVersion,
      ScriptEventRegistryService.EventDefinition definition,
      String sourceService,
      AutomationAdmissionStateService.AdmissionStateSummary admissionState,
      long scriptPinEpoch) {
    String scriptId = requiredText(request.getScriptId(), "script_id");
    if (handlerAuditExistsForScript(request, schemaVersion, scriptId)
        || workItemExistsForScript(request, schemaVersion, scriptId)) {
      return new TriggerAdmission(true, OUTCOME_ADMITTED, "admitted_handlers_resolved", 1);
    }
    persistWorkItemForScript(
        request,
        schemaVersion,
        definition,
        scriptId,
        "",
        sourceService,
        admissionState,
        scriptPinEpoch);
    return new TriggerAdmission(true, OUTCOME_ADMITTED, "admitted_handlers_resolved", 1);
  }

  private void admitHandler(
      TriggerScriptEventRequest request,
      String schemaVersion,
      ScriptEventRegistryService.EventDefinition definition,
      ResolvedHandler handler,
      String sourceService,
      AutomationAdmissionStateService.AdmissionStateSummary admissionState,
      long scriptPinEpoch) {
    if (handlerAuditExists(request, schemaVersion, handler.binding())) {
      return;
    }
    if (!request.getIsDryRun()
        && ScriptQuotaClasses.consumesLiveScriptQuota(definition.quotaClass())
        && !quotaService.tryAcquire(request.getTenantId(), handler.binding().getScriptId())) {
      persistHandlerAudit(
          request,
          schemaVersion,
          handler.binding().getScriptId(),
          handler.pluginOwner(),
          sourceService,
          null,
          "ADMISSION",
          "quota_denied",
          "script_quota_denied",
          scriptPinEpoch);
      return;
    }
    persistWorkItem(
        request,
        schemaVersion,
        definition,
        handler.binding().getScriptId(),
        handler.binding().getPriorityTag(),
        handler.pluginOwner(),
        sourceService,
        requestScopeValues(request),
        admissionState,
        scriptPinEpoch);
  }

  private void persistWorkItem(
      TriggerScriptEventRequest request,
      String schemaVersion,
      ScriptEventRegistryService.EventDefinition definition,
      String scriptId,
      String priorityTag,
      PluginOwner pluginOwner,
      String sourceService,
      HandlerScopeValues scopeValues,
      AutomationAdmissionStateService.AdmissionStateSummary admissionState,
      long scriptPinEpoch) {
    Long admissionEpoch = admissionState == null ? 0L : admissionState.admissionEpoch();
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
    item.setPluginId(resolveHandlerPluginId(request, pluginOwner));
    item.setPluginVersionId(resolveHandlerPluginVersionId(request, pluginOwner));
    item.setEventType(request.getEventType());
    item.setEventSchemaVersion(schemaVersion);
    item.setQuotaClass(ScriptQuotaClasses.normalize(definition.quotaClass()));
    item.setScriptPatchVersion(request.getScriptPatchVersion());
    item.setScriptPinEpoch(scriptPinEpoch);
    item.setScriptPinControlPlaneRequestId(normalize(request.getScriptPinControlPlaneRequestId()));
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
    AutomationQueuePublicationSupport.enqueueAfterCommit(automationQueueService, saved, LOGGER);
    persistHandlerAudit(
        request,
        schemaVersion,
        scriptId,
        pluginOwner,
        sourceService,
        saved,
        "ADMISSION",
        "work_item_persisted",
        "handler_resolved",
        scriptPinEpoch);
  }

  private void persistWorkItemForScript(
      TriggerScriptEventRequest request,
      String schemaVersion,
      ScriptEventRegistryService.EventDefinition definition,
      String scriptId,
      String priorityTag,
      String sourceService,
      AutomationAdmissionStateService.AdmissionStateSummary admissionState,
      long scriptPinEpoch) {
    persistWorkItem(
        request,
        schemaVersion,
        definition,
        scriptId,
        priorityTag,
        null,
        sourceService,
        requestScopeValues(request),
        admissionState,
        scriptPinEpoch);
  }

  private void persistHandlerAudit(
      TriggerScriptEventRequest request,
      String schemaVersion,
      String scriptId,
      PluginOwner pluginOwner,
      String sourceService,
      ScriptWorkItem workItem,
      String finalStage,
      String finalOutcome,
      String finalReason,
      long scriptPinEpoch) {
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
    audit.setPluginId(resolveHandlerPluginId(request, pluginOwner));
    audit.setPluginVersionId(resolveHandlerPluginVersionId(request, pluginOwner));
    audit.setEventType(request.getEventType());
    audit.setEventSchemaVersion(schemaVersion);
    audit.setScriptPatchVersion(request.getScriptPatchVersion());
    audit.setScriptPinEpoch(scriptPinEpoch > 0L ? scriptPinEpoch : null);
    audit.setScriptPinControlPlaneRequestId(normalize(request.getScriptPinControlPlaneRequestId()));
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

  private static String resolveHandlerPluginId(
      TriggerScriptEventRequest request, PluginOwner pluginOwner) {
    return pluginOwner == null ? normalize(request.getPluginId()) : pluginOwner.pluginId();
  }

  private static String resolveHandlerPluginVersionId(
      TriggerScriptEventRequest request, PluginOwner pluginOwner) {
    return pluginOwner == null
        ? normalize(request.getPluginVersionId())
        : pluginOwner.pluginVersionId();
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
        .existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndScriptIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptPinEpochAndScriptPinControlPlaneRequestIdAndScriptEventIdAndDryRun(
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
            request.getScriptPinEpoch(),
            request.getScriptPinControlPlaneRequestId(),
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
        .existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndScriptIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptPinEpochAndScriptPinControlPlaneRequestIdAndScriptEventIdAndDryRun(
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
            request.getScriptPinEpoch(),
            request.getScriptPinControlPlaneRequestId(),
            request.getScriptEventId(),
            request.getIsDryRun());
  }

  private boolean matchesScope(
      ScriptEventBinding binding, TriggerScriptEventRequest request, Map<String, Object> payload) {
    return switch (normalizeScopeType(binding.getTargetScopeType())) {
      case SCOPE_ACTION_CATEGORY -> matchesActionCategoryScope(binding, payload);
      case SCOPE_ACTION_TAG -> matchesActionTagScope(binding, payload);
      case "GLOBAL" -> binding.getTargetScopeId().isBlank();
      case "ENTITY" -> binding.getTargetScopeId().equals(request.getEntityId());
      case "REGION" -> binding.getTargetScopeId().equals(request.getRegionId());
      case SCOPE_COMMAND_ALIAS -> matchesCommandAliasScope(binding, payload);
      default -> false;
    };
  }

  private boolean matchesActionCategoryScope(
      ScriptEventBinding binding, Map<String, Object> payload) {
    if (payload == null) {
      return false;
    }
    String bindingCategory = normalizeActionClassifier(binding.getTargetScopeId());
    String payloadCategory = normalizeActionClassifier(stringValue(payload.get("actionCategory")));
    return !bindingCategory.isBlank() && bindingCategory.equals(payloadCategory);
  }

  private boolean matchesActionTagScope(ScriptEventBinding binding, Map<String, Object> payload) {
    if (payload == null) {
      return false;
    }
    String bindingTag = normalizeActionClassifier(binding.getTargetScopeId());
    if (bindingTag.isBlank()) {
      return false;
    }
    Object payloadTags = payload.get("actionTags");
    if (!(payloadTags instanceof List<?> tags)) {
      return false;
    }
    return tags.stream()
        .map(ScriptEventIngressServiceImpl::stringValue)
        .map(ScriptEventIngressServiceImpl::normalizeActionClassifier)
        .anyMatch(bindingTag::equals);
  }

  private boolean matchesCommandAliasScope(
      ScriptEventBinding binding, Map<String, Object> payload) {
    if (payload == null) {
      return false;
    }
    String bindingAlias = normalizeCommandAlias(binding.getTargetScopeId());
    if (bindingAlias.isBlank()) {
      return false;
    }
    String payloadAlias =
        firstPresent(
            normalizeCommandAlias(stringValue(payload.get("commandAlias"))),
            normalizeCommandAlias(stringValue(payload.get("commandName"))));
    return bindingAlias.equals(payloadAlias);
  }

  private ScriptEventIngressAudit findExisting(
      TriggerScriptEventRequest request, String schemaVersion, String sourceService) {
    if (request.getTenantId().isBlank()
        || request.getEventType().isBlank()
        || request.getScriptPatchVersion().isBlank()
        || request.getScriptEventId().isBlank()) {
      return null;
    }
    // Routing is audit payload rather than ingress identity, but malformed bundles still fail
    // before an idempotency lookup can replay a prior result.
    RoutingBundleSupport.normalize(
        request.getWorldSlug(), request.getRealmSlug(), request.getPointerVersion());
    boolean instanceScoped = !request.getGameInstanceId().isBlank();
    return repository
        .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptPinEpochAndScriptPinControlPlaneRequestIdAndScriptEventIdAndDryRunAndSourceService(
            request.getTenantId(),
            nullableText(request.getGameInstanceId()),
            instanceScoped ? nullableText(request.getRegionId()) : null,
            instanceScoped && request.getRegionEpoch() > 0 ? request.getRegionEpoch() : null,
            instanceScoped ? nullableText(request.getEntityId()) : null,
            instanceScoped ? normalizePlayableStateScope(request.getPlayableStateScope()) : null,
            request.getEventType(),
            schemaVersion,
            request.getScriptPatchVersion(),
            instanceScoped && request.getScriptPinEpoch() > 0 ? request.getScriptPinEpoch() : null,
            request.getScriptPinControlPlaneRequestId(),
            request.getScriptEventId(),
            request.getIsDryRun(),
            sourceService)
        .orElse(null);
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
    Long accountId = SessionContext.currentAccountIdOrNull();
    if (accountId != null) {
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

  private static String firstPresent(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private static String normalize(String value) {
    return value == null ? "" : value;
  }

  private static String nullableText(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String stringValue(Object value) {
    return value instanceof String text ? text : "";
  }

  private static String normalizeCommandAlias(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static String normalizeActionClassifier(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  private static String normalizeScopeType(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
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

  private static final class PinEpochHolder {
    private Long value;
  }

  private record PinValidation(TriggerAdmission admission, Long scriptPinEpoch) {}

  private record AdmissionStateValidation(
      TriggerAdmission admission, AutomationAdmissionStateService.AdmissionStateSummary state) {
    private static AdmissionStateValidation admitted(
        AutomationAdmissionStateService.AdmissionStateSummary state) {
      return new AdmissionStateValidation(
          new TriggerAdmission(true, OUTCOME_ADMITTED, "admitted_for_handler_resolution", 0),
          state);
    }

    private static AdmissionStateValidation rejected(TriggerAdmission admission) {
      return new AdmissionStateValidation(admission, null);
    }
  }

  private record PluginOwner(String pluginId, String pluginVersionId) {}

  private record PluginOwnerResolution(
      Map<String, PluginOwner> ownersByScriptId, boolean hasUnresolvedPluginOwner) {
    private static final PluginOwnerResolution EMPTY = new PluginOwnerResolution(Map.of(), false);
  }

  private record PluginOwnerResolutionResult(PluginOwner owner, boolean pluginOwnerUnresolved) {
    private static PluginOwnerResolutionResult firstParty() {
      return new PluginOwnerResolutionResult(null, false);
    }

    private static PluginOwnerResolutionResult unresolvedPluginOwner() {
      return new PluginOwnerResolutionResult(null, true);
    }

    private static PluginOwnerResolutionResult resolved(PluginOwner owner) {
      return new PluginOwnerResolutionResult(owner, false);
    }
  }

  private record ResolvedHandler(ScriptEventBinding binding, PluginOwner pluginOwner) {}
}
