package net.firedevops.firemud.common.saga.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SagaStepRepository extends JpaRepository<SagaStep, Long> {
  List<SagaStep> findByInstanceId(Long instanceId);
}
