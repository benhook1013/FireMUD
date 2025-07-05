package net.fire_devops.firemud.repository;

import net.fire_devops.firemud.entity.GameInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GameInstanceRepository extends JpaRepository<GameInstance, Long> {
}
