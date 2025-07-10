package net.firedevops.firemud.repository;

import java.util.List;
import net.firedevops.firemud.entity.SagaStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SagaStepRepository extends JpaRepository<SagaStep, Long> {
  List<SagaStep> findByInstanceId(Long instanceId);
}
