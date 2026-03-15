package net.firedevops.firemud.gamesession.repository;

import net.firedevops.firemud.gamesession.entity.GameManifest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GameManifestRepository extends JpaRepository<GameManifest, Long> {}
