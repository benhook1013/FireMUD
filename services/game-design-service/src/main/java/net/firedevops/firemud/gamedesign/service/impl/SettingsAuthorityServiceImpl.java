package net.firedevops.firemud.gamedesign.service.impl;

import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
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
  private static final String INVALID_BYTE_BOUNDS_MESSAGE =
      "Reconnection buffer hardMaxBytes must be at least softMaxBytes";
  private static final String INCOMPLETE_BYTE_BOUNDS_MESSAGE =
      "Reconnection buffer softMaxBytes and hardMaxBytes must be set together "
          + "or inherited from a complete tenant override";
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
    validateDomainPayload(domain, payload);
    List<GameSettingsOverride> lockedReconnectionRows =
        domain == ScopedSettingsOverrides.SettingsDomain.RECONNECTION
            ? repository.findReconnectionRowsByTenantIdForUpdate(normalizedTenantId)
            : List.of();
    if (domain == ScopedSettingsOverrides.SettingsDomain.RECONNECTION) {
      validateReconnectionInheritance(
          (ScopedSettingsOverrides.ReconnectionOverride) payload,
          normalizedGameInstanceId,
          lockedReconnectionRows);
    }

    GameSettingsOverride entity;
    if (domain == ScopedSettingsOverrides.SettingsDomain.RECONNECTION) {
      entity = existingOrNewReconnectionOverride(lockedReconnectionRows, normalizedGameInstanceId);
    } else if (normalizedGameInstanceId == null) {
      entity =
          repository
              .findByTenantIdAndGameInstanceIdIsNullAndDomain(normalizedTenantId, domain.name())
              .orElseGet(GameSettingsOverride::new);
    } else {
      entity =
          repository
              .findByTenantIdAndGameInstanceIdAndDomain(
                  normalizedTenantId, normalizedGameInstanceId, domain.name())
              .orElseGet(GameSettingsOverride::new);
    }

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
    List<GameSettingsOverride> lockedReconnectionRows =
        domain == ScopedSettingsOverrides.SettingsDomain.RECONNECTION
            ? repository.findReconnectionRowsByTenantIdForUpdate(normalizedTenantId)
            : List.of();
    if (normalizedGameInstanceId == null) {
      if (domain == ScopedSettingsOverrides.SettingsDomain.RECONNECTION) {
        validateTenantReconnectionMutation(null, lockedReconnectionRows);
      }
      GameSettingsOverride entity;
      if (domain == ScopedSettingsOverrides.SettingsDomain.RECONNECTION) {
        entity = findReconnectionOverride(lockedReconnectionRows, null);
      } else {
        entity =
            repository
                .findByTenantIdAndGameInstanceIdIsNullAndDomain(
                    normalizedTenantId, domain.name())
                .orElse(null);
      }
      if (entity != null) {
        repository.delete(entity);
      }
      return;
    }
    GameSettingsOverride entity;
    if (domain == ScopedSettingsOverrides.SettingsDomain.RECONNECTION) {
      entity = findReconnectionOverride(lockedReconnectionRows, normalizedGameInstanceId);
    } else {
      entity =
          repository
              .findByTenantIdAndGameInstanceIdAndDomain(
                  normalizedTenantId, normalizedGameInstanceId, domain.name())
              .orElse(null);
    }
    if (entity != null) {
      repository.delete(entity);
    }
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
      Object payload) {
    switch (domain) {
      case RECONNECTION ->
          validateReconnectionShape((ScopedSettingsOverrides.ReconnectionOverride) payload);
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

  private void validateReconnectionShape(
      ScopedSettingsOverrides.ReconnectionOverride reconnection) {
    if (reconnection.isEmpty()) {
      throw new IllegalArgumentException("Reconnection override must set policy or buffer values");
    }
    if (reconnection.policy() != null
        && reconnection.policy().resumeWindowMs() != null
        && reconnection.policy().resumeWindowMs() <= 0L) {
      throw new IllegalArgumentException("Reconnection resumeWindowMs must be positive");
    }
    ScopedSettingsOverrides.ReconnectionOverride.BufferOverride buffer = reconnection.buffer();
    if (buffer != null) {
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
      if (buffer.softMaxBytes() != null
          && buffer.hardMaxBytes() != null
          && buffer.hardMaxBytes() < buffer.softMaxBytes()) {
        throw new IllegalArgumentException(INVALID_BYTE_BOUNDS_MESSAGE);
      }
    }
  }

  private void validateReconnectionInheritance(
      ScopedSettingsOverrides.ReconnectionOverride reconnection,
      Long gameInstanceId,
      List<GameSettingsOverride> lockedReconnectionRows) {
    if (gameInstanceId == null) {
      validateTenantReconnectionMutation(reconnection, lockedReconnectionRows);
      return;
    }
    GameSettingsOverride tenantRow = findReconnectionOverride(lockedReconnectionRows, null);
    validatePersistedReconnectionByteBounds(
        tenantRow == null
            ? null
            : deserialize(
                tenantRow.getPayload(), ScopedSettingsOverrides.ReconnectionOverride.class),
        reconnection);
  }

  private void validateTenantReconnectionMutation(
      ScopedSettingsOverrides.ReconnectionOverride prospectiveParent,
      List<GameSettingsOverride> lockedReconnectionRows) {
    if (prospectiveParent != null) {
      validatePersistedReconnectionByteBounds(null, prospectiveParent);
    }
    for (GameSettingsOverride childRow : lockedReconnectionRows) {
      if (childRow.getGameInstanceId() == null) {
        continue;
      }
      validatePersistedReconnectionByteBounds(
          prospectiveParent,
          deserialize(childRow.getPayload(), ScopedSettingsOverrides.ReconnectionOverride.class));
    }
  }

  private GameSettingsOverride findReconnectionOverride(
      List<GameSettingsOverride> lockedReconnectionRows, Long gameInstanceId) {
    return lockedReconnectionRows.stream()
        .filter(row -> Objects.equals(row.getGameInstanceId(), gameInstanceId))
        .findFirst()
        .orElse(null);
  }

  private GameSettingsOverride existingOrNewReconnectionOverride(
      List<GameSettingsOverride> lockedReconnectionRows, Long gameInstanceId) {
    GameSettingsOverride existing =
        findReconnectionOverride(lockedReconnectionRows, gameInstanceId);
    return existing == null ? new GameSettingsOverride() : existing;
  }

  private void validatePersistedReconnectionByteBounds(
      ScopedSettingsOverrides.ReconnectionOverride parent,
      ScopedSettingsOverrides.ReconnectionOverride child) {
    ScopedSettingsOverrides.ReconnectionOverride.BufferOverride parentBuffer =
        parent == null ? null : parent.buffer();
    ScopedSettingsOverrides.ReconnectionOverride.BufferOverride childBuffer =
        child == null ? null : child.buffer();
    boolean parentSetsByteBound = setsByteBound(parentBuffer);
    boolean childSetsByteBound = setsByteBound(childBuffer);
    if (!parentSetsByteBound && !childSetsByteBound) {
      return;
    }
    Integer softMaxBytes = inheritedSoftMaxBytes(parent, child);
    Integer hardMaxBytes = inheritedHardMaxBytes(parent, child);
    if (softMaxBytes != null && hardMaxBytes != null && hardMaxBytes < softMaxBytes) {
      throw new IllegalArgumentException(INVALID_BYTE_BOUNDS_MESSAGE);
    }
    if ((parentSetsByteBound && !hasCompleteByteBounds(parentBuffer))
        || (childSetsByteBound && (softMaxBytes == null || hardMaxBytes == null))) {
      throw new IllegalArgumentException(INCOMPLETE_BYTE_BOUNDS_MESSAGE);
    }
  }

  private boolean setsByteBound(
      ScopedSettingsOverrides.ReconnectionOverride.BufferOverride buffer) {
    return buffer != null && (buffer.softMaxBytes() != null || buffer.hardMaxBytes() != null);
  }

  private boolean hasCompleteByteBounds(
      ScopedSettingsOverrides.ReconnectionOverride.BufferOverride buffer) {
    return buffer != null && buffer.softMaxBytes() != null && buffer.hardMaxBytes() != null;
  }

  private Integer inheritedSoftMaxBytes(
      ScopedSettingsOverrides.ReconnectionOverride parent,
      ScopedSettingsOverrides.ReconnectionOverride child) {
    if (child != null && child.buffer() != null && child.buffer().softMaxBytes() != null) {
      return child.buffer().softMaxBytes();
    }
    if (parent != null && parent.buffer() != null && parent.buffer().softMaxBytes() != null) {
      return parent.buffer().softMaxBytes();
    }
    return null;
  }

  private Integer inheritedHardMaxBytes(
      ScopedSettingsOverrides.ReconnectionOverride parent,
      ScopedSettingsOverrides.ReconnectionOverride child) {
    if (child != null && child.buffer() != null && child.buffer().hardMaxBytes() != null) {
      return child.buffer().hardMaxBytes();
    }
    if (parent != null && parent.buffer() != null && parent.buffer().hardMaxBytes() != null) {
      return parent.buffer().hardMaxBytes();
    }
    return null;
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
