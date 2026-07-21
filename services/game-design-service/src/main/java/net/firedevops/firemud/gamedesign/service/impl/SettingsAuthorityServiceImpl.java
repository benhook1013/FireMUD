package net.firedevops.firemud.gamedesign.service.impl;

import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.settings.ScopedSettingsOverrides;
import net.firedevops.firemud.common.settings.ScopedSettingsSnapshot;
import net.firedevops.firemud.gamedesign.entity.GameSettingsOverride;
import net.firedevops.firemud.gamedesign.repository.GameSettingsOverrideRepository;
import net.firedevops.firemud.gamedesign.service.SettingsAuthorityService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class SettingsAuthorityServiceImpl implements SettingsAuthorityService {
  private final GameSettingsOverrideRepository repository;
  private final ObjectMapper objectMapper;

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "gamedesign.settings.getScopedOverrides")
  public ScopedSettingsSnapshot getScopedOverrides(String tenantId, Long gameInstanceId) {
    String normalizedTenantId = normalizeTenantId(tenantId);
    Long normalizedGameInstanceId = normalizeGameInstanceId(gameInstanceId);
    List<GameSettingsOverride> tenantRows =
        repository.findByTenantIdAndGameInstanceIdIsNull(normalizedTenantId);
    List<GameSettingsOverride> gameInstanceRows =
        normalizedGameInstanceId == null
            ? List.of()
            : repository.findByTenantIdAndGameInstanceId(
                normalizedTenantId, normalizedGameInstanceId);
    return new ScopedSettingsSnapshot(toOverrides(tenantRows), toOverrides(gameInstanceRows));
  }

  @Override
  @Transactional
  @Timed(value = "gamedesign.settings.putDomainOverride")
  public void putDomainOverride(
      String tenantId,
      Long gameInstanceId,
      ScopedSettingsOverrides.SettingsDomain domain,
      ScopedSettingsOverrides overrides) {
    String normalizedTenantId = normalizeTenantId(tenantId);
    Long normalizedGameInstanceId = normalizeGameInstanceId(gameInstanceId);
    Object payload = extractDomainPayload(domain, overrides);
    validateDomainPayload(domain, payload, normalizedTenantId, normalizedGameInstanceId);

    GameSettingsOverride entity =
        normalizedGameInstanceId == null
            ? repository
                .findByTenantIdAndGameInstanceIdIsNullAndDomain(normalizedTenantId, domain.name())
                .orElseGet(GameSettingsOverride::new)
            : repository
                .findByTenantIdAndGameInstanceIdAndDomain(
                    normalizedTenantId, normalizedGameInstanceId, domain.name())
                .orElseGet(GameSettingsOverride::new);

    entity.setTenantId(normalizedTenantId);
    entity.setGameInstanceId(normalizedGameInstanceId);
    entity.setDomain(domain.name());
    entity.setPayload(serialize(payload));
    entity.setUpdatedAt(Instant.now());
    repository.save(entity);
  }

  @Override
  @Transactional
  @Timed(value = "gamedesign.settings.deleteDomainOverride")
  public void deleteDomainOverride(
      String tenantId, Long gameInstanceId, ScopedSettingsOverrides.SettingsDomain domain) {
    String normalizedTenantId = normalizeTenantId(tenantId);
    Long normalizedGameInstanceId = normalizeGameInstanceId(gameInstanceId);
    if (normalizedGameInstanceId == null) {
      repository
          .findByTenantIdAndGameInstanceIdIsNullAndDomain(normalizedTenantId, domain.name())
          .ifPresent(repository::delete);
      return;
    }
    repository
        .findByTenantIdAndGameInstanceIdAndDomain(
            normalizedTenantId, normalizedGameInstanceId, domain.name())
        .ifPresent(repository::delete);
  }

  private ScopedSettingsOverrides toOverrides(List<GameSettingsOverride> rows) {
    ScopedSettingsOverrides.ReconnectionOverride reconnection = null;
    ScopedSettingsOverrides.CommunicationOverride communication = null;
    ScopedSettingsOverrides.PresentationOverride presentation = null;
    ScopedSettingsOverrides.MovementOverride movement = null;
    ScopedSettingsOverrides.WorldTopologyOverride worldTopology = null;
    ScopedSettingsOverrides.CommandHistoryOverride commandHistory = null;
    ScopedSettingsOverrides.CommandCapabilitiesOverride commandCapabilities = null;

    for (GameSettingsOverride row : rows) {
      ScopedSettingsOverrides.SettingsDomain domain =
          ScopedSettingsOverrides.SettingsDomain.valueOf(row.getDomain());
      switch (domain) {
        case RECONNECTION ->
            reconnection =
                deserialize(row.getPayload(), ScopedSettingsOverrides.ReconnectionOverride.class);
        case COMMUNICATION ->
            communication =
                deserialize(row.getPayload(), ScopedSettingsOverrides.CommunicationOverride.class);
        case PRESENTATION ->
            presentation =
                deserialize(row.getPayload(), ScopedSettingsOverrides.PresentationOverride.class);
        case MOVEMENT ->
            movement =
                deserialize(row.getPayload(), ScopedSettingsOverrides.MovementOverride.class);
        case WORLD_TOPOLOGY ->
            worldTopology =
                deserialize(row.getPayload(), ScopedSettingsOverrides.WorldTopologyOverride.class);
        case COMMAND_HISTORY ->
            commandHistory =
                deserialize(row.getPayload(), ScopedSettingsOverrides.CommandHistoryOverride.class);
        case COMMAND_CAPABILITIES ->
            commandCapabilities =
                deserialize(
                    row.getPayload(), ScopedSettingsOverrides.CommandCapabilitiesOverride.class);
      }
    }
    return new ScopedSettingsOverrides(
        reconnection,
        communication,
        presentation,
        movement,
        worldTopology,
        commandHistory,
        commandCapabilities);
  }

  private Object extractDomainPayload(
      ScopedSettingsOverrides.SettingsDomain domain, ScopedSettingsOverrides overrides) {
    if (overrides == null || overrides.isEmpty()) {
      throw new IllegalArgumentException("Overrides payload must include the selected domain");
    }
    Object payload =
        switch (domain) {
          case RECONNECTION -> overrides.reconnection();
          case COMMUNICATION -> overrides.communication();
          case PRESENTATION -> overrides.presentation();
          case MOVEMENT -> overrides.movement();
          case WORLD_TOPOLOGY -> overrides.worldTopology();
          case COMMAND_HISTORY -> overrides.commandHistory();
          case COMMAND_CAPABILITIES -> overrides.commandCapabilities();
        };
    if (payload == null) {
      throw new IllegalArgumentException("Overrides payload must include the selected domain");
    }
    return payload;
  }

  private void validateDomainPayload(
      ScopedSettingsOverrides.SettingsDomain domain,
      Object payload,
      String tenantId,
      Long gameInstanceId) {
    switch (domain) {
      case RECONNECTION ->
          validateReconnection(
              (ScopedSettingsOverrides.ReconnectionOverride) payload, tenantId, gameInstanceId);
      case COMMAND_HISTORY ->
          validateCommandHistory((ScopedSettingsOverrides.CommandHistoryOverride) payload);
      case COMMAND_CAPABILITIES ->
          validateCommandCapabilities(
              (ScopedSettingsOverrides.CommandCapabilitiesOverride) payload);
      default -> {
        return;
      }
    }
  }

  private void validateReconnection(
      ScopedSettingsOverrides.ReconnectionOverride reconnection,
      String tenantId,
      Long gameInstanceId) {
    if (reconnection.isEmpty()) {
      throw new IllegalArgumentException("Reconnection override must set policy or buffer values");
    }
    if (reconnection.policy() != null
        && reconnection.policy().resumeWindowMs() != null
        && reconnection.policy().resumeWindowMs() <= 0L) {
      throw new IllegalArgumentException("Reconnection resumeWindowMs must be positive");
    }
    ScopedSettingsOverrides.ReconnectionOverride.BufferOverride buffer = reconnection.buffer();
    if (buffer == null) {
      return;
    }
    if (buffer.ttlMs() != null && buffer.ttlMs() < 0L) {
      throw new IllegalArgumentException("Reconnection buffer ttlMs must be non-negative");
    }
    if (buffer.maxEntries() != null && buffer.maxEntries() < 1) {
      throw new IllegalArgumentException("Reconnection buffer maxEntries must be positive");
    }
    if (buffer.minMessages() != null && buffer.minMessages() < 1) {
      throw new IllegalArgumentException("Reconnection buffer minMessages must be positive");
    }
    if (buffer.minLines() != null && buffer.minLines() < 1) {
      throw new IllegalArgumentException("Reconnection buffer minLines must be positive");
    }
    if (buffer.softMaxBytes() != null && buffer.softMaxBytes() < 1) {
      throw new IllegalArgumentException("Reconnection buffer softMaxBytes must be positive");
    }
    if (buffer.hardMaxBytes() != null && buffer.hardMaxBytes() < 1) {
      throw new IllegalArgumentException("Reconnection buffer hardMaxBytes must be positive");
    }
    validatePersistedReconnectionByteBounds(reconnection, tenantId, gameInstanceId);
  }

  private void validatePersistedReconnectionByteBounds(
      ScopedSettingsOverrides.ReconnectionOverride candidate,
      String tenantId,
      Long gameInstanceId) {
    if (gameInstanceId != null) {
      ScopedSettingsOverrides.ReconnectionOverride tenantOverride =
          repository
              .findByTenantIdAndGameInstanceIdIsNullAndDomain(
                  tenantId, ScopedSettingsOverrides.SettingsDomain.RECONNECTION.name())
              .map(
                  row ->
                      deserialize(
                          row.getPayload(), ScopedSettingsOverrides.ReconnectionOverride.class))
              .orElse(null);
      validatePersistedReconnectionByteBounds(tenantOverride, candidate);
      return;
    }

    validatePersistedReconnectionByteBounds(null, candidate);
  }

  private void validatePersistedReconnectionByteBounds(
      ScopedSettingsOverrides.ReconnectionOverride parent,
      ScopedSettingsOverrides.ReconnectionOverride child) {
    Integer softMaxBytes = inheritedSoftMaxBytes(parent, child);
    Integer hardMaxBytes = inheritedHardMaxBytes(parent, child);
    if (softMaxBytes != null && hardMaxBytes != null && hardMaxBytes < softMaxBytes) {
      throw new IllegalArgumentException(
          "Reconnection buffer hardMaxBytes must be at least softMaxBytes");
    }
  }

  private Integer inheritedSoftMaxBytes(
      ScopedSettingsOverrides.ReconnectionOverride parent,
      ScopedSettingsOverrides.ReconnectionOverride child) {
    if (child != null && child.buffer() != null && child.buffer().softMaxBytes() != null) {
      return child.buffer().softMaxBytes();
    }
    return parent == null || parent.buffer() == null ? null : parent.buffer().softMaxBytes();
  }

  private Integer inheritedHardMaxBytes(
      ScopedSettingsOverrides.ReconnectionOverride parent,
      ScopedSettingsOverrides.ReconnectionOverride child) {
    if (child != null && child.buffer() != null && child.buffer().hardMaxBytes() != null) {
      return child.buffer().hardMaxBytes();
    }
    return parent == null || parent.buffer() == null ? null : parent.buffer().hardMaxBytes();
  }

  private void validateCommandHistory(
      ScopedSettingsOverrides.CommandHistoryOverride commandHistory) {
    Integer maxEntries = commandHistory.maxEntries();
    if (maxEntries != null && (maxEntries < 1 || maxEntries > 20)) {
      throw new IllegalArgumentException("Command history maxEntries must be between 1 and 20");
    }
  }

  private void validateCommandCapabilities(
      ScopedSettingsOverrides.CommandCapabilitiesOverride commandCapabilities) {
    if (commandCapabilities.isEmpty()) {
      throw new IllegalArgumentException(
          "Command capabilities override must set at least one capability");
    }
  }

  private String normalizeTenantId(String tenantId) {
    if (!StringUtils.hasText(tenantId)) {
      throw new IllegalArgumentException("tenantId is required");
    }
    return tenantId.trim();
  }

  private Long normalizeGameInstanceId(Long gameInstanceId) {
    if (gameInstanceId == null || gameInstanceId <= 0L) {
      return null;
    }
    return gameInstanceId;
  }

  private String serialize(Object payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JacksonException ex) {
      throw new IllegalStateException("Failed to serialize settings override payload", ex);
    }
  }

  private <T> T deserialize(String payload, Class<T> targetType) {
    try {
      return objectMapper.readValue(payload, targetType);
    } catch (JacksonException ex) {
      throw new IllegalStateException("Failed to deserialize settings override payload", ex);
    }
  }
}
