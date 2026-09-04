package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
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
    // The request envelope is bounded before event identity lookup or any domain
    // claim.  This is transport/schema validation, not an event-scope output
    // budget decision, so no ingress audit or admission result may be written.
    rejectOversizedInputEnvelope(request);
    request = normalizeRequest(request);
    sourceService = normalize(sourceService);
    long tenantKey = RequestIdValidation.requirePositiveLong(request.getTenantId(), "tenantId");
    validateGameplayRoutingBundleShape(request, sourceService);
    String schemaVersion = schemaVersion(request);
    String requestFingerprint = requestFingerprint(request, schemaVersion, sourceService);
    ScriptEventIngressAudit existing = findExisting(request, schemaVersion, sourceService);
    if (existing != null) {
      if (!sameIngressRequest(
          existing, request, schemaVersion, sourceService, requestFingerprint)) {
        throw new IllegalArgumentException("script_event_id already records a different request");
      }
      if (existing.getRequestFingerprint() == null || existing.getRequestFingerprint().isBlank()) {
        existing.setRequestFingerprint(requestFingerprint);
        repository.save(existing);
      }
      return new TriggerAdmission(
          existing.isAdmitted(),
          existing.getAdmissionOutcome(),
          existing.getAdmissionReason(),
          existing.getResolvedHandlerCount());
    }
    requiredText(request.getTenantId(), "tenant_id");
    requiredText(request.getEventType(), "event_type");
    requiredText(request.getScriptPatchVersion(), "script_patch_version");
    requiredText(request.getScriptEventId(), "script_event_id");
    ScriptEventIngressAudit audit =
        newIngressClaim(request, schemaVersion, sourceService, requestFingerprint);
    ScriptEventIngressAuditRepository.IdempotentInsertResult claim =
        repository.insertIfAbsentByIdentity(audit);
    if (claim != null) {
      if (claim.audit() == null) {
        throw new IllegalStateException("ingress claim did not return its durable audit row");
      }
      if (!claim.inserted()) {
        ScriptEventIngressAudit existingAudit = claim.audit();
        if (!sameIngressRequest(
            existingAudit, request, schemaVersion, sourceService, requestFingerprint)) {
          throw new IllegalArgumentException("script_event_id already records a different request");
        }
        if (existingAudit.getRequestFingerprint() == null
            || existingAudit.getRequestFingerprint().isBlank()) {
          existingAudit.setRequestFingerprint(requestFingerprint);
          repository.save(existingAudit);
        }
        return new TriggerAdmission(
            existingAudit.isAdmitted(),
            existingAudit.getAdmissionOutcome(),
            existingAudit.getAdmissionReason(),
            existingAudit.getResolvedHandlerCount());
      }
      // The repository returns the persisted row (including its generated id and row version).
      // Keep that row as the update target when finalizing the claim.
      audit = claim.audit();
    }
    ScriptEventRegistryService.EventDefinition definition =
        eventRegistryService
            .getDefinition(normalize(request.getEventType()), schemaVersion)
            .orElse(null);

    AdmissionAuthority authority = new AdmissionAuthority();
    TriggerAdmission admission =
        validate(request, schemaVersion, sourceService, definition, authority);
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
                    authority);
      }
    }
    HandlerScopeValues requestScopeValues = requestScopeValues(request);
    // Finalize the durable claim after validation and any handler work. In PostgreSQL the
    // insert-or-read claim serializes concurrent requests on the complete event identity, so a
    // duplicate cannot reach quota or handler effects until this outcome is committed.
    if (audit == null) {
      throw new IllegalStateException("ingress claim row is required");
    }
    audit.setTenantId(requiredText(normalize(request.getTenantId()), "tenant_id"));
    audit.setGameInstanceId(optionalText(request.getGameInstanceId()));
    audit.setRegionId(optionalText(request.getRegionId()));
    audit.setRegionEpoch(request.getRegionEpoch() > 0 ? request.getRegionEpoch() : null);
    audit.setEntityId(optionalText(request.getEntityId()));
    audit.setPlayableStateScope(requestScopeValues.playableStateScope());
    audit.setWorldSlug(requestScopeValues.worldSlug());
    audit.setRealmSlug(requestScopeValues.realmSlug());
    audit.setPointerVersion(requestScopeValues.pointerVersion());
    audit.setScriptId(normalize(request.getScriptId()));
    audit.setPluginId(normalize(request.getPluginId()));
    audit.setPluginVersionId(normalize(request.getPluginVersionId()));
    audit.setScriptPinEpoch(currentScriptPinEpoch(request, authority));
    setPluginFence(audit, request, authority);
    audit.setEventType(requiredText(normalize(request.getEventType()), "event_type"));
    audit.setEventSchemaVersion(schemaVersion);
    audit.setQuotaClass(
        ScriptQuotaClasses.normalize(definition == null ? null : definition.quotaClass()));
    audit.setScriptPatchVersion(
        requiredText(normalize(request.getScriptPatchVersion()), "script_patch_version"));
    audit.setScriptEventId(requiredText(normalize(request.getScriptEventId()), "script_event_id"));
    audit.setSourceService(normalize(sourceService));
    audit.setTriggerMode(request.getTriggerMode().name());
    audit.setSourceKind(sourceKind(request));
    audit.setSourceState(admission.admitted() ? "TRIGGER_ADMITTED" : "TRIGGER_REJECTED");
    audit.setSourceOrdinal(sourceOrdinal(request));
    audit.setSourceDueTickId(sourceDueTickId(request));
    audit.setSourceDueAtMs(sourceDueAtMs(request));
    audit.setDryRun(request.getIsDryRun());
    audit.setReadSnapshotToken(normalize(request.getReadSnapshotToken()));
    audit.setPayloadJson(normalize(request.getPayloadJson()));
    audit.setRequestFingerprint(requestFingerprint);
    audit.setAdmitted(admission.admitted());
    audit.setAdmissionOutcome(admission.outcome());
    audit.setAdmissionReason(admission.reason());
    audit.setResolvedHandlerCount(admission.resolvedHandlerCount());
    // A null result is retained as a legacy adapter fallback; production's atomic implementation
    // always returns a durable claim row, and a successful claim with no row fails above before
    // any validation or handler side effects.
    repository.save(audit);
    return admission;
  }

  private ScriptEventIngressAudit newIngressClaim(
      TriggerScriptEventRequest request,
      String schemaVersion,
      String sourceService,
      String requestFingerprint) {
    HandlerScopeValues scopeValues = requestScopeValues(request);
    ScriptEventIngressAudit audit = new ScriptEventIngressAudit();
    audit.setTenantId(requiredText(normalize(request.getTenantId()), "tenant_id"));
    audit.setGameInstanceId(optionalText(request.getGameInstanceId()));
    audit.setRegionId(optionalText(request.getRegionId()));
    audit.setRegionEpoch(request.getRegionEpoch() > 0 ? request.getRegionEpoch() : null);
    audit.setEntityId(optionalText(request.getEntityId()));
    audit.setPlayableStateScope(scopeValues.playableStateScope());
    audit.setWorldSlug(scopeValues.worldSlug());
    audit.setRealmSlug(scopeValues.realmSlug());
    audit.setPointerVersion(scopeValues.pointerVersion());
    audit.setScriptId(normalize(request.getScriptId()));
    audit.setPluginId(normalize(request.getPluginId()));
    audit.setPluginVersionId(normalize(request.getPluginVersionId()));
    audit.setEventType(requiredText(normalize(request.getEventType()), "event_type"));
    audit.setEventSchemaVersion(schemaVersion);
    audit.setScriptPatchVersion(
        requiredText(normalize(request.getScriptPatchVersion()), "script_patch_version"));
    audit.setScriptEventId(requiredText(normalize(request.getScriptEventId()), "script_event_id"));
    audit.setSourceService(normalize(sourceService));
    audit.setTriggerMode(request.getTriggerMode().name());
    audit.setDryRun(request.getIsDryRun());
    audit.setReadSnapshotToken(normalize(request.getReadSnapshotToken()));
    audit.setPayloadJson(normalize(request.getPayloadJson()));
    audit.setRequestFingerprint(requestFingerprint);
    audit.setAdmitted(false);
    audit.setAdmissionOutcome("INGRESS_CLAIMED");
    audit.setAdmissionReason("ingress_claimed");
    audit.setSourceState("INGRESS_CLAIMED");
    return audit;
  }

  private static boolean sameIngressRequest(
      ScriptEventIngressAudit existing,
      TriggerScriptEventRequest request,
      String schemaVersion,
      String sourceService,
      String requestFingerprint) {
    if (requestFingerprint.equals(normalize(existing.getRequestFingerprint()))) {
      return true;
    }
    // Rows created before request fingerprints were introduced can be upgraded
    // only after comparing every immutable input used by the digest.
    RoutingBundleSupport.RoutingBundle storedRoutingBundle =
        RoutingBundleSupport.normalize(
            existing.getWorldSlug(), existing.getRealmSlug(), existing.getPointerVersion());
    RoutingBundleSupport.RoutingBundle requestRoutingBundle =
        RoutingBundleSupport.normalize(
            request.getWorldSlug(), request.getRealmSlug(), request.getPointerVersion());
    long existingRegionEpoch = existing.getRegionEpoch() == null ? 0L : existing.getRegionEpoch();
    long requestRegionEpoch = request.getRegionEpoch() > 0 ? request.getRegionEpoch() : 0L;
    return normalize(existing.getRequestFingerprint()).isBlank()
        && same(existing.getTenantId(), request.getTenantId())
        && same(existing.getGameInstanceId(), request.getGameInstanceId())
        && same(existing.getRegionId(), request.getRegionId())
        && existingRegionEpoch == requestRegionEpoch
        && same(existing.getEntityId(), request.getEntityId())
        && same(
            existing.getPlayableStateScope(),
            normalizePlayableStateScope(request.getPlayableStateScope()))
        && same(storedRoutingBundle.worldSlug(), requestRoutingBundle.worldSlug())
        && same(storedRoutingBundle.realmSlug(), requestRoutingBundle.realmSlug())
        && same(storedRoutingBundle.pointerVersion(), requestRoutingBundle.pointerVersion())
        && same(existing.getScriptId(), request.getScriptId())
        && same(existing.getPluginId(), request.getPluginId())
        && same(existing.getPluginVersionId(), request.getPluginVersionId())
        && same(existing.getEventType(), request.getEventType())
        && same(existing.getEventSchemaVersion(), schemaVersion)
        && same(existing.getScriptPatchVersion(), request.getScriptPatchVersion())
        && same(existing.getScriptEventId(), request.getScriptEventId())
        && same(existing.getSourceService(), sourceService)
        && same(existing.getTriggerMode(), request.getTriggerMode().name())
        && existing.isDryRun() == request.getIsDryRun()
        && same(existing.getReadSnapshotToken(), request.getReadSnapshotToken())
        && same(existing.getPayloadJson(), request.getPayloadJson());
  }

  private static boolean same(String left, String right) {
    return normalize(left).equals(normalize(right));
  }

  private TriggerAdmission validate(
      TriggerScriptEventRequest request,
      String schemaVersion,
      String sourceService,
      ScriptEventRegistryService.EventDefinition definition,
      AdmissionAuthority authority) {
    requiredText(request.getTenantId(), "tenant_id");
    requiredText(request.getEventType(), "event_type");
    requiredText(request.getScriptPatchVersion(), "script_patch_version");
    requiredText(request.getScriptEventId(), "script_event_id");
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
    TriggerAdmission pinAdmission = validatePinnedPatch(request, authority);
    if (pinAdmission != null) {
      return pinAdmission;
    }
    TriggerAdmission pluginAdmission = validatePluginRuntimeState(request, authority);
    if (pluginAdmission != null) {
      return pluginAdmission;
    }
    return new TriggerAdmission(true, OUTCOME_ADMITTED, "admitted_for_handler_resolution", 0);
  }

  private void rejectOversizedInputEnvelope(TriggerScriptEventRequest request) {
    if (request.getPayloadJson().getBytes(StandardCharsets.UTF_8).length
        > outputProperties.getMaxSerializedWorkItemBytes()) {
      throw new IllegalArgumentException("payload_json exceeds input envelope limit");
    }
  }

  private TriggerAdmission rejected(String reason) {
    return new TriggerAdmission(false, OUTCOME_REGISTRY_REJECTED, reason, 0);
  }

  private TriggerAdmission validateBuiltInPayload(TriggerScriptEventRequest request) {
    String eventType = normalize(request.getEventType());
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

  private TriggerAdmission validatePinnedPatch(
      TriggerScriptEventRequest request, AdmissionAuthority authority) {
    if (request.getGameInstanceId().isBlank()) {
      return null;
    }
    final GetGameInstanceRuntimeStateResponse runtime;
    try {
      runtime =
          gameSessionControlPlaneClient.getGameInstanceRuntimeState(
              request.getTenantId(), request.getGameInstanceId(), request.getRegionId());
    } catch (RuntimeException ex) {
      return new TriggerAdmission(false, OUTCOME_PIN_STATE_UNAVAILABLE, "pin_state_unavailable", 0);
    }
    if (runtime == null || (runtime.hasError() && !runtime.getError().getCode().isBlank())) {
      return new TriggerAdmission(false, OUTCOME_PIN_STATE_UNAVAILABLE, "pin_state_unavailable", 0);
    }
    if (!runtime.hasRuntimeState()) {
      return new TriggerAdmission(false, OUTCOME_PIN_STATE_UNAVAILABLE, "pin_state_unavailable", 0);
    }
    var runtimeState = runtime.getRuntimeState();
    if (!request.getTenantId().equals(runtimeState.getTenantId())
        || !request.getGameInstanceId().equals(runtimeState.getGameInstanceId())) {
      // A successful owner RPC with a different scope is not authority for this request. Do not
      // project it before rejecting the trigger.
      return new TriggerAdmission(false, OUTCOME_PIN_STATE_UNAVAILABLE, "pin_state_unavailable", 0);
    }
    authority.runtimeState = runtime;
    if (runtimeState.getScriptPinEpoch() <= 0) {
      return new TriggerAdmission(false, OUTCOME_PIN_STATE_UNAVAILABLE, "pin_state_unavailable", 0);
    }
    scriptPatchPinProjectionService.observeRuntimeState(
        request.getTenantId(), request.getGameInstanceId(), runtimeState);
    if (!request.getScriptPatchVersion().equals(runtimeState.getPinnedScriptPatchVersion())) {
      return new TriggerAdmission(false, OUTCOME_VERSION_UNAVAILABLE, "version_unavailable", 0);
    }
    if (request.getPlayableStateScopeValue() != 0
        && runtimeState.getPlayableStateScopeValue() != 0
        && request.getPlayableStateScope() != runtimeState.getPlayableStateScope()) {
      return new TriggerAdmission(
          false, OUTCOME_VERSION_UNAVAILABLE, "playable_state_scope_mismatch", 0);
    }
    if (!request.getRegionId().isBlank()
        && !runtimeState.getRegionId().isBlank()
        && !request.getRegionId().equals(runtimeState.getRegionId())) {
      return new TriggerAdmission(
          false, OUTCOME_VERSION_UNAVAILABLE, "runtime_region_scope_advanced", 0);
    }
    if (request.getRegionEpoch() > 0
        && runtimeState.getRegionEpoch() > 0
        && request.getRegionEpoch() != runtimeState.getRegionEpoch()) {
      return new TriggerAdmission(
          false, OUTCOME_VERSION_UNAVAILABLE, "runtime_region_scope_advanced", 0);
    }
    return null;
  }

  private TriggerAdmission validatePluginRuntimeState(
      TriggerScriptEventRequest request, AdmissionAuthority authority) {
    boolean hasPluginId = !request.getPluginId().isBlank();
    boolean hasPluginVersion = !request.getPluginVersionId().isBlank();
    if (!hasPluginId && !hasPluginVersion) {
      return null;
    }
    if (!hasPluginId || !hasPluginVersion || request.getGameInstanceId().isBlank()) {
      return rejected("missing_plugin_identity");
    }
    Optional<PluginRuntimeStateService.PluginRuntimeStatus> status =
        resolvePluginStatus(authority, request, request.getPluginId());
    if (status.isEmpty()) {
      return new TriggerAdmission(false, OUTCOME_VERSION_UNAVAILABLE, "plugin_not_active", 0);
    }
    if (status.get().pluginState() != PluginState.PLUGIN_STATE_ENABLED) {
      return new TriggerAdmission(false, OUTCOME_VERSION_UNAVAILABLE, "plugin_disabled", 0);
    }
    if (!normalize(status.get().activePluginVersionId())
        .equals(normalize(request.getPluginVersionId()))) {
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

  private boolean hasUsablePluginFence(
      ResolvedHandler handler, TriggerScriptEventRequest request, AdmissionAuthority authority) {
    if (handler.pluginOwner() == null) {
      return true;
    }
    return resolvePluginStatus(authority, request, handler.pluginOwner().pluginId())
        .filter(
            status ->
                normalize(handler.pluginOwner().pluginVersionId())
                    .equals(normalize(status.activePluginVersionId())))
        .filter(status -> status.pluginActivationEpoch() > 0 && status.lifecycleRevision() > 0)
        .isPresent();
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
      AdmissionAuthority authority) {
    if (isOnLoadRequest(request)) {
      return admissionWithOnLoadHandler(
          request, schemaVersion, definition, sourceService, admissionState, authority);
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
    if (handlers.stream().anyMatch(handler -> !hasUsablePluginFence(handler, request, authority))) {
      return new TriggerAdmission(
          false, OUTCOME_VERSION_UNAVAILABLE, "plugin_lifecycle_unavailable", 0);
    }
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
                authority));
    String reason = handlers.isEmpty() ? "admitted_no_handlers" : "admitted_handlers_resolved";
    return new TriggerAdmission(true, OUTCOME_ADMITTED, reason, handlers.size());
  }

  private boolean hasMatchingPluginOwner(PluginOwner owner, TriggerScriptEventRequest request) {
    return owner != null
        && normalize(owner.pluginId()).equals(normalize(request.getPluginId()))
        && normalize(owner.pluginVersionId()).equals(normalize(request.getPluginVersionId()));
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
    return normalize(owner.pluginVersionId())
        .equals(normalize(activePluginVersions.get(normalize(owner.pluginId()))));
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
          new PluginOwner(normalize(owner.pluginId()), normalize(owner.pluginVersionId())));
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
      AdmissionAuthority authority) {
    String scriptId = requiredText(request.getScriptId(), "script_id");
    if (handlerAuditExistsForScript(request, schemaVersion, scriptId)
        || workItemExistsForScript(request, schemaVersion, scriptId)) {
      return new TriggerAdmission(true, OUTCOME_ADMITTED, "admitted_handlers_resolved", 1);
    }
    persistWorkItemForScript(
        request, schemaVersion, definition, scriptId, "", sourceService, admissionState, authority);
    return new TriggerAdmission(true, OUTCOME_ADMITTED, "admitted_handlers_resolved", 1);
  }

  private void admitHandler(
      TriggerScriptEventRequest request,
      String schemaVersion,
      ScriptEventRegistryService.EventDefinition definition,
      ResolvedHandler handler,
      String sourceService,
      AutomationAdmissionStateService.AdmissionStateSummary admissionState,
      AdmissionAuthority authority) {
    if (handlerAuditExists(request, schemaVersion, handler.binding(), handler.pluginOwner())
        || workItemExists(request, schemaVersion, handler.binding(), handler.pluginOwner())) {
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
          bindingIdentity(handler.binding()),
          sourceService,
          null,
          "ADMISSION",
          "quota_denied",
          "script_quota_denied",
          authority);
      return;
    }
    persistWorkItem(
        request,
        schemaVersion,
        definition,
        handler.binding().getScriptId(),
        bindingIdentity(handler.binding()),
        handler.binding().getPriorityTag(),
        handler.pluginOwner(),
        sourceService,
        requestScopeValues(request),
        admissionState,
        authority);
  }

  private void persistWorkItem(
      TriggerScriptEventRequest request,
      String schemaVersion,
      ScriptEventRegistryService.EventDefinition definition,
      String scriptId,
      String bindingId,
      String priorityTag,
      PluginOwner pluginOwner,
      String sourceService,
      HandlerScopeValues scopeValues,
      AutomationAdmissionStateService.AdmissionStateSummary admissionState,
      AdmissionAuthority authority) {
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
    item.setBindingId(bindingId);
    item.setPluginId(resolveHandlerPluginId(request, pluginOwner));
    item.setPluginVersionId(resolveHandlerPluginVersionId(request, pluginOwner));
    setPluginFence(item, request, pluginOwner, authority);
    item.setScriptPinEpoch(currentScriptPinEpoch(request, authority));
    item.setEventType(request.getEventType());
    item.setEventSchemaVersion(schemaVersion);
    item.setQuotaClass(ScriptQuotaClasses.normalize(definition.quotaClass()));
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
    AutomationQueuePublicationSupport.enqueueAfterCommit(automationQueueService, saved, LOGGER);
    persistHandlerAudit(
        request,
        schemaVersion,
        scriptId,
        pluginOwner,
        bindingId,
        sourceService,
        saved,
        "ADMISSION",
        "work_item_persisted",
        "handler_resolved",
        authority);
  }

  private void persistWorkItemForScript(
      TriggerScriptEventRequest request,
      String schemaVersion,
      ScriptEventRegistryService.EventDefinition definition,
      String scriptId,
      String priorityTag,
      String sourceService,
      AutomationAdmissionStateService.AdmissionStateSummary admissionState,
      AdmissionAuthority authority) {
    persistWorkItem(
        request,
        schemaVersion,
        definition,
        scriptId,
        null,
        priorityTag,
        null,
        sourceService,
        requestScopeValues(request),
        admissionState,
        authority);
  }

  private void persistHandlerAudit(
      TriggerScriptEventRequest request,
      String schemaVersion,
      String scriptId,
      PluginOwner pluginOwner,
      String bindingId,
      String sourceService,
      ScriptWorkItem workItem,
      String finalStage,
      String finalOutcome,
      String finalReason,
      AdmissionAuthority authority) {
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
    audit.setBindingId(bindingId);
    audit.setPluginId(resolveHandlerPluginId(request, pluginOwner));
    audit.setPluginVersionId(resolveHandlerPluginVersionId(request, pluginOwner));
    audit.setScriptPinEpoch(currentScriptPinEpoch(request, authority));
    setPluginFence(audit, request, pluginOwner, authority);
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

  private static String bindingIdentity(ScriptEventBinding binding) {
    return binding == null || binding.getId() == null ? null : binding.getId().toString();
  }

  private long currentScriptPinEpoch(
      TriggerScriptEventRequest request, AdmissionAuthority authority) {
    if (request.getGameInstanceId().isBlank()) {
      return 0L;
    }
    GetGameInstanceRuntimeStateResponse runtime = authority.runtimeState;
    if (runtime == null
        || (runtime.hasError() && !runtime.getError().getCode().isBlank())
        || !runtime.hasRuntimeState()) {
      return 0L;
    }
    return runtime.getRuntimeState().getScriptPinEpoch();
  }

  private void setPluginFence(
      ScriptWorkItem item,
      TriggerScriptEventRequest request,
      PluginOwner pluginOwner,
      AdmissionAuthority authority) {
    String pluginId = normalize(item.getPluginId());
    String pluginVersionId = normalize(item.getPluginVersionId());
    PluginFence fence = resolvePluginFence(request, pluginId, pluginVersionId, authority);
    item.setPluginActivationEpoch(fence.pluginActivationEpoch());
    item.setLifecycleRevision(fence.lifecycleRevision());
  }

  private void setPluginFence(
      ScriptEventIngressAudit audit,
      TriggerScriptEventRequest request,
      AdmissionAuthority authority) {
    setPluginFence(audit, request, null, authority);
  }

  private void setPluginFence(
      ScriptEventAudit audit,
      TriggerScriptEventRequest request,
      PluginOwner pluginOwner,
      AdmissionAuthority authority) {
    String pluginId = resolveHandlerPluginId(request, pluginOwner);
    String pluginVersionId = resolveHandlerPluginVersionId(request, pluginOwner);
    PluginFence fence = resolvePluginFence(request, pluginId, pluginVersionId, authority);
    audit.setPluginActivationEpoch(fence.pluginActivationEpoch());
    audit.setLifecycleRevision(fence.lifecycleRevision());
  }

  private void setPluginFence(
      ScriptEventIngressAudit audit,
      TriggerScriptEventRequest request,
      PluginOwner owner,
      AdmissionAuthority authority) {
    String pluginId = resolveHandlerPluginId(request, owner);
    String pluginVersionId = resolveHandlerPluginVersionId(request, owner);
    PluginFence fence = resolvePluginFence(request, pluginId, pluginVersionId, authority);
    audit.setPluginActivationEpoch(fence.pluginActivationEpoch());
    audit.setLifecycleRevision(fence.lifecycleRevision());
  }

  private PluginFence resolvePluginFence(
      TriggerScriptEventRequest request,
      String pluginId,
      String pluginVersionId,
      AdmissionAuthority authority) {
    if (pluginId.isBlank() || pluginVersionId.isBlank() || request.getGameInstanceId().isBlank()) {
      return PluginFence.EMPTY;
    }
    return resolvePluginStatus(authority, request, pluginId)
        .filter(
            status -> normalize(pluginVersionId).equals(normalize(status.activePluginVersionId())))
        .map(status -> new PluginFence(status.pluginActivationEpoch(), status.lifecycleRevision()))
        .orElse(PluginFence.EMPTY);
  }

  private Optional<PluginRuntimeStateService.PluginRuntimeStatus> resolvePluginStatus(
      AdmissionAuthority authority, TriggerScriptEventRequest request, String pluginId) {
    if (pluginId.isBlank() || request.getGameInstanceId().isBlank()) {
      return Optional.empty();
    }
    return authority.pluginStatuses.computeIfAbsent(
        pluginId,
        ignored ->
            pluginRuntimeStateService.getStatus(
                request.getTenantId(), request.getGameInstanceId(), pluginId));
  }

  private static final class AdmissionAuthority {
    private GetGameInstanceRuntimeStateResponse runtimeState;
    private final Map<String, Optional<PluginRuntimeStateService.PluginRuntimeStatus>>
        pluginStatuses = new HashMap<>();
  }

  private record PluginFence(long pluginActivationEpoch, long lifecycleRevision) {
    private static final PluginFence EMPTY = new PluginFence(0L, 0L);
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
      TriggerScriptEventRequest request,
      String schemaVersion,
      ScriptEventBinding binding,
      PluginOwner pluginOwner) {
    return handlerAuditExistsForScope(
        request,
        schemaVersion,
        binding.getScriptId(),
        bindingIdentity(binding),
        pluginOwner,
        requestScopeValues(request));
  }

  private boolean handlerAuditExistsForScript(
      TriggerScriptEventRequest request, String schemaVersion, String scriptId) {
    return handlerAuditExistsForScope(
        request, schemaVersion, scriptId, null, null, requestScopeValues(request));
  }

  private boolean handlerAuditExistsForScope(
      TriggerScriptEventRequest request,
      String schemaVersion,
      String scriptId,
      String bindingId,
      PluginOwner pluginOwner,
      HandlerScopeValues scopeValues) {
    return eventAuditRepository
        .existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndScriptIdAndPluginIdAndPluginVersionIdAndBindingIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
            normalize(request.getTenantId()),
            normalize(request.getGameInstanceId()),
            normalize(request.getRegionId()),
            request.getRegionEpoch() > 0 ? request.getRegionEpoch() : 0L,
            scopeValues.entityId(),
            scopeValues.playableStateScope(),
            scopeValues.worldSlug(),
            scopeValues.realmSlug(),
            scopeValues.pointerVersion(),
            scriptId,
            resolveHandlerPluginId(request, pluginOwner),
            resolveHandlerPluginVersionId(request, pluginOwner),
            bindingId,
            normalize(request.getEventType()),
            schemaVersion,
            normalize(request.getScriptPatchVersion()),
            normalize(request.getScriptEventId()),
            request.getIsDryRun());
  }

  private boolean workItemExists(
      TriggerScriptEventRequest request,
      String schemaVersion,
      ScriptEventBinding binding,
      PluginOwner pluginOwner) {
    return workItemExistsForScope(
        request,
        schemaVersion,
        binding.getScriptId(),
        bindingIdentity(binding),
        pluginOwner,
        requestScopeValues(request));
  }

  private boolean workItemExistsForScript(
      TriggerScriptEventRequest request, String schemaVersion, String scriptId) {
    return workItemExistsForScope(
        request, schemaVersion, scriptId, null, null, requestScopeValues(request));
  }

  private boolean workItemExistsForScope(
      TriggerScriptEventRequest request,
      String schemaVersion,
      String scriptId,
      String bindingId,
      PluginOwner pluginOwner,
      HandlerScopeValues scopeValues) {
    return workItemRepository
        .existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndScriptIdAndPluginIdAndPluginVersionIdAndBindingIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
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
            resolveHandlerPluginId(request, pluginOwner),
            resolveHandlerPluginVersionId(request, pluginOwner),
            bindingId,
            request.getEventType(),
            schemaVersion,
            request.getScriptPatchVersion(),
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
    return repository
        .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
            normalize(request.getTenantId()),
            optionalText(request.getGameInstanceId()),
            optionalText(request.getRegionId()),
            request.getRegionEpoch() > 0 ? request.getRegionEpoch() : null,
            optionalText(request.getEntityId()),
            normalizePlayableStateScope(request.getPlayableStateScope()),
            normalize(request.getEventType()),
            schemaVersion,
            normalize(request.getScriptPatchVersion()),
            normalize(request.getScriptEventId()),
            request.getIsDryRun(),
            normalize(sourceService))
        .orElse(null);
  }

  private String schemaVersion(TriggerScriptEventRequest request) {
    return request.getEventSchemaVersion().isBlank()
        ? DEFAULT_SCHEMA_VERSION
        : normalize(request.getEventSchemaVersion());
  }

  private static String requestFingerprint(
      TriggerScriptEventRequest request, String schemaVersion, String sourceService) {
    String canonical =
        String.join(
            "\u0000",
            normalize(request.getTenantId()),
            normalize(request.getGameInstanceId()),
            normalize(request.getRegionId()),
            Long.toString(Math.max(0L, request.getRegionEpoch())),
            normalize(request.getEntityId()),
            normalizePlayableStateScope(request.getPlayableStateScope()),
            normalize(request.getWorldSlug()),
            normalize(request.getRealmSlug()),
            normalize(request.getPointerVersion()),
            normalize(request.getScriptId()),
            normalize(request.getPluginId()),
            normalize(request.getPluginVersionId()),
            normalize(request.getEventType()),
            schemaVersion,
            normalize(request.getScriptPatchVersion()),
            normalize(request.getScriptEventId()),
            normalize(sourceService),
            request.getTriggerMode().name(),
            Boolean.toString(request.getIsDryRun()),
            normalize(request.getReadSnapshotToken()),
            normalize(request.getPayloadJson()));
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte item : digest) {
        hex.append(String.format("%02x", item));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
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
    return value == null ? "" : value.strip();
  }

  private static TriggerScriptEventRequest normalizeRequest(TriggerScriptEventRequest request) {
    return request.toBuilder()
        .setTenantId(normalize(request.getTenantId()))
        .setGameInstanceId(normalize(request.getGameInstanceId()))
        .setRegionId(normalize(request.getRegionId()))
        .setRegionEpoch(Math.max(0L, request.getRegionEpoch()))
        .setEntityId(normalize(request.getEntityId()))
        .setScriptId(normalize(request.getScriptId()))
        .setPluginId(normalize(request.getPluginId()))
        .setPluginVersionId(normalize(request.getPluginVersionId()))
        .setEventType(normalize(request.getEventType()))
        .setEventSchemaVersion(normalize(request.getEventSchemaVersion()))
        .setScriptPatchVersion(normalize(request.getScriptPatchVersion()))
        .setScriptEventId(normalize(request.getScriptEventId()))
        .setWorldSlug(normalize(request.getWorldSlug()))
        .setRealmSlug(normalize(request.getRealmSlug()))
        .setPointerVersion(normalize(request.getPointerVersion()))
        .setReadSnapshotToken(normalize(request.getReadSnapshotToken()))
        .setPayloadJson(normalize(request.getPayloadJson()))
        .build();
  }

  private static String optionalText(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static String stringValue(Object value) {
    return value instanceof String text ? text : "";
  }

  private static String normalizeCommandAlias(String value) {
    return normalize(value).toLowerCase(Locale.ROOT);
  }

  private static String normalizeActionClassifier(String value) {
    return normalize(value).toUpperCase(Locale.ROOT);
  }

  private static String normalizeScopeType(String value) {
    return normalize(value).toUpperCase(Locale.ROOT);
  }

  private static boolean isOnLoadRequest(TriggerScriptEventRequest request) {
    return "onLoad".equals(normalize(request.getEventType()));
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
