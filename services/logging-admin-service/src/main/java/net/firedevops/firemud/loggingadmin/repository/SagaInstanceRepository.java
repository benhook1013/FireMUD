package net.firedevops.firemud.loggingadmin.repository;

import net.firedevops.firemud.common.saga.persistence.SagaInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SagaInstanceRepository extends JpaRepository<SagaInstance, Long> {}
