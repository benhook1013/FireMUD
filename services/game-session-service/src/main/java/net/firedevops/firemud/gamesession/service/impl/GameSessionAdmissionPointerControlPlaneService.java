package net.firedevops.firemud.gamesession.service.impl;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuditEntry;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService.PointerAuditKey;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.v1.AdmissionPointerControlPlaneEntry;
import net.firedevops.firemud.gamesession.v1.ExecutePreparedVersionCutoverRequest;
import net.firedevops.firemud.gamesession.v1.ExecutePreparedVersionCutoverResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointerAuditRequest;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointerAuditResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointersResponse;
import net.firedevops.firemud.gamesession.v1.SetAdmissionPointerRequest;
import net.firedevops.firemud.gamesession.v1.SetAdmissionPointerResponse;
import org.springframework.stereotype.Service;

@Service
final class GameSessionAdmissionPointerControlPlaneService {
  static final class AdmissionPointerMutationPreconditionException extends RuntimeException {
    AdmissionPointerMutationPreconditionException(String message) {
      super(message);
    }
  }

  private final GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService;

  GameSessionAdmissionPointerControlPlaneService(
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService) {
    this.gameplayAdmissionPointerAuthorityService = gameplayAdmissionPointerAuthorityService;
  }

  ListAdmissionPointersResponse listAdmissionPointers(List<Long> requestedTenantIds) {
    List<Long> tenantIds = List.copyOf(new LinkedHashSet<>(requestedTenantIds));
    Set<Long> tenantScope = Set.copyOf(tenantIds);
    List<GameplayAdmissionPointerSnapshot> pointers =
        (tenantScope.isEmpty()
                ? gameplayAdmissionPointerAuthorityService.listPointers()
                : gameplayAdmissionPointerAuthorityService.listPointersForTenants(tenantIds))
            .stream()
                .filter(
                    pointer -> tenantScope.isEmpty() || tenantScope.contains(pointer.tenantId()))
                .toList();
    List<PointerAuditKey> auditKeys =
        pointers.stream()
            .map(
                pointer ->
                    new PointerAuditKey(
                        pointer.tenantId(), pointer.worldSlug(), pointer.realmSlug()))
            .distinct()
            .toList();
    Map<PointerAuditKey, GameplayAdmissionPointerAuditEntry> latestAudits =
        gameplayAdmissionPointerAuthorityService.findLatestPointerAudits(auditKeys);
    List<AdmissionPointerControlPlaneEntry> entries =
        pointers.stream()
            .map(
                pointer -> {
                  PointerAuditKey key =
                      new PointerAuditKey(
                          pointer.tenantId(), pointer.worldSlug(), pointer.realmSlug());
                  GameplayAdmissionPointerAuditEntry latestAudit = latestAudits.get(key);
                  if (latestAudit == null) {
                    throw new AdmissionPointerAuditUnavailableException(
                        "Admission pointer audit unavailable for current pointer "
                            + pointer.tenantId()
                            + ":"
                            + pointer.worldSlug()
                            + "/"
                            + pointer.realmSlug());
                  }
                  if (!matchesCurrentPointer(pointer, latestAudit)) {
                    throw new AdmissionPointerAuditUnavailableException(
                        "Admission pointer audit does not match current pointer "
                            + pointer.tenantId()
                            + ":"
                            + pointer.worldSlug()
                            + "/"
                            + pointer.realmSlug());
                  }
                  return toEntry(latestAudit);
                })
            .toList();
    return ListAdmissionPointersResponse.newBuilder().addAllPointers(entries).build();
  }

  ListAdmissionPointerAuditResponse listAdmissionPointerAudit(
      ListAdmissionPointerAuditRequest request) {
    long tenantId = ControlPlaneRequestParser.parsePositiveLong(request.getTenantId(), "tenant_id");
    requireText(request.getWorldSlug(), "world_slug is required");
    requireText(request.getRealmSlug(), "realm_slug is required");
    List<GameplayAdmissionPointerAuditEntry> audit =
        gameplayAdmissionPointerAuthorityService.listPointerAudit(
            tenantId, request.getWorldSlug(), request.getRealmSlug());
    if (audit.isEmpty()) {
      throw new IllegalArgumentException("Admission pointer not found");
    }
    return ListAdmissionPointerAuditResponse.newBuilder()
        .addAllAudit(audit.stream().map(this::toEntry).toList())
        .build();
  }

  SetAdmissionPointerResponse setAdmissionPointer(
      long tenantId, long targetGameInstanceId, SetAdmissionPointerRequest request) {
    throw new AdmissionPointerMutationPreconditionException(
        "SetAdmissionPointer is disabled until catalog revision and stable realm/namespace "
            + "identity preconditions are supported");
  }

  ExecutePreparedVersionCutoverResponse executePreparedVersionCutover(
      long tenantId, long targetGameInstanceId, ExecutePreparedVersionCutoverRequest request) {
    requireText(request.getWorldSlug(), "world_slug is required");
    requireText(request.getRealmSlug(), "realm_slug is required");
    requireText(request.getPreparedVersionUpgradeId(), "prepared_version_upgrade_id is required");
    requireText(request.getActorPrincipal(), "actor_principal is required");
    requireText(request.getControlPlaneRequestId(), "control_plane_request_id is required");
    throw new AdmissionPointerMutationPreconditionException(
        "prepared cutover is temporarily disabled until catalog revision preconditions, "
            + "World hold binding, source drain, and durable execution contracts are supported");
  }

  private AdmissionPointerControlPlaneEntry toEntry(GameplayAdmissionPointerAuditEntry entry) {
    AdmissionPointerControlPlaneEntry.Builder builder =
        AdmissionPointerControlPlaneEntry.newBuilder()
            .setWorldSlug(entry.worldSlug())
            .setWorldDisplayName(entry.worldDisplayName())
            .setRealmSlug(entry.realmSlug())
            .setRealmDisplayName(entry.realmDisplayName())
            .setTenantId(Long.toString(entry.tenantId()))
            .setGameInstanceId(Long.toString(entry.gameInstanceId()))
            .setPointerVersion(entry.pointerVersion())
            .setVisible(entry.visible())
            .setPublicProductionRealm(entry.publicProductionRealm())
            .setRequiresCharacterSelection(entry.requiresCharacterSelection())
            .setStateScope(entry.stateScope())
            .setCharacterCreationPolicy(entry.characterCreationPolicy())
            .setActorPrincipal(entry.actorPrincipal())
            .setReason(entry.reason())
            .setControlPlaneRequestId(entry.controlPlaneRequestId())
            .setOccurredAtMs(entry.occurredAt().toEpochMilli());
    if (entry.catalogRevision() != null && entry.catalogRevision() > 0L) {
      builder.setCatalogRevision(entry.catalogRevision());
    }
    if (entry.realmId() != null) {
      builder.setRealmId(entry.realmId().toString());
    }
    if (entry.playableStateNamespaceId() != null) {
      builder.setPlayableStateNamespaceId(entry.playableStateNamespaceId().toString());
    }
    if (!normalizeBlank(entry.preparedVersionUpgradeId()).isEmpty()) {
      builder.setPreparedVersionUpgradeId(entry.preparedVersionUpgradeId());
    }
    return builder.build();
  }

  private static boolean matchesCurrentPointer(
      GameplayAdmissionPointerSnapshot pointer, GameplayAdmissionPointerAuditEntry audit) {
    return Objects.equals(pointer.worldSlug(), audit.worldSlug())
        && Objects.equals(pointer.worldDisplayName(), audit.worldDisplayName())
        && Objects.equals(pointer.realmSlug(), audit.realmSlug())
        && Objects.equals(pointer.realmDisplayName(), audit.realmDisplayName())
        && pointer.tenantId() == audit.tenantId()
        && pointer.gameInstanceId() == audit.gameInstanceId()
        && pointer.pointerVersion() == audit.pointerVersion()
        && pointer.catalogRevision() > 0L
        && audit.catalogRevision() != null
        && audit.catalogRevision() > 0L
        && pointer.catalogRevision() == audit.catalogRevision()
        && pointer.realmId() != null
        && pointer.realmId().equals(audit.realmId())
        && pointer.playableStateNamespaceId() != null
        && pointer.playableStateNamespaceId().equals(audit.playableStateNamespaceId())
        && pointer.visible() == audit.visible()
        && pointer.publicProductionRealm() == audit.publicProductionRealm()
        && pointer.requiresCharacterSelection() == audit.requiresCharacterSelection()
        && Objects.equals(pointer.stateScope(), audit.stateScope())
        && Objects.equals(pointer.characterCreationPolicy(), audit.characterCreationPolicy());
  }

  private String normalizeBlank(String value) {
    return value == null || value.isBlank() ? "" : value;
  }

  private void requireText(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
  }
}
