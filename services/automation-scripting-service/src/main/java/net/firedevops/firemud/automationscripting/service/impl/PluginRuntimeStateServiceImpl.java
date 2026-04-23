package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.automationscripting.client.GameDesignControlPlaneClient;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeEvent;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeState;
import net.firedevops.firemud.automationscripting.repository.PluginRuntimeEventRepository;
import net.firedevops.firemud.automationscripting.repository.PluginRuntimeStateRepository;
import net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleInstanceService;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.PluginComponentPolicyDecision;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private final PluginRuntimeStateRepository repository;
  private final PluginRuntimeEventRepository eventRepository;
  private final GameDesignControlPlaneClient gameDesignControlPlaneClient;
  private final GameSessionControlPlaneClient gameSessionControlPlaneClient;
  private final ScriptScheduleInstanceService scriptScheduleInstanceService;

  public PluginRuntimeStateServiceImpl(
      PluginRuntimeStateRepository repository,
      PluginRuntimeEventRepository eventRepository,
      GameDesignControlPlaneClient gameDesignControlPlaneClient,
      GameSessionControlPlaneClient gameSessionControlPlaneClient,
      ScriptScheduleInstanceService scriptScheduleInstanceService) {
    this.repository = repository;
    this.eventRepository = eventRepository;
    this.gameDesignControlPlaneClient = gameDesignControlPlaneClient;
    this.gameSessionControlPlaneClient = gameSessionControlPlaneClient;
    this.scriptScheduleInstanceService = scriptScheduleInstanceService;
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
        .map(PluginRuntimeStateServiceImpl::toStatus);
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
        .map(PluginRuntimeStateServiceImpl::toEventSummary)
        .toList();
  }

  @Override
  @Transactional
  public ActivationResult setActiveVersion(ActivationCommand command) {
    requireText(command.tenantId(), "tenant_id");
    requireText(command.gameInstanceId(), "game_instance_id");
    requireText(command.pluginId(), "plugin_id");
    requireText(command.targetPluginVersionId(), "target_plugin_version_id");
    validateActivation(command);
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
    String statusReason = normalizeReason(command.reason(), "operator_activation");
    String controlPlaneRequestId = normalize(command.controlPlaneRequestId());
    String actorPrincipal = normalize(command.actorPrincipal());
    if (matches(
        state, command.targetPluginVersionId(), PluginState.PLUGIN_STATE_ENABLED, statusReason)) {
      return new ActivationResult(previous, previous, normalize(state.getControlPlaneRequestId()));
    }
    state.setActivePluginVersionId(command.targetPluginVersionId());
    state.setPendingPluginVersionId("");
    state.setPluginState(PluginState.PLUGIN_STATE_ENABLED.name());
    state.setStatusReason(statusReason);
    state.setControlPlaneRequestId(controlPlaneRequestId);
    state.setActorPrincipal(actorPrincipal);
    state.setLastChangedAt(now);
    state.setLastPolicyCheckedAt(now);
    PluginRuntimeState saved = repository.save(state);
    appendEvent(saved, previous, controlPlaneRequestId, actorPrincipal, now);
    reconcileSchedules(saved);
    return new ActivationResult(previous, saved.getActivePluginVersionId(), controlPlaneRequestId);
  }

  private void validateActivation(ActivationCommand command) {
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

    var runtime =
        gameSessionControlPlaneClient.getGameInstanceRuntimeState(
            command.tenantId(), command.gameInstanceId());
    if (runtime.hasError() && !runtime.getError().getCode().isBlank()) {
      throw new IllegalArgumentException(
          "GAME_INSTANCE_RUNTIME_UNAVAILABLE: " + runtime.getError().getMessage());
    }
    long runtimeVersionId;
    try {
      runtimeVersionId = Long.parseLong(runtime.getRuntimeState().getRuntimeVersionId());
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException(
          "GAME_INSTANCE_RUNTIME_UNAVAILABLE: runtime version id is not numeric");
    }
    if (publication.getPluginVersion().getBaseVersionId() != runtimeVersionId) {
      throw new IllegalArgumentException(
          "PLUGIN_BASE_VERSION_MISMATCH: plugin base version does not match runtime version");
    }
    requireAbilitySchemaMatch(
        command, runtimeVersionId, publication.getPluginVersion().getAbilitySchemaDigest());
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
      Optional<String> disableReason = disableReasonForCurrentPolicy(state);
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
    return disableReasonForCurrentPolicy(state)
        .map(
            reason ->
                new PluginPolicyViolation(
                    state.getGameInstanceId(),
                    state.getPluginId(),
                    normalize(state.getActivePluginVersionId()),
                    reason,
                    state.getLastChangedAt().toEpochMilli()));
  }

  private Optional<String> disableReasonForCurrentPolicy(PluginRuntimeState state) {
    var publication =
        gameDesignControlPlaneClient.getPublishedPluginVersion(
            state.getTenantId(), state.getPluginId(), normalize(state.getActivePluginVersionId()));
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
      case PLUGIN_COMPONENT_POLICY_DECISION_ALLOWED -> Optional.empty();
      case PLUGIN_COMPONENT_POLICY_DECISION_BLOCKED ->
          Optional.of("plugin_component_policy_blocked");
      default -> Optional.of("component_policy_unavailable");
    };
  }

  private void disableForPolicy(PluginRuntimeState state, String reason, Instant now) {
    String previous = normalize(state.getActivePluginVersionId());
    state.setPluginState(PluginState.PLUGIN_STATE_DISABLED.name());
    state.setStatusReason(reason);
    state.setControlPlaneRequestId("policy-reconcile-" + now.toEpochMilli());
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
    reconcileSchedules(saved);
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
    if (matches(state, previous, targetState, statusReason)) {
      return;
    }
    state.setPluginState(targetState.name());
    state.setStatusReason(statusReason);
    state.setControlPlaneRequestId(normalize(command.controlPlaneRequestId()));
    state.setActorPrincipal(normalize(command.actorPrincipal()));
    state.setLastChangedAt(now);
    PluginRuntimeState saved = repository.save(state);
    appendEvent(
        saved,
        previous,
        normalize(saved.getControlPlaneRequestId()),
        normalize(saved.getActorPrincipal()),
        now);
    reconcileSchedules(saved);
  }

  private void reconcileSchedules(PluginRuntimeState state) {
    var runtime =
        gameSessionControlPlaneClient.getGameInstanceRuntimeState(
            state.getTenantId(), state.getGameInstanceId());
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
    scriptScheduleInstanceService.reconcileObservedRuntimeState(
        state.getTenantId(), state.getGameInstanceId(), runtime.getRuntimeState());
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

  private static PluginRuntimeStatus toStatus(PluginRuntimeState state) {
    return new PluginRuntimeStatus(
        normalize(state.getActivePluginVersionId()),
        normalize(state.getPendingPluginVersionId()),
        PluginState.valueOf(state.getPluginState()),
        state.getStatusReason(),
        state.getLastChangedAt().toEpochMilli(),
        normalize(state.getControlPlaneRequestId()),
        normalize(state.getActorPrincipal()),
        state.getLastPolicyCheckedAt().toEpochMilli());
  }

  private static PluginRuntimeEventSummary toEventSummary(PluginRuntimeEvent event) {
    return new PluginRuntimeEventSummary(
        event.getEventId(),
        event.getTenantId(),
        event.getGameInstanceId(),
        event.getPluginId(),
        normalize(event.getPreviousPluginVersionId()),
        normalize(event.getActivePluginVersionId()),
        PluginState.valueOf(event.getPluginState()),
        event.getStatusReason(),
        normalize(event.getControlPlaneRequestId()),
        normalize(event.getActorPrincipal()),
        event.getObservedAt().toEpochMilli());
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
      PluginRuntimeState state,
      String targetActivePluginVersionId,
      PluginState targetState,
      String statusReason) {
    return normalize(state.getActivePluginVersionId())
            .equals(normalize(targetActivePluginVersionId))
        && targetState.name().equals(state.getPluginState())
        && statusReason.equals(state.getStatusReason());
  }

  private static String normalizeReason(String reason, String defaultReason) {
    return reason == null || reason.isBlank() ? defaultReason : reason;
  }

  private static String normalize(String value) {
    return value == null ? "" : value;
  }

  private static void requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
  }
}
