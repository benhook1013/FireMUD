package net.firedevops.firemud.worldmanagement.repository;

import java.util.List;
import net.firedevops.firemud.worldmanagement.entity.Region;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface RegionRepository extends JpaRepository<Region, Long> {

  @Modifying
  @Transactional
  @Query("delete from Region r where r.tenantId = :tenantId")
  void deleteByTenantId(Long tenantId);

  List<Region> findByTenantId(Long tenantId);

  List<Region> findByTenantIdAndShardId(Long tenantId, Integer shardId);
}
