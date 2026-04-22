package net.firedevops.firemud.automationscripting.service.impl;

import java.time.Instant;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeState;
import net.firedevops.firemud.automationscripting.repository.PluginRuntimeStateRepository;
import net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PluginRuntimeStateServiceImpl implements PluginRuntimeStateService {
  private final PluginRuntimeStateRepository repository;

  public PluginRuntimeStateServiceImpl(PluginRuntimeStateRepository repository) {
    this.repository = repository;
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
        state.getLastChangedAt().toEpochMilli());
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
