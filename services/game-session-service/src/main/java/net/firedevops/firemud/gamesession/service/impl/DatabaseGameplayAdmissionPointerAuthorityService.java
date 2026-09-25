package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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

@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification =
        "Constructor validation fails fast for Spring-managed repositories without exposing a"
            + " partially initialized authority service.")
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
      long tenantId, String worldSlug, String realmSlug) {
    return pointerRepository
        .findByTenantIdAndWorldSlugAndRealmSlug(tenantId, worldSlug, realmSlug)
        .map(this::toSnapshot);
  }

  @Override
  @Transactional(readOnly = true)
  public List<GameplayAdmissionPointerSnapshot> listByRuntimeTarget(
      long tenantId, long gameInstanceId) {
    return pointerRepository.findAllByTenantIdAndGameInstanceId(tenantId, gameInstanceId).stream()
        .map(this::toSnapshot)
        .toList();
  }

  @Override
  @Transactional
  public GameplayAdmissionPointerSnapshot upsertPointer(GameplayAdmissionPointerMutation mutation) {
    validateMutation(mutation);
    Instant now = Instant.now();
    GameplayAdmissionPointer pointer =
        pointerRepository
            .findByTenantIdAndWorldSlugAndRealmSlug(
                mutation.tenantId(), mutation.worldSlug(), mutation.realmSlug())
            .orElseGet(GameplayAdmissionPointer::new);
    if (pointer.getId() != null
        && pointer.getTenantId() != null
        && pointer.getTenantId() != mutation.tenantId()) {
      throw new IllegalArgumentException("tenant_id does not own admission pointer");
    }
    if (pointer.getId() != null
        && !Objects.equals(pointer.getStateScope(), mutation.stateScope())) {
      throw new IllegalArgumentException(
          "state_scope changes require a new playable-state lifecycle");
    }
    enforceExpectedPointerVersion(pointer, mutation.expectedPointerVersion());
    long nextPointerVersion =
        pointer.getId() == null ? 1L : Math.max(pointer.getPointerVersion() + 1L, 1L);
    long nextCatalogRevision = nextCatalogRevision(pointer, mutation);
    pointer.setWorldSlug(mutation.worldSlug());
    pointer.setWorldDisplayName(mutation.worldDisplayName());
    pointer.setRealmSlug(mutation.realmSlug());
    pointer.setRealmDisplayName(mutation.realmDisplayName());
    pointer.setTenantId(mutation.tenantId());
    pointer.setGameInstanceId(mutation.gameInstanceId());
    pointer.setPointerVersion(nextPointerVersion);
    pointer.setCatalogRevision(nextCatalogRevision);
    pointer.setVisible(mutation.visible());
    pointer.setPublicProductionRealm(mutation.publicProductionRealm());
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
    event.setPublicProductionRealm(saved.isPublicProductionRealm());
    event.setRequiresCharacterSelection(saved.isRequiresCharacterSelection());
    event.setStateScope(saved.getStateScope());
    event.setCharacterCreationPolicy(saved.getCharacterCreationPolicy());
    event.setActorPrincipal(mutation.actorPrincipal());
    event.setReason(mutation.reason());
    event.setControlPlaneRequestId(mutation.controlPlaneRequestId());
    event.setPreparedVersionUpgradeId(mutation.preparedVersionUpgradeId());
    event.setOccurredAt(now);
    eventRepository.save(event);

    return toSnapshot(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public List<GameplayAdmissionPointerAuditEntry> listPointerAudit(
      long tenantId, String worldSlug, String realmSlug) {
    return eventRepository
        .findByTenantIdAndWorldSlugAndRealmSlugOrderByOccurredAtDesc(tenantId, worldSlug, realmSlug)
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
                    event.isPublicProductionRealm(),
                    event.isRequiresCharacterSelection(),
                    event.getStateScope(),
                    event.getCharacterCreationPolicy(),
                    event.getActorPrincipal(),
                    event.getReason(),
                    event.getControlPlaneRequestId(),
                    event.getPreparedVersionUpgradeId(),
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
        pointer.isPublicProductionRealm(),
        pointer.isRequiresCharacterSelection(),
        pointer.getStateScope(),
        pointer.getCharacterCreationPolicy(),
        pointer.getCatalogRevision() == null ? 0L : pointer.getCatalogRevision(),
        pointer.getRealmId(),
        pointer.getPlayableStateNamespaceId());
  }

  private long nextCatalogRevision(
      GameplayAdmissionPointer pointer, GameplayAdmissionPointerMutation mutation) {
    if (pointer.getId() == null) {
      return 1L;
    }
    Long currentRevision = pointer.getCatalogRevision();
    if (currentRevision == null || currentRevision <= 0L) {
      throw new IllegalStateException("Admission pointer catalog revision is missing or invalid");
    }
    if (!catalogPolicyMatches(pointer, mutation)) {
      return Math.addExact(currentRevision, 1L);
    }
    return currentRevision;
  }

  private boolean catalogPolicyMatches(
      GameplayAdmissionPointer pointer, GameplayAdmissionPointerMutation mutation) {
    return Objects.equals(pointer.getWorldSlug(), mutation.worldSlug())
        && Objects.equals(pointer.getWorldDisplayName(), mutation.worldDisplayName())
        && Objects.equals(pointer.getRealmSlug(), mutation.realmSlug())
        && Objects.equals(pointer.getRealmDisplayName(), mutation.realmDisplayName())
        && pointer.isVisible() == mutation.visible()
        && pointer.isPublicProductionRealm() == mutation.publicProductionRealm()
        && pointer.isRequiresCharacterSelection() == mutation.requiresCharacterSelection()
        && Objects.equals(pointer.getStateScope(), mutation.stateScope())
        && Objects.equals(pointer.getCharacterCreationPolicy(), mutation.characterCreationPolicy());
  }

  private void validateMutation(GameplayAdmissionPointerMutation mutation) {
    requireText(mutation.worldSlug(), "world_slug is required");
    requireText(mutation.worldDisplayName(), "world_display_name is required");
    requireText(mutation.realmSlug(), "realm_slug is required");
    requireText(mutation.realmDisplayName(), "realm_display_name is required");
    if (!"SHARED".equals(mutation.stateScope()) && !"ISOLATED".equals(mutation.stateScope())) {
      throw new IllegalArgumentException("state_scope must be SHARED or ISOLATED");
    }
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
