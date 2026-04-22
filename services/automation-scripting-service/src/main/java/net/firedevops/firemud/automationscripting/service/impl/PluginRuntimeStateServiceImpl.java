package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.client.GameDesignControlPlaneClient;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeState;
import net.firedevops.firemud.automationscripting.repository.PluginRuntimeStateRepository;
import net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected dependencies are internal Spring collaborators")
public class PluginRuntimeStateServiceImpl implements PluginRuntimeStateService {
  private final PluginRuntimeStateRepository repository;
  private final GameDesignControlPlaneClient gameDesignControlPlaneClient;
  private final GameSessionControlPlaneClient gameSessionControlPlaneClient;

  public PluginRuntimeStateServiceImpl(
      PluginRuntimeStateRepository repository,
      GameDesignControlPlaneClient gameDesignControlPlaneClient,
      GameSessionControlPlaneClient gameSessionControlPlaneClient) {
    this.repository = repository;
    this.gameDesignControlPlaneClient = gameDesignControlPlaneClient;
    this.gameSessionControlPlaneClient = gameSessionControlPlaneClient;
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
    state.setActivePluginVersionId(command.targetPluginVersionId());
    state.setPendingPluginVersionId("");
    state.setPluginState(PluginState.PLUGIN_STATE_ENABLED.name());
    state.setStatusReason(normalizeReason(command.reason(), "operator_activation"));
    state.setControlPlaneRequestId(normalize(command.controlPlaneRequestId()));
    state.setActorPrincipal(normalize(command.actorPrincipal()));
    state.setLastChangedAt(now);
    PluginRuntimeState saved = repository.save(state);
    return new ActivationResult(
        previous, saved.getActivePluginVersionId(), normalize(command.controlPlaneRequestId()));
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
    state.setPluginState(targetState.name());
    state.setStatusReason(normalizeReason(command.reason(), defaultReason));
    state.setControlPlaneRequestId(normalize(command.controlPlaneRequestId()));
    state.setActorPrincipal(normalize(command.actorPrincipal()));
    state.setLastChangedAt(now);
    repository.save(state);
  }

  private static PluginRuntimeState newState(
      String tenantId, String gameInstanceId, String pluginId, Instant now) {
    PluginRuntimeState state = new PluginRuntimeState();
    state.setTenantId(tenantId);
    state.setGameInstanceId(gameInstanceId);
    state.setPluginId(pluginId);
    state.setPluginState(PluginState.PLUGIN_STATE_DISABLED.name());
    state.setStatusReason("not_activated");
    state.setLastChangedAt(now);
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
        normalize(state.getActorPrincipal()));
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
