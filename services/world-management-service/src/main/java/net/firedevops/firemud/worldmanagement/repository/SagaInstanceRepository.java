package net.firedevops.firemud.worldmanagement.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "NM_SAME_SIMPLE_NAME_AS_INTERFACE",
    justification =
        "Service-local Spring repository intentionally extends the shared saga contract")
public interface SagaInstanceRepository
    extends net.firedevops.firemud.common.saga.persistence.SagaInstanceRepository {}
