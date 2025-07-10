package net.firedevops.firemud.repository;

import java.util.Optional;
import net.firedevops.firemud.entity.GameInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GameInstanceRepository extends JpaRepository<GameInstance, Long> {
  Optional<GameInstance> findFirstByOwnerAccountIdAndStatus(Long ownerAccountId, String status);
}
