package net.firedevops.firemud.automationscripting.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "NM_SAME_SIMPLE_NAME_AS_INTERFACE",
    justification =
        "Spring repository bean intentionally binds the shared saga persistence contract.")
public interface SagaInstanceRepository
    extends net.firedevops.firemud.common.saga.persistence.SagaInstanceRepository {}
