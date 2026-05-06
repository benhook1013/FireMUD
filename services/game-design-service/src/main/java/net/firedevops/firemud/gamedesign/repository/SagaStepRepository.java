package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "NM_SAME_SIMPLE_NAME_AS_INTERFACE",
    justification = "Service-local repository adapter intentionally mirrors shared interface name")
public interface SagaStepRepository
    extends net.firedevops.firemud.common.saga.persistence.SagaStepRepository {}
