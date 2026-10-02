package net.firedevops.firemud.gamesession.service.impl;

import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuditEntry;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
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

  ListAdmissionPointersResponse listAdmissionPointers() {
    java.util.List<net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot>
        pointers = gameplayAdmissionPointerAuthorityService.listPointers();
    java.util.List<AdmissionPointerControlPlaneEntry> entries =
        pointers.stream()
            .map(
                pointer -> {
                  java.util.List<GameplayAdmissionPointerAuditEntry> audit =
                      gameplayAdmissionPointerAuthorityService.listPointerAudit(
                          pointer.tenantId(), pointer.worldSlug(), pointer.realmSlug());
                  if (audit.isEmpty()) {
                    throw new AdmissionPointerAuditUnavailableException(
                        "Admission pointer audit unavailable for current pointer "
                            + pointer.tenantId()
                            + ":"
                            + pointer.worldSlug()
                            + "/"
                            + pointer.realmSlug());
                  }
                  return toCurrentEntry(pointer, audit.getFirst());
                })
            .toList();
    return ListAdmissionPointersResponse.newBuilder().addAllPointers(entries).build();
  }

  ListAdmissionPointerAuditResponse listAdmissionPointerAudit(
      ListAdmissionPointerAuditRequest request) {
    long tenantId = ControlPlaneRequestParser.parsePositiveLong(request.getTenantId(), "tenant_id");
    requireText(request.getWorldSlug(), "world_slug is required");
    requireText(request.getRealmSlug(), "realm_slug is required");
    java.util.List<GameplayAdmissionPointerAuditEntry> audit =
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
    GameplayAdmissionPointerSnapshot currentPointer =
        gameplayAdmissionPointerAuthorityService
            .findPointer(tenantId, request.getWorldSlug(), request.getRealmSlug())
            .orElse(null);
    if (currentPointer != null) {
      throw new AdmissionPointerMutationPreconditionException(
          "admission-pointer updates are temporarily disabled until catalog revision "
              + "preconditions are supported");
    }
    throw new AdmissionPointerMutationPreconditionException(
        "admission-pointer creation is temporarily disabled until catalog revision and "
            + "stable realm/namespace identity preconditions are supported");
  }

  ExecutePreparedVersionCutoverResponse executePreparedVersionCutover(
      long tenantId, long targetGameInstanceId, ExecutePreparedVersionCutoverRequest request) {
    requireText(request.getWorldSlug(), "world_slug is required");
    requireText(request.getRealmSlug(), "realm_slug is required");
    requireText(request.getPreparedVersionUpgradeId(), "prepared_version_upgrade_id is required");
    requireText(request.getActorPrincipal(), "actor_principal is required");
    requireText(request.getControlPlaneRequestId(), "control_plane_request_id is required");
    throw new AdmissionPointerMutationPreconditionException(
        "prepared cutover is temporarily disabled until catalog revision preconditions "
            + "are supported");
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
    if (entry.catalogRevision() != null) {
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

  private AdmissionPointerControlPlaneEntry toCurrentEntry(
      GameplayAdmissionPointerSnapshot pointer, GameplayAdmissionPointerAuditEntry auditEntry) {
    AdmissionPointerControlPlaneEntry.Builder builder =
        AdmissionPointerControlPlaneEntry.newBuilder()
            .setWorldSlug(pointer.worldSlug())
            .setWorldDisplayName(pointer.worldDisplayName())
            .setRealmSlug(pointer.realmSlug())
            .setRealmDisplayName(pointer.realmDisplayName())
            .setTenantId(Long.toString(pointer.tenantId()))
            .setGameInstanceId(Long.toString(pointer.gameInstanceId()))
            .setPointerVersion(pointer.pointerVersion())
            .setVisible(pointer.visible())
            .setPublicProductionRealm(pointer.publicProductionRealm())
            .setRequiresCharacterSelection(pointer.requiresCharacterSelection())
            .setStateScope(pointer.stateScope())
            .setCharacterCreationPolicy(pointer.characterCreationPolicy())
            .setActorPrincipal(auditEntry.actorPrincipal())
            .setReason(auditEntry.reason())
            .setControlPlaneRequestId(auditEntry.controlPlaneRequestId())
            .setOccurredAtMs(auditEntry.occurredAt().toEpochMilli());
    if (pointer.catalogRevision() > 0) {
      builder.setCatalogRevision(pointer.catalogRevision());
    }
    if (pointer.realmId() != null) {
      builder.setRealmId(pointer.realmId().toString());
    }
    if (pointer.playableStateNamespaceId() != null) {
      builder.setPlayableStateNamespaceId(pointer.playableStateNamespaceId().toString());
    }
    if (!normalizeBlank(auditEntry.preparedVersionUpgradeId()).isEmpty()) {
      builder.setPreparedVersionUpgradeId(auditEntry.preparedVersionUpgradeId());
    }
    return builder.build();
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
