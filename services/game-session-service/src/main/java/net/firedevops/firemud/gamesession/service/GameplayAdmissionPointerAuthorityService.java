package net.firedevops.firemud.gamesession.service;

import java.util.List;
import java.util.Optional;

public interface GameplayAdmissionPointerAuthorityService {
  List<GameplayAdmissionPointerSnapshot> listPointers();

  Optional<GameplayAdmissionPointerSnapshot> findPointer(
      long tenantId, String worldSlug, String realmSlug);

  List<GameplayAdmissionPointerSnapshot> listByRuntimeTarget(long tenantId, long gameInstanceId);

  GameplayAdmissionPointerSnapshot upsertPointer(GameplayAdmissionPointerMutation mutation);

  List<GameplayAdmissionPointerAuditEntry> listPointerAudit(String worldSlug, String realmSlug);
}
