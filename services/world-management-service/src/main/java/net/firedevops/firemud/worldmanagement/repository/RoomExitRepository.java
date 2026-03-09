package net.firedevops.firemud.worldmanagement.repository;

import java.util.List;
import net.firedevops.firemud.worldmanagement.entity.RoomExit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RoomExitRepository extends JpaRepository<RoomExit, Long> {
  List<RoomExit> findByTenantId(Long tenantId);

  @Query(
      "SELECT re FROM RoomExit re JOIN FETCH re.toRoom WHERE re.tenantId = :tenantId AND re.fromRoom.id = :roomId")
  List<RoomExit> findByTenantIdAndFromRoomId(
      @Param("tenantId") Long tenantId, @Param("roomId") Long roomId);
}
