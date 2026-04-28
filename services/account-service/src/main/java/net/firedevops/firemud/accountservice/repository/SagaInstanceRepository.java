package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "NM_SAME_SIMPLE_NAME_AS_INTERFACE",
    justification =
        "Service-local Spring repository wrapper intentionally mirrors shared saga SPI.")
public interface SagaInstanceRepository
    extends net.firedevops.firemud.common.saga.persistence.SagaInstanceRepository {}
