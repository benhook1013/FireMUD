package net.firedevops.firemud.entitymanagement.repository;

import java.util.List;
import net.firedevops.firemud.entitymanagement.entity.ActorResourceState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ActorResourceStateRepository extends JpaRepository<ActorResourceState, Long> {
  List<ActorResourceState> findByTenantIdAndGameInstanceIdAndCharacterIdOrderByStatKeyAsc(
      Long tenantId, String gameInstanceId, Long characterId);
}
