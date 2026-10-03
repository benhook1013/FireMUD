package net.firedevops.firemud.gamesession.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface GameplayAdmissionPointerAuthorityService {
  record PointerAuditKey(long tenantId, String worldSlug, String realmSlug) {}

  List<GameplayAdmissionPointerSnapshot> listPointers();

  List<GameplayAdmissionPointerSnapshot> listPointersForTenants(List<Long> tenantIds);

  List<GameplayAdmissionPointerSnapshot> listPointersByTenant(long tenantId);

  Optional<GameplayAdmissionPointerSnapshot> findPointer(
      long tenantId, String worldSlug, String realmSlug);

  List<GameplayAdmissionPointerSnapshot> listByRuntimeTarget(long tenantId, long gameInstanceId);

  GameplayAdmissionPointerSnapshot upsertPointer(GameplayAdmissionPointerMutation mutation);

  List<GameplayAdmissionPointerAuditEntry> listPointerAudit(
      long tenantId, String worldSlug, String realmSlug);

  Optional<GameplayAdmissionPointerAuditEntry> findLatestPointerAudit(
      long tenantId, String worldSlug, String realmSlug);

  Map<PointerAuditKey, GameplayAdmissionPointerAuditEntry> findLatestPointerAudits(
      List<PointerAuditKey> keys);
}
