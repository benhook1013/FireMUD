package net.firedevops.firemud.worldmanagement.repository;

import net.firedevops.firemud.worldmanagement.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoomRepository extends JpaRepository<Room, Long> {
  java.util.List<Room> findByTenantIdOrderByIdAsc(Long tenantId);
}
