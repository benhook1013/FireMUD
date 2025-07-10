package net.firedevops.firemud.repository;

import java.util.List;
import net.firedevops.firemud.entity.RoomExit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoomExitRepository extends JpaRepository<RoomExit, Long> {
  List<RoomExit> findByTenantId(Long tenantId);
}
