package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.automationscripting.client.GameDesignControlPlaneClient;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeEvent;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeRequestHistory;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeState;
import net.firedevops.firemud.automationscripting.repository.PluginRuntimeEventRepository;
import net.firedevops.firemud.automationscripting.repository.PluginRuntimeRequestHistoryRepository;
import net.firedevops.firemud.automationscripting.repository.PluginRuntimeStateRepository;
import net.firedevops.firemud.automationscripting.service.PluginActivationPreflightService;
import net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleInstanceService;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.gamedesign.v1.GetPublishedPluginVersionResponse;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.PluginComponentPolicyDecision;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected dependencies are internal Spring collaborators")
public class PluginRuntimeStateServiceImpl implements PluginRuntimeStateService {
  private static final Logger logger = LoggerFactory.getLogger(PluginRuntimeStateServiceImpl.class);
  private static final String PARTICIPANT_KEY_AUTOMATION_SCRIPTING = "AUTOMATION_SCRIPTING";
  private static final String ACTOR_POLICY_RECONCILER = "automation-scripting-policy-reconciler";
  private static final String DEFAULT_DISABLED_REASON = "not_activated";
  private static final String OPERATION_ACTIVATE = "ACTIVATE";
  private static final int MAX_CONTROL_PLANE_REQUEST_ID_LENGTH = 128;

  private final PluginRuntimeStateRepository repository;
  private final PluginRuntimeEventRepository eventRepository;
  private final GameDesignControlPlaneClient gameDesignControlPlaneClient;
  private final GameSessionControlPlaneClient gameSessionControlPlaneClient;
  private final ScriptScheduleInstanceService scriptScheduleInstanceService;
  private final PluginActivationPreflightService pluginActivationPreflightService;
  private final PluginRuntimeRequestHistoryRepository requestHistoryRepository;

  @Autowired
  public PluginRuntimeStateServiceImpl(
      PluginRuntimeStateRepository repository,
      PluginRuntimeEventRepository eventRepository,
      GameDesignControlPlaneClient gameDesignControlPlaneClient,
      GameSessionControlPlaneClient gameSessionControlPlaneClient,
      ScriptScheduleInstanceService scriptScheduleInstanceService,
      PluginActivationPreflightService pluginActivationPreflightService,
      PluginRuntimeRequestHistoryRepository requestHistoryRepository) {
    this.repository = repository;
    this.eventRepository = eventRepository;
    this.gameDesignControlPlaneClient = gameDesignControlPlaneClient;
    this.gameSessionControlPlaneClient = gameSessionControlPlaneClient;
    this.scriptScheduleInstanceService = scriptScheduleInstanceService;
    this.pluginActivationPreflightService = pluginActivationPreflightService;
    this.requestHistoryRepository = requestHistoryRepository;
  }

  PluginRuntimeStateServiceImpl(
      PluginRuntimeStateRepository repository,
      PluginRuntimeEventRepository eventRepository,
      GameDesignControlPlaneClient gameDesignControlPlaneClient,
      GameSessionControlPlaneClient gameSessionControlPlaneClient,
      ScriptScheduleInstanceService scriptScheduleInstanceService) {
    this(
        repository,
        eventRepository,
        gameDesignControlPlaneClient,
        gameSessionControlPlaneClient,
        scriptScheduleInstanceService,
        (tenantId, gameInstanceId, scriptPatchVersion, pluginId, pluginVersionId) -> {},
        null);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PluginRuntimeStatus> getStatus(
      String tenantId, String gameInstanceId, String pluginId) {
    requireText(tenantId, "tenant_id");
    requireText(gameInstanceId, "game_instance_id");
    requireText(pluginId, "plugin_id");
    return repository
        .findByTenantIdAndGameInstanceIdAndPluginId(tenantId, gameInstanceId, pluginId)
        .map(state -> toStatus(state, publicationLinks(tenantId, pluginId, state)));
  }

  @Override
  @Transactional(readOnly = true)
  public Map<String, String> getActivePluginVersions(
      String tenantId, String gameInstanceId, String runtimeRegionId, long runtimeRegionEpoch) {
    requireText(tenantId, "tenant_id");
    requireText(gameInstanceId, "game_instance_id");
    Map<String, String> active = new HashMap<>();
    for (PluginRuntimeState state :
        repository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId)) {
      if (!PluginState.PLUGIN_STATE_ENABLED.name().equals(state.getPluginState())) {
        continue;
      }
      if (!AutomationRuntimeScopeSupport.matches(state, runtimeRegionId, runtimeRegionEpoch)) {
        continue;
      }
      String pluginId = normalize(state.getPluginId());
      String activePluginVersionId = normalize(state.getActivePluginVersionId());
      if (!pluginId.isBlank() && !activePluginVersionId.isBlank()) {
        active.put(pluginId, activePluginVersionId);
      }
    }
    return Map.copyOf(active);
  }

  @Override
  @Transactional(readOnly = true)
  public List<PluginRuntimeEventSummary> listEvents(
      String tenantId,
      String gameInstanceId,
      String pluginId,
      PluginState pluginState,
      String activePluginVersionId,
      long changedAfterMs,
      long changedBeforeMs,
      int limit) {
    requireText(tenantId, "tenant_id");
    int boundedLimit = limit <= 0 ? 100 : Math.min(limit, 500);
    String pluginStateFilter =
        pluginState == PluginState.PLUGIN_STATE_UNSPECIFIED ? "" : pluginState.name();
    return eventRepository
        .findEvents(
            tenantId,
            normalize(gameInstanceId),
            normalize(pluginId),
            pluginStateFilter,
            normalize(activePluginVersionId),
            changedAfterMs <= 0 ? null : Instant.ofEpochMilli(changedAfterMs),
            changedBeforeMs <= 0 ? null : Instant.ofEpochMilli(changedBeforeMs),
            PageRequest.of(0, boundedLimit))
        .stream()
        .map(event -> toEventSummary(event, publicationLinks(tenantId, pluginId, event)))
        .toList();
  }

  @Override
  @Transactional
  public ActivationResult setActiveVersion(ActivationCommand command) {
    requireText(command.tenantId(), "tenant_id");
    requireText(command.gameInstanceId(), "game_instance_id");
    requireText(command.pluginId(), "plugin_id");
    requireText(command.targetPluginVersionId(), "target_plugin_version_id");
    PluginRuntimeState existingState =
        repository
            .findByTenantIdAndGameInstanceIdAndPluginId(
                command.tenantId(), command.gameInstanceId(), command.pluginId())
            .orElse(null);
    Instant now = Instant.now();
    PluginRuntimeState state =
        existingState != null
            ? existingState
            : newState(command.tenantId(), command.gameInstanceId(), command.pluginId(), now);
    String previous = normalize(state.getActivePluginVersionId());
    String statusReason = normalizeReason(command.reason(), "operator_activation");
    String controlPlaneRequestId = requireControlPlaneRequestId(command.controlPlaneRequestId());
    String actorPrincipal = normalize(command.actorPrincipal());
    String requestFingerprint = activationFingerprint(command, statusReason);
    Optional<PluginRuntimeRequestHistory> priorRequest =
        findPriorRequest(
            command.tenantId(),
            command.gameInstanceId(),
            command.pluginId(),
            OPERATION_ACTIVATE,
            controlPlaneRequestId);
    if (priorRequest.isPresent()) {
      PluginRuntimeRequestHistory history = priorRequest.orElseThrow();
      verifyRequestFingerprint(history.getRequestFingerprint(), requestFingerprint);
      return new ActivationResult(
          history.getPreviousPluginVersionId(),
          history.getActivePluginVersionId(),
          controlPlaneRequestId);
    }
    if (controlPlaneRequestId.equals(normalize(state.getControlPlaneRequestId()))) {
      if (!requestFingerprint.equals(normalize(state.getControlPlaneRequestFingerprint()))) {
        throw new IllegalArgumentException(
            "control_plane_request_id already records a different activation request");
      }
      return new ActivationResult(previous, previous, controlPlaneRequestId);
    }
    GetGameInstanceRuntimeStateResponse runtime = validateActivation(command, existingState);
    if (matches(state, command.targetPluginVersionId(), PluginState.PLUGIN_STATE_ENABLED)) {
      // A different request must receive its own durable acknowledgement, even when
      // the requested state is already present.
      state.setControlPlaneRequestId(controlPlaneRequestId);
      state.setControlPlaneRequestFingerprint(requestFingerprint);
      state.setActorPrincipal(actorPrincipal);
      state.setStatusReason(statusReason);
      state.setLastChangedAt(now);
      PluginRuntimeState saved = repository.save(state);
      appendEvent(saved, previous, controlPlaneRequestId, actorPrincipal, now);
      recordRequest(
          saved,
          OPERATION_ACTIVATE,
          controlPlaneRequestId,
          requestFingerprint,
          previous,
          saved.getActivePluginVersionId(),
          now);
      return new ActivationResult(previous, previous, controlPlaneRequestId);
    }
    state.setActivePluginVersionId(command.targetPluginVersionId());
    state.setPluginActivationEpoch(state.getPluginActivationEpoch() + 1L);
    state.setLifecycleRevision(state.getLifecycleRevision() + 1L);
    state.setPendingPluginVersionId("");
    state.setPluginState(PluginState.PLUGIN_STATE_ENABLED.name());
    state.setStatusReason(statusReason);
    state.setControlPlaneRequestId(controlPlaneRequestId);
    state.setControlPlaneRequestFingerprint(requestFingerprint);
    state.setActorPrincipal(actorPrincipal);
    state.setLastChangedAt(now);
    state.setLastPolicyCheckedAt(now);
    observeRuntimeScope(state, runtime);
    PluginRuntimeState saved = repository.save(state);
    appendEvent(saved, previous, controlPlaneRequestId, actorPrincipal, now);
    recordRequest(
        saved,
        OPERATION_ACTIVATE,
        controlPlaneRequestId,
        requestFingerprint,
        previous,
        saved.getActivePluginVersionId(),
        now);
    reconcileSchedules(saved);
    return new ActivationResult(previous, saved.getActivePluginVersionId(), controlPlaneRequestId);
  }

  private GetGameInstanceRuntimeStateResponse validateActivation(
      ActivationCommand command, PluginRuntimeState existingState) {
    var publication =
        gameDesignControlPlaneClient.getPublishedPluginVersion(
            command.tenantId(), command.pluginId(), command.targetPluginVersionId());
    if (publication.hasError() && !publication.getError().getCode().isBlank()) {
      throw new IllegalArgumentException(
          "PLUGIN_VERSION_NOT_PUBLISHED: " + publication.getError().getMessage());
    }
    if (publication.getPluginVersion().getPublicationState()
        != VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED) {
      throw new IllegalArgumentException(
          "PLUGIN_VERSION_NOT_PUBLISHED: plugin version is not in PUBLISHED state");
    }
    if (publication.getPluginVersion().getSignerKeyId().isBlank()) {
      throw new IllegalArgumentException(
          "PLUGIN_SIGNER_POLICY_UNAVAILABLE: plugin signer key is missing");
    }
    if (publication.getPluginVersion().getSignerRevoked()) {
      throw new IllegalArgumentException(
          "PLUGIN_SIGNER_REVOKED: plugin signer is revoked for activation");
    }
    if (publication.getPluginVersion().getComponentPolicyDecision()
        == PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_BLOCKED) {
      throw new IllegalArgumentException(
          "PLUGIN_COMPONENT_POLICY_BLOCKED: plugin component policy blocks activation");
    }
    if (publication.getPluginVersion().getComponentPolicyDecision()
        == PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_UNSPECIFIED) {
      throw new IllegalArgumentException(
          "PLUGIN_COMPONENT_POLICY_UNAVAILABLE: plugin component policy decision is missing");
    }

    GetGameInstanceRuntimeStateResponse runtime =
        gameSessionControlPlaneClient.getGameInstanceRuntimeState(
            command.tenantId(),
            command.gameInstanceId(),
            existingState == null ? "" : normalize(existingState.getRuntimeRegionId()));
    if (runtime.hasError() && !runtime.getError().getCode().isBlank()) {
      throw new IllegalArgumentException(
          "GAME_INSTANCE_RUNTIME_UNAVAILABLE: " + runtime.getError().getMessage());
    }
    long runtimeVersionId =
        requireRuntimeVersionId(runtime.getRuntimeState().getRuntimeVersionId());
    if (publication.getPluginVersion().getBaseVersionId() != runtimeVersionId) {
      throw new IllegalArgumentException(
          "PLUGIN_BASE_VERSION_MISMATCH: plugin base version does not match runtime version");
    }
    requireAbilitySchemaMatch(
        command, runtimeVersionId, publication.getPluginVersion().getAbilitySchemaDigest());
    pluginActivationPreflightService.validateActivation(
        command.tenantId(),
        command.gameInstanceId(),
        runtime.getRuntimeState().getPinnedScriptPatchVersion(),
        command.pluginId(),
        command.targetPluginVersionId());
    return runtime;
  }

  private static long requireRuntimeVersionId(String runtimeVersionId) {
    try {
      return RequestIdValidation.requirePositiveLong(runtimeVersionId, "runtimeVersionId");
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(
          "GAME_INSTANCE_RUNTIME_UNAVAILABLE: " + ex.getMessage(), ex);
    }
  }

  private void requireAbilitySchemaMatch(
      ActivationCommand command, long runtimeVersionId, String pluginAbilitySchemaDigest) {
    var releaseBundle =
        gameDesignControlPlaneClient.getPublishedReleaseBundle(
            command.tenantId(), runtimeVersionId);
    if (releaseBundle.hasError() && !releaseBundle.getError().getCode().isBlank()) {
      throw new IllegalArgumentException(
          "GAME_INSTANCE_RUNTIME_UNAVAILABLE: " + releaseBundle.getError().getMessage());
    }
    ParticipantDigest automationDigest =
        releaseBundle.getBundle().getParticipantDigestsList().stream()
            .filter(
                digest -> PARTICIPANT_KEY_AUTOMATION_SCRIPTING.equals(digest.getParticipantKey()))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "PLUGIN_ABILITY_SCHEMA_MISMATCH: runtime ability schema digest is unavailable"));
    if (!automationDigest.getContentDigest().equals(pluginAbilitySchemaDigest)) {
      throw new IllegalArgumentException(
          "PLUGIN_ABILITY_SCHEMA_MISMATCH: plugin ability schema digest does not match runtime version");
    }
  }

  @Override
  @Transactional
  public boolean disable(PluginStateCommand command) {
    transition(command, PluginState.PLUGIN_STATE_DISABLED, "operator_disable");
    return true;
  }

  @Override
  @Transactional
  public boolean drain(PluginStateCommand command) {
    transition(command, PluginState.PLUGIN_STATE_DRAINING, "operator_drain");
    return true;
  }

  @Override
  @Transactional
  public PolicyReconciliationResult reconcileActivePluginPolicy(int maxItems) {
    int limit = Math.max(1, maxItems);
    List<PluginRuntimeState> activeStates =
        repository.findByPluginStateAndActivePluginVersionIdNotOrderByLastChangedAtAsc(
            PluginState.PLUGIN_STATE_ENABLED.name(), "", PageRequest.of(0, limit));
    Instant now = Instant.now();
    int disabledCount = 0;
    for (PluginRuntimeState state : activeStates) {
      Optional<String> disableReason =
          disableReasonForCurrentPolicy(
              gameDesignControlPlaneClient.getPublishedPluginVersion(
                  state.getTenantId(),
                  state.getPluginId(),
                  normalize(state.getActivePluginVersionId())));
      if (disableReason.isPresent()) {
        disableForPolicy(state, disableReason.get(), now);
        disabledCount++;
      } else {
        state.setLastPolicyCheckedAt(now);
        repository.save(state);
      }
    }
    return new PolicyReconciliationResult(activeStates.size(), disabledCount);
  }

  @Override
  @Transactional(readOnly = true)
  public PluginPolicyConvergence getPluginPolicyConvergence(
      String tenantId, String gameInstanceId, int maxResults) {
    requireText(tenantId, "tenant_id");
    int limit = Math.max(1, maxResults <= 0 ? 100 : maxResults);
    List<PluginRuntimeState> activeStates =
        normalize(gameInstanceId).isBlank()
            ? repository
                .findByTenantIdAndPluginStateAndActivePluginVersionIdNotOrderByLastChangedAtAsc(
                    tenantId, PluginState.PLUGIN_STATE_ENABLED.name(), "", PageRequest.of(0, limit))
            : repository
                .findByTenantIdAndGameInstanceIdAndPluginStateAndActivePluginVersionIdNotOrderByLastChangedAtAsc(
                    tenantId,
                    gameInstanceId,
                    PluginState.PLUGIN_STATE_ENABLED.name(),
                    "",
                    PageRequest.of(0, limit));
    AutomationRuntimeScopeSupport.RuntimeScope runtimeScope =
        normalize(gameInstanceId).isBlank()
            ? AutomationRuntimeScopeSupport.RuntimeScope.UNKNOWN
            : AutomationRuntimeScopeSupport.currentRuntimeScope(
                gameSessionControlPlaneClient,
                tenantId,
                gameInstanceId,
                preferredRuntimeRegionId(activeStates));
    activeStates =
        activeStates.stream()
            .filter(state -> AutomationRuntimeScopeSupport.matches(state, runtimeScope))
            .toList();
    long evaluatedAtMs = Instant.now().toEpochMilli();
    List<PluginPolicyViolation> violations =
        activeStates.stream()
            .map(state -> violationForCurrentPolicy(state))
            .flatMap(Optional::stream)
            .toList();
    return new PluginPolicyConvergence(
        activeStates.size(), violations.size(), violations.isEmpty(), evaluatedAtMs, violations);
  }

  private Optional<PluginPolicyViolation> violationForCurrentPolicy(PluginRuntimeState state) {
    String activePluginVersionId = normalize(state.getActivePluginVersionId());
    var publication =
        gameDesignControlPlaneClient.getPublishedPluginVersion(
            state.getTenantId(), state.getPluginId(), activePluginVersionId);
    PluginPublicationLink publicationLink = toPublicationLink(activePluginVersionId, publication);
    return disableReasonForCurrentPolicy(publication)
        .map(
            reason ->
                new PluginPolicyViolation(
                    state.getGameInstanceId(),
                    normalize(state.getRuntimeRegionId()),
                    zeroIfNull(state.getRuntimeRegionEpoch()),
                    state.getPluginId(),
                    activePluginVersionId,
                    reason,
                    state.getLastChangedAt().toEpochMilli(),
                    publicationLink));
  }

  private Optional<String> disableReasonForCurrentPolicy(
      GetPublishedPluginVersionResponse publication) {
    if (publication.hasError() && !publication.getError().getCode().isBlank()) {
      return Optional.of("signer_policy_unavailable");
    }
    if (publication.getPluginVersion().getPublicationState()
        != VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED) {
      return Optional.of("plugin_version_not_published");
    }
    if (publication.getPluginVersion().getSignerKeyId().isBlank()) {
      return Optional.of("signer_policy_unavailable");
    }
    if (publication.getPluginVersion().getSignerRevoked()) {
      return Optional.of("signer_revoked");
    }
    return switch (publication.getPluginVersion().getComponentPolicyDecision()) {
      case PLUGIN_COMPONENT_POLICY_DECISION_ALLOWED, PLUGIN_COMPONENT_POLICY_DECISION_REPORT_ONLY ->
          Optional.empty();
      case PLUGIN_COMPONENT_POLICY_DECISION_BLOCKED ->
          Optional.of("plugin_component_policy_blocked");
      default -> Optional.of("component_policy_unavailable");
    };
  }

  private void disableForPolicy(PluginRuntimeState state, String reason, Instant now) {
    String previous = normalize(state.getActivePluginVersionId());
    String previousState = normalize(state.getPluginState());
    long previousPluginActivationEpoch = state.getPluginActivationEpoch();
    long previousLifecycleRevision = state.getLifecycleRevision();
    state.setPluginState(PluginState.PLUGIN_STATE_DISABLED.name());
    // Policy reconciliation is also a lifecycle transition.  Advance the durable
    // fence so work captured while ENABLED cannot execute after this disable.
    state.setPluginActivationEpoch(state.getPluginActivationEpoch() + 1L);
    state.setLifecycleRevision(state.getLifecycleRevision() + 1L);
    state.setStatusReason(reason);
    String logicalTransitionFingerprint =
        String.join(
            "\u0000",
            normalize(state.getTenantId()),
            normalize(state.getGameInstanceId()),
            normalize(state.getPluginId()),
            previousState,
            previous,
            Long.toString(previousPluginActivationEpoch),
            Long.toString(previousLifecycleRevision),
            PluginState.PLUGIN_STATE_DISABLED.name(),
            normalize(reason),
            ACTOR_POLICY_RECONCILER);
    // Keep the generated request identity bounded by the VARCHAR(128) contract while making a
    // retry of this exact policy transition resolve to the same durable request history row.
    String requestId = "policy-reconcile-" + sha256(logicalTransitionFingerprint);
    state.setControlPlaneRequestId(requestId);
    state.setControlPlaneRequestFingerprint(sha256(logicalTransitionFingerprint));
    state.setActorPrincipal(ACTOR_POLICY_RECONCILER);
    state.setLastChangedAt(now);
    state.setLastPolicyCheckedAt(now);
    PluginRuntimeState saved = repository.save(state);
    appendEvent(
        saved,
        previous,
        normalize(saved.getControlPlaneRequestId()),
        normalize(saved.getActorPrincipal()),
        now);
    recordRequest(
        saved,
        "DISABLE",
        normalize(saved.getControlPlaneRequestId()),
        normalize(saved.getControlPlaneRequestFingerprint()),
        previous,
        saved.getActivePluginVersionId(),
        now);
    reconcileSchedules(saved);
  }

  private static String preferredRuntimeRegionId(List<PluginRuntimeState> activeStates) {
    for (PluginRuntimeState state : activeStates) {
      String runtimeRegionId = normalize(state.getRuntimeRegionId());
      if (!runtimeRegionId.isBlank() && zeroIfNull(state.getRuntimeRegionEpoch()) > 0) {
        return runtimeRegionId;
      }
    }
    return "";
  }

  private void transition(
      PluginStateCommand command, PluginState targetState, String defaultReason) {
    requireText(command.tenantId(), "tenant_id");
    requireText(command.gameInstanceId(), "game_instance_id");
    requireText(command.pluginId(), "plugin_id");
    Instant now = Instant.now();
    PluginRuntimeState state =
        repository
            .findByTenantIdAndGameInstanceIdAndPluginId(
                command.tenantId(), command.gameInstanceId(), command.pluginId())
            .orElseGet(
                () ->
                    newState(
                        command.tenantId(), command.gameInstanceId(), command.pluginId(), now));
    String previous = normalize(state.getActivePluginVersionId());
    String statusReason = normalizeReason(command.reason(), defaultReason);
    String requestId = requireControlPlaneRequestId(command.controlPlaneRequestId());
    String requestFingerprint = stateCommandFingerprint(command, targetState, statusReason);
    String operation = targetState == PluginState.PLUGIN_STATE_DISABLED ? "DISABLE" : "DRAIN";
    Optional<PluginRuntimeRequestHistory> priorRequest =
        findPriorRequest(
            command.tenantId(), command.gameInstanceId(), command.pluginId(), operation, requestId);
    if (priorRequest.isPresent()) {
      verifyRequestFingerprint(
          priorRequest.orElseThrow().getRequestFingerprint(), requestFingerprint);
      return;
    }
    if (requestId.equals(normalize(state.getControlPlaneRequestId()))) {
      if (!requestFingerprint.equals(normalize(state.getControlPlaneRequestFingerprint()))) {
        throw new IllegalArgumentException(
            "control_plane_request_id already records a different plugin state request");
      }
      return;
    }
    if (targetState.name().equals(state.getPluginState())) {
      state.setControlPlaneRequestId(requestId);
      state.setControlPlaneRequestFingerprint(requestFingerprint);
      state.setActorPrincipal(normalize(command.actorPrincipal()));
      state.setStatusReason(statusReason);
      state.setLastChangedAt(now);
      PluginRuntimeState saved = repository.save(state);
      appendEvent(saved, previous, requestId, normalize(command.actorPrincipal()), now);
      recordRequest(
          saved,
          operation,
          requestId,
          requestFingerprint,
          previous,
          saved.getActivePluginVersionId(),
          now);
      return;
    }
    String priorState = normalize(state.getPluginState());
    state.setPluginState(targetState.name());
    // A completed disable/revocation invalidates every previously captured activation, including
    // a later same-version re-enable. Entering DRAINING intentionally retains the epoch so the
    // bounded pre-drain admission exception can be evaluated by the final owner.
    if (targetState == PluginState.PLUGIN_STATE_DISABLED
        && !normalize(state.getActivePluginVersionId()).isBlank()
        && (PluginState.PLUGIN_STATE_ENABLED.name().equals(priorState)
            || PluginState.PLUGIN_STATE_DRAINING.name().equals(priorState))) {
      state.setPluginActivationEpoch(state.getPluginActivationEpoch() + 1L);
    }
    state.setLifecycleRevision(state.getLifecycleRevision() + 1L);
    state.setStatusReason(statusReason);
    state.setControlPlaneRequestId(requestId);
    state.setControlPlaneRequestFingerprint(requestFingerprint);
    state.setActorPrincipal(normalize(command.actorPrincipal()));
    state.setLastChangedAt(now);
    PluginRuntimeState saved = repository.save(state);
    appendEvent(
        saved,
        previous,
        normalize(saved.getControlPlaneRequestId()),
        normalize(saved.getActorPrincipal()),
        now);
    recordRequest(
        saved,
        operation,
        requestId,
        requestFingerprint,
        previous,
        saved.getActivePluginVersionId(),
        now);
    reconcileSchedules(saved);
  }

  private void reconcileSchedules(PluginRuntimeState state) {
    GetGameInstanceRuntimeStateResponse runtime =
        gameSessionControlPlaneClient.getGameInstanceRuntimeState(
            state.getTenantId(), state.getGameInstanceId(), normalize(state.getRuntimeRegionId()));
    if (runtime == null) {
      logger.warn(
          "Skipping schedule reconciliation for tenant {} gameInstance {} plugin {} because runtime state client returned null",
          state.getTenantId(),
          state.getGameInstanceId(),
          state.getPluginId());
      return;
    }
    if (runtime.hasError() && !runtime.getError().getCode().isBlank()) {
      logger.warn(
          "Skipping schedule reconciliation for tenant {} gameInstance {} plugin {} because runtime state is unavailable: {}",
          state.getTenantId(),
          state.getGameInstanceId(),
          state.getPluginId(),
          runtime.getError().getCode());
      return;
    }
    if (!runtime.hasRuntimeState()) {
      logger.warn(
          "Skipping schedule reconciliation for tenant {} gameInstance {} plugin {} because runtime state payload is missing",
          state.getTenantId(),
          state.getGameInstanceId(),
          state.getPluginId());
      return;
    }
    if (observeRuntimeScope(state, runtime)) {
      repository.save(state);
    }
    scriptScheduleInstanceService.reconcileObservedRuntimeState(
        state.getTenantId(),
        state.getGameInstanceId(),
        runtime.getRuntimeState(),
        state.getLastChangedAt(),
        state.getPluginId());
  }

  private static PluginRuntimeState newState(
      String tenantId, String gameInstanceId, String pluginId, Instant now) {
    PluginRuntimeState state = new PluginRuntimeState();
    state.setTenantId(tenantId);
    state.setGameInstanceId(gameInstanceId);
    state.setPluginId(pluginId);
    state.setPluginState(PluginState.PLUGIN_STATE_DISABLED.name());
    state.setStatusReason(DEFAULT_DISABLED_REASON);
    state.setLastChangedAt(now);
    state.setLastPolicyCheckedAt(Instant.EPOCH);
    return state;
  }

  private Map<String, PluginPublicationLink> publicationLinks(
      String tenantId, String pluginId, PluginRuntimeState state) {
    Map<String, PluginPublicationLink> links = new HashMap<>();
    for (String pluginVersionId :
        List.of(
            normalize(state.getActivePluginVersionId()),
            normalize(state.getPendingPluginVersionId()))) {
      if (pluginVersionId.isBlank() || links.containsKey(pluginVersionId)) {
        continue;
      }
      links.put(
          pluginVersionId,
          toPublicationLink(
              pluginVersionId,
              gameDesignControlPlaneClient.getPublishedPluginVersion(
                  tenantId, pluginId, pluginVersionId)));
    }
    return links;
  }

  private Map<String, PluginPublicationLink> publicationLinks(
      String tenantId, String pluginId, PluginRuntimeEvent event) {
    Map<String, PluginPublicationLink> links = new HashMap<>();
    for (String pluginVersionId :
        List.of(
            normalize(event.getPreviousPluginVersionId()),
            normalize(event.getActivePluginVersionId()))) {
      if (pluginVersionId.isBlank() || links.containsKey(pluginVersionId)) {
        continue;
      }
      links.put(
          pluginVersionId,
          toPublicationLink(
              pluginVersionId,
              gameDesignControlPlaneClient.getPublishedPluginVersion(
                  tenantId, pluginId, pluginVersionId)));
    }
    return links;
  }

  private static PluginRuntimeStatus toStatus(
      PluginRuntimeState state, Map<String, PluginPublicationLink> publicationLinks) {
    String activePluginVersionId = normalize(state.getActivePluginVersionId());
    String pendingPluginVersionId = normalize(state.getPendingPluginVersionId());
    return new PluginRuntimeStatus(
        activePluginVersionId,
        pendingPluginVersionId,
        normalize(state.getRuntimeRegionId()),
        zeroIfNull(state.getRuntimeRegionEpoch()),
        PluginState.valueOf(state.getPluginState()),
        state.getStatusReason(),
        state.getLastChangedAt().toEpochMilli(),
        normalize(state.getControlPlaneRequestId()),
        normalize(state.getActorPrincipal()),
        state.getLastPolicyCheckedAt().toEpochMilli(),
        publicationLinks.get(activePluginVersionId),
        publicationLinks.get(pendingPluginVersionId),
        state.getPluginActivationEpoch(),
        state.getLifecycleRevision());
  }

  private static PluginPublicationLink toPublicationLink(
      String pluginVersionId, GetPublishedPluginVersionResponse publication) {
    if (publication.hasError() && !publication.getError().getCode().isBlank()) {
      return new PluginPublicationLink(
          pluginVersionId,
          0L,
          VersionLifecycleState.VERSION_LIFECYCLE_STATE_UNSPECIFIED,
          "",
          0L,
          publication.getError().getCode(),
          publication.getError().getMessage());
    }
    return new PluginPublicationLink(
        normalize(publication.getPluginVersion().getPluginVersionId()),
        publication.getPluginVersion().getPublicationId(),
        publication.getPluginVersion().getPublicationState(),
        normalize(publication.getPluginVersion().getStatusReason()),
        publication.getPluginVersion().getLastChangedAtMs(),
        "",
        "");
  }

  private static PluginRuntimeEventSummary toEventSummary(
      PluginRuntimeEvent event, Map<String, PluginPublicationLink> publicationLinks) {
    String previousPluginVersionId = normalize(event.getPreviousPluginVersionId());
    String activePluginVersionId = normalize(event.getActivePluginVersionId());
    return new PluginRuntimeEventSummary(
        event.getEventId(),
        event.getTenantId(),
        event.getGameInstanceId(),
        normalize(event.getRuntimeRegionId()),
        zeroIfNull(event.getRuntimeRegionEpoch()),
        event.getPluginId(),
        previousPluginVersionId,
        activePluginVersionId,
        PluginState.valueOf(event.getPluginState()),
        event.getStatusReason(),
        normalize(event.getControlPlaneRequestId()),
        normalize(event.getActorPrincipal()),
        event.getObservedAt().toEpochMilli(),
        publicationLinks.get(previousPluginVersionId),
        publicationLinks.get(activePluginVersionId));
  }

  private void appendEvent(
      PluginRuntimeState state,
      String previousPluginVersionId,
      String controlPlaneRequestId,
      String actorPrincipal,
      Instant observedAt) {
    PluginRuntimeEvent event = new PluginRuntimeEvent();
    event.setEventId("prte-" + UUID.randomUUID());
    event.setTenantId(state.getTenantId());
    event.setGameInstanceId(state.getGameInstanceId());
    event.setRuntimeRegionId(normalize(state.getRuntimeRegionId()));
    event.setRuntimeRegionEpoch(state.getRuntimeRegionEpoch());
    event.setPluginId(state.getPluginId());
    event.setPreviousPluginVersionId(normalize(previousPluginVersionId));
    event.setActivePluginVersionId(normalize(state.getActivePluginVersionId()));
    event.setPluginState(state.getPluginState());
    event.setStatusReason(state.getStatusReason());
    event.setControlPlaneRequestId(controlPlaneRequestId);
    event.setActorPrincipal(actorPrincipal);
    event.setObservedAt(observedAt);
    eventRepository.save(event);
  }

  private static boolean matches(
      PluginRuntimeState state, String targetActivePluginVersionId, PluginState targetState) {
    return normalize(state.getActivePluginVersionId())
            .equals(normalize(targetActivePluginVersionId))
        && targetState.name().equals(state.getPluginState());
  }

  private static String normalizeReason(String reason, String defaultReason) {
    return reason == null || reason.isBlank() ? defaultReason : reason;
  }

  private Optional<PluginRuntimeRequestHistory> findPriorRequest(
      String tenantId, String gameInstanceId, String pluginId, String operation, String requestId) {
    return requestHistoryRepository == null
        ? Optional.empty()
        : requestHistoryRepository.find(tenantId, gameInstanceId, pluginId, operation, requestId);
  }

  private static void verifyRequestFingerprint(
      String storedFingerprint, String requestFingerprint) {
    if (!normalize(storedFingerprint).equals(requestFingerprint)) {
      throw new IllegalArgumentException(
          "control_plane_request_id already records a different plugin request");
    }
  }

  private void recordRequest(
      PluginRuntimeState state,
      String operation,
      String requestId,
      String fingerprint,
      String previousPluginVersionId,
      String activePluginVersionId,
      Instant createdAt) {
    if (requestHistoryRepository == null) {
      return;
    }
    PluginRuntimeRequestHistory history = new PluginRuntimeRequestHistory();
    history.setTenantId(state.getTenantId());
    history.setGameInstanceId(state.getGameInstanceId());
    history.setPluginId(state.getPluginId());
    history.setOperation(operation);
    history.setControlPlaneRequestId(requestId);
    history.setRequestFingerprint(fingerprint);
    history.setPreviousPluginVersionId(normalize(previousPluginVersionId));
    history.setActivePluginVersionId(normalize(activePluginVersionId));
    history.setPluginActivationEpoch(state.getPluginActivationEpoch());
    history.setLifecycleRevision(state.getLifecycleRevision());
    history.setPluginState(state.getPluginState());
    history.setCreatedAt(createdAt);
    requestHistoryRepository.insertOrGet(history);
  }

  private static String activationFingerprint(
      PluginRuntimeStateService.ActivationCommand command, String statusReason) {
    return sha256(
        String.join(
            "\u0000",
            normalize(command.tenantId()),
            normalize(command.gameInstanceId()),
            normalize(command.pluginId()),
            normalize(command.targetPluginVersionId()),
            PluginState.PLUGIN_STATE_ENABLED.name(),
            statusReason,
            normalize(command.actorPrincipal())));
  }

  private static String stateCommandFingerprint(
      PluginRuntimeStateService.PluginStateCommand command,
      PluginState targetState,
      String statusReason) {
    return sha256(
        String.join(
            "\u0000",
            normalize(command.tenantId()),
            normalize(command.gameInstanceId()),
            normalize(command.pluginId()),
            targetState.name(),
            statusReason,
            normalize(command.actorPrincipal())));
  }

  private static String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte item : digest) {
        hex.append(String.format("%02x", item));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  private static String normalize(String value) {
    return value == null ? "" : value;
  }

  private static long zeroIfNull(Long value) {
    return value == null ? 0L : value;
  }

  private static boolean observeRuntimeScope(
      PluginRuntimeState state, GetGameInstanceRuntimeStateResponse runtime) {
    if (runtime == null || !runtime.hasRuntimeState()) {
      return false;
    }
    return observeRuntimeScope(state, runtime.getRuntimeState());
  }

  private static boolean observeRuntimeScope(
      PluginRuntimeState state, GameInstanceRuntimeState runtimeState) {
    String runtimeRegionId = normalize(runtimeState.getRegionId());
    Long runtimeRegionEpoch =
        runtimeState.getRegionEpoch() > 0 ? runtimeState.getRegionEpoch() : null;
    boolean changed =
        !runtimeRegionId.equals(normalize(state.getRuntimeRegionId()))
            || zeroIfNull(runtimeRegionEpoch) != zeroIfNull(state.getRuntimeRegionEpoch());
    state.setRuntimeRegionId(runtimeRegionId);
    state.setRuntimeRegionEpoch(runtimeRegionEpoch);
    return changed;
  }

  private static void requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
  }

  private static String requireControlPlaneRequestId(String value) {
    String normalized = normalize(value);
    if (normalized.isBlank()) {
      throw new IllegalArgumentException("control_plane_request_id is required");
    }
    if (normalized.length() > MAX_CONTROL_PLANE_REQUEST_ID_LENGTH) {
      throw new IllegalArgumentException(
          "control_plane_request_id must be at most "
              + MAX_CONTROL_PLANE_REQUEST_ID_LENGTH
              + " characters");
    }
    return normalized;
  }
}
