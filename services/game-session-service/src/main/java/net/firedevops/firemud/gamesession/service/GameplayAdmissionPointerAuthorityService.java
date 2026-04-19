package net.firedevops.firemud.gamesession.service;

import java.util.List;
import java.util.Optional;

public interface GameplayAdmissionPointerAuthorityService {
  List<GameplayAdmissionPointerSnapshot> listPointers();

  Optional<GameplayAdmissionPointerSnapshot> findPointer(String worldSlug, String realmSlug);

  Optional<GameplayAdmissionPointerSnapshot> findByRuntimeTarget(
      long tenantId, long gameInstanceId);

  GameplayAdmissionPointerSnapshot upsertPointer(GameplayAdmissionPointerMutation mutation);

  List<GameplayAdmissionPointerAuditEntry> listPointerAudit(String worldSlug, String realmSlug);
}
