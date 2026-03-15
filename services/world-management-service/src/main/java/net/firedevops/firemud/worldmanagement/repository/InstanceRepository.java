package net.firedevops.firemud.worldmanagement.repository;

import java.time.LocalDateTime;
import java.util.List;
import net.firedevops.firemud.worldmanagement.entity.Instance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InstanceRepository extends JpaRepository<Instance, Long> {
  List<Instance> findByExpiresAtBefore(LocalDateTime cutoff);
}
