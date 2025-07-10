package net.firedevops.firemud.repository;

import net.firedevops.firemud.entity.GameAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GameAssetRepository extends JpaRepository<GameAsset, Long> {}
