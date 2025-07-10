package net.firedevops.firemud.repository;

import net.firedevops.firemud.entity.SagaInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SagaInstanceRepository extends JpaRepository<SagaInstance, Long> {}
