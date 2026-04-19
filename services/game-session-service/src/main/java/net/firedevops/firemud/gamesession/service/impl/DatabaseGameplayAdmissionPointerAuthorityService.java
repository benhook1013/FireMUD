package net.firedevops.firemud.gamesession.service.impl;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointer;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointerEvent;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.service.AdmissionPointerVersionMismatchException;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuditEntry;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerMutation;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatabaseGameplayAdmissionPointerAuthorityService
    implements GameplayAdmissionPointerAuthorityService {
  private final GameplayAdmissionPointerRepository pointerRepository;
  private final GameplayAdmissionPointerEventRepository eventRepository;

  public DatabaseGameplayAdmissionPointerAuthorityService(
      GameplayAdmissionPointerRepository pointerRepository,
      GameplayAdmissionPointerEventRepository eventRepository) {
    this.pointerRepository =
        Objects.requireNonNull(pointerRepository, "pointerRepository must not be null");
    this.eventRepository =
        Objects.requireNonNull(eventRepository, "eventRepository must not be null");
  }

  @Override
  @Transactional(readOnly = true)
  public List<GameplayAdmissionPointerSnapshot> listPointers() {
    return pointerRepository.findAllByOrderByWorldSlugAscRealmSlugAsc().stream()
        .map(this::toSnapshot)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<GameplayAdmissionPointerSnapshot> findPointer(
      String worldSlug, String realmSlug) {
    return pointerRepository
        .findByWorldSlugAndRealmSlug(worldSlug, realmSlug)
        .map(this::toSnapshot);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<GameplayAdmissionPointerSnapshot> findByRuntimeTarget(
      long tenantId, long gameInstanceId) {
    return pointerRepository.findAllByOrderByWorldSlugAscRealmSlugAsc().stream()
        .filter(pointer -> pointer.getTenantId() == tenantId)
        .filter(pointer -> pointer.getGameInstanceId() == gameInstanceId)
        .findFirst()
        .map(this::toSnapshot);
  }

  @Override
  @Transactional
  public GameplayAdmissionPointerSnapshot upsertPointer(GameplayAdmissionPointerMutation mutation) {
    validateMutation(mutation);
    Instant now = Instant.now();
    GameplayAdmissionPointer pointer =
        pointerRepository
            .findByWorldSlugAndRealmSlug(mutation.worldSlug(), mutation.realmSlug())
            .orElseGet(GameplayAdmissionPointer::new);
    enforceExpectedPointerVersion(pointer, mutation.expectedPointerVersion());
    long nextPointerVersion =
        pointer.getId() == null ? 1L : Math.max(pointer.getPointerVersion() + 1L, 1L);
    pointer.setWorldSlug(mutation.worldSlug());
    pointer.setWorldDisplayName(mutation.worldDisplayName());
    pointer.setRealmSlug(mutation.realmSlug());
    pointer.setRealmDisplayName(mutation.realmDisplayName());
    pointer.setTenantId(mutation.tenantId());
    pointer.setGameInstanceId(mutation.gameInstanceId());
    pointer.setPointerVersion(nextPointerVersion);
    pointer.setVisible(mutation.visible());
    pointer.setRequiresCharacterSelection(mutation.requiresCharacterSelection());
    pointer.setStateScope(mutation.stateScope());
    pointer.setCharacterCreationPolicy(mutation.characterCreationPolicy());
    pointer.setLastUpdatedBy(mutation.actorPrincipal());
    pointer.setLastUpdateReason(mutation.reason());
    if (pointer.getCreatedAt() == null) {
      pointer.setCreatedAt(now);
    }
    pointer.setUpdatedAt(now);
    GameplayAdmissionPointer saved = pointerRepository.save(pointer);

    GameplayAdmissionPointerEvent event = new GameplayAdmissionPointerEvent();
    event.setWorldSlug(saved.getWorldSlug());
    event.setRealmSlug(saved.getRealmSlug());
    event.setWorldDisplayName(saved.getWorldDisplayName());
    event.setRealmDisplayName(saved.getRealmDisplayName());
    event.setTenantId(saved.getTenantId());
    event.setGameInstanceId(saved.getGameInstanceId());
    event.setPointerVersion(saved.getPointerVersion());
    event.setVisible(saved.isVisible());
    event.setRequiresCharacterSelection(saved.isRequiresCharacterSelection());
    event.setStateScope(saved.getStateScope());
    event.setCharacterCreationPolicy(saved.getCharacterCreationPolicy());
    event.setActorPrincipal(mutation.actorPrincipal());
    event.setReason(mutation.reason());
    event.setControlPlaneRequestId(mutation.controlPlaneRequestId());
    event.setOccurredAt(now);
    eventRepository.save(event);

    return toSnapshot(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public List<GameplayAdmissionPointerAuditEntry> listPointerAudit(
      String worldSlug, String realmSlug) {
    return eventRepository
        .findByWorldSlugAndRealmSlugOrderByOccurredAtDesc(worldSlug, realmSlug)
        .stream()
        .map(
            event ->
                new GameplayAdmissionPointerAuditEntry(
                    event.getWorldSlug(),
                    event.getRealmSlug(),
                    event.getWorldDisplayName(),
                    event.getRealmDisplayName(),
                    event.getTenantId(),
                    event.getGameInstanceId(),
                    event.getPointerVersion(),
                    event.isVisible(),
                    event.isRequiresCharacterSelection(),
                    event.getStateScope(),
                    event.getCharacterCreationPolicy(),
                    event.getActorPrincipal(),
                    event.getReason(),
                    event.getControlPlaneRequestId(),
                    event.getOccurredAt()))
        .toList();
  }

  private GameplayAdmissionPointerSnapshot toSnapshot(GameplayAdmissionPointer pointer) {
    return new GameplayAdmissionPointerSnapshot(
        pointer.getWorldSlug(),
        pointer.getWorldDisplayName(),
        pointer.getRealmSlug(),
        pointer.getRealmDisplayName(),
        pointer.getTenantId(),
        pointer.getGameInstanceId(),
        pointer.getPointerVersion(),
        pointer.isVisible(),
        pointer.isRequiresCharacterSelection(),
        pointer.getStateScope(),
        pointer.getCharacterCreationPolicy());
  }

  private void validateMutation(GameplayAdmissionPointerMutation mutation) {
    requireText(mutation.worldSlug(), "world_slug is required");
    requireText(mutation.worldDisplayName(), "world_display_name is required");
    requireText(mutation.realmSlug(), "realm_slug is required");
    requireText(mutation.realmDisplayName(), "realm_display_name is required");
    requireText(mutation.stateScope(), "state_scope is required");
    requireText(mutation.characterCreationPolicy(), "character_creation_policy is required");
    requireText(mutation.actorPrincipal(), "actor_principal is required");
    requireText(mutation.reason(), "reason is required");
    requireText(mutation.controlPlaneRequestId(), "control_plane_request_id is required");
    if (mutation.tenantId() <= 0) {
      throw new IllegalArgumentException("tenant_id must be positive");
    }
    if (mutation.gameInstanceId() <= 0) {
      throw new IllegalArgumentException("game_instance_id must be positive");
    }
  }

  private void requireText(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
  }

  private void enforceExpectedPointerVersion(
      GameplayAdmissionPointer pointer, Long expectedPointerVersion) {
    if (expectedPointerVersion == null) {
      return;
    }
    long currentPointerVersion = pointer.getId() == null ? 0L : pointer.getPointerVersion();
    if (currentPointerVersion != expectedPointerVersion) {
      throw new AdmissionPointerVersionMismatchException(
          "expected_pointer_version does not match current pointer version");
    }
  }
}
