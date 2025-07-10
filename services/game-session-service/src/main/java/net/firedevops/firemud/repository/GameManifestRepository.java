package net.firedevops.firemud.repository;

import net.firedevops.firemud.entity.GameManifest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GameManifestRepository extends JpaRepository<GameManifest, Long> {}
