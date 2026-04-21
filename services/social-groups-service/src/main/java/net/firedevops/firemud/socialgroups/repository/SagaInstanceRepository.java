package net.firedevops.firemud.socialgroups.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "NM_SAME_SIMPLE_NAME_AS_INTERFACE",
    justification = "Service-local repository keeps Spring Data naming aligned with the domain")
public interface SagaInstanceRepository
    extends net.firedevops.firemud.common.saga.persistence.SagaInstanceRepository {}
