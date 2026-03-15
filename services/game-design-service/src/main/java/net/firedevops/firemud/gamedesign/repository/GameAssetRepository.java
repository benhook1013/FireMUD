package net.firedevops.firemud.gamedesign.repository;

import java.util.List;
import net.firedevops.firemud.gamedesign.entity.GameAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GameAssetRepository extends JpaRepository<GameAsset, Long> {
  List<GameAsset> findByTenantId(String tenantId);
}
