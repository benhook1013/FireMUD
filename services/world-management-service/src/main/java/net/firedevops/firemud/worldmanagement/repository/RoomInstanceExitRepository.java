package net.firedevops.firemud.worldmanagement.repository;

import java.util.List;
import net.firedevops.firemud.worldmanagement.entity.RoomInstanceExit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomInstanceExitRepository extends JpaRepository<RoomInstanceExit, Long> {
  @Query(
      """
      SELECT rie
      FROM RoomInstanceExit rie
      JOIN FETCH rie.toRoomInstance
      WHERE rie.tenantId = :tenantId
        AND rie.gameInstanceId = :gameInstanceId
        AND rie.fromRoomInstance.roomInstanceId = :roomInstanceId
      ORDER BY rie.id ASC
      """)
  List<RoomInstanceExit> findByTenantIdAndGameInstanceIdAndFromRoomInstanceId(
      @Param("tenantId") Long tenantId,
      @Param("gameInstanceId") Long gameInstanceId,
      @Param("roomInstanceId") Long roomInstanceId);
}
